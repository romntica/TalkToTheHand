package com.jinn.talktothehand.presentation

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

class ConfigListenerService : WearableListenerService() {
    
    private val PROTOCOL_VERSION = 1

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        
        val config = RecorderConfig(this)

        if (messageEvent.path == "/config/update_v2") {
            try {
                val buffer = ByteBuffer.wrap(messageEvent.data)
                if (buffer.capacity() >= 4) {
                    val version = buffer.int
                    if (version == PROTOCOL_VERSION) {
                        // v1 Packet: [VERSION][CHUNK][STORAGE][BITRATE][RATE][AUTOSTART][TELEMETRY]
                        if (buffer.remaining() >= 24) { // 6 ints
                            val chunkKb = buffer.int
                            val storageMb = buffer.int
                            val bitrate = buffer.int
                            val sampleRate = buffer.int
                            val autoStartInt = buffer.int
                            val telemetryInt = buffer.int
                            
                            Log.d("ConfigListener", "Received v2 config: Chunk=${chunkKb}KB, Storage=${storageMb}MB, Bitrate=$bitrate bps, Rate=$sampleRate Hz, AutoStart=$autoStartInt, Telemetry=$telemetryInt")
                            
                            config.maxChunkSizeKb = chunkKb
                            config.maxStorageSizeMb = storageMb
                            config.bitrate = bitrate
                            config.samplingRate = sampleRate
                            config.isAutoStartEnabled = (autoStartInt == 1)
                            config.isTelemetryEnabled = (telemetryInt == 1)
                        }
                    } else {
                        Log.w("ConfigListener", "Unsupported protocol version: $version")
                    }
                }
            } catch (e: Exception) {
                Log.e("ConfigListener", "Error parsing v2 update", e)
            }
        } 
        
        else if (messageEvent.path == "/config/request_v2") {
            Log.d("ConfigListener", "Received v2 config request")
            // v2 Response: [VERSION][CHUNK][STORAGE][BITRATE][RATE][AUTOSTART][TELEMETRY]
            val buffer = ByteBuffer.allocate(28)
            buffer.putInt(PROTOCOL_VERSION)
            buffer.putInt(config.maxChunkSizeKb)
            buffer.putInt(config.maxStorageSizeMb)
            buffer.putInt(config.bitrate)
            buffer.putInt(config.samplingRate)
            buffer.putInt(if (config.isAutoStartEnabled) 1 else 0)
            buffer.putInt(if (config.isTelemetryEnabled) 1 else 0)
            
            val nodeId = messageEvent.sourceNodeId
            com.google.android.gms.wearable.Wearable.getMessageClient(this)
                .sendMessage(nodeId, "/config/current_v2", buffer.array())
        }
    }
}
