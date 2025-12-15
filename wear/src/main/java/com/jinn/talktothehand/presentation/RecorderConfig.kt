package com.jinn.talktothehand.presentation

import android.content.Context
import androidx.core.content.edit

class RecorderConfig(context: Context) {
    private val prefs = context.getSharedPreferences("recorder_config", Context.MODE_PRIVATE)

    // Stored in KB to support 0.5 MB (512 KB)
    var maxChunkSizeKb: Int
        get() = prefs.getInt("max_chunk_kb", 1024) // Default 1 MB = 1024 KB
        set(value) = prefs.edit { putInt("max_chunk_kb", value) }

    // Stored in MB to support 100 MB increments
    var maxStorageSizeMb: Int
        get() = prefs.getInt("max_storage_mb", 2048) // Default 2 GB = 2048 MB
        set(value) = prefs.edit { putInt("max_storage_mb", value) }
    
    // Stored in bps (e.g., 32000)
    var bitrate: Int
        get() = prefs.getInt("audio_bitrate", 32000) // Default 32 KB = 32000 bps
        set(value) = prefs.edit { putInt("audio_bitrate", value) }
        
    // Sampling rate in Hz (e.g., 44100, 48000, 16000)
    var samplingRate: Int
        get() = prefs.getInt("audio_sampling_rate", 16000) // Default 16kHz
        set(value) = prefs.edit { putInt("audio_sampling_rate", value) }

    // Auto-start on boot
    var isAutoStartEnabled: Boolean
        get() = prefs.getBoolean("auto_start_enabled", false)
        set(value) = prefs.edit { putBoolean("auto_start_enabled", value) }
        
    // Telemetry enabled
    var isTelemetryEnabled: Boolean
        get() = prefs.getBoolean("telemetry_enabled", false) // Default false as requested
        set(value) = prefs.edit { putBoolean("telemetry_enabled", value) }
        
    val maxChunkSizeBytes: Long
        get() = maxChunkSizeKb * 1024L
        
    val maxStorageSizeBytes: Long
        get() = maxStorageSizeMb * 1024L * 1024L
}
