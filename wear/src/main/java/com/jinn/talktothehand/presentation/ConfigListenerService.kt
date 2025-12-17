package com.jinn.talktothehand.presentation

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

class ConfigListenerService : WearableListenerService() {
    
    companion object {
        private const val TAG = "ConfigListener"
        // Paths
        private const val PATH_UPDATE_V2 = "/config/update_v2"
        private const val PATH_REQUEST_V2 = "/config/request_v2"
        private const val PATH_CURRENT_V2 = "/config/current_v2"
        
        // Protocol Constants
        private const val PROTOCOL_VERSION_1 = 1
        private const val PROTOCOL_VERSION_2 = 2
        private const val PROTOCOL_VERSION_3 = 3
        private const val CURRENT_PROTOCOL_VERSION = PROTOCOL_VERSION_3
        
        // Packet Sizes
        private const val MIN_HEADER_SIZE = 4 // 1 int
        private const val PAYLOAD_SIZE_V1 = 24 // 6 ints
        // V2 added silenceThreshold
        // V3 adds silenceDetectionStrategy
        private const val RESPONSE_BUFFER_SIZE = 36 // 9 ints (Version + 8 fields)
        
        // Broadcast Actions
        const val ACTION_CONFIG_CHANGED = "com.jinn.talktothehand.CONFIG_CHANGED"
        const val EXTRA_REQUIRES_RESTART = "requires_restart"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        
        val config = RecorderConfig(this)

        if (messageEvent.path == PATH_UPDATE_V2) {
            handleConfigUpdate(messageEvent.data, config)
        } else if (messageEvent.path == PATH_REQUEST_V2) {
            handleConfigRequest(messageEvent.sourceNodeId, config)
        }
    }
    
    private fun handleConfigUpdate(data: ByteArray, config: RecorderConfig) {
        try {
            val buffer = ByteBuffer.wrap(data)
            if (buffer.capacity() < MIN_HEADER_SIZE) {
                Log.w(TAG, "Buffer too small for header: ${buffer.capacity()}")
                return
            }
            
            val version = buffer.int
            
            // Support versions 1, 2, and 3
            if (version in PROTOCOL_VERSION_1..PROTOCOL_VERSION_3) {
                // Base V1 payload: 6 ints
                if (buffer.remaining() >= PAYLOAD_SIZE_V1) { 
                    // **FIX**: Reading as MB, not KB
                    val chunkMb = buffer.int
                    val storageMb = buffer.int
                    val bitrate = buffer.int
                    val sampleRate = buffer.int
                    val autoStartInt = buffer.int
                    val telemetryInt = buffer.int
                    
                    // Capture old values to check for significant changes
                    val oldBitrate = config.bitrate
                    val oldSampleRate = config.samplingRate
                    val oldStrategy = config.silenceDetectionStrategy
                    
                    // **FIX**: Setting maxChunkSizeMb
                    config.maxChunkSizeMb = chunkMb
                    config.maxStorageSizeMb = storageMb
                    config.bitrate = bitrate
                    config.samplingRate = sampleRate
                    config.isAutoStartEnabled = (autoStartInt == 1)
                    config.isTelemetryEnabled = (telemetryInt == 1)
                    
                    // Default values for newer fields
                    var silenceThreshold = 1000 
                    var silenceStrategy = 0
                    
                    // V2: Silence Threshold
                    if (version >= PROTOCOL_VERSION_2 && buffer.remaining() >= 4) {
                        silenceThreshold = buffer.int
                        config.silenceThreshold = silenceThreshold
                    }
                    
                    // V3: Silence Detection Strategy
                    if (version >= PROTOCOL_VERSION_3 && buffer.remaining() >= 4) {
                        silenceStrategy = buffer.int
                        config.silenceDetectionStrategy = silenceStrategy
                    }
                    
                    Log.d(TAG, "Received v$version config: Chunk=${chunkMb}MB, Storage=${storageMb}MB, Bitrate=$bitrate bps, Rate=$sampleRate Hz, Silence=$silenceThreshold, Strategy=$silenceStrategy")
                    
                    // Check if critical parameters changed
                    // Changing strategy usually doesn't require full restart of app, but safe to notify
                    val requiresRestart = (oldBitrate != bitrate) || (oldSampleRate != sampleRate) || (oldStrategy != silenceStrategy)
                    notifyConfigChanged(requiresRestart)
                } else {
                    Log.w(TAG, "Buffer too small for payload V1: ${buffer.remaining()}")
                }
            } else {
                Log.w(TAG, "Unsupported protocol version: $version")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing config update", e)
        }
    }
    
    private fun notifyConfigChanged(requiresRestart: Boolean) {
        val intent = Intent(ACTION_CONFIG_CHANGED)
        intent.putExtra(EXTRA_REQUIRES_RESTART, requiresRestart)
        intent.setPackage(packageName) // Restrict to this app
        sendBroadcast(intent)
    }
    
    private fun handleConfigRequest(nodeId: String, config: RecorderConfig) {
        Log.d(TAG, "Received config request from $nodeId")
        
        try {
            // v3 Response: [VERSION][CHUNK][STORAGE][BITRATE][RATE][AUTOSTART][TELEMETRY][SILENCE][STRATEGY]
            val buffer = ByteBuffer.allocate(RESPONSE_BUFFER_SIZE)
            buffer.putInt(CURRENT_PROTOCOL_VERSION)
            // **FIX**: Sending maxChunkSizeMb
            buffer.putInt(config.maxChunkSizeMb)
            buffer.putInt(config.maxStorageSizeMb)
            buffer.putInt(config.bitrate)
            buffer.putInt(config.samplingRate)
            buffer.putInt(if (config.isAutoStartEnabled) 1 else 0)
            buffer.putInt(if (config.isTelemetryEnabled) 1 else 0)
            buffer.putInt(config.silenceThreshold)
            buffer.putInt(config.silenceDetectionStrategy)
            
            Wearable.getMessageClient(this)
                .sendMessage(nodeId, PATH_CURRENT_V2, buffer.array())
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send config response", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error building/sending config response", e)
        }
    }
}
