package com.jinn.talktothehand.presentation

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

class TransferAckListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        if (messageEvent.path == "/voice_recording_ack") {
            val fileName = String(messageEvent.data)
            Log.d("AckListener", "Received ACK for file: $fileName")

            // Locate the file in the app's files directory
            val file = File(filesDir, fileName)
            if (file.exists()) {
                if (file.delete()) {
                    Log.d("AckListener", "File deleted successfully: $fileName")
                } else {
                    Log.w("AckListener", "Failed to delete file: $fileName")
                }
            } else {
                Log.w("AckListener", "File to delete not found: $fileName")
            }
        }
    }
}
