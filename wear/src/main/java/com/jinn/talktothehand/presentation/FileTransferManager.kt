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
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class FileTransferManager(private val context: Context) {
    
    private val config = RecorderConfig(context)
    
    init {
        schedulePeriodicSync()
    }

    fun transferFile(file: File) {
        if (!file.exists()) return

        val workData = Data.Builder()
            .putString(FileTransferWorker.KEY_FILE_PATH, file.absolutePath)
            .putBoolean(FileTransferWorker.KEY_TELEMETRY_ENABLED, config.isTelemetryEnabled)
            .build()
            
        val transferWork = OneTimeWorkRequestBuilder<FileTransferWorker>()
            .setInputData(workData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                FileTransferWorker.BACKOFF_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${FileTransferWorker.UNIQUE_WORK_PREFIX}${file.name}",
            ExistingWorkPolicy.KEEP, 
            transferWork
        )
    }

    /**
     * Scans the files directory for abandoned recordings and queues them for transfer.
     */
    fun checkAndRetryPendingTransfers() {
        val filesDir = context.filesDir
        val files = filesDir?.listFiles { _, name -> name.endsWith(".aac") }
        
        files?.forEach { file ->
            if (file.name.contains("_temp")) {
                // If a temp file hasn't been modified in a minute, assume it's from a crash.
                if (System.currentTimeMillis() - file.lastModified() > ABANDONED_FILE_THRESHOLD_MS) {
                     Log.d(TAG, "Found abandoned temp file, recovering: ${file.name}")
                     val recoveredName = file.name.replace("_temp", "_recovered")
                     val recoveredFile = File(file.parent, recoveredName)
                     if (file.renameTo(recoveredFile)) {
                         transferFile(recoveredFile)
                     } else {
                         transferFile(file) // Retry with original name if rename fails
                     }
                }
            } else {
                Log.d(TAG, "Found finalized file, ensuring it's queued for transfer: ${file.name}")
                transferFile(file)
            }
        }
    }
    
    private fun schedulePeriodicSync() {
        // Using 15 minutes directly to avoid a potential build issue with the MIN_PERIODIC_INTERVAL_MILLIS constant.
        val syncWork = PeriodicWorkRequestBuilder<PendingFilesCheckWorker>(
            15, 
            TimeUnit.MINUTES
        ).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PendingFilesCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncWork
        )
    }
    
    companion object {
        private const val TAG = "FileTransferManager"
        private const val ABANDONED_FILE_THRESHOLD_MS = 60000L
    }
}

class FileTransferWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private var remoteLogger: RemoteLogger? = null

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val file = File(filePath)
        
        if (inputData.getBoolean(KEY_TELEMETRY_ENABLED, false)) {
            remoteLogger = RemoteLogger(applicationContext)
        }
        
        remoteLogger?.info(TAG, "Starting transfer for: ${file.name}")
        
        if (!file.exists()) {
             remoteLogger?.error(TAG, "File not found for transfer: $filePath")
             return Result.failure()
        }

        return try {
            val nodeClient = Wearable.getNodeClient(applicationContext)
            
            val nodes = withContext(Dispatchers.IO) {
                try {
                    Tasks.await(nodeClient.connectedNodes, NODE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    remoteLogger?.error(TAG, "Timeout waiting for connected nodes")
                    emptyList<com.google.android.gms.wearable.Node>()
                }
            }
            
            val phoneNode = nodes.firstOrNull()
            if (phoneNode == null) {
                remoteLogger?.info(TAG, "No connected node found. Retrying later.")
                return Result.retry() 
            }

            val channelClient = Wearable.getChannelClient(applicationContext)
            val channelPath = "/voice_recording/${file.name}"
            
            remoteLogger?.info(TAG, "Opening channel to ${phoneNode.displayName} for ${file.name}")
            
            val channel = withContext(Dispatchers.IO) {
                Tasks.await(channelClient.openChannel(phoneNode.id, channelPath), CHANNEL_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            
            val outputStream = withContext(Dispatchers.IO) {
                Tasks.await(channelClient.getOutputStream(channel), CHANNEL_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }

            withContext(Dispatchers.IO) {
                outputStream.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            
            remoteLogger?.info(TAG, "File stream sent successfully: ${file.name}")
            Result.success()
        } catch (e: Exception) {
            remoteLogger?.error(TAG, "Transfer failed, will retry.", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FileTransferWorker"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_TELEMETRY_ENABLED = "telemetry_enabled"
        const val UNIQUE_WORK_PREFIX = "transfer_"
        const val BACKOFF_DELAY_MS = 15000L
        private const val NODE_CONNECT_TIMEOUT_MS = 5000L
        private const val CHANNEL_OPEN_TIMEOUT_MS = 10000L
    }
}

class PendingFilesCheckWorker(
    context: Context, 
    workerParams: WorkerParameters
) : Worker(context, workerParams) { // Changed to simple Worker

    override fun doWork(): Result {
        FileTransferManager(applicationContext).checkAndRetryPendingTransfers()
        return Result.success()
    }
    
    companion object {
        const val UNIQUE_WORK_NAME = "PeriodicFileSync"
    }
}
