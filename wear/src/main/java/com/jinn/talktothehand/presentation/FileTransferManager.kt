package com.jinn.talktothehand.presentation

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Manages reliable file transfers from Watch to Mobile.
 * Handles both audio chunks and diagnostic telemetry logs.
 */
class FileTransferManager(private val context: Context) {
    
    private val config = RecorderConfig(context)
    
    init {
        schedulePeriodicSync()
    }

    /**
     * Enqueues a voice recording file for transfer.
     */
    fun transferFile(file: File) {
        if (!file.exists()) return

        val workData = Data.Builder()
            .putString(FileTransferWorker.KEY_FILE_PATH, file.absolutePath)
            .putBoolean(FileTransferWorker.KEY_TELEMETRY_ENABLED, config.isTelemetryEnabled)
            .build()
            
        val transferWork = OneTimeWorkRequestBuilder<FileTransferWorker>()
            .setInputData(workData)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(TRANSFER_INITIAL_DELAY_SEC, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FileTransferWorker.BACKOFF_DELAY_MS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${FileTransferWorker.UNIQUE_WORK_PREFIX}${file.name}",
            ExistingWorkPolicy.KEEP, 
            transferWork
        )
    }

    /**
     * Enqueues the telemetry log file for transfer.
     * Triggered periodically or after an app crash/restart.
     */
    fun transferTelemetryLog() {
        val logFile = File(context.filesDir, "telemetry.log")
        if (!logFile.exists() || logFile.length() == 0L) return

        val workData = Data.Builder()
            .putString(TelemetryTransferWorker.KEY_FILE_PATH, logFile.absolutePath)
            .build()

        val logTransferWork = OneTimeWorkRequestBuilder<TelemetryTransferWorker>()
            .setInputData(workData)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            TelemetryTransferWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE, // Always replace with latest log state
            logTransferWork
        )
    }

    fun checkAndRetryPendingTransfers() {
        val filesDir = context.filesDir

        // 1. Recover Audio Files
        filesDir?.listFiles { _, name -> name.endsWith(".aac") }?.forEach { file ->
            if (file.name.contains("_temp")) {
                if (System.currentTimeMillis() - file.lastModified() > ABANDONED_FILE_THRESHOLD_MS) {
                     val recoveredFile = File(file.parent, file.name.replace("_temp", "_recovered"))
                     if (file.renameTo(recoveredFile)) transferFile(recoveredFile) else transferFile(file)
                }
            } else {
                transferFile(file)
            }
        }

        // 2. Trigger Telemetry Sync
        transferTelemetryLog()
    }
    
    private fun schedulePeriodicSync() {
        // [UX Optimization] Changed from 15 minutes to 24 hours to save battery.
        // Periodic work acts as a daily cleanup for any failed or abandoned transfers.
        val syncWork = PeriodicWorkRequestBuilder<PendingFilesCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(false) // Daily check doesn't necessarily need charging
                .build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PendingFilesCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, // Update existing 15m work to 24h
            syncWork
        )
    }
    
    companion object {
        private const val TAG = "FileTransferManager"
        private const val ABANDONED_FILE_THRESHOLD_MS = 60000L
        private const val TRANSFER_INITIAL_DELAY_SEC = 5L
    }
}

/**
 * Worker for transferring voice chunks.
 */
class FileTransferWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val file = File(filePath)
        if (!file.exists()) return Result.failure()

        return try {
            val phoneNode = getPhoneNode(applicationContext) ?: return Result.retry()
            val channelClient = Wearable.getChannelClient(applicationContext)
            val channelPath = "/voice_recording/${file.name}"

            val channel = withContext(Dispatchers.IO) { Tasks.await(channelClient.openChannel(phoneNode.id, channelPath)) }
            val outputStream = withContext(Dispatchers.IO) { Tasks.await(channelClient.getOutputStream(channel)) }

            withContext(Dispatchers.IO) {
                outputStream.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
        const val KEY_TELEMETRY_ENABLED = "telemetry_enabled"
        const val UNIQUE_WORK_PREFIX = "transfer_"
        const val BACKOFF_DELAY_MS = 15000L
    }
}

/**
 * Worker specifically for Telemetry Logs.
 */
class TelemetryTransferWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return Result.success()

        return try {
            val phoneNode = getPhoneNode(applicationContext) ?: return Result.retry()
            val channelClient = Wearable.getChannelClient(applicationContext)
            val channelPath = "/telemetry/log/${file.name}"

            val channel = withContext(Dispatchers.IO) { Tasks.await(channelClient.openChannel(phoneNode.id, channelPath)) }
            val outputStream = withContext(Dispatchers.IO) { Tasks.await(channelClient.getOutputStream(channel)) }

            withContext(Dispatchers.IO) {
                outputStream.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
        const val UNIQUE_WORK_NAME = "telemetry_log_transfer"
    }
}

suspend fun getPhoneNode(context: Context): com.google.android.gms.wearable.Node? {
    val nodeClient = Wearable.getNodeClient(context)
    return withContext(Dispatchers.IO) {
        try {
            val nodes = Tasks.await(nodeClient.connectedNodes, 5000, TimeUnit.MILLISECONDS)
            nodes.firstOrNull()
        } catch (e: TimeoutException) {
            null
        }
    }
}

class PendingFilesCheckWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        FileTransferManager(applicationContext).checkAndRetryPendingTransfers()
        return Result.success()
    }
    companion object { const val UNIQUE_WORK_NAME = "PeriodicFileSync" }
}
