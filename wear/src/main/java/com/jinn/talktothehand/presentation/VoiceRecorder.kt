package com.jinn.talktothehand.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Advanced Voice Recorder with Interruptible Backoff and Enhanced VAD logic.
 * Optimized for stability and crash resilience.
 */
class VoiceRecorder(private val context: Context) {

    interface StateListener {
        fun onRecordingStateChanged(isPaused: Boolean)
        fun onError(message: String)
    }

    var stateListener: StateListener? = null

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var mediaCodec: MediaCodec? = null
    
    private var outputStream: FileOutputStream? = null
    private var fileChannel: FileChannel? = null
    
    private val config = RecorderConfig(context)
    private val remoteLogger = RemoteLogger(context)
    
    private val _isRecording = AtomicBoolean(false)
    val isRecording: Boolean get() = _isRecording.get()
        
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var isPaused = false
        private set
    
    private var isPausedByFocus = false

    var currentFile: File? = null
        private set

    private var _startTime = 0L
    private var _accumulatedTime = 0L
    private var _finalDuration = 0L
    
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var _bytesWrittenToFile = 0L

    val durationMillis: Long
        get() {
            if (!_isRecording.get()) return _finalDuration
            val currentSession = if (!isPaused) System.currentTimeMillis() - _startTime else 0L
            return _accumulatedTime + currentSession
        }
        
    val currentFileSize: Long
        get() = _bytesWrittenToFile + writeBuffer.position()

    private val recorderScope = CoroutineScope(Dispatchers.IO + Job())
    private var recordingJob: Job? = null

    private var sampleRate = SAMPLE_RATE_16K
    private var recordingBufferSize = 0
    
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    private val adtsHeaderBuffer = ByteArray(7)
    private val adtsHeaderByteBuffer = ByteBuffer.wrap(adtsHeaderBuffer)
    private val writeBuffer = ByteBuffer.allocateDirect(16384)

    private var preRollBuffer: ShortArray? = null
    private var isPreRollFlushed = false
    private var currentDutyCycleDelayMs = BASE_DUTY_CYCLE_DELAY_MS
    private var totalSamplesProcessed = 0L

    private val wakeupSignal = Channel<Unit>(Channel.CONFLATED)

    fun start(outputFile: File, reason: String = "Unknown"): Boolean {
        if (isRecording || (recordingJob?.isActive == true)) return false

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            remoteLogger.error(TAG, "Permission denied")
            return false
        }

