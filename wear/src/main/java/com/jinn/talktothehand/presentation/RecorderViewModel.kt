package com.jinn.talktothehand.presentation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Wear OS Recorder ViewModel using Messenger for IPC sync.
 * UI Only: Vibration and hardware logic moved to the Service.
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val serviceConnection = RecorderServiceConnection(application)
    private val config = RecorderConfig(application)

    // --- UI States ---
    var isRecording by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var elapsedTimeMillis by mutableLongStateOf(0L)
        private set
    var fileSizeString by mutableStateOf("0 MB")
        private set
    var sessionChunkCount by mutableStateOf(0)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set

    init {
        serviceConnection.bind()
        
        viewModelScope.launch {
            serviceConnection.engineStatus.collectLatest { status ->
                isRecording = status.isRecording
                isPaused = status.isPaused
                elapsedTimeMillis = status.elapsedMillis
                fileSizeString = Formatter.formatFileSize(getApplication(), status.currentFileSize)
                sessionChunkCount = config.sessionChunkCount
                
                status.error?.let {
                    errorMessage = it
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            FileTransferManager(application).checkAndRetryPendingTransfers()
        }
    }

    fun refreshState() {
        serviceConnection.sendCommand(VoiceRecorderService.MSG_FORCE_WAKEUP)
    }

    fun startRecording() {
        if (isBusy || isRecording) return
        isBusy = true
        errorMessage = null 
        isRecording = true
        
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val intent = Intent(context, VoiceRecorderService::class.java).apply {
                        action = VoiceRecorderService.ACTION_START_FOREGROUND
                    }
                    context.startForegroundService(intent)
                }
            } catch (e: Exception) {
                isRecording = false
                errorMessage = "Launch failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    fun stopRecording() {
        if (isBusy) return
        isBusy = true
        
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val intent = Intent(context, VoiceRecorderService::class.java).apply {
                        action = VoiceRecorderService.ACTION_STOP_FOREGROUND
                    }
                    context.startService(intent)
                }
            } catch (e: Exception) {
                errorMessage = "Stop error: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    fun restartRecordingSession() {
        if (isBusy || !isRecording) return
        viewModelScope.launch {
            isBusy = true
            try {
                // Using MSG_SET_RECORDING with arg1=0 to stop
                serviceConnection.sendCommand(VoiceRecorderService.MSG_SET_RECORDING, 0)
                delay(1000)
                startRecording()
            } finally {
                isBusy = false
            }
        }
    }

    fun pauseRecording() { serviceConnection.sendCommand(VoiceRecorderService.MSG_PAUSE) }
    fun resumeRecording() { serviceConnection.sendCommand(VoiceRecorderService.MSG_RESUME) }

    fun startUiUpdates() { }
    fun stopUiUpdates() { }

    fun dismissError() { errorMessage = null }
    
    fun getFormattedTime(): String {
        val h = TimeUnit.MILLISECONDS.toHours(elapsedTimeMillis)
        val m = TimeUnit.MILLISECONDS.toMinutes(elapsedTimeMillis) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis) % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    override fun onCleared() {
        super.onCleared()
        serviceConnection.unbind()
    }
}
