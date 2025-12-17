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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Handles audio recording from the microphone, encoding to AAC (ADTS), and saving to file.
 * Features robustness against errors, battery optimization strategies, and background operation support.
 */
class VoiceRecorder(private val context: Context) {
    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var mediaCodec: MediaCodec? = null
    
    // Use FileChannel for efficient writing without unnecessary allocations
    private var outputStream: FileOutputStream? = null
    private var fileChannel: FileChannel? = null
    
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
    
    // Track total bytes written to include pending buffer
    @Volatile
    private var _bytesWrittenToFile = 0L

    val durationMillis: Long
        get() {
            if (!_isRecording.get()) return 0L
            val currentSession = if (!isPaused) System.currentTimeMillis() - _startTime else 0L
            return _accumulatedTime + currentSession
        }
        
    // Expose total size (written + pending) for UI to prevent "0MB" display during buffering
    val currentFileSize: Long
        get() = _bytesWrittenToFile + writeBuffer.position()

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Coroutine scope for background work
    private val recorderScope = CoroutineScope(Dispatchers.IO + Job())
    private var recordingJob: Job? = null

    private var sampleRate = SAMPLE_RATE_16K
    private var recordingBufferSize = 0
    
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val silenceHoldMs = SILENCE_HOLD_MS
    
    private var lastDebugLogTime = 0L
    
    // Reusable buffer for ADTS header to avoid allocation in loop
    private val adtsHeaderBuffer = ByteArray(7)
    private val adtsHeaderByteBuffer = ByteBuffer.wrap(adtsHeaderBuffer)
    
