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
import android.media.MediaMuxer
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
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class VoiceRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
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
            // If not recording, we return 0 (or previous duration if we wanted to support stopped state, but stop clears it)
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

    // Supported sample rates to try
    // We prioritize the configured rate, then fallback to standard rates
    private var sampleRate = 16000
    private var recordingBufferSize = 0
    
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    // bitRate is now fetched from config in initializeRecorder
    private val silenceThreshold = 1500
    private val silenceHoldMs = 1000L
    
    private var lastDebugLogTime = 0L

    fun start(outputFile: File): Boolean {
        // Prevent starting if we are recording OR if the previous job is still cleaning up
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

        // Set to true immediately to avoid race condition with ViewModel timer check
        _isRecording.set(true)
        isPaused = false
        lastError = null // Reset error
        
        // Initialize timing and file
        currentFile = outputFile
        _startTime = System.currentTimeMillis()
        _accumulatedTime = 0L

        recordingJob = recorderScope.launch {
            try {
                if (!initializeRecorder(outputFile)) {
                    // Cleanup handled in finally block, which will reset _isRecording to false
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
    private fun initializeRecorder(outputFile: File): Boolean {
        try {
            // 1. Determine Sample Rate priority list
            val configuredRate = config.samplingRate
            // Updated supported rates list: 16kHz, 44.1kHz, 48kHz
            val supportedSampleRates = intArrayOf(16000, 44100, 48000)
            
            // Build list: Configured rate first, then others (skipping duplicates)
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
                        // Use a slightly larger buffer for safety, but cap it to avoid huge allocations
                        bufferSize = maxOf(minBufferSize * 2, 4096)
                        if (bufferSize > 65536) bufferSize = 65536
                        
                        // Permission is already checked in start()
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

            // 2. Initialize Encoder
            val currentBitRate = config.bitrate
            Log.d("VoiceRecorder", "Using bitrate: $currentBitRate bps")
            
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, currentBitRate)
            
            // Changed from HE-AAC to LC-AAC for better compatibility
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) 
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, recordingBufferSize)
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // 3. Initialize Muxer
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 4. Start
            mediaCodec?.start()
            audioRecord?.startRecording()
            
            return true

        } catch (e: Exception) {
            remoteLogger.error("VoiceRecorder", "Initialization failed", e)
            return false
        }
    }

    private suspend fun writeAudioData() = withContext(Dispatchers.IO) {
        if (recordingBufferSize <= 0) {
            remoteLogger.error("VoiceRecorder", "Invalid buffer size: $recordingBufferSize")
            lastError = "Invalid buffer size"
            return@withContext
        }

        val buffer: ShortArray
        try {
            buffer = ShortArray(recordingBufferSize / 2) // ShortArray for 16-bit PCM
        } catch (e: OutOfMemoryError) {
            remoteLogger.error("VoiceRecorder", "Failed to allocate memory for buffer", e)
            lastError = "Out of memory"
            return@withContext
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        var presentationTimeUs = 0L
        var lastSoundTime = System.currentTimeMillis()
        var currentFileSize = 0L
        
        // Use configured max size
        val maxStorageBytes = config.maxStorageSizeBytes

        try {
            while (isActive && _isRecording.get()) {
                // Check Max Storage
                if (currentFileSize >= maxStorageBytes) {
                    Log.w("VoiceRecorder", "Max storage limit reached: $maxStorageBytes bytes")
                    lastError = "Storage limit reached"
                    stop() // Internal stop
                    break
                }

                // Handle Pause
                if (isPaused) {
                    // Just wait without reading from AudioRecord to avoid errors
                    Thread.sleep(100)
                    continue
                }

                // Read safely
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (readSize < 0) {
                    if (readSize == AudioRecord.ERROR_INVALID_OPERATION || 
                        readSize == AudioRecord.ERROR_BAD_VALUE ||
                        readSize == AudioRecord.ERROR_DEAD_OBJECT) {
                        remoteLogger.error("VoiceRecorder", "AudioRecord critical error: $readSize")
                        lastError = "Microphone error: $readSize"
                        break
                    }
                    if (isPaused) {
                        continue
                    }
                    Log.w("VoiceRecorder", "AudioRecord read error: $readSize")
                    continue
                }
                
                if (readSize == 0) {
                    // Avoid busy loop if no data is available
                    Thread.sleep(5)
                    continue
                }

                // --- Silence Detection ---
                var maxAmp = 0
                for (i in 0 until readSize) {
                    val amp = abs(buffer[i].toInt())
                    if (amp > maxAmp) maxAmp = amp
                }

                val now = System.currentTimeMillis()
                if (maxAmp > silenceThreshold) {
                    lastSoundTime = now
                }
                
                if (now - lastDebugLogTime > 3000) {
                    Log.d("VoiceRecorder", "Current Amp: $maxAmp, Threshold: $silenceThreshold, Recording: ${now - lastSoundTime < silenceHoldMs}")
                    lastDebugLogTime = now
                }

                // If silence is detected, we simply skip ENCODING this chunk.
                if (now - lastSoundTime < silenceHoldMs) {
                    
                    val inputBufferIndex = mediaCodec?.dequeueInputBuffer(5000) ?: -1
                    if (inputBufferIndex >= 0) {
                        try {
                            val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                
                                val bytesRequired = readSize * 2
                                val capacity = inputBuffer.capacity()

                                if (capacity >= bytesRequired) {
                                    // Robust write
                                    inputBuffer.asShortBuffer().put(buffer, 0, readSize)
                                    mediaCodec?.queueInputBuffer(inputBufferIndex, 0, bytesRequired, presentationTimeUs, 0)
                                    presentationTimeUs += (readSize.toLong() * 1000000L / sampleRate)
                                } else {
                                    Log.w("VoiceRecorder", "Input buffer underflow: Capacity $capacity < Required $bytesRequired. Truncating.")
                                    val shortsToCopy = capacity / 2
                                    if (shortsToCopy > 0) {
                                        inputBuffer.asShortBuffer().put(buffer, 0, shortsToCopy)
                                        mediaCodec?.queueInputBuffer(inputBufferIndex, 0, shortsToCopy * 2, presentationTimeUs, 0)
                                        presentationTimeUs += (shortsToCopy.toLong() * 1000000L / sampleRate)
                                    } else {
                                         mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, 0)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                             remoteLogger.error("VoiceRecorder", "Input buffer queueing failed", e)
                             try {
                                 mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, 0)
                             } catch (e2: Exception) { /* Ignore */ }
                        }
                    }
                }
                
                // --- Drain Encoder Output ---
                // Helper to drain output, returns true if EOS reached
                fun drainOutput(endOfStream: Boolean): Boolean {
                    var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) {
                                val newFormat = mediaCodec?.outputFormat
                                if (newFormat != null) {
                                    trackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                                    if (trackIndex >= 0) {
                                        mediaMuxer?.start()
                                        muxerStarted = true
                                    } else {
                                        remoteLogger.error("VoiceRecorder", "Failed to add track to muxer")
                                    }
                                }
                            }
                        } else {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            
                            if (bufferInfo.size != 0 && muxerStarted) {
                                val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)
                                if (encodedData != null) {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                                    currentFileSize += bufferInfo.size
                                }
                            }
                            
                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                            
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                return true
                            }
                        }
                        outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    }
                    return false
                }
                
                drainOutput(false)
            }
            
            // --- End of Stream Handling ---
            try {
                val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                if (inputBufferIndex >= 0) {
                    mediaCodec?.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                
                var retries = 0
                while (retries < 10) {
                    val bufferInfo = MediaCodec.BufferInfo()
                    var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        retries++
                        continue
                    }
                    
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                             val newFormat = mediaCodec?.outputFormat
                             if (newFormat != null) {
                                trackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                                if (trackIndex >= 0) {
                                    mediaMuxer?.start()
                                    muxerStarted = true
                                }
                             }
                        }
                        continue
                    }
                    
                    if (outputBufferIndex < 0) break 
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    
                    if (bufferInfo.size != 0 && muxerStarted) {
                         val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)
                         if (encodedData != null) {
                             encodedData.position(bufferInfo.offset)
                             encodedData.limit(bufferInfo.offset + bufferInfo.size)
                             mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                         }
                    }
                    
                    mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w("VoiceRecorder", "Error sending/draining EOS", e)
            }

        } catch (e: Exception) {
            remoteLogger.error("VoiceRecorder", "Encoding loop failed", e)
            if (lastError == null) lastError = "Recording failed: ${e.message}"
        } finally {
            if (muxerStarted) {
                 try { mediaMuxer?.stop() } catch (e: Exception) { Log.e("VoiceRecorder", "Muxer stop failed", e) }
            }
        }
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
                audioRecord?.startRecording()
                isPaused = false
                _startTime = System.currentTimeMillis()
            } catch (e: Exception) {
                e.printStackTrace()
                // Recovery: Try to re-init or just stop?
                stop() 
            }
        }
    }

    fun stop() {
        _isRecording.set(false)
        // cleanup() will be called by the coroutine's finally block
    }
    
    // Call this when the recorder is no longer needed (e.g. ViewModel cleared)
    fun release() {
        stop()
        recorderScope.cancel()
    }

    // Suspend function to wait for recording to finish cleaning up
    suspend fun stopRecording() {
        _isRecording.set(false)
        recordingJob?.join()
    }
    
    // Robust Cleanup Function
    private fun cleanup() {
        _isRecording.set(false)
        isPaused = false
        
        // Use separate try-catch blocks for each release to ensure one failure doesn't block others
        try { mediaCodec?.stop() } catch (_: Exception) { /* Ignored */ }
        try { mediaCodec?.release() } catch (_: Exception) { /* Ignored */ }
        mediaCodec = null
        
        try { audioRecord?.stop() } catch (_: Exception) { /* Ignored */ }
        try { audioRecord?.release() } catch (_: Exception) { /* Ignored */ }
        audioRecord = null
        
        try { mediaMuxer?.release() } catch (_: Exception) { /* Ignored */ }
        mediaMuxer = null
        
        try { abandonAudioFocus() } catch (_: Exception) { /* Ignored */ }
        
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
