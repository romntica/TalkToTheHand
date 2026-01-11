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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Advanced Voice Recorder with Precise PTS Management.
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
    private var _finalDuration = 0L // Stores duration after stop
    
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

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

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

                if (!initializeHardware()) {
                    lastError = "Initialization failed"
                    stateListener?.onError(lastError ?: "Hardware init failed")
                    return@launch
                }
                
                val preRollSamples = (sampleRate * PRE_ROLL_MS / 1000).toInt()
                preRollBuffer = ShortArray(preRollSamples)
                
                writeAudioData()
                
            } catch (e: Exception) {
                remoteLogger.error(TAG, "Recording session error", e)
                lastError = "Recording error: ${e.message}"
                stateListener?.onError(lastError ?: "Unknown error")
            } finally {
                _finalDuration = durationMillis // Capture final duration before full cleanup
                cleanup(reason = "Finished/Error")
            }
        }
        
        return true
    }

    @SuppressLint("MissingPermission")
    private fun initializeHardware(): Boolean {
        releaseMediaComponents()
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
                        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, recordingBufferSize)
                        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                            validRateFound = true
                            break
                        } else {
                            audioRecord?.release()
                            audioRecord = null
                        }
                    }
                } catch (_: Exception) {}
            }

            if (!validRateFound || audioRecord == null) return false

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) 
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, recordingBufferSize)
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
            audioRecord?.startRecording()
            
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun writeAudioData() = withContext(Dispatchers.IO) {
        val buffer = ShortArray(recordingBufferSize / 2)
        val bufferInfo = MediaCodec.BufferInfo()
        val maxStorageBytes = config.maxStorageSizeBytes
        
        var lastSoundTime = 0L 
        var silenceDurationMs = 0L
        val vadFrameSize = (sampleRate * 0.03).toInt() 

        while (isActive && _isRecording.get()) {
            try {
                if (currentFileSize >= maxStorageBytes) break
                if (isPaused) { delay(100); continue }
                
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readSize <= 0) { delay(10); continue }

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
                        } else {
                            delay((readSize * 1000L) / sampleRate)
                        }
                    }
                }
                
                drainOutput(bufferInfo)

            } catch (e: Exception) {
                Log.e(TAG, "Critical loop error", e)
                delay(1000)
            }
        }
        
        drainOutput(bufferInfo, isEos = true)
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

    private suspend fun performAggressiveDutyCycleBackoff() {
        try {
            audioRecord?.stop()
            delay(currentDutyCycleDelayMs)
            currentDutyCycleDelayMs = min(currentDutyCycleDelayMs * 2, MAX_DUTY_CYCLE_DELAY_MS)
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Duty cycle error", e)
        }
    }

    private fun processEncodingRobust(buffer: ShortArray, readSize: Int) {
        var offset = 0
        while (offset < readSize) {
            val inputIdx = mediaCodec?.dequeueInputBuffer(10000) ?: -1
            if (inputIdx >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputIdx) ?: break
                inputBuffer.clear()
                
                val maxShorts = inputBuffer.capacity() / 2
                val shortsToPut = min(readSize - offset, maxShorts)
                
                inputBuffer.asShortBuffer().put(buffer, offset, shortsToPut)
                val ptsUs = (totalSamplesProcessed * 1_000_000L) / sampleRate
                mediaCodec?.queueInputBuffer(inputIdx, 0, shortsToPut * 2, ptsUs, 0)
                
                totalSamplesProcessed += shortsToPut
                offset += shortsToPut
            } else {
                Log.w(TAG, "Encoder busy, skipping chunk at offset $offset")
                break
            }
        }
    }

    private fun drainOutput(info: MediaCodec.BufferInfo, isEos: Boolean = false) {
        if (isEos) {
            val inputIdx = mediaCodec?.dequeueInputBuffer(5000) ?: -1
            if (inputIdx >= 0) {
                mediaCodec?.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }

        var outputIdx = mediaCodec?.dequeueOutputBuffer(info, 0) ?: -1
        while (outputIdx >= 0) {
            val encodedData = mediaCodec?.getOutputBuffer(outputIdx)
            if (encodedData != null && info.size > 0) {
                val packetSize = info.size + 7
                if (writeBuffer.remaining() < packetSize) {
                    flushBuffer(forceSync = false)
                }
                fillADTSHeader(adtsHeaderBuffer, packetSize)
                writeBuffer.put(adtsHeaderBuffer)
                writeBuffer.put(encodedData)
            }
            mediaCodec?.releaseOutputBuffer(outputIdx, false)
            outputIdx = mediaCodec?.dequeueOutputBuffer(info, 0) ?: -1
        }
    }

    private fun flushBuffer(forceSync: Boolean) {
        try {
            if (writeBuffer.position() > 0) {
                writeBuffer.flip()
                val written = fileChannel?.write(writeBuffer) ?: 0
                _bytesWrittenToFile += written
                writeBuffer.clear()
                if (forceSync) {
                    fileChannel?.force(false)
                }
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
            _finalDuration = durationMillis // Capture before setting state to false
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
    }

    private fun releaseMediaComponents() {
        try { mediaCodec?.stop(); mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
    
    private fun safeReleaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock.release() }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val WAKELOCK_TAG = "TalkToTheHand:RecWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L
        private const val SAMPLE_RATE_16K = 16000
        private const val SAMPLE_RATE_44K = 44100
        private const val SAMPLE_RATE_48K = 48000
        private const val OPTIMAL_BUFFER_SIZE = 16384 
        private const val PRE_ROLL_MS = 800L 
        private const val SILENCE_HOLD_MS = 1000L 
        private const val DEEP_SILENCE_THRESHOLD_MS = 10000L 
        private const val BASE_DUTY_CYCLE_DELAY_MS = 1000L 
        private const val MAX_DUTY_CYCLE_DELAY_MS = 30000L 
    }
}
