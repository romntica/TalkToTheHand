package com.jinn.talktothehand.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lightweight BootReceiver.
 * Triggers the RecordingGuardian to handle auto-start or crash recovery.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i(TAG, "Boot detected: $action. Triggering Guardian startup.")
            
            // CRITICAL: Actually trigger the functional startup sequence
            val guardian = RecordingGuardian(context)
            guardian.handleStartup(isBoot = true)
        }
    }
    
    companion object {
        private const val TAG = "BootReceiver"
    }
}
