package com.jinn.talktothehand.presentation

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.*
import java.io.File

/**
 * THE GUARDIAN (Control Plane): Central orchestrator for the entire app.
 * Ensures data integrity and reconciles engine state with UI/Complications.
 */
class RecordingGuardian(private val context: Context) {

    private val config = RecorderConfig(context)
    private val sessionLock = SessionLock(context.filesDir)
    private val sessionState = SessionState(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "Guardian"
        const val ACTION_STATE_CHANGED = "com.jinn.talktothehand.action.STATE_CHANGED"
        const val ACTION_FILE_READY = "com.jinn.talktothehand.action.FILE_READY"
        
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_CHUNK_COUNT = "chunk_count"
        const val EXTRA_CURRENT_SIZE = "current_size"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        
        const val EXTRA_IS_HEARTBEAT = "is_heartbeat"
        const val EXTRA_HEARTBEAT_COUNT = "heartbeat_count"
    }

    /**
     * Reconciles recording state based on a specific user action (Start/Stop).
     * Uses isTransitioning flag to prevent race conditions during cross-process sync.
     */
    fun reconcileWithAction(isStarting: Boolean) {
        scope.launch {
            config.isTransitioning = true // LOCK
            if (isStarting) {
                Log.d(TAG, "reconcileWithAction: START requested")
                startEngine("UserIntent")
            } else {
                Log.d(TAG, "reconcileWithAction: STOP requested")
                stopEngine()
            }
            refreshComplications()
        }
    }

    /**
     * CRITICAL: Verifies that the sync file matches the actual engine status.
     * Prevents stale "Recording" indicators on watch faces after reboot or crash.
     */
    fun verifyIntegrity() {
        scope.launch {
            val isEngineActuallyLocked = sessionLock.isLocked
            if (!isEngineActuallyLocked) {
                Log.d(TAG, "Integrity Check: Engine is IDLE. Syncing file state.")
                sessionState.update(isRecording = false, isPaused = false, chunkCount = 0, sizeBytes = 0L)
                config.isRecording = false
                config.isPaused = false
            } else {
                Log.i(TAG, "Integrity Check: Engine is ACTIVE. Maintaining state.")
            }
            refreshComplications()
        }
    }

    /**
     * Handles specific startup sequences (Boot vs App Launch).
     */
    fun handleStartup(isBoot: Boolean = false) {
        scope.launch {
            config.isTransitioning = false
            
            val isEngineActuallyLocked = sessionLock.isLocked
            if (!isEngineActuallyLocked) {
                sessionState.update(isRecording = false, isPaused = false, chunkCount = 0, sizeBytes = 0L)
            }

            if (isBoot) {
                Log.i(TAG, "Boot startup: Checking AutoStart.")
                if (isEngineActuallyLocked) recoverAbandonedFile()

                if (config.isAutoStartEnabled) {
                    delay(10000L) // Wait for system to settle
                    if (config.isAutoStartEnabled && !sessionLock.isLocked) {
                        Log.i(TAG, "Executing Boot Auto-start.")
                        startEngine("BootAutoStart")
                    }
                }
            } else {
                Log.i(TAG, "Standard startup: Checking immediate actions.")
                if (isEngineActuallyLocked) {
                    recoverAbandonedFile()
                    startEngine("CrashRecovery")
                } else if (config.isAutoStartEnabled) {
                    Log.i(TAG, "Auto-start triggered on launch.")
                    startEngine("AppOpenAutoStart")
                }
                refreshComplications()
            }
        }
    }

    private fun recoverAbandonedFile() {
        val lastPath = sessionLock.getLastFilePath() ?: return
        val file = File(lastPath)
        if (file.exists()) {
            val recoveredFile = File(file.parent, file.name.replace("_temp", "_recovered"))
            if (file.renameTo(recoveredFile)) {
                FileTransferManager(context).transferFile(recoveredFile)
            }
        }
        sessionLock.unlock()
        sessionState.update(isRecording = false, isPaused = false, chunkCount = 0, sizeBytes = 0L)
    }

    fun startEngine(reason: String) {
        val intent = Intent(context, VoiceRecorderService::class.java).apply {
            action = VoiceRecorderService.ACTION_START_FOREGROUND
            putExtra("start_reason", reason)
        }
        context.startForegroundService(intent)
    }

    private fun stopEngine() {
        val intent = Intent(context, VoiceRecorderService::class.java).apply {
            action = VoiceRecorderService.ACTION_STOP_FOREGROUND
        }
        context.startService(intent)
    }

    fun requestImmediateSync() {
        context.sendBroadcast(Intent(VoiceRecorderService.ACTION_SYNC_STATS).apply {
            setPackage(context.packageName)
        })
    }

    fun refreshComplications() {
        listOf(
            RecordingProgressComplicationService::class.java,
            QuickRecordIconComplicationService::class.java,
            QuickRecordComplicationService::class.java
        ).forEach { serviceClass ->
            try {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, serviceClass)).requestUpdateAll()
            } catch (e: Exception) {
                Log.w(TAG, "Update deferred for ${serviceClass.simpleName}: ${e.message}")
            }
        }
    }

    class ReportReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val guardian = RecordingGuardian(context)
            val config = RecorderConfig(context)
            val sessionState = SessionState(context)
            
            when (intent.action) {
                ACTION_STATE_CHANGED -> {
                    val isRec = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
                    val isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                    val count = intent.getIntExtra(EXTRA_CHUNK_COUNT, 0)
                    val size = intent.getLongExtra(EXTRA_CURRENT_SIZE, 0L)
                    
                    sessionState.update(isRec, isPaused, count, size)
                    
                    config.isRecording = isRec
                    config.isPaused = isPaused
                    config.sessionChunkCount = count
                    config.isTransitioning = false
                    
                    guardian.refreshComplications()
                }
                ACTION_FILE_READY -> {
                    val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return
                    FileTransferManager(context).transferFile(File(context.filesDir, fileName))
                }
            }
        }
    }
}
