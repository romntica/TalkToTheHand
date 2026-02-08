package com.jinn.talktothehand.presentation

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

/**
 * Listens for configuration updates from the mobile app.
 * Updates persistent storage. New settings will be applied on the next recording session.
 */
class ConfigListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "ConfigListener"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/config/update_v2") {
            val config = RecorderConfig(applicationContext)
            val buffer = ByteBuffer.wrap(messageEvent.data)
            
            try {
                val version = buffer.int
                if (version >= 3) {
                    // Update persistent settings. These do not affect current active sessions.
                    config.maxChunkSizeMb = buffer.int
                    config.maxStorageSizeMb = buffer.int
                    config.bitrate = buffer.int
                    config.samplingRate = buffer.int
                    config.isAutoStartEnabled = (buffer.int == 1)
                    config.isTelemetryEnabled = (buffer.int == 1)
                    config.silenceThreshold = buffer.int
                    config.silenceDetectionStrategy = buffer.int
                    
                    Log.i(TAG, "Config updated in storage: version=$version")
                    
                    // Notify MainActivity to update UI if it's currently visible
                    sendBroadcast(Intent("com.jinn.talktothehand.CONFIG_UPDATED").apply {
                        setPackage(packageName)
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse config update", e)
            }
        } else if (messageEvent.path == "/config/request_v2") {
            val config = RecorderConfig(applicationContext)
            val buffer = ByteBuffer.allocate(36)
            buffer.putInt(3)
            buffer.putInt(config.maxChunkSizeMb)
            buffer.putInt(config.maxStorageSizeMb)
            buffer.putInt(config.bitrate)
            buffer.putInt(config.samplingRate)
            buffer.putInt(if (config.isAutoStartEnabled) 1 else 0)
            buffer.putInt(if (config.isTelemetryEnabled) 1 else 0)
            buffer.putInt(config.silenceThreshold)
            buffer.putInt(config.silenceDetectionStrategy)
            
            com.google.android.gms.wearable.Wearable.getMessageClient(this)
                .sendMessage(messageEvent.sourceNodeId, "/config/current_v2", buffer.array())
        }
    }
}
