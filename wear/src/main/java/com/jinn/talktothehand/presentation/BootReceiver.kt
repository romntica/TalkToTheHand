package com.jinn.talktothehand.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = RecorderConfig(context)
            if (config.isAutoStartEnabled) {
                Log.d("BootReceiver", "Boot completed and auto-start enabled, launching MainActivity")
                val launchIntent = Intent(context, MainActivity::class.java)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                Log.d("BootReceiver", "Boot completed but auto-start is disabled")
            }
        }
    }
}
