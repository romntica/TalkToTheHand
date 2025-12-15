package com.jinn.talktothehand

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class VoiceRecordingListenerService : WearableListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    
    companion object {
        const val ACTION_TRANSFER_STATUS = "com.jinn.talktothehand.TRANSFER_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FILENAME = "filename"
        
        const val STATUS_STARTED = "STARTED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        
        private const val LOG_FILENAME = "wear_logs.txt"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        
        if (messageEvent.path == "/telemetry/error") {
            val logMessage = String(messageEvent.data, StandardCharsets.UTF_8)
            Log.e("VoiceListener", "Received remote error: $logMessage")
            
            // Save to file
            executor.execute {
                saveLogToFile(logMessage)
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        
        if (channel.path.startsWith("/voice_recording")) {
            Log.d("VoiceListener", "Channel opened: ${channel.path}")
            
            // Extract filename from path for notification
            val rawFileName = if (channel.path.contains("/")) {
                channel.path.substringAfterLast("/")
            } else {
                "Unknown"
            }
            
            // Notify UI: Transfer Started
            sendTransferStatus(STATUS_STARTED, rawFileName)
            
            val channelClient = Wearable.getChannelClient(this)
            val senderNodeId = channel.nodeId
            
            // Do I/O in background
            executor.execute {
                try {
                    // Get InputStream
                    val inputStream = Tasks.await(channelClient.getInputStream(channel), 10000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    
                    val fileName = if (rawFileName != "Unknown") {
                        rawFileName
                    } else {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        // Changed extension to .aac for ADTS stream
                        "Recording_${timestamp}.aac"
                    }
                    
                    // Use MediaStore for Android 10+ (Scoped Storage) compatibility.
                    // This is required for Galaxy S25 (Android 15+).
                    val resolver = contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/aac") // Updated MIME type for AAC
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TalkToTheHand")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    var transferSuccess = false
                    
                    if (uri != null) {
                        Log.d("VoiceListener", "Saving to MediaStore: $uri")
                        
                        try {
                            inputStream.use { input ->
                                resolver.openOutputStream(uri).use { output ->
                                    if (output != null) {
                                        input.copyTo(output)
                                        Log.d("VoiceListener", "File saved successfully")
                                        transferSuccess = true
                                    } else {
                                        Log.e("VoiceListener", "Failed to open output stream")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("VoiceListener", "Stream copy failed", e)
                            // Clean up pending entry if failed
                            resolver.delete(uri, null, null)
                        }
                        
                        if (transferSuccess) {
                            // Mark as not pending
                            contentValues.clear()
                            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            resolver.update(uri, contentValues, null, null)
                            
                            // Send ACK back to watch
                            sendAck(senderNodeId, fileName)
                            
                            // Notify UI: Success
                            sendTransferStatus(STATUS_COMPLETED, fileName)
                        } else {
                             sendTransferStatus(STATUS_FAILED, fileName)
                        }
                        
                    } else {
                        Log.e("VoiceListener", "Failed to create MediaStore entry")
                        sendTransferStatus(STATUS_FAILED, fileName)
                    }
                    
                    channelClient.close(channel)
                    
                } catch (e: Exception) {
                    Log.e("VoiceListener", "Error saving file", e)
                    sendTransferStatus(STATUS_FAILED, rawFileName)
                }
            }
        }
    }
    
    private fun saveLogToFile(message: String) {
        val logLine = "$message\n\n"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveLogToMediaStore(logLine)
        } else {
            saveLogToLegacyStorage(logLine)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveLogToMediaStore(logLine: String) {
        val resolver = contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/TalkToTheHand/"
        
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(LOG_FILENAME, relativePath)
        
        var uri: Uri? = null
        
        try {
            // Try to find existing log file to append
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }
            
            // Create new if not found
            if (uri == null) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_FILENAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TalkToTheHand")
                }
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            }
            
            // Write data (append mode "wa")
            uri?.let {
                resolver.openOutputStream(it, "wa")?.use { outputStream ->
                    outputStream.write(logLine.toByteArray(StandardCharsets.UTF_8))
                }
                Log.d("VoiceListener", "Telemetry appended to: $uri")
            }
        } catch (e: Exception) {
            Log.e("VoiceListener", "Failed to save telemetry to MediaStore", e)
        }
    }

    private fun saveLogToLegacyStorage(logLine: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(downloadsDir, "TalkToTheHand")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            val logFile = File(appDir, LOG_FILENAME)
            FileOutputStream(logFile, true).use { fos ->
                fos.write(logLine.toByteArray(StandardCharsets.UTF_8))
            }
            Log.d("VoiceListener", "Telemetry saved to ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("VoiceListener", "Failed to save telemetry to legacy storage", e)
        }
    }
    
    private fun sendAck(nodeId: String, fileName: String) {
        Wearable.getMessageClient(this)
            .sendMessage(nodeId, "/voice_recording_ack", fileName.toByteArray())
            .addOnSuccessListener { 
                Log.d("VoiceListener", "ACK sent for $fileName")
            }
            .addOnFailureListener { e ->
                Log.e("VoiceListener", "Failed to send ACK", e)
            }
    }
    
    private fun sendTransferStatus(status: String, fileName: String) {
        val intent = Intent(ACTION_TRANSFER_STATUS)
        intent.putExtra(EXTRA_STATUS, status)
        intent.putExtra(EXTRA_FILENAME, fileName)
        intent.setPackage(packageName) // Security: Only send to my own app
        sendBroadcast(intent)
    }
}
