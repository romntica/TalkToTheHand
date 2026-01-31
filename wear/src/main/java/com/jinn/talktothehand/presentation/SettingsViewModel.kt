package com.jinn.talktothehand.presentation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/**
 * UI State Management for Settings, as per project standards.
 * Holds draft values until 'Apply' is clicked.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val config = RecorderConfig(application)

    var autoStart by mutableStateOf(config.isAutoStartEnabled)
    var telemetry by mutableStateOf(config.isTelemetryEnabled)
    var chunkSize by mutableStateOf(config.maxChunkSizeMb)
    var storageSize by mutableStateOf(config.maxStorageSizeMb)
    var aggressiveVad by mutableStateOf(config.isAggressiveVadEnabled)
    var silenceThreshold by mutableStateOf(config.silenceThreshold)

    fun applySettings() {
        config.isAutoStartEnabled = autoStart
        config.isTelemetryEnabled = telemetry
        config.maxChunkSizeMb = chunkSize
        config.maxStorageSizeMb = storageSize
        config.isAggressiveVadEnabled = aggressiveVad
        config.silenceThreshold = silenceThreshold
    }
}
