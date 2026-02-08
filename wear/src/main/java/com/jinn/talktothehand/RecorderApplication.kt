package com.jinn.talktothehand

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.jinn.talktothehand.presentation.RecorderConfig
import com.jinn.talktothehand.presentation.RecordingGuardian
import com.jinn.talktothehand.presentation.SessionState
import kotlinx.coroutines.*

/**
 * Optimized Application class for Wear OS.
 * Ensures conservative state management and fast startup.
 */
class RecorderApplication : Application(), Configuration.Provider {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        
        val processName = getProcessNameInternal()
        Log.i("RecorderApp", "Process started: $processName")

        if (processName == packageName) {
            // 1. IMMEDIATE STATE PURGE: 
            // Clear UI state synchronously on the main thread to ensure 
            // complications never show "Recording" by mistake after reboot.
            try {
                SessionState(this).update(isRecording = false, isPaused = false, chunkCount = 0, sizeBytes = 0L)
            } catch (e: Exception) {
                Log.e("RecorderApp", "Failed to purge state", e)
            }
            
            appScope.launch {
                try {
                    val context = this@RecorderApplication
                    val config = RecorderConfig(context)
                    
                    // Background maintenance
                    config.performMigration(context)
                    
                    val guardian = RecordingGuardian(context)
                    guardian.verifyIntegrity()
                    
                    Log.d("RecorderApp", "Background maintenance completed.")
                } catch (e: Exception) {
                    Log.e("RecorderApp", "Startup error", e)
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.ERROR)
            .build()

    private fun getProcessNameInternal(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            packageName
        }
    }
}