        safeReleaseWakeLock()
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)

        if (!requestAudioFocus()) {
            remoteLogger.error(TAG, "Failed to gain audio focus")
            safeReleaseWakeLock()
            return false
        }
        
        remoteLogger.info(TAG, "Recording STARTED ($reason)")

        _isRecording.set(true)
        isPaused = false
        isPausedByFocus = false
        lastError = null 
        
        currentFile = outputFile
        _startTime = System.currentTimeMillis()
        _accumulatedTime = 0L
        _finalDuration = 0L
        _bytesWrittenToFile = 0L
        writeBuffer.clear()
        
        totalSamplesProcessed = 0L
        currentDutyCycleDelayMs = BASE_DUTY_CYCLE_DELAY_MS 
        isPreRollFlushed = false

        recordingJob = recorderScope.launch {
            try {
                outputStream = FileOutputStream(outputFile)
                fileChannel = outputStream?.channel

                if (!initializeHardwareWithRetry()) {
                    lastError = "Mic access failed"
                    stateListener?.onError(lastError!!)
                    return@launch
                }
                
                val preRollSamples = (sampleRate * PRE_ROLL_MS / 1000).toInt()
                preRollBuffer = ShortArray(preRollSamples)
                
                writeAudioData()
                
            } catch (e: Exception) {
                remoteLogger.error(TAG, "Recording session error", e)
                lastError = "Recording error: ${e.message}"
                stateListener?.onError(lastError!!)
            } finally {
                _finalDuration = durationMillis
                cleanup(reason = "Finished/Error")
            }
        }
        
        return true
    }

    /**
     * Triggers an immediate wakeup from power-saving backoff.
     */
    fun forceWakeup() {
        wakeupSignal.trySend(Unit)
    }

    private suspend fun initializeHardwareWithRetry(): Boolean {
        val startWaitTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startWaitTime < MIC_ACCESS_TIMEOUT_MS) {
            if (initializeHardware()) {
                val testBuffer = ShortArray(1024)
                val readResult = try { audioRecord?.read(testBuffer, 0, testBuffer.size) ?: -1 } catch (_: Exception) { -1 }
                if (readResult > 0) return true
            }
            releaseMediaComponents()
            delay(250) 
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun initializeHardware(): Boolean {
        try {
            val configuredRate = config.samplingRate
            val supportedSampleRates = intArrayOf(SAMPLE_RATE_16K, SAMPLE_RATE_44K, SAMPLE_RATE_48K)
            val ratesToTry = mutableListOf<Int>().apply {
                add(configuredRate)
                supportedSampleRates.forEach { if (it != configuredRate) add(it) }
            }
            
            var validRateFound = false
            for (rate in ratesToTry) {
                try {
                    val minBufferSize = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
                    if (minBufferSize > 0) {
                        sampleRate = rate
                        recordingBufferSize = maxOf(minBufferSize * 2, OPTIMAL_BUFFER_SIZE)
                        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, recordingBufferSize)
                        
                        if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                            recorder.startRecording()
                            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                audioRecord = recorder
                                validRateFound = true
                                break
                            }
                        }
                        recorder.release()
                    }
                } catch (_: Exception) {}
            }

            if (!validRateFound || audioRecord == null) return false

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) 
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, recordingBufferSize)
            
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            mediaCodec = codec
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Hardware init failed", e)
            return false
        }
    }

    private suspend fun writeAudioData() = withContext(Dispatchers.IO) {
        val buffer = ShortArray(recordingBufferSize / 2)
        val bufferInfo = MediaCodec.BufferInfo()
        val maxStorageBytes = config.maxStorageSizeBytes
        
        var lastSoundTime = System.currentTimeMillis() 
        var silenceDurationMs = 0L
        val vadFrameSize = (sampleRate * 0.03).toInt() 

        while (isActive && _isRecording.get()) {
            try {
                if (currentFileSize >= maxStorageBytes) break
                if (isPaused) { delay(100); continue }
                
                // Hardware Guard: Verify encoder is still alive
                if (mediaCodec == null || audioRecord == null) break

                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readSize <= 0) {
                    if (readSize == AudioRecord.ERROR_INVALID_OPERATION || readSize == AudioRecord.ERROR_BAD_VALUE) {
                        throw IllegalStateException("Hardware connection lost")
                    }
                    delay(10); continue 
                }

                var isVoiceDetected = false
                var offset = 0
                val threshold = config.silenceThreshold.toDouble()
                
                while (offset + vadFrameSize <= readSize) {
                    var sumSquares = 0.0
                    for (i in 0 until vadFrameSize) {
                        val s = buffer[offset + i].toDouble()
                        sumSquares += s * s
                    }
                    if (sqrt(sumSquares / vadFrameSize) > threshold) {
                        isVoiceDetected = true
                        break
                    }
                    offset += vadFrameSize
                }

                val now = System.currentTimeMillis()

                if (isVoiceDetected) {
                    lastSoundTime = now
                    silenceDurationMs = 0
                    currentDutyCycleDelayMs = BASE_DUTY_CYCLE_DELAY_MS 
                    
                    if (!isPreRollFlushed) {
                        preRollBuffer?.let { prBuffer ->
                            processEncodingRobust(prBuffer, prBuffer.size)
                        }
                        isPreRollFlushed = true
                    }
                    processEncodingRobust(buffer, readSize)
                } else {
                    silenceDurationMs += (readSize * 1000L) / sampleRate
                    
                    if (now - lastSoundTime < SILENCE_HOLD_MS) {
                        processEncodingRobust(buffer, readSize)
                    } else {
                        updatePreRollBuffer(buffer, readSize)
                        isPreRollFlushed = false 
                        
                        if (config.silenceDetectionStrategy == 1 && silenceDurationMs > DEEP_SILENCE_THRESHOLD_MS) {
                            performAggressiveDutyCycleBackoff()
                            silenceDurationMs = 0
                        } else {
                            delay((readSize * 1000L) / sampleRate)
                        }
                    }
                }
                
                drainOutput(bufferInfo)

            } catch (e: Exception) {
                Log.e(TAG, "Critical loop error", e)
                lastError = "Loop error: ${e.message}"
                // Don't call onError here, cleanup will handle state
                break
            }
        }
        
        // Final Drain: Only if encoder is still valid
        if (mediaCodec != null) {
            drainOutput(bufferInfo, isEos = true)
        }
        flushBuffer(forceSync = true)
    }

    private fun updatePreRollBuffer(buffer: ShortArray, readSize: Int) {
        val prBuffer = preRollBuffer ?: return
        val prSize = prBuffer.size
        if (readSize >= prSize) {
            System.arraycopy(buffer, readSize - prSize, prBuffer, 0, prSize)
        } else {
            val remaining = prSize - readSize
            System.arraycopy(prBuffer, readSize, prBuffer, 0, remaining)
            System.arraycopy(buffer, 0, prBuffer, remaining, readSize)
        }
    }

    /**
     * Aggressive Power Saving: Powers down MIC during deep silence.
     */
    private suspend fun performAggressiveDutyCycleBackoff() {
        try {
            Log.d(TAG, "Deep silence: Powering down MIC (Backoff: ${currentDutyCycleDelayMs}ms)")
            releaseMediaComponents()
            
            val receivedSignal = withTimeoutOrNull(currentDutyCycleDelayMs) {
                wakeupSignal.receive()
            }

            if (receivedSignal != null) {
                Log.i(TAG, "Backoff interrupted: Immediate wakeup")
            }

            currentDutyCycleDelayMs = min(currentDutyCycleDelayMs * 2, MAX_DUTY_CYCLE_DELAY_MS)
            
            if (_isRecording.get()) {
                if (!initializeHardwareWithRetry()) {
                    throw IllegalStateException("Background mic access lost")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backoff error", e)
            throw e
        }
    }

    private fun processEncodingRobust(buffer: ShortArray, readSize: Int) {
        val codec = mediaCodec ?: return
        var offset = 0
        try {
            while (offset < readSize) {
                val inputIdx = codec.dequeueInputBuffer(10000)
                if (inputIdx >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIdx) ?: break
                    inputBuffer.clear()
                    val maxShorts = inputBuffer.capacity() / 2
                    val shortsToPut = min(readSize - offset, maxShorts)
                    inputBuffer.asShortBuffer().put(buffer, offset, shortsToPut)
                    val ptsUs = (totalSamplesProcessed * 1_000_000L) / sampleRate
                    codec.queueInputBuffer(inputIdx, 0, shortsToPut * 2, ptsUs, 0)
                    totalSamplesProcessed += shortsToPut
                    offset += shortsToPut
                } else break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Encoding failed: ${e.message}")
        }
    }

    private fun drainOutput(info: MediaCodec.BufferInfo, isEos: Boolean = false) {
        val codec = mediaCodec ?: return
        try {
            if (isEos) {
                val inputIdx = codec.dequeueInputBuffer(5000)
                if (inputIdx >= 0) {
                    codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
            
            var outputIdx = codec.dequeueOutputBuffer(info, 0)
            while (outputIdx >= 0) {
                val encodedData = codec.getOutputBuffer(outputIdx)
                if (encodedData != null && info.size > 0) {
                    val packetSize = info.size + 7
                    if (writeBuffer.remaining() < packetSize) flushBuffer(forceSync = false)
                    fillADTSHeader(adtsHeaderBuffer, packetSize)
                    writeBuffer.put(adtsHeaderBuffer)
                    writeBuffer.put(encodedData)
                }
                codec.releaseOutputBuffer(outputIdx, false)
                outputIdx = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: IllegalStateException) {
            // This happens if the codec was released by the system or another thread
            Log.w(TAG, "Codec invalid during drain: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected drain error", e)
        }
    }

    private fun flushBuffer(forceSync: Boolean) {
        try {
            if (writeBuffer.position() > 0) {
                writeBuffer.flip()
                val written = fileChannel?.write(writeBuffer) ?: 0
                _bytesWrittenToFile += written
                writeBuffer.clear()
                if (forceSync) fileChannel?.force(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flush error", e)
        }
    }

    private fun fillADTSHeader(packet: ByteArray, packetLen: Int) {
        val freqIdx = when (sampleRate) { 16000 -> 8; 44100 -> 4; 48000 -> 3; else -> 8 }
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((2 - 1) shl 6) or (freqIdx shl 2)).toByte()
        packet[3] = ((1 shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    private fun requestAudioFocus(): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        synchronized(this) {
                            if (isRecording && !isPaused) {
                                isPausedByFocus = true
                                pause()
                                stateListener?.onRecordingStateChanged(true)
                            }
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        synchronized(this) {
                            if (isRecording && isPausedByFocus) {
                                resume()
                                isPausedByFocus = false
                                stateListener?.onRecordingStateChanged(false)
                            }
                        }
                    }
                }
            }.build()
            
        audioFocusRequest = focusRequest
        val result = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun pause() {
        if (isRecording && !isPaused) {
            isPaused = true
            _accumulatedTime += System.currentTimeMillis() - _startTime
            flushBuffer(forceSync = true)
            try { audioRecord?.stop() } catch (_: Exception) {}
        }
    }

    fun resume() {
        if (isRecording && isPaused) {
            try {
                audioRecord?.startRecording()
                isPaused = false
                _startTime = System.currentTimeMillis()
            } catch (_: Exception) {}
        }
    }

    fun stop(reason: String = "User Action") { 
        if (_isRecording.get()) {
            _finalDuration = durationMillis
            _isRecording.set(false) 
        }
    }
    
    fun release() { stop("Release"); recorderScope.cancel() }
    suspend fun stopRecording(reason: String = "User Action") { stop(reason); recordingJob?.join() }
    
    private fun cleanup(reason: String? = null) {
        _isRecording.set(false)
        releaseMediaComponents()
        flushBuffer(forceSync = true) 
        try { fileChannel?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        safeReleaseWakeLock()
        preRollBuffer = null
        Log.i(TAG, "Cleanup completed ($reason)")
    }

    private fun releaseMediaComponents() {
        try { 
            mediaCodec?.let {
                it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaCodec = null
        
        try { 
            audioRecord?.let {
                it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        audioRecord = null
    }
    
    private fun safeReleaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock.release() }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val WAKELOCK_TAG = "TalkToTheHand:RecWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L
        private const val MIC_ACCESS_TIMEOUT_MS = 2000L 
        private const val SAMPLE_RATE_16K = 16000
        private const val SAMPLE_RATE_44K = 44100
        private const val SAMPLE_RATE_48K = 48000
        private const val OPTIMAL_BUFFER_SIZE = 16384 
        private const val PRE_ROLL_MS = 800L 
        private const val SILENCE_HOLD_MS = 1000L 
        private const val DEEP_SILENCE_THRESHOLD_MS = 15000L 
        private const val BASE_DUTY_CYCLE_DELAY_MS = 1000L 
        private const val MAX_DUTY_CYCLE_DELAY_MS = 30000L 
    }
}
