package com.jinn.talktothehand.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver that handles complication clicks.
 * Using a BroadcastReceiver is more efficient than a transparent Activity
 * and satisfies the background-to-foreground service start restrictions
 * when triggered by a system complication.
 */
class ComplicationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_TOGGLE_RECORDING) {
            val serviceIntent = Intent(context, VoiceRecorderService::class.java).apply {
                this.action = VoiceRecorderService.ACTION_TOGGLE_RECORDING
            }
            // Starting FGS from a complication-triggered receiver is allowed
            context.startForegroundService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "com.jinn.talktothehand.action.TOGGLE_RECORDING"
    }
}
