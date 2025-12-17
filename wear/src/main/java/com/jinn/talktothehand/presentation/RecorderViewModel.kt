package com.jinn.talktothehand.presentation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * ViewModel for the Recorder screen.
 * Handles UI state, service binding via RecorderServiceConnection, and high-level recording control.
 *
 * BATTERY OPTIMIZATION NOTE:
 * This ViewModel implements "Lifecycle-Aware UI Updates".
 * The timer that updates 'elapsedTimeMillis' and 'fileSizeString' runs ONLY
 * when the app is visible (ON_RESUME). It stops when the app is backgrounded (ON_PAUSE).
 * This prevents the CPU from waking up unnecessarily when the screen is off.
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    // Helper to manage service connection reactively
    private val serviceConnection = RecorderServiceConnection(application)
    
    // Direct reference to recorder from the connection flow
    // Safe to hold because it uses ApplicationContext
    private var activeRecorder: VoiceRecorder? = null
    
    private var recordingFile: File? = null
    private var currentFileTimestamp = ""
    private val fileTransferManager = FileTransferManager(application)
    private val config = RecorderConfig(application) 
    
    // UI States
    var isRecording by mutableStateOf(false)
        private set
    
    var isPaused by mutableStateOf(false)
        private set

    var elapsedTimeMillis by mutableLongStateOf(0L)
        private set

    var fileSizeString by mutableStateOf("0 MB")
        private set
        
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Job for updating UI (elapsed time, file size).
    private var uiUpdateJob: Job? = null

    init {
        // Start Service Connection
        serviceConnection.bind()
        
        // Observe Recorder Availability
        viewModelScope.launch {
            serviceConnection.recorderFlow.collectLatest { recorder ->
                activeRecorder = recorder
                // Restore UI state if reconnecting to a running recorder
                if (recorder != null && recorder.isRecording) {
                    isRecording = true
                    isPaused = recorder.isPaused
                    
                    // Restore file reference
                    if (recordingFile == null) {
                        recordingFile = recorder.currentFile
                        recordingFile?.name?.let { name ->
                            val parts = name.split("_")
                            if (parts.size >= 2) {
                                currentFileTimestamp = parts[0] + "_" + parts[1]
                            }
                        }
                    }
                } else if (recorder == null) {
                    // Service disconnected / unbound
                    isRecording = false
                }
            }
        }
        
        // Recover any abandoned files from previous crashes or ungraceful stops
        viewModelScope.launch(Dispatchers.IO) {
            fileTransferManager.checkAndRetryPendingTransfers()
        }
    }

    /**
     * Starts the recording process.
     * 1. Starts Foreground Service (so recording survives backgrounding).
     * 2. Initializes new file.
     * 3. Starts hardware recording via Service.
     *
     * Runs on IO dispatcher to avoid blocking Main Thread during IPC calls.
     */
    fun startRecording() {
        errorMessage = null 
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Explicitly start foreground service
                val intent = Intent(getApplication(), VoiceRecorderService::class.java)
                intent.action = VoiceRecorderService.ACTION_START_FOREGROUND
                getApplication<Application>().startForegroundService(intent)
                
                if (startNewRecordingFile("User Button Press")) {
                    withContext(Dispatchers.Main) {
                        isRecording = true
                        isPaused = false
                        vibrate(VIBRATION_SHORT) 
                        startUiUpdates() // Start UI timer since we are initiating from UI
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to start: ${e.message}"
                    vibrate(VIBRATION_ERROR)
                }
            }
        }
    }
    
    private fun startNewRecordingFile(reason: String): Boolean {
        currentFileTimestamp = DateFormat.format(TIMESTAMP_FORMAT, Date()).toString()
        val fileName = "${currentFileTimestamp}${SUFFIX_TEMP}"
        recordingFile = File(getApplication<Application>().filesDir, fileName)
        
        return recordingFile?.let { file ->
            val recorder = activeRecorder
            if (recorder != null) {
                val started = recorder.start(file, reason)
                if (!started) {
                    errorMessage = recorder.lastError ?: "Failed to start recording"
                }
                started
            } else {
                errorMessage = "Service not connected"
                false
            }
        } ?: false
    }

    /**
     * Finalizes the current recording file.
     * Renames from _temp.aac to final filename and queues for transfer.
     */
    private fun finalizeCurrentFile() {
        recordingFile?.let { file ->
            if (file.exists()) {
                val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis)
                // Only save if significant content or time recorded (> 4KB or > 0s visible)
                if (seconds > 0 || file.length() > MIN_FILE_SIZE_BYTES) { 
                    val newName = "${currentFileTimestamp}_${seconds}s${SUFFIX_FINAL}"
                    val parentFile = file.parentFile ?: getApplication<Application>().filesDir
                    val newFile = File(parentFile, newName)
                    
                    var renameSuccess = file.renameTo(newFile)
                    if (!renameSuccess) {
                        Log.w(TAG, "Rename failed, trying manual copy/delete")
                        try {
                            file.copyTo(newFile, overwrite = true)
                            file.delete()
                            renameSuccess = true
                        } catch (e: IOException) {
                            Log.e(TAG, "Manual copy failed", e)
                            errorMessage = "Failed to save file: ${e.message}"
                            vibrate(VIBRATION_ERROR)
                        }
                    }
                    
                    if (renameSuccess) {
                        fileTransferManager.transferFile(newFile)
                    } else {
                        Log.w(TAG, "Transferring temp file as fallback")
                        fileTransferManager.transferFile(file)
                    }
                } else {
                    // Discard empty/tiny files
                    file.delete()
                }
            }
        }
    }

    fun pauseRecording() {
        activeRecorder?.pause()
        isPaused = true
    }

    fun resumeRecording() {
        activeRecorder?.resume()
        isPaused = false
    }

    fun stopRecording() {
        stopUiUpdates()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Wait for recorder to finish its loop cleanly
                activeRecorder?.stopRecording("User Button Press") 
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder", e)
            }
            
            withContext(Dispatchers.Main) {
                isRecording = false
                isPaused = false
            }
            
            // Stop Foreground Service to remove notification
            val intent = Intent(getApplication(), VoiceRecorderService::class.java)
            intent.action = VoiceRecorderService.ACTION_STOP_FOREGROUND
            getApplication<Application>().startService(intent) 
            
            finalizeCurrentFile()
            recordingFile = null
            
            withContext(Dispatchers.Main) {
                elapsedTimeMillis = 0
                fileSizeString = "0 MB"
                vibrate(VIBRATION_DOUBLE)
            }
        }
    }
    
    /**
     * Restarts the current recording session to apply new settings.
     * This respects the current paused state.
     */
    fun restartRecordingSession() {
        if (!isRecording) return

        val wasPaused = isPaused

        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Restarting recording session to apply new config...")
            try {
                activeRecorder?.stopRecording("Config Change Restart")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping for restart", e)
            }
            
            finalizeCurrentFile()
            
            if (startNewRecordingFile("Config Change Restart")) {
                withContext(Dispatchers.Main) {
                    isRecording = true
                    
                    if (wasPaused) {
                        pauseRecording()
                    } else {
                        isPaused = false
                        vibrate(VIBRATION_SHORT)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    // Manually stop fully if restart fails
                    activeRecorder?.stop("Restart Failed")
                    isRecording = false
                    isPaused = false
                    errorMessage = "Failed to restart recording"
                    vibrate(VIBRATION_ERROR)
                }
            }
        }
    }
    
    fun dismissError() {
        errorMessage = null
    }

    /**
     * Starts the coroutine that updates the UI state (time, size).
     * Should only be called when the UI is visible (ON_RESUME) to save battery.
     */
    fun startUiUpdates() {
        if (uiUpdateJob?.isActive == true) return // Already running
        
        uiUpdateJob = viewModelScope.launch(Dispatchers.IO) { 
            while (isActive) {
                // Monitor if recorder stopped externally (e.g. by service error)
                val recorder = activeRecorder
                if (recorder != null && !recorder.isRecording && isRecording) {
                    withContext(Dispatchers.Main) {
                        val error = recorder.lastError
                        if (error != null) {
                            errorMessage = error
                            vibrate(VIBRATION_ERROR)
                        } else {
                            vibrate(VIBRATION_DOUBLE)
                        }
                        // Stop with specific reason for system-triggered stop
                        stopRecordingInternal("System/Error Stop")
                    }
                    break
                }

                // Get accurate duration from recorder
                val recorderDuration = recorder?.durationMillis ?: 0L
                
                withContext(Dispatchers.Main) {
                     elapsedTimeMillis = recorderDuration
                     updateFileSize()
                }
                
                checkFileSizeAndSplit()
                
                // Update UI once per second to save battery
                delay(TIMER_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Internal stop logic without UI-specific stop handling (like button double-press prevention).
     * Used when the system or error stops the recording.
     */
    private suspend fun stopRecordingInternal(reason: String) {
        stopUiUpdates()
        
        // Stop service and finalize
        try {
             // Recorder is likely already stopped if we are here, but ensure clean state
             activeRecorder?.stop(reason)
        } catch (e: Exception) { Log.e(TAG, "Error in internal stop", e) }
        
        isRecording = false
        isPaused = false
        
        val intent = Intent(getApplication(), VoiceRecorderService::class.java)
        intent.action = VoiceRecorderService.ACTION_STOP_FOREGROUND
        getApplication<Application>().startService(intent)
        
        finalizeCurrentFile()
        recordingFile = null
        
        elapsedTimeMillis = 0
        fileSizeString = "0 MB"
    }

    /**
     * Stops the UI update coroutine.
     * Should be called when UI goes to background (ON_PAUSE).
     */
    fun stopUiUpdates() {
        uiUpdateJob?.cancel()
        uiUpdateJob = null
    }

    private fun updateFileSize() {
        // Use the realtime size from the recorder directly
        val size = activeRecorder?.currentFileSize ?: 0L
        if (size > 0) {
            fileSizeString = Formatter.formatFileSize(getApplication(), size)
        }
    }
    
    /**
     * Checks if the current file has exceeded the max chunk size.
     * If so, splits the recording by stopping and starting a new file.
     * Runs on IO thread to avoid blocking main thread.
     */
    private suspend fun checkFileSizeAndSplit() {
        val recorder = activeRecorder
        val currentSize = recorder?.currentFileSize ?: 0L
        val maxChunkSizeBytes = config.maxChunkSizeBytes 
        
        if (currentSize >= maxChunkSizeBytes) {
             // Perform split logic
             try {
                 recorder?.stopRecording("File Split Limit Reached")
             } catch (e: Exception) {
                 Log.e(TAG, "Error stopping for split", e)
             }
             
             finalizeCurrentFile()
             
             if (!startNewRecordingFile("File Split Continue")) {
                 withContext(Dispatchers.Main) {
                     stopRecordingInternal("Split Restart Failed") 
                 }
             } 
        }
    }
    
    private fun vibrate(effect: VibrationEffect) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(effect)
        }
    }
    
    fun getFormattedTime(): String {
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedTimeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedTimeMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onCleared() {
        super.onCleared()
        // Delegate unbinding to our helper class
        serviceConnection.unbind()
    }
    
    companion object {
        private const val TAG = "RecorderViewModel"
        private const val TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"
        private const val SUFFIX_TEMP = "_temp.aac"
        private const val SUFFIX_FINAL = ".aac"
        private const val MIN_FILE_SIZE_BYTES = 4096L
        private const val TIMER_INTERVAL_MS = 1000L // 1Hz update for battery efficiency
        
        // Vibration Effects
        private val VIBRATION_SHORT = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        private val VIBRATION_DOUBLE = VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
        private val VIBRATION_ERROR = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
