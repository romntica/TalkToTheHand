package com.jinn.talktothehand.presentation

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

class TransferAckListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "AckListener"
        private const val ACK_PATH = "/voice_recording_ack"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        if (messageEvent.path == ACK_PATH) {
            val rawFileName = String(messageEvent.data)
            
            // --- Security: Path Traversal Check ---
            // Ensure the filename is not malicious and does not navigate directories.
            val sanitizedFileName = File(rawFileName).name // Extracts only the filename part
            if (sanitizedFileName != rawFileName) {
                Log.e(TAG, "Path traversal attempt blocked. Original: $rawFileName, Sanitized: $sanitizedFileName")
                return
            }

            Log.d(TAG, "Received ACK for file: $sanitizedFileName")

            // Locate the file in the app's private files directory
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
    }
}
