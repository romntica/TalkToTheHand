package com.jinn.talktothehand.presentation

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import android.util.Log

/**
 * Direct-Boot aware configuration manager with automatic migration support.
 * Ensures settings persist correctly even when storage location moves for security.
 */
class RecorderConfig(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "recorder_config"
        private const val TAG = "RecorderConfig"
    }

    private val safeContext: Context = run {
        val deviceContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
        // Migration: If the protected storage doesn't exist yet, move from credential storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!deviceContext.moveSharedPreferencesFrom(context, PREFS_NAME)) {
                // If move returns false, it usually means it's already moved or no source exists
            }
        }
        deviceContext
    }

    private val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveBatch(
        autoStart: Boolean,
        telemetry: Boolean,
        chunkSizeMb: Int,
        storageSizeMb: Int,
        bitrate: Int,
        samplingRate: Int,
        silenceThreshold: Int,
        isAggressiveVad: Boolean
    ) {
        prefs.edit(commit = true) { // Use commit for cross-process immediate visibility
            putBoolean("auto_start_enabled", autoStart)
            putBoolean("telemetry_enabled", telemetry)
            putInt("max_chunk_mb", chunkSizeMb)
            putInt("max_storage_mb", storageSizeMb)
            putInt("audio_bitrate", bitrate)
            putInt("audio_sampling_rate", samplingRate)
            putInt("silence_threshold", silenceThreshold)
            putInt("silence_strategy", if (isAggressiveVad) 1 else 0)
        }
    }

    var maxChunkSizeMb: Int
        get() = prefs.getInt("max_chunk_mb", 5) 
        set(value) = prefs.edit(commit = true) { putInt("max_chunk_mb", value) }

    var maxStorageSizeMb: Int
        get() = prefs.getInt("max_storage_mb", 500) 
        set(value) = prefs.edit(commit = true) { putInt("max_storage_mb", value) }
    
    var bitrate: Int
        get() = prefs.getInt("audio_bitrate", 32000)
        set(value) = prefs.edit(commit = true) { putInt("audio_bitrate", value) }
        
    var samplingRate: Int
        get() = prefs.getInt("audio_sampling_rate", 16000)
        set(value) = prefs.edit(commit = true) { putInt("audio_sampling_rate", value) }

    var isAutoStartEnabled: Boolean
        get() = prefs.getBoolean("auto_start_enabled", false)
        set(value) = prefs.edit(commit = true) { putBoolean("auto_start_enabled", value) }
        
    var isTelemetryEnabled: Boolean
        get() = prefs.getBoolean("telemetry_enabled", false)
        set(value) = prefs.edit(commit = true) { putBoolean("telemetry_enabled", value) }
        
    var silenceThreshold: Int
        get() = prefs.getInt("silence_threshold", 1000)
        set(value) = prefs.edit(commit = true) { putInt("silence_threshold", value) }
        
    var silenceDetectionStrategy: Int
        get() = prefs.getInt("silence_strategy", 0)
        set(value) = prefs.edit(commit = true) { putInt("silence_strategy", value) }

    var isAggressiveVadEnabled: Boolean
        get() = silenceDetectionStrategy == 1
        set(value) { silenceDetectionStrategy = if (value) 1 else 0 }

    var sessionChunkCount: Int
        get() = prefs.getInt("session_chunk_count", 0)
        set(value) = prefs.edit(commit = true) { putInt("session_chunk_count", value) }

    var currentChunkSizeBytes: Long
        get() = prefs.getLong("current_chunk_size", 0L)
        set(value) = prefs.edit(commit = true) { putLong("current_chunk_size", value) }

    var isRecording: Boolean
        get() = prefs.getBoolean("is_recording", false)
        set(value) = prefs.edit(commit = true) { putBoolean("is_recording", value) }

    var isPaused: Boolean
        get() = prefs.getBoolean("is_paused", false)
        set(value) = prefs.edit(commit = true) { putBoolean("is_paused", value) }

    var isTransitioning: Boolean
        get() = prefs.getBoolean("is_transitioning", false)
        set(value) = prefs.edit(commit = true) { putBoolean("is_transitioning", value) }

    val maxChunkSizeBytes: Long get() = maxChunkSizeMb * 1024L * 1024L
    val maxStorageSizeBytes: Long get() = maxStorageSizeMb * 1024L * 1024L
}
