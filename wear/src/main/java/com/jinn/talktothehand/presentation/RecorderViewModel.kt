package com.jinn.talktothehand.presentation

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.TimeUnit

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    // Removed direct VoiceRecorder instance. Now accessing via Service.
    // Use WeakReference to avoid "StaticFieldLeaks" or "Context leaks" lint warnings
    private var voiceRecorderServiceRef: WeakReference<VoiceRecorderService>? = null
    private var isBound = false
    
    private var recordingFile: File? = null
    private var currentFileTimestamp = ""
    private val fileTransferManager = FileTransferManager(application)
    private val config = RecorderConfig(application) // Config instance
    
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

    private var timerJob: Job? = null
    private var startTime = 0L
    private var accumulatedTime = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VoiceRecorderService.LocalBinder
            val serviceInstance = binder.getService()
            voiceRecorderServiceRef = WeakReference(serviceInstance)
            isBound = true
            
            // Sync state if service was already running
            serviceInstance.recorder?.let { recorder ->
                if (recorder.isRecording) {
                    isRecording = true
                    isPaused = recorder.isPaused
                    
                    // Restore current file if we lost it (e.g. Activity recreation)
                    if (recordingFile == null) {
                        recordingFile = recorder.currentFile
                        // Restore timestamp from filename if possible
                        recordingFile?.name?.let { name ->
                            val parts = name.split("_")
                            if (parts.size >= 2) {
                                currentFileTimestamp = parts[0] + "_" + parts[1]
                            }
                        }
                    }

                    // We don't have exact start time if we rebound, but we can approximate or just start timer
                    if (timerJob == null) {
                        startTimer()
                    }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            voiceRecorderServiceRef = null
        }
    }

    init {
        // Bind to the service
        val intent = Intent(application, VoiceRecorderService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        // Check for any untransferred files from previous sessions
        viewModelScope.launch(Dispatchers.IO) {
            fileTransferManager.checkAndRetryPendingTransfers()
        }
    }

    fun startRecording() {
        errorMessage = null // Clear previous errors
        
        // Start Foreground Service
        val intent = Intent(getApplication(), VoiceRecorderService::class.java)
        intent.action = VoiceRecorderService.ACTION_START_FOREGROUND
        getApplication<Application>().startForegroundService(intent)
        
        if (startNewRecordingFile()) {
            isRecording = true
            isPaused = false
            vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)) // Short buzz for start
            startTimer()
        }
    }
    
    private fun startNewRecordingFile(): Boolean {
        // Use a filesystem-safe date format (avoid colons)
        currentFileTimestamp = DateFormat.format("yyyyMMdd_HHmmss", Date()).toString()
        // Changed extension to .aac for ADTS stream
        val fileName = "${currentFileTimestamp}_temp.aac"
        recordingFile = File(getApplication<Application>().filesDir, fileName)
        
        return recordingFile?.let {
            val recorder = voiceRecorderServiceRef?.get()?.recorder
            if (recorder != null) {
                val started = recorder.start(it)
                if (!started) {
                    errorMessage = recorder.lastError ?: "Failed to start recording"
                    vibrateError()
                }
                started
            } else {
                errorMessage = "Service not connected"
                vibrateError()
                false
            }
        } ?: false
    }

    private fun finalizeCurrentFile() {
        recordingFile?.let { file ->
            if (file.exists()) {
                val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis)
                // Only save if it's not a tiny file resulting from immediate error
                // Also check if it's a valid recording session
                if (seconds > 0 || file.length() > 4096) { 
                    // Changed extension to .aac for ADTS stream
                    val newName = "${currentFileTimestamp}_${seconds}s.aac"
                    val parentFile = file.parentFile ?: getApplication<Application>().filesDir
                    val newFile = File(parentFile, newName)
                    
                    var renameSuccess = file.renameTo(newFile)
                    if (!renameSuccess) {
                        Log.w("RecorderViewModel", "Rename failed, trying manual copy/delete")
                        try {
                            file.copyTo(newFile, overwrite = true)
                            file.delete()
                            renameSuccess = true
                        } catch (e: IOException) {
                            Log.e("RecorderViewModel", "Manual copy failed", e)
                            errorMessage = "Failed to save file: ${e.message}"
                            vibrateError()
                        }
                    }
                    
                    if (renameSuccess) {
                        fileTransferManager.transferFile(newFile)
                    } else {
                        // If rename failed completely, try to transfer the original temp file
                        // so at least we don't lose data, though the name will be ugly.
                        Log.w("RecorderViewModel", "Transferring temp file as fallback")
                        fileTransferManager.transferFile(file)
                    }
                } else {
                    // Delete empty/broken file
                    file.delete()
                }
            }
        }
    }

    fun pauseRecording() {
        voiceRecorderServiceRef?.get()?.recorder?.pause()
        isPaused = true
        stopTimer()
    }

    fun resumeRecording() {
        voiceRecorderServiceRef?.get()?.recorder?.resume()
        isPaused = false
        startTimer()
    }

    fun stopRecording() {
        // Stop timer immediately to prevent race conditions with split logic
        stopTimer()
        
        viewModelScope.launch {
            try {
                voiceRecorderServiceRef?.get()?.recorder?.stopRecording() // Suspend wait
            } catch (e: Exception) {
                Log.e("RecorderViewModel", "Error stopping recorder", e)
            }
            
            isRecording = false
            isPaused = false
            
            // Stop Foreground Service
            val intent = Intent(getApplication(), VoiceRecorderService::class.java)
            intent.action = VoiceRecorderService.ACTION_STOP_FOREGROUND
            getApplication<Application>().startService(intent) // Triggers onStartCommand to stop foreground
            
            finalizeCurrentFile()
            recordingFile = null
            
            accumulatedTime = 0
            elapsedTimeMillis = 0
            fileSizeString = "0 MB"
            
            // Double buzz for stop
            val doubleBuzz = VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1) 
            vibrate(doubleBuzz)
        }
    }
    
    fun dismissError() {
        errorMessage = null
    }

    private fun startTimer() {
        startTime = System.currentTimeMillis()
        timerJob = viewModelScope.launch(Dispatchers.IO) { // Run on IO to handle file operations safely
            while (isActive) {
                // Monitor if recorder stopped externally (e.g. max storage, error)
                val recorder = voiceRecorderServiceRef?.get()?.recorder
                if (recorder != null && !recorder.isRecording && isRecording && !isPaused) {
                    withContext(Dispatchers.Main) {
                        // Check for error from recorder
                        val error = recorder.lastError
                        if (error != null) {
                            errorMessage = error
                            vibrateError()
                        } else {
                            // Normal stop or limit reached without specific error message? 
                            val doubleBuzz = VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
                            vibrate(doubleBuzz)
                        }
                        stopRecording()
                    }
                    break
                }

                // Prefer accurate time from recorder service
                val recorderDuration = recorder?.durationMillis ?: 0L
                val currentElapsed = if (recorderDuration > 0) {
                     recorderDuration
                } else {
                     accumulatedTime + (System.currentTimeMillis() - startTime)
                }
                
                // Update State on Main Thread
                withContext(Dispatchers.Main) {
                     elapsedTimeMillis = currentElapsed
                     updateFileSize()
                }
                
                checkFileSizeAndSplit()
                delay(100)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        accumulatedTime = elapsedTimeMillis
    }

    private fun updateFileSize() {
        recordingFile?.let {
            if (it.exists()) {
                val size = it.length()
                fileSizeString = Formatter.formatFileSize(getApplication(), size)
            }
        }
    }
    
    private suspend fun checkFileSizeAndSplit() {
        val file = recordingFile
        val maxChunkSizeBytes = config.maxChunkSizeBytes // Use dynamic config
        
        if (file != null && file.exists() && file.length() >= maxChunkSizeBytes) {
             // Split logic
             withContext(Dispatchers.Main) {
                 // Stop the current recorder safely
                 try {
                     voiceRecorderServiceRef?.get()?.recorder?.stopRecording()
                 } catch (e: Exception) {
                     Log.e("RecorderViewModel", "Error stopping for split", e)
                 }
                 
                 finalizeCurrentFile()
                 
                 // Start new file
                 if (!startNewRecordingFile()) {
                     stopRecording()
                 } else {
                     // Reset timer base for new file? Or keep cumulative?
                     // If we want cumulative time for the SESSION, we don't reset accumulatedTime.
                     // But we DO reset the startTime because a new file has started writing.
                     // Actually, if we want "Session Time", we keep accumulatedTime. 
                     // BUT, if we are splitting, the "file size" corresponds to the NEW file, 
                     // while "elapsed time" might correspond to total session. 
                     // Let's assume we want Total Session Time.
                 }
             }
        }
    }
    
    private fun vibrate(effect: VibrationEffect) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(effect)
        }
    }
    
    private fun vibrateError() {
        // Long buzz for error (500ms)
        vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }
    
    fun getFormattedTime(): String {
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedTimeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedTimeMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onCleared() {
        super.onCleared()
        // Unbind from service
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}
