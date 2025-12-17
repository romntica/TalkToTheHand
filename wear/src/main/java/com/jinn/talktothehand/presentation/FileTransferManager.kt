package com.jinn.talktothehand.presentation

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class FileTransferManager(private val context: Context) {
    
    // Create a single config instance to read settings
    private val config = RecorderConfig(context)
    
    init {
        schedulePeriodicSync()
    }

    fun transferFile(file: File) {
        if (!file.exists()) return

        // Pass telemetry setting directly to the worker to avoid SharedPreferences issues in a background process
        val workData = Data.Builder()
            .putString("file_path", file.absolutePath)
            .putBoolean("telemetry_enabled", config.isTelemetryEnabled)
            .build()
            
        // We use WorkManager to handle the transfer reliability.
        val transferWork = OneTimeWorkRequestBuilder<FileTransferWorker>()
            .setInputData(workData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED) // Requires connection (Bluetooth/WiFi) to phone
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL, // Exponential backoff for better battery life
                15000L, // Start with 15 seconds (15s, 30s, 60s, 2m, 4m...)
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "transfer_${file.name}", // Unique name per file
            ExistingWorkPolicy.KEEP, // Don't duplicate if already queued
            transferWork
        )
    }

    /**
     * Scans the files directory for any finalized recordings (not temp)
     * AND also retries unexpected *temp files which might be from crashes.
     */
    fun checkAndRetryPendingTransfers() {
        val filesDir = context.filesDir
        // Now including _temp files in the scan to recover crashed sessions
        // Changed extension to .aac for ADTS stream
        val files = filesDir?.listFiles { _, name -> 
            name.endsWith(".aac") 
        }
        
        // Safely iterate over files
        files?.forEach { file ->
            // If it's a temp file, we should probably rename it to a recovered file first
            // so we don't try to transfer it while it's actively being written to (though this runs on init)
            // But if the app just started, any existing _temp file is likely a leftover from a crash.
            if (file.name.contains("_temp")) {
                val lastModified = file.lastModified()
                // If the file hasn't been modified in the last 1 minute, assume it's abandoned
                if (System.currentTimeMillis() - lastModified > 60000) {
                     Log.d("FileTransferManager", "Found abandoned temp file, recovering: ${file.name}")
                     val recoveredName = file.name.replace("_temp", "_recovered")
                     val recoveredFile = File(file.parent, recoveredName)
                     if (file.renameTo(recoveredFile)) {
                         transferFile(recoveredFile)
                     } else {
                         // If rename fails, try transferring as is
                         transferFile(file)
                     }
                }
            } else {
                Log.d("FileTransferManager", "Found pending file, retrying transfer: ${file.name}")
                transferFile(file)
            }
        }
    }
    
    private fun schedulePeriodicSync() {
        // Run a background check every 15 minutes (minimum allowed by WorkManager)
        // to cleanup any stuck files.
        val syncWork = PeriodicWorkRequestBuilder<PendingFilesCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "PeriodicFileSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncWork
        )
    }
}

class FileTransferWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    // Logger is now nullable and initialized only if telemetry is enabled
    private var remoteLogger: RemoteLogger? = null

    override suspend fun doWork(): Result {
        val filePath = inputData.getString("file_path") ?: return Result.failure()
        val file = File(filePath)
        
        // Read telemetry flag from worker data, not from SharedPreferences
        val isTelemetryEnabled = inputData.getBoolean("telemetry_enabled", false)
        if (isTelemetryEnabled) {
            remoteLogger = RemoteLogger(applicationContext)
        }
        
        remoteLogger?.info("FileTransferWorker", "Starting transfer for: ${file.name}")
        
        if (!file.exists()) {
             remoteLogger?.error("FileTransferWorker", "File not found for transfer: $filePath")
             return Result.failure()
        }

        return try {
            val nodeClient = Wearable.getNodeClient(applicationContext)
            
            // Wait for connected nodes with a timeout to avoid hanging indefinitely
            val nodes = withContext(Dispatchers.IO) {
                try {
                    Tasks.await(nodeClient.connectedNodes, 5000, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    remoteLogger?.error("FileTransferWorker", "Timeout waiting for connected nodes")
                    emptyList<com.google.android.gms.wearable.Node>()
                }
            }
            
            val phoneNode = nodes.firstOrNull()
            
            if (phoneNode == null) {
                remoteLogger?.info("FileTransferWorker", "No connected node found. Retrying later.")
                return Result.retry() 
            }

            val channelClient = Wearable.getChannelClient(applicationContext)
            val channelPath = "/voice_recording/${file.name}"
            
            remoteLogger?.info("FileTransferWorker", "Opening channel to ${phoneNode.displayName} for ${file.name}")
            
            // Add timeouts to channel operations
            val channel = withContext(Dispatchers.IO) {
                Tasks.await(channelClient.openChannel(phoneNode.id, channelPath), 10000, TimeUnit.MILLISECONDS)
            }
            
            val outputStream = withContext(Dispatchers.IO) {
                Tasks.await(channelClient.getOutputStream(channel), 10000, TimeUnit.MILLISECONDS)
            }

            withContext(Dispatchers.IO) {
                outputStream.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            
            remoteLogger?.info("FileTransferWorker", "File stream sent successfully: ${file.name}")
            Result.success()
        } catch (e: Exception) {
            remoteLogger?.error("FileTransferWorker", "Transfer failed, will retry.", e)
            Result.retry()
        }
    }
}

class PendingFilesCheckWorker(
    context: Context, 
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // Re-use the manager logic to scan and enqueue
        FileTransferManager(applicationContext).checkAndRetryPendingTransfers()
        return Result.success()
    }
}