    // BATTERY OPTIMIZATION: Write Buffer
    // Accumulate ~128KB of AAC data before writing to disk.
    // 128KB AAC ~ 32 seconds of audio at 32kbps.
    // This drastically reduces Flash storage wake-ups, saving power.
    private val writeBuffer = ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE)

    // Updated start method to accept a reason
    fun start(outputFile: File, reason: String = "Unknown"): Boolean {
        if (isRecording || (recordingJob?.isActive == true)) {
            Log.w(TAG, "Cannot start: Recording in progress or cleanup incomplete")
            return false
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            remoteLogger.error(TAG, "Permission denied")
            return false
        }

        safeReleaseWakeLock()
        // Acquire wake lock to keep CPU running even if screen turns off
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)

        if (!requestAudioFocus()) {
            remoteLogger.error(TAG, "Failed to gain audio focus")
            safeReleaseWakeLock()
            return false
        }
        
        remoteLogger.info(TAG, "Recording STARTED ($reason) at ${System.currentTimeMillis()}")

        _isRecording.set(true)
        isPaused = false
        lastError = null 
        
        currentFile = outputFile
        _startTime = System.currentTimeMillis()
        _accumulatedTime = 0L
        _bytesWrittenToFile = 0L // Reset counter
        writeBuffer.clear() // Reset write buffer

        recordingJob = recorderScope.launch {
            try {
                // Open File Stream ONCE. We keep this open even if hardware restarts.
                try {
                    outputStream = FileOutputStream(outputFile)
                    fileChannel = outputStream?.channel
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
                remoteLogger.error(TAG, "Recording error", e)
                lastError = "Recording error: ${e.message}"
            } finally {
                cleanup(reason = "Finished/Error") // Default cleanup reason if not explicitly stopped
            }
        }
        
        return true
    }

    @SuppressLint("MissingPermission")
    private fun initializeHardware(): Boolean {
        // Clear previous instances if any to ensure clean state
        releaseMediaComponents()

        try {
            val configuredRate = config.samplingRate
            val supportedSampleRates = intArrayOf(SAMPLE_RATE_16K, SAMPLE_RATE_44K, SAMPLE_RATE_48K)
            
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
                        // Optimize for Battery: Use a larger buffer (32KB ~ 1s at 16kHz) to reduce CPU wakeups
                        bufferSize = maxOf(minBufferSize * 2, OPTIMAL_BUFFER_SIZE)
                        if (bufferSize > MAX_BUFFER_SIZE) bufferSize = MAX_BUFFER_SIZE
                        
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
                            Log.d(TAG, "Initialized with SampleRate: $sampleRate, BufferSize: $recordingBufferSize")
                            break
                        } else {
                            audioRecord?.release()
                            audioRecord = null
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to init rate $rate", e)
                }
            }

            if (!validRateFound || audioRecord == null) {
                remoteLogger.error(TAG, "No supported sample rate found")
                return false
            }

            val currentBitRate = config.bitrate
            Log.d(TAG, "Using bitrate: $currentBitRate bps")
            
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
            remoteLogger.error(TAG, "Hardware Init failed", e)
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

        // Helper to drain output from MediaCodec, returns true if EOS reached
        fun drainOutput(timeoutUs: Long): Boolean {
            try {
                var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: -1
                while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ADTS format handles config inline, no track setup needed
                    } else {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        
                        if (bufferInfo.size != 0) {
                            val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)
                            if (encodedData != null) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                
                                val packetSize = bufferInfo.size + 7
                                
                                // Write Batching Logic:
                                // Check if write buffer has space, if not flush to disk
                                if (writeBuffer.remaining() < packetSize) {
                                    writeBuffer.flip()
                                    val written = fileChannel?.write(writeBuffer) ?: 0
                                    _bytesWrittenToFile += written
                                    writeBuffer.clear()
                                }

                                // 1. Put ADTS Header
                                fillADTSHeader(adtsHeaderBuffer, packetSize)
                                adtsHeaderByteBuffer.clear()
                                // Copy from array to ByteBuffer if needed, or just put array directly if supported
                                // Since writeBuffer is direct, put(ByteArray) works fine.
                                writeBuffer.put(adtsHeaderBuffer)
                                
                                // 2. Put Body
                                writeBuffer.put(encodedData)
                                
                                currentFileSize += packetSize
                                // Reset error count on successful write indicating healthy state
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
                Log.e(TAG, "Drain error", e)
                throw e // Re-throw to trigger recovery in main loop
            }
            return false
        }

        var lastSoundTime = System.currentTimeMillis()
        var silenceDurationMs = 0L // Track continuous silence
        
        // Hysteresis State: 0 = Silence, 1 = Voice
        var vadState = 0
        
        // Use shorter frame size for VAD analysis (e.g. 30ms at 16kHz is 480 samples)
        val vadFrameSize = (sampleRate * 0.03).toInt() 

        while (isActive && _isRecording.get()) {
            try {
                // Check Max Storage
                if (currentFileSize >= maxStorageBytes) {
                    Log.w(TAG, "Max storage limit reached")
                    lastError = "Storage limit reached"
                    // Internal stop due to limits
                    _isRecording.set(false) // Trigger loop exit
                    // Cleanup will log 'Finished/Error' unless we passed a reason, but here we break loop
                    break
                }

                if (isPaused) {
                    delay(PAUSE_SLEEP_MS) 
                    continue
                }
                
                // Ensure hardware is initialized before reading
                if (audioRecord == null) {
                    throw RuntimeException("AudioRecord is null")
                }

                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (readSize < 0) {
                     // Check for critical errors that need recovery
                    if (readSize == AudioRecord.ERROR_DEAD_OBJECT || readSize == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e(TAG, "Critical AudioRecord error: $readSize. Attempting recovery.")
                        throw RuntimeException("AudioRecord dead object")
                    }
                    
                    if (isPaused) continue
                    Log.w(TAG, "AudioRecord read error: $readSize")
                    delay(PAUSE_SLEEP_MS) 
                    continue
                }
                
                if (readSize == 0) {
                    delay(EMPTY_READ_SLEEP_MS) 
                    continue
                }

                // --- Improved VAD: RMS & Hysteresis ---
                val currentSilenceThreshold = config.silenceThreshold
                val strategy = config.silenceDetectionStrategy
                
                // Calculate RMS for finer-grained analysis
                // We analyze in chunks (frames) to avoid averaging out short bursts
                var isVoiceDetectedInChunk = false
                
                var offset = 0
                while (offset + vadFrameSize <= readSize) {
                    var sumSquares = 0.0
                    for (i in 0 until vadFrameSize) {
                        val sample = buffer[offset + i].toDouble()
                        sumSquares += sample * sample
                    }
                    val rms = sqrt(sumSquares / vadFrameSize)
                    
                    // Simple Hysteresis logic
                    // If currently silent, need higher threshold (User Config) to trigger Voice
                    // If currently voice, stay in voice until drops below lower threshold (Config * 0.5)
                    val threshold = if (vadState == 1) currentSilenceThreshold * 0.5 else currentSilenceThreshold.toDouble()
                    
                    if (rms > threshold) {
                        vadState = 1
                        isVoiceDetectedInChunk = true
                    } else {
                        // Only switch to silence if we were in voice
                        // We rely on the silenceHoldMs timer for the actual "off" logic later
                        if (vadState == 1 && rms < threshold) {
                             vadState = 0
                        }
                    }
                    offset += vadFrameSize
                }

                val now = System.currentTimeMillis()
                val bufferDurationMs = (readSize.toLong() * 1000) / sampleRate
                
                if (isVoiceDetectedInChunk) {
                    lastSoundTime = now
                    silenceDurationMs = 0
                } else {
                    silenceDurationMs += bufferDurationMs
                }

                if (now - lastSoundTime < silenceHoldMs) {
                    // Audio present - Process it
                    val inputBufferIndex = mediaCodec?.dequeueInputBuffer(DEQUEUE_TIMEOUT_US) ?: -1
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val bytesRequired = readSize * 2
                            if (inputBuffer.capacity() >= bytesRequired) {
                                inputBuffer.asShortBuffer().put(buffer, 0, readSize)
                                mediaCodec?.queueInputBuffer(inputBufferIndex, 0, bytesRequired, 0, 0)
                            } else {
                                // Handle overflow simply by dropping frame
                                 mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                            }
                        }
                    }
                } else {
                    // Silence detected
                    
                    // STRATEGY 1: Aggressive Duty Cycling (Mic OFF)
                    // If silence persists > 5 seconds and strategy is aggressive
                    if (strategy == 1 && silenceDurationMs > 5000) {
                        // Log.d(TAG, "Deep Sleep: Stopping Mic for 1s")
                        try {
                            // Turn OFF Mic
                            audioRecord?.stop()
                            // Sleep CPU for 1s
                            delay(1000) 
                            // Turn ON Mic
                            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                                audioRecord?.startRecording()
                            }
                            // Reset silence counter partially to avoid thrashing, 
                            // but keep it high so we can go back to sleep quickly if silence persists.
                            // However, we need to read new data to update silence status.
                        } catch (e: Exception) {
                            Log.w(TAG, "Duty cycle error", e)
                        }
                    } else {
                        // STRATEGY 0: Standard (Mic ON, CPU Sleep)
                        // Sleep exactly as long as the buffer duration
                        delay(bufferDurationMs) 
                    }
                }

                drainOutput(0)

            } catch (e: Exception) {
                Log.e(TAG, "Error in recording loop", e)
                errorCount++
                
                // Indefinite recovery with exponential backoff capped at 10s
                val backoffDelay = min(BASE_BACKOFF_DELAY_MS * (2.0.pow(errorCount - 1)).toLong(), MAX_BACKOFF_DELAY_MS)
                Log.i(TAG, "Attempting hardware recovery... ($errorCount) in ${backoffDelay}ms")
                
                try {
                    delay(backoffDelay) 
                    if (!initializeHardware()) {
                        Log.e(TAG, "Recovery failed")
                    } else {
                         Log.i(TAG, "Hardware recovered successfully")
                         errorCount = 0 // Reset on success
                    }
                } catch (recEx: Exception) {
                    Log.e(TAG, "Recovery crashed", recEx)
                }
            }
        }
        
        // --- End of Stream Handling ---
        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(EOS_TIMEOUT_US) ?: -1
            if (inputBufferIndex >= 0) {
                mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Drain final data
            drainOutput(EOS_TIMEOUT_US)
            
            // Flush any remaining data in the write buffer to disk
            if (writeBuffer.position() > 0) {
                writeBuffer.flip()
                val written = fileChannel?.write(writeBuffer) ?: 0
                _bytesWrittenToFile += written
                writeBuffer.clear()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sending/draining EOS", e)
        }
    }
    
    /**
     *  Fills the ADTS header into the provided byte array.
     */
    private fun fillADTSHeader(packet: ByteArray, packetLen: Int) {
        val profile = 2 // LC
        val freqIdx = when (sampleRate) {
            SAMPLE_RATE_16K -> 8
            SAMPLE_RATE_44K -> 4
            SAMPLE_RATE_48K -> 3
            else -> 8 
        }
        val chanCfg = 1 
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        // chanCfg is 1. (1 shr 2) is 0.
        packet[2] = (((profile - 1) shl 6) or (freqIdx shl 2)).toByte()
        
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
                // Log but don't crash on stop failure
                Log.w(TAG, "Error stopping audio record on pause", e)
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
                Log.e(TAG, "Error resuming recording", e)
                stop("Resume Failed") 
            }
        }
    }

    // Updated stop method to accept reason
    fun stop(reason: String = "User Action") {
        _isRecording.set(false)
        cleanupReason = reason // Store reason for cleanup
    }
    
    // Store cleanup reason if stop is called asynchronously
    @Volatile
    private var cleanupReason = "Unknown"
    
    fun release() {
        stop("App Released")
        recorderScope.cancel()
    }

    suspend fun stopRecording(reason: String = "User Action") {
        stop(reason)
        recordingJob?.join()
    }
    
    private fun cleanup(reason: String? = null) {
        _isRecording.set(false)
        isPaused = false
        
        releaseMediaComponents()
        
        // Flush and close stream
        try {
            if (writeBuffer.position() > 0) {
                writeBuffer.flip()
                val written = fileChannel?.write(writeBuffer) ?: 0
                _bytesWrittenToFile += written
            }
        } catch (e: Exception) { Log.e(TAG, "Error flushing buffer on cleanup", e) }
        
        try { fileChannel?.close() } catch (_: Exception) {}
        fileChannel = null
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        
        try { abandonAudioFocus() } catch (_: Exception) {}
        
        safeReleaseWakeLock()
        
        currentFile = null
        _accumulatedTime = 0L
        _startTime = 0L
        _bytesWrittenToFile = 0L
        
        val finalReason = reason ?: cleanupReason
        remoteLogger.info(TAG, "Recording STOPPED ($finalReason) at ${System.currentTimeMillis()}")
        Log.d(TAG, "Cleanup complete: $finalReason")
    }

    private fun releaseMediaComponents() {
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
    
    private fun safeReleaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release failed", e)
        }
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val WAKELOCK_TAG = "TalkToTheHand:RecordingWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L // 10 hours
        private const val SAMPLE_RATE_16K = 16000
        private const val SAMPLE_RATE_44K = 44100
        private const val SAMPLE_RATE_48K = 48000
        private const val MIN_BUFFER_SIZE = 4096
        private const val OPTIMAL_BUFFER_SIZE = 65536 // Increased to 64KB for Max Battery Savings
        private const val MAX_BUFFER_SIZE = 65536
        private const val WRITE_BUFFER_SIZE = 131072 // Increased to 128KB for write batching
        private const val SILENCE_HOLD_MS = 1000L
        private const val PAUSE_SLEEP_MS = 100L
        private const val EMPTY_READ_SLEEP_MS = 5L
        private const val DEQUEUE_TIMEOUT_US = 5000L
        private const val EOS_TIMEOUT_US = 10000L
        private const val BASE_BACKOFF_DELAY_MS = 500L
        private const val MAX_BACKOFF_DELAY_MS = 10000L
    }
}
