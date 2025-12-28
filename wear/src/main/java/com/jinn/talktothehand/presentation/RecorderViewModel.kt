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

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val serviceConnection = RecorderServiceConnection(application)
    private var activeRecorder: VoiceRecorder? = null
    
    private var recordingFile: File? = null
    private var currentFileTimestamp = ""
    private val fileTransferManager = FileTransferManager(application)
    private val config = RecorderConfig(application) 
    private val remoteLogger = RemoteLogger(application)
    private val sessionLock = SessionLock(application.filesDir)
    
    // --- UI States ---
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
    var isBusy by mutableStateOf(false)
        private set

    private var uiUpdateJob: Job? = null

    init {
        serviceConnection.bind()
        
        viewModelScope.launch {
            serviceConnection.recorderFlow.collectLatest { recorder ->
                activeRecorder = recorder
                if (recorder != null && recorder.isRecording) {
                    isRecording = true
                    isPaused = recorder.isPaused
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
                    isRecording = false
                }
            }
        }
        
        checkForCrashedSession()
        
        viewModelScope.launch(Dispatchers.IO) {
            fileTransferManager.checkAndRetryPendingTransfers()
        }
    }

    private fun checkForCrashedSession() {
        if (sessionLock.isLocked) {
            val reason = sessionLock.readLockReason()
            remoteLogger.error("RecorderViewModel", "Unclean shutdown detected. Details: $reason")
            vibrate(VIBRATION_ERROR)
            sessionLock.unlock()
        }
    }

    fun startRecording() {
        if (isBusy) return
        isBusy = true
        errorMessage = null 
        
        viewModelScope.launch {
            try {
                val started = withContext(Dispatchers.IO) {
                    val intent = Intent(getApplication(), VoiceRecorderService::class.java)
                    intent.action = VoiceRecorderService.ACTION_START_FOREGROUND
                    getApplication<Application>().startForegroundService(intent)
                    startNewRecordingFile("User Button Press")
                }
                if (started) {
                    isRecording = true
                    isPaused = false
                    startUiUpdates()
                }
            } catch (e: Exception) {
                errorMessage = "Failed to start: ${e.message}"
                vibrate(VIBRATION_ERROR)
            } finally {
                isBusy = false
            }
        }
    }
    
    private fun startNewRecordingFile(reason: String): Boolean {
        currentFileTimestamp = DateFormat.format(TIMESTAMP_FORMAT, Date()).toString()
        val fileName = "${currentFileTimestamp}${SUFFIX_TEMP}"
        recordingFile = File(getApplication<Application>().filesDir, fileName)
        
        if (!sessionLock.lock(reason)) {
            remoteLogger.error("RecorderViewModel", "Failed to create session lock file")
        }
        
        return recordingFile?.let { file ->
            val recorder = activeRecorder
            if (recorder != null) {
                val started = recorder.start(file, reason)
                if (!started) {
                    errorMessage = recorder.lastError ?: "Failed to start recording"
                    sessionLock.unlock()
                }
                started
            } else {
                errorMessage = "Service not connected"
                sessionLock.unlock()
                false
            }
        } ?: false
    }

    private fun finalizeCurrentFile() {
        val finalFileSize = activeRecorder?.currentFileSize ?: 0L
        recordingFile?.let { file ->
            if (!file.exists()) {
                Log.w(TAG, "File to finalize does not exist: ${file.path}")
                return
            }

            val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis)
            if (seconds > 0 || finalFileSize > MIN_FILE_SIZE_BYTES) { 
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
                file.delete()
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
        stopRecordingInternal("User Button Press")
    }
    
    private fun stopRecordingInternal(reason: String) {
        if (isBusy || !isRecording) return
        isBusy = true
        stopUiUpdates()
        
        val currentDuration = activeRecorder?.durationMillis ?: elapsedTimeMillis

        if (currentDuration < 2000 && reason == "User Button Press") {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        activeRecorder?.stop(reason)
                        recordingFile?.delete()
                        sessionLock.unlock()

                        val intent = Intent(getApplication(), VoiceRecorderService::class.java)
                        intent.action = VoiceRecorderService.ACTION_STOP_FOREGROUND
                        getApplication<Application>().startService(intent)
                    }
                    isRecording = false
                    isPaused = false
                    recordingFile = null
                    elapsedTimeMillis = 0
                    fileSizeString = "0 MB"
                } finally {
                    isBusy = false
                }
            }
            return
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activeRecorder?.stopRecording(reason)
                    finalizeCurrentFile()
                    sessionLock.unlock()

                    val intent = Intent(getApplication(), VoiceRecorderService::class.java)
                    intent.action = VoiceRecorderService.ACTION_STOP_FOREGROUND
                    getApplication<Application>().startService(intent) 
                }
                isRecording = false
                isPaused = false
                recordingFile = null
                elapsedTimeMillis = 0
                fileSizeString = "0 MB"
            } finally {
                isBusy = false
            }
        }
    }
    
    fun restartRecordingSession() {
        if (!isRecording || isBusy) return
        isBusy = true

        val wasPaused = isPaused

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activeRecorder?.stopRecording("Config Change Restart")
                    finalizeCurrentFile()
                    startNewRecordingFile("Config Change Restart")
                }
                isRecording = true
                if (wasPaused) {
                    pauseRecording()
                }
            } finally {
                isBusy = false
            }
        }
    }
    
    fun dismissError() {
        errorMessage = null
    }

    fun startUiUpdates() {
        if (uiUpdateJob?.isActive == true) return
        uiUpdateJob = viewModelScope.launch(Dispatchers.Main) { 
            while (isActive) {
                val recorder = activeRecorder
                if (recorder != null && !recorder.isRecording && isRecording) {
                    stopRecordingInternal("System/Error Stop")
                    break
                }

                elapsedTimeMillis = recorder?.durationMillis ?: 0L
                updateFileSize()
                
                checkFileSizeAndSplit()
                delay(TIMER_INTERVAL_MS)
            }
        }
    }

    fun stopUiUpdates() {
        uiUpdateJob?.cancel()
        uiUpdateJob = null
    }

    private fun updateFileSize() {
        val size = activeRecorder?.currentFileSize ?: 0L
        fileSizeString = Formatter.formatFileSize(getApplication(), size)
    }
    
    private suspend fun checkFileSizeAndSplit() {
        val recorder = activeRecorder
        val currentSize = recorder?.currentFileSize ?: 0L
        val maxChunkSizeBytes = config.maxChunkSizeBytes 
        
        if (currentSize >= maxChunkSizeBytes) {
            withContext(Dispatchers.IO) {
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
        serviceConnection.unbind()
    }
    
    companion object {
        private const val TAG = "RecorderViewModel"
        private const val TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"
        private const val SUFFIX_TEMP = "_temp.aac"
        private const val SUFFIX_FINAL = ".aac"
        private const val MIN_FILE_SIZE_BYTES = 1024L
        private const val TIMER_INTERVAL_MS = 1000L
        
        private val VIBRATION_SHORT = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        private val VIBRATION_DOUBLE = VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
        private val VIBRATION_ERROR = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
