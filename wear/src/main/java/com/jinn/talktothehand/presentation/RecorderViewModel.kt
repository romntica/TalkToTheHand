package com.jinn.talktothehand.presentation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import java.util.concurrent.TimeUnit

/**
 * Wear OS Recorder ViewModel - Display and UI Command only.
 * 
 * Logic follows the Command Pattern via Intents to VoiceRecorderService.
 * UI updates are sticky to handle transient recording pauses during chunk splits.
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val serviceConnection = RecorderServiceConnection(application)
    private var activeRecorder: VoiceRecorder? = null
    
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
                setupRecorderListener(recorder)
                updateStateFromRecorder(recorder)
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            FileTransferManager(application).checkAndRetryPendingTransfers()
        }
    }

    private fun setupRecorderListener(recorder: VoiceRecorder?) {
        recorder?.stateListener = object : VoiceRecorder.StateListener {
            override fun onRecordingStateChanged(paused: Boolean) {
                viewModelScope.launch(Dispatchers.Main) { isPaused = paused }
            }
            override fun onError(message: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    errorMessage = message
                    vibrate(VIBRATION_ERROR)
                }
            }
        }
    }

    private fun updateStateFromRecorder(recorder: VoiceRecorder?) {
        if (recorder != null && recorder.isRecording) {
            isRecording = true
            isPaused = recorder.isPaused
            startUiUpdates()
        }
    }

    fun startRecording() {
        if (isBusy || isRecording) return
        isBusy = true
        errorMessage = null 
        
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
                errorMessage = "Start failed: ${e.message}"
                vibrate(VIBRATION_ERROR)
            } finally {
                delay(500)
                isBusy = false
            }
        }
    }

    fun stopRecording() {
        if (isBusy || !isRecording) return
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
                stopUiUpdates()
                resetUIState()
                vibrate(VIBRATION_DOUBLE)
            } catch (e: Exception) {
                errorMessage = "Stop error: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    /**
     * Restarts the session via Service.
     */
    fun restartRecordingSession() {
        if (isBusy || !isRecording) return
        viewModelScope.launch {
            isBusy = true
            try {
                // Just trigger restart via intent sequence
                stopRecording()
                delay(1000)
                startRecording()
            } finally {
                isBusy = false
            }
        }
    }

    private fun resetUIState() {
        isRecording = false
        isPaused = false
        elapsedTimeMillis = 0
        fileSizeString = "0 MB"
    }

    fun pauseRecording() { activeRecorder?.pause() }
    fun resumeRecording() { activeRecorder?.resume() }

    fun startUiUpdates() {
        if (uiUpdateJob?.isActive == true) return
        uiUpdateJob = viewModelScope.launch(Dispatchers.Main) { 
            var nullCounter = 0
            while (isActive) {
                val recorder = activeRecorder
                
                if (recorder != null && recorder.isRecording) {
                    nullCounter = 0
                    isRecording = true
                    isPaused = recorder.isPaused
                    elapsedTimeMillis = recorder.durationMillis
                    fileSizeString = Formatter.formatFileSize(getApplication(), recorder.currentFileSize)
                } else {
                    // Sticky UI: If recorder is null or not recording, wait up to 3 seconds
                    // before giving up. This prevents flicker during chunk splits.
                    nullCounter++
                    if (nullCounter > 3 && isRecording) {
                        isRecording = false
                        break
                    }
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
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }
    
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
    
    companion object {
        private const val TAG = "RecorderViewModel"
        private val VIBRATION_SHORT = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        private val VIBRATION_DOUBLE = VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
        private val VIBRATION_ERROR = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
