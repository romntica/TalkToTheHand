package com.jinn.talktothehand.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Wear OS Recorder ViewModel with explicit service event synchronization.
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val serviceConnection = RecorderServiceConnection(application)
    private var activeRecorder: VoiceRecorder? = null
    private val config = RecorderConfig(application)
    
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // --- UI States ---
    var isRecording by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var elapsedTimeMillis by mutableLongStateOf(0L)
        private set
    var fileSizeString by mutableStateOf("0 MB")
        private set
    var sessionChunkCount by mutableIntStateOf(0)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set

    private var uiUpdateJob: Job? = null

    // Explicit Status Receiver for Service Events
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VoiceRecorderService.ACTION_STATUS_CHANGED) {
                val serviceIsRecording = intent.getBooleanExtra(VoiceRecorderService.EXTRA_IS_RECORDING, false)
                val error = intent.getStringExtra(VoiceRecorderService.EXTRA_ERROR)

                viewModelScope.launch(Dispatchers.Main) {
                    isRecording = serviceIsRecording
                    if (error != null) {
                        errorMessage = error
                        vibrate(VIBRATION_ERROR)
                    }
                    if (isRecording) startUiUpdates() else stopUiUpdates()
                }
            }
        }
    }

    init {
        serviceConnection.bind()
        
        // Register Status Broadcast Receiver
        val filter = IntentFilter(VoiceRecorderService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(statusReceiver, filter)
        }

        viewModelScope.launch {
            serviceConnection.recorderFlow.collectLatest { recorder ->
                activeRecorder = recorder
                if (recorder != null) {
                    setupRecorderListener(recorder)
                    syncStateWithService()
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            FileTransferManager(application).checkAndRetryPendingTransfers()
        }
    }

    private fun setupRecorderListener(recorder: VoiceRecorder) {
        recorder.stateListener = object : VoiceRecorder.StateListener {
            override fun onRecordingStateChanged(paused: Boolean) {
                isPaused = paused
            }
            override fun onError(message: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    errorMessage = message
                    vibrate(VIBRATION_ERROR)
                    syncStateWithService()
                }
            }
        }
    }

    fun refreshState() {
        activeRecorder?.forceWakeup()
        syncStateWithService()
    }

    private fun syncStateWithService() {
        val recorder = activeRecorder
        val isServiceActive = (recorder != null && recorder.isRecording) || config.isRecording
        
        isRecording = isServiceActive
        if (isServiceActive) {
            isPaused = recorder?.isPaused ?: false
            sessionChunkCount = config.sessionChunkCount
            startUiUpdates()
        } else {
            stopUiUpdates()
            resetUIState()
        }
    }

    fun startRecording() {
        if (isBusy || isRecording) return
        isBusy = true
        errorMessage = null 

        // Optimistic UI state - will be corrected by Broadcast if service fails
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
                vibrate(VIBRATION_SHORT)
            } catch (e: Exception) {
                isRecording = false
                errorMessage = "Launch failed: ${e.message}"
                vibrate(VIBRATION_ERROR)
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
                vibrate(VIBRATION_DOUBLE)
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
                stopRecording()
                delay(1000)
                startRecording()
            } finally {
                isBusy = false
            }
        }
    }

    private fun resetUIState() {
        isPaused = false
        elapsedTimeMillis = 0
        fileSizeString = "0 MB"
        sessionChunkCount = 0
    }

    fun pauseRecording() { activeRecorder?.pause() }
    fun resumeRecording() { activeRecorder?.resume() }

    fun startUiUpdates() {
        if (uiUpdateJob?.isActive == true) return
        uiUpdateJob = viewModelScope.launch(Dispatchers.Main) { 
            while (isActive) {
                val recorder = activeRecorder
                
                if (recorder != null && recorder.isRecording) {
                    isRecording = true
                    isPaused = recorder.isPaused
                    elapsedTimeMillis = recorder.durationMillis
                    fileSizeString = Formatter.formatFileSize(getApplication(), recorder.currentFileSize)
                    sessionChunkCount = config.sessionChunkCount
                } else if (config.isRecording) {
                    isRecording = true
                    fileSizeString = Formatter.formatFileSize(getApplication(), config.currentChunkSizeBytes)
                    sessionChunkCount = config.sessionChunkCount
                } else {
                    isRecording = false
                    resetUIState()
                    break
                }
                delay(1000) 
            }
        }
    }

    fun stopUiUpdates() {
        uiUpdateJob?.cancel()
        uiUpdateJob = null
    }

    fun dismissError() { errorMessage = null }
    
    private fun vibrate(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(effect)
        }
    }
    
    fun getFormattedTime(): String {
        val h = TimeUnit.MILLISECONDS.toHours(elapsedTimeMillis)
        val m = TimeUnit.MILLISECONDS.toMinutes(elapsedTimeMillis) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(elapsedTimeMillis) % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(statusReceiver)
        serviceConnection.unbind()
    }
    
    companion object {
        private val VIBRATION_SHORT = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        private val VIBRATION_DOUBLE = VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
        private val VIBRATION_ERROR = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
