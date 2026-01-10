package com.jinn.talktothehand.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver for BOOT_COMPLETED events to handle auto-start functionality.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = RecorderConfig(context)
            
            if (config.isAutoStartEnabled) {
                Log.i(TAG, "Boot completed and auto-start is enabled. Initiating background recording.")
                
                // Start the service directly to begin recording immediately without UI interaction.
                val serviceIntent = Intent(context, VoiceRecorderService::class.java).apply {
                    action = VoiceRecorderService.ACTION_START_FOREGROUND
                }
                
                // Android 8.0+ requires startForegroundService for background starts.
                context.startForegroundService(serviceIntent)
                
                // Also launch MainActivity to provide visual feedback to the user.
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            } else {
                Log.d(TAG, "Boot completed but auto-start is disabled.")
            }
        }
    }
    
    companion object {
        private const val TAG = "BootReceiver"
    }
}
