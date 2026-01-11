package com.jinn.talktothehand.presentation

import android.content.Context
import androidx.core.content.edit

class RecorderConfig(context: Context) {
    private val prefs = context.getSharedPreferences("recorder_config", Context.MODE_PRIVATE)

    var maxChunkSizeMb: Int
        get() = prefs.getInt("max_chunk_mb", 5) 
        set(value) = prefs.edit { putInt("max_chunk_mb", value) }

    var maxStorageSizeMb: Int
        get() = prefs.getInt("max_storage_mb", 2048)
        set(value) = prefs.edit { putInt("max_storage_mb", value) }
    
    var bitrate: Int
        get() = prefs.getInt("audio_bitrate", 32000)
        set(value) = prefs.edit { putInt("audio_bitrate", value) }
        
    var samplingRate: Int
        get() = prefs.getInt("audio_sampling_rate", 16000)
        set(value) = prefs.edit { putInt("audio_sampling_rate", value) }

    var isAutoStartEnabled: Boolean
        get() = prefs.getBoolean("auto_start_enabled", false)
        set(value) = prefs.edit { putBoolean("auto_start_enabled", value) }
        
    var isTelemetryEnabled: Boolean
        get() = prefs.getBoolean("telemetry_enabled", false)
        set(value) = prefs.edit { putBoolean("telemetry_enabled", value) }
        
    var silenceThreshold: Int
        get() = prefs.getInt("silence_threshold", 1000)
        set(value) = prefs.edit { putInt("silence_threshold", value) }
        
    var silenceDetectionStrategy: Int
        get() = prefs.getInt("silence_strategy", 0)
        set(value) = prefs.edit { putInt("silence_strategy", value) }

    /**
     * Total number of chunks recorded in the current session.
     */
    var sessionChunkCount: Int
        get() = prefs.getInt("session_chunk_count", 0)
        set(value) = prefs.edit { putInt("session_chunk_count", value) }

    /**
     * Current size of the active recording chunk in bytes.
     */
    var currentChunkSizeBytes: Long
        get() = prefs.getLong("current_chunk_size", 0L)
        set(value) = prefs.edit { putLong("current_chunk_size", value) }

    /**
     * Whether the recorder is currently active.
     */
    var isRecording: Boolean
        get() = prefs.getBoolean("is_recording", false)
        set(value) = prefs.edit { putBoolean("is_recording", value) }

    val maxChunkSizeBytes: Long get() = maxChunkSizeMb * 1024L * 1024L
    val maxStorageSizeBytes: Long get() = maxStorageSizeMb * 1024L * 1024L
}
