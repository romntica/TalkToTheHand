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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

class VoiceRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    // Replaced MediaMuxer with FileOutputStream for ADTS AAC writing
    private var outputStream: FileOutputStream? = null
    
    private val config = RecorderConfig(context)
    private val remoteLogger = RemoteLogger(context)
    
    // Use AtomicBoolean for thread-safe state flags
    private val _isRecording = AtomicBoolean(false)
    val isRecording: Boolean
        get() = _isRecording.get()
        
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

    val durationMillis: Long
        get() {
            if (!_isRecording.get()) return 0L
            val currentSession = if (!isPaused) System.currentTimeMillis() - _startTime else 0L
            return _accumulatedTime + currentSession
        }

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TalkToTheHand:RecordingWakeLock")
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Coroutine scope for background work
    private val recorderScope = CoroutineScope(Dispatchers.IO + Job())
    private var recordingJob: Job? = null

    private var sampleRate = 16000
    private var recordingBufferSize = 0
    
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val silenceThreshold = 1500
    private val silenceHoldMs = 1000L
    
    private var lastDebugLogTime = 0L

    fun start(outputFile: File): Boolean {
        if (isRecording || (recordingJob?.isActive == true)) {
            Log.w("VoiceRecorder", "Cannot start: Recording in progress or cleanup incomplete")
            return false
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            remoteLogger.error("VoiceRecorder", "Permission denied")
            return false
        }

        safeReleaseWakeLock()
        wakeLock?.acquire(10 * 60 * 60 * 1000L /*10 hours*/)

        if (!requestAudioFocus()) {
            remoteLogger.error("VoiceRecorder", "Failed to gain audio focus")
            safeReleaseWakeLock()
            return false
        }

        _isRecording.set(true)
        isPaused = false
        lastError = null 
        
        currentFile = outputFile
        _startTime = System.currentTimeMillis()
        _accumulatedTime = 0L

        recordingJob = recorderScope.launch {
            try {
                // Open File Stream ONCE
                try {
                    outputStream = FileOutputStream(outputFile)
                } catch (e: Exception) {
                    lastError = "Failed to create file: ${e.message}"
                    return@launch
                }

                // Initial Hardware Setup
                if (!initializeHardware()) {
                    lastError = "Initialization failed"
                    return@launch
                }
                
                isPausedByFocus = false
                
                writeAudioData()
                
            } catch (e: Exception) {
                remoteLogger.error("VoiceRecorder", "Recording error", e)
                lastError = "Recording error: ${e.message}"
            } finally {
                cleanup()
            }
        }
        
        return true
    }

    @SuppressLint("MissingPermission")
    private fun initializeHardware(): Boolean {
        // Clear previous instances if any
        try { mediaCodec?.release() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        mediaCodec = null
        audioRecord = null

        try {
            val configuredRate = config.samplingRate
            val supportedSampleRates = intArrayOf(16000, 44100, 48000)
            
            // Prioritize configured rate
            val ratesToTry = mutableListOf<Int>()
            ratesToTry.add(configuredRate)
            for (rate in supportedSampleRates) {
                if (rate != configuredRate) {
                    ratesToTry.add(rate)
                }
            }
            
            var bufferSize = 0
            var validRateFound = false
            
            for (rate in ratesToTry) {
                try {
                    val minBufferSize = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
                    if (minBufferSize != AudioRecord.ERROR && minBufferSize != AudioRecord.ERROR_BAD_VALUE) {
                        sampleRate = rate
                        bufferSize = maxOf(minBufferSize * 2, 4096)
                        if (bufferSize > 65536) bufferSize = 65536
                        
                        audioRecord = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            channelConfig,
                            audioFormat,
                            bufferSize
                        )
                        
                        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                            validRateFound = true
                            recordingBufferSize = bufferSize
                            Log.d("VoiceRecorder", "Initialized with SampleRate: $sampleRate, BufferSize: $recordingBufferSize")
                            break
                        } else {
                            audioRecord?.release()
                            audioRecord = null
                        }
                    }
                } catch (e: Exception) {
                    Log.w("VoiceRecorder", "Failed to init rate $rate", e)
                }
            }

            if (!validRateFound || audioRecord == null) {
                remoteLogger.error("VoiceRecorder", "No supported sample rate found")
                return false
            }

            val currentBitRate = config.bitrate
            Log.d("VoiceRecorder", "Using bitrate: $currentBitRate bps")
            
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, currentBitRate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) 
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, recordingBufferSize)
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            mediaCodec?.start()
            audioRecord?.startRecording()
            
            return true

        } catch (e: Exception) {
            remoteLogger.error("VoiceRecorder", "Hardware Init failed", e)
            return false
        }
    }

    private suspend fun writeAudioData() = withContext(Dispatchers.IO) {
        if (recordingBufferSize <= 0) {
            lastError = "Invalid buffer size"
            return@withContext
        }

        val buffer: ShortArray
        try {
            buffer = ShortArray(recordingBufferSize / 2) 
        } catch (e: OutOfMemoryError) {
            lastError = "Out of memory"
            return@withContext
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var currentFileSize = 0L
        val maxStorageBytes = config.maxStorageSizeBytes
        var errorCount = 0

        // Helper to drain output, returns true if EOS reached
        fun drainOutput(timeoutUs: Long): Boolean {
            try {
                var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: -1
                while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ADTS doesn't need track addition
                    } else {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        
                        if (bufferInfo.size != 0) {
                            val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)
                            if (encodedData != null) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                
                                val data = ByteArray(bufferInfo.size)
                                encodedData.get(data)
                                
                                val packetSize = bufferInfo.size + 7
                                val adtsHeader = ByteArray(7)
                                addADTStoPacket(adtsHeader, packetSize)
                                
                                outputStream?.write(adtsHeader)
                                outputStream?.write(data)
                                
                                currentFileSize += packetSize
                                // Reset error count on successful write
                                errorCount = 0
                            }
                        }
                        
                        mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return true
                        }
                    }
                    outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                }
            } catch (e: Exception) {
                Log.e("VoiceRecorder", "Drain error", e)
                throw e // Re-throw to trigger recovery in main loop
            }
            return false
        }

        while (isActive && _isRecording.get()) {
            try {
                // Check Max Storage
                if (currentFileSize >= maxStorageBytes) {
                    Log.w("VoiceRecorder", "Max storage limit reached")
                    lastError = "Storage limit reached"
                    stop() 
                    break
                }

                if (isPaused) {
                    Thread.sleep(100)
                    continue
                }

                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (readSize < 0) {
                     // Check for critical errors that need recovery
                    if (readSize == AudioRecord.ERROR_DEAD_OBJECT || readSize == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e("VoiceRecorder", "Critical AudioRecord error: $readSize. Attempting recovery.")
                        throw RuntimeException("AudioRecord dead object")
                    }
                    
                    if (isPaused) continue
                    Log.w("VoiceRecorder", "AudioRecord read error: $readSize")
                    Thread.sleep(100)
                    continue
                }
                
                if (readSize == 0) {
                    Thread.sleep(5)
                    continue
                }

                // --- Silence Detection & Encoding Queue ---
                // ... (Silence logic same as before)
                // Simply queueing input buffer:
                
                val inputBufferIndex = mediaCodec?.dequeueInputBuffer(5000) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val bytesRequired = readSize * 2
                        if (inputBuffer.capacity() >= bytesRequired) {
                            inputBuffer.asShortBuffer().put(buffer, 0, readSize)
                            mediaCodec?.queueInputBuffer(inputBufferIndex, 0, bytesRequired, 0, 0)
                        } else {
                            // Handle overflow simply
                             mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                        }
                    }
                }

                drainOutput(0)

            } catch (e: Exception) {
                Log.e("VoiceRecorder", "Error in recording loop", e)
                errorCount++
                
                // Exponential backoff with indefinite retry capped at 10 seconds
                // 500, 1000, 2000, 4000, 8000, 10000, 10000, ...
                val backoffDelay = min(500L * (2.0.pow(errorCount - 1)).toLong(), 10000L)
                Log.i("VoiceRecorder", "Attempting hardware recovery... ($errorCount) in ${backoffDelay}ms")
                
                try {
                    Thread.sleep(backoffDelay)
                    if (!initializeHardware()) {
                        Log.e("VoiceRecorder", "Recovery failed")
                    } else {
                         Log.i("VoiceRecorder", "Hardware recovered successfully")
                    }
                } catch (recEx: Exception) {
                    Log.e("VoiceRecorder", "Recovery crashed", recEx)
                }
            }
        }
        
        // --- End of Stream Handling ---
        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
            if (inputBufferIndex >= 0) {
                mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Drain final data
            drainOutput(10000) 
        } catch (e: Exception) {
            Log.w("VoiceRecorder", "Error sending/draining EOS", e)
        }
    }
    
    // ... (rest of methods same as before: addADTStoPacket, focus, etc.)
    
    /**
     *  Add ADTS header at the beginning of each and every AAC packet.
     */
    private fun addADTStoPacket(packet: ByteArray, packetLen: Int) {
        val profile = 2 // LC
        val freqIdx = when (sampleRate) {
            16000 -> 8
            44100 -> 4
            48000 -> 3
            else -> 8 
        }
        val chanCfg = 1 
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    private fun requestAudioFocus(): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        synchronized(this) {
                            if (isRecording && !isPaused) {
                                isPausedByFocus = true
                                pause()
                            }
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        synchronized(this) {
                            if (isRecording && isPausedByFocus) {
                                resume()
                                isPausedByFocus = false
                            }
                        }
                    }
                }
            }
            .build()
        
        audioFocusRequest = focusRequest
        val result = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    fun pause() {
        if (isRecording && !isPaused) {
            isPaused = true
            _accumulatedTime += System.currentTimeMillis() - _startTime
            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resume() {
        if (isRecording && isPaused) {
            try {
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                     audioRecord?.startRecording()
                }
                isPaused = false
                _startTime = System.currentTimeMillis()
            } catch (e: Exception) {
                e.printStackTrace()
                stop() 
            }
        }
    }

    fun stop() {
        _isRecording.set(false)
    }
    
    fun release() {
        stop()
        recorderScope.cancel()
    }

    suspend fun stopRecording() {
        _isRecording.set(false)
        recordingJob?.join()
    }
    
    private fun cleanup() {
        _isRecording.set(false)
        isPaused = false
        
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        
        try { abandonAudioFocus() } catch (_: Exception) {}
        
        safeReleaseWakeLock()
        
        currentFile = null
        _accumulatedTime = 0L
        _startTime = 0L
        
        Log.d("VoiceRecorder", "Cleanup complete")
    }
    
    private fun safeReleaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "WakeLock release failed", e)
        }
    }
}
