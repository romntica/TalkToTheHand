package com.jinn.talktothehand.presentation

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

class TransferAckListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "AckListener"
        const val ACK_PATH = "/voice_recording_ack"
        const val TELEMETRY_ACK_PATH = "/telemetry_ack"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            ACK_PATH -> {
                val rawFileName = String(messageEvent.data)
                handleVoiceRecordingAck(rawFileName)
            }
            TELEMETRY_ACK_PATH -> {
                handleTelemetryAck()
            }
        }
    }

    private fun handleVoiceRecordingAck(rawFileName: String) {
        // --- Security: Path Traversal Check ---
        val sanitizedFileName = File(rawFileName).name
        if (sanitizedFileName != rawFileName) {
            Log.e(TAG, "Path traversal attempt blocked. Original: $rawFileName, Sanitized: $sanitizedFileName")
            return
        }

        Log.d(TAG, "Received ACK for file: $sanitizedFileName")

        val file = File(filesDir, sanitizedFileName)
        if (file.exists()) {
            if (file.delete()) {
                Log.d(TAG, "File deleted successfully: $sanitizedFileName")
            } else {
                Log.w(TAG, "Failed to delete file: $sanitizedFileName")
            }
        } else {
            Log.w(TAG, "File to delete not found: $sanitizedFileName")
        }
    }

    private fun handleTelemetryAck() {
        Log.d(TAG, "Received Telemetry ACK. Clearing log file.")
        val logFile = File(filesDir, "telemetry.log")
        if (logFile.exists()) {
            if (logFile.delete()) {
                Log.d(TAG, "Telemetry log file cleared.")
            } else {
                Log.w(TAG, "Failed to clear telemetry log file.")
            }
        }
    }
}
