package com.jinn.talktothehand.presentation

import android.content.Context
import androidx.core.content.edit

class RecorderConfig(context: Context) {
    private val prefs = context.getSharedPreferences("recorder_config", Context.MODE_PRIVATE)

    // Stored in MB. Default 10MB.
    var maxChunkSizeMb: Int
        get() = prefs.getInt("max_chunk_mb", 10) 
        set(value) = prefs.edit { putInt("max_chunk_mb", value) }

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
        
    // Silence detection threshold (amplitude)
    var silenceThreshold: Int
        get() = prefs.getInt("silence_threshold", 1000) // Default 1000
        set(value) = prefs.edit { putInt("silence_threshold", value) }
        
    // Silence Detection Strategy
    // 0 = Standard (Sleep CPU, Keep Mic On) - No data loss, good battery.
    // 1 = Aggressive (Duty Cycling) - Mic Off during silence. Saves more battery, potential latency/clipping on wake.
    var silenceDetectionStrategy: Int
        get() = prefs.getInt("silence_strategy", 0) // Default 0 (Standard)
        set(value) = prefs.edit { putInt("silence_strategy", value) }
        
    val maxChunkSizeBytes: Long
        get() = maxChunkSizeMb * 1024L * 1024L
        
    val maxStorageSizeBytes: Long
        get() = maxStorageSizeMb * 1024L * 1024L
}
