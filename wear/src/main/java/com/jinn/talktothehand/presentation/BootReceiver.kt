package com.jinn.talktothehand.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lightweight BootReceiver.
 * Does NOT start MainActivity immediately to prevent system-wide startup ANRs.
 * Complications will start their own process (:complication) when requested by the system.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val config = RecorderConfig(context)
            val sessionLock = SessionLock(context.filesDir)
            
            val needsAction = config.isAutoStartEnabled || sessionLock.isLocked
            
            if (needsAction) {
                Log.i(TAG, "Boot detected. Deferring functional startup to RecordingGuardian.")
                // We don't start MainActivity here anymore. 
                // Instead, the RecordingGuardian (Control Plane) will be triggered 
                // by the system or when the user eventually opens the app.
                // This allows the :complication process to have enough resources to start.
            }
        }
    }
    
    companion object {
        private const val TAG = "BootReceiver"
    }
}
