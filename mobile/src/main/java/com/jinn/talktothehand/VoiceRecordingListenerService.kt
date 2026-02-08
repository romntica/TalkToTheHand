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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service to listen for incoming voice recordings and telemetry logs from the Wear OS device.
 * Optimized for Android 14+ with enhanced resource management and data isolation.
 */
class VoiceRecordingListenerService : WearableListenerService() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    companion object {
        const val TAG = "VoiceListener"
        const val ACTION_TRANSFER_STATUS = "com.jinn.talktothehand.TRANSFER_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FILENAME = "filename"
        
        const val STATUS_STARTED = "STARTED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        
        const val ACTION_CONFIG_RECEIVED = "com.jinn.talktothehand.CONFIG_RECEIVED"
        const val EXTRA_CONFIG_DATA = "config_data"

        // Path constants for data isolation
        private const val PATH_VOICE_RECORDING = "/voice_recording"
        private const val PATH_TELEMETRY_LOG = "/telemetry/log"

        // ACK Paths (Synchronized with Wear constants)
        private const val PATH_VOICE_ACK = "/voice_recording_ack"
        private const val PATH_TELEMETRY_ACK = "/telemetry_ack"
        
        private const val CHANNEL_TIMEOUT_MS = 10000L
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        when (messageEvent.path) {
            "/telemetry/error" -> {
                val logMessage = String(messageEvent.data, StandardCharsets.UTF_8)
                executor.execute { saveLogToFile(logMessage) }
            }
            "/config/current_v2" -> {
                 val intent = Intent(ACTION_CONFIG_RECEIVED).apply {
                     putExtra(EXTRA_CONFIG_DATA, messageEvent.data)
                     setPackage(packageName)
                 }
                 sendBroadcast(intent)
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        
        val isAudio = channel.path.startsWith(PATH_VOICE_RECORDING)
        val isLog = channel.path.startsWith(PATH_TELEMETRY_LOG)

        // Only handle registered paths
        if (!isAudio && !isLog) return

        Log.d(TAG, "Channel opened: ${channel.path}")

        val rawFileName = channel.path.substringAfterLast("/", "Unknown")
        sendTransferStatus(STATUS_STARTED, rawFileName)

        val channelClient = Wearable.getChannelClient(this)
        val senderNodeId = channel.nodeId

        executor.execute {
            try {
                val inputStream = Tasks.await(channelClient.getInputStream(channel), CHANNEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                // Define destination folder based on data type. 
                // Telemetry logs are stored in the 'Logs' subfolder of the main 'TalkToTheHand' directory.
                val relativePath = if (isLog) {
                    "${Environment.DIRECTORY_DOWNLOADS}/TalkToTheHand/Logs"
                } else {
                    "${Environment.DIRECTORY_DOWNLOADS}/TalkToTheHand"
                }

                val mimeType = if (isLog) "text/plain" else "audio/aac"

                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, rawFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                var success = false

                if (uri != null) {
                    try {
                        inputStream.use { input ->
                            resolver.openOutputStream(uri).use { output ->
                                if (output != null) {
                                    input.copyTo(output)
                                    success = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Stream copy failed", e)
                        resolver.delete(uri, null, null)
                    }

                    if (success) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues.clear()
                            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            resolver.update(uri, contentValues, null, null)
                        }

                        // Send appropriate ACK back to the watch to finalize the transfer on their end.
                        if (isLog) {
                            sendTelemetryAck(senderNodeId)
                        } else {
                            sendAck(senderNodeId, rawFileName)
                        }

                        sendTransferStatus(STATUS_COMPLETED, rawFileName)
                        Log.d(TAG, "File saved to $relativePath: $rawFileName")
                    } else {
                        sendTransferStatus(STATUS_FAILED, rawFileName)
                    }
                } else {
                    sendTransferStatus(STATUS_FAILED, rawFileName)
                }
                channelClient.close(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing channel data", e)
                sendTransferStatus(STATUS_FAILED, rawFileName)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: Exception) {
            executor.shutdownNow()
        }
        super.onDestroy()
    }
    
    private fun saveLogToFile(message: String) {
        val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val logFilename = "WearLog_$dateStamp.txt"
        val logLine = "$message\n\n"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveLogToMediaStore(logFilename, logLine)
        } else {
            saveLogToLegacyStorage(logFilename, logLine)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveLogToMediaStore(filename: String, logLine: String) {
        val resolver = contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/TalkToTheHand/Logs/"
        val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(filename, relativePath)
        
        var existingUri: Uri? = null
        try {
            resolver.query(queryUri, arrayOf(MediaStore.Downloads._ID), selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    existingUri = ContentUris.withAppendedId(queryUri, id)
                }
            }

            val uri = existingUri ?: run {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                resolver.insert(queryUri, contentValues)
            }

            if (uri != null) {
                resolver.openOutputStream(uri, "wa")?.use { 
                    it.write(logLine.toByteArray(StandardCharsets.UTF_8)) 
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save telemetry to MediaStore", e)
        }
    }

    private fun saveLogToLegacyStorage(filename: String, logLine: String) {
        try {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadsDir, "TalkToTheHand/Logs")
            if (!logDir.exists()) logDir.mkdirs()
            FileOutputStream(File(logDir, filename), true).use { 
                it.write(logLine.toByteArray(StandardCharsets.UTF_8)) 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save telemetry to legacy storage", e)
        }
    }
    
    private fun sendAck(nodeId: String, fileName: String) {
        Wearable.getMessageClient(this)
            .sendMessage(nodeId, PATH_VOICE_ACK, fileName.toByteArray())
    }

    private fun sendTelemetryAck(nodeId: String) {
        Wearable.getMessageClient(this)
            .sendMessage(nodeId, PATH_TELEMETRY_ACK, ByteArray(0))
    }
    
    private fun sendTransferStatus(status: String, fileName: String) {
        val intent = Intent(ACTION_TRANSFER_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_FILENAME, fileName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
