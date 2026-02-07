package com.jinn.talktothehand.presentation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/**
 * UI State Management for Settings.
 * Holds draft values until 'Apply' is clicked.
 * Now triggers Guardian upon application to ensure immediate effect.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val config = RecorderConfig(application)
    private val guardian = RecordingGuardian(application)

    var autoStart: Boolean by mutableStateOf(config.isAutoStartEnabled)
    var telemetry: Boolean by mutableStateOf(config.isTelemetryEnabled)
    var chunkSize: Int by mutableStateOf(config.maxChunkSizeMb)
    var storageSize: Int by mutableStateOf(config.maxStorageSizeMb)
    var aggressiveVad: Boolean by mutableStateOf(config.isAggressiveVadEnabled)
    var silenceThreshold: Int by mutableStateOf(config.silenceThreshold)
    var bitrate: Int by mutableStateOf(config.bitrate)
    var samplingRate: Int by mutableStateOf(config.samplingRate)

    /**
     * Persists settings and immediately reconciles state.
     */
    fun applySettings() {
        config.saveBatch(
            autoStart = autoStart,
            telemetry = telemetry,
            chunkSizeMb = chunkSize,
            storageSizeMb = storageSize,
            bitrate = bitrate,
            samplingRate = samplingRate,
            silenceThreshold = silenceThreshold,
            isAggressiveVad = aggressiveVad
        )
        
        // Trigger Guardian to start/stop engine based on NEW settings immediately
        guardian.handleStartup(isBoot = false)
    }
}
