package com.jinn.talktothehand

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.work.Configuration
import com.jinn.talktothehand.presentation.RecordingGuardian
import kotlinx.coroutines.*

/**
 * Standard Application class with Asynchronous Integrity Management.
 * Ensures the app's process starts instantly without blocking on I/O.
 */
class RecorderApplication : Application(), Configuration.Provider {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        // 1. Elevate priority slightly for responsiveness
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Exception) {}

        super.onCreate()
        
        val processName = getProcessNameInternal()
        Log.i("RecorderApp", "Process started: $processName")

        // 2. NON-BLOCKING SELF-HEALING: 
        // Only run integrity checks in the main process and offload to background scope.
        // This prevents Startup ANRs by returning control to the system immediately.
        if (processName == packageName) {
            appScope.launch {
                Log.d("RecorderApp", "Background Integrity Check initiated")
                val guardian = RecordingGuardian(this@RecorderApplication)
                guardian.verifyIntegrity()
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
