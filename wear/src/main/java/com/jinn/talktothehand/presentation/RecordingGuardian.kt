package com.jinn.talktothehand.presentation

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * THE GUARDIAN (Control Plane): Central orchestrator for the entire app.
 * Ensures data integrity and reconciles engine state with UI/Complications.
 */
class RecordingGuardian(private val context: Context) {

    private val config = RecorderConfig(context)
    private val sessionLock = SessionLock(context)
    private val sessionState = SessionState(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
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

        private const val BOOT_RECOVERY_DELAY_MS = 3000L  
        private const val BOOT_AUTOSTART_DELAY_MS = 7000L 

        private val isHandlingStartup = AtomicBoolean(false)
        
        // STATIC: Track current startup job across instances to prevent overlapping auto-starts
        private var activeStartupJob: Job? = null
    }

    /**
     * Reconciles recording state based on a specific user action (Start/Stop).
     */
    fun reconcileWithAction(isStarting: Boolean) {
        // IMPORTANT: Cancel any pending auto-start logic immediately if user takes control
        cancelPendingStartup()
        
        scope.launch {
            config.isTransitioning = true
            if (isStarting) {
                Log.d(TAG, "reconcileWithAction: START requested")
                startEngine("UserIntent")
            } else {
                Log.d(TAG, "reconcileWithAction: STOP requested")
                stopEngine()
            }
            refreshComplications(force = true)
        }
    }

    private fun cancelPendingStartup() {
        activeStartupJob?.let {
            if (it.isActive) {
                Log.i(TAG, "Cancelling pending startup sequence due to manual intervention.")
                it.cancel()
            }
        }
        activeStartupJob = null
    }

    /**
     * CRITICAL: Verifies that the sync file matches the actual engine status.
     */
    fun verifyIntegrity() {
        scope.launch {
            val isEngineActuallyLocked = sessionLock.isLocked
            val storedState = sessionState.read()
            
            if (!isEngineActuallyLocked && storedState.isRecording) {
                Log.w(TAG, "Integrity Breach: Syncing UI to IDLE.")
                forceResetUIState()
            }
        }
    }

    private fun forceResetUIState() {
        sessionState.update(isRecording = false, isPaused = false, chunkCount = 0, sizeBytes = 0L)
        config.isRecording = false
        config.isPaused = false
        config.isTransitioning = false
        refreshComplications(force = true)
    }

    /**
     * Handles startup sequences. UI remains IDLE until verified engine signal.
     */
    fun handleStartup(isBoot: Boolean = false) {
        if (isBoot && isHandlingStartup.getAndSet(true)) return

        // Cancel any previous sequence before starting a new one
        cancelPendingStartup()

        activeStartupJob = scope.launch {
            try {
                val isEngineActuallyLocked = sessionLock.isLocked
                
                // Reset UI to idle before assessing state
                forceResetUIState()

                if (isBoot) {
                    Log.i(TAG, "Boot startup sequence initiated.")
                    if (isEngineActuallyLocked) {
                        recoverAbandonedFile()
                        delay(BOOT_RECOVERY_DELAY_MS)
                        ensureActive { return@launch }
                        startEngineViaActivity("BootRecovery")
                    } else if (config.isAutoStartEnabled) {
                        Log.d(TAG, "AutoStart pending... waiting ${BOOT_AUTOSTART_DELAY_MS}ms")
                        delay(BOOT_AUTOSTART_DELAY_MS)
                        
                        // Re-check conditions after delay
                        ensureActive { return@launch }
                        if (config.isAutoStartEnabled && !sessionLock.isLocked) {
                            startEngineViaActivity("BootAutoStart")
                        }
                    }
                    delay(5000L)
                    isHandlingStartup.set(false)
                } else {
                    // Regular App Open or Crash Recovery
                    if (isEngineActuallyLocked) {
                        recoverAbandonedFile()
                        startEngine("CrashRecovery")
                    } else if (config.isAutoStartEnabled) {
                        startEngine("AppOpenAutoStart")
                    }
                    refreshComplications(force = true)
                }
            } finally {
                // Clear the job reference when done
                if (activeStartupJob?.isActive == false) {
                    activeStartupJob = null
                }
            }
        }
    }

    private fun recoverAbandonedFile() {
        val lastPath = sessionLock.getLastFilePath() ?: return
        val file = File(lastPath)
        if (file.exists()) {
            val recoveredFile = File(file.parent, file.name.replace("_temp", "_recovered"))
            if (file.renameTo(recoveredFile)) {
                val transferManager = FileTransferManager(context)
                transferManager.transferFile(recoveredFile)
                transferManager.transferTelemetryLog()
            }
        }
        sessionLock.unlock()
    }

    fun startEngine(reason: String) {
        try {
            val intent = Intent(context, VoiceRecorderService::class.java).apply {
                action = VoiceRecorderService.ACTION_START_FOREGROUND
                putExtra("start_reason", reason)
            }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Direct start failed ($reason). Trying Activity bridge.", e)
            startEngineViaActivity(reason)
        }
    }

    private fun startEngineViaActivity(reason: String) {
        try {
            Log.i(TAG, "Launching bridge activity: $reason")
            val intent = Intent(context, TransparentServiceLauncherActivity::class.java).apply {
                action = VoiceRecorderService.ACTION_START_FOREGROUND
                putExtra("start_reason", reason)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent, 
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bridge activity failed to launch", e)
        }
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

    private inline fun ensureActive(block: () -> Unit) {
        if (activeStartupJob?.isCancelled == true) {
            Log.d(TAG, "Startup sequence aborted (Job Cancelled)")
            block()
        }
    }

    /**
     * Refreshes all complication providers.
     */
    fun refreshComplications(force: Boolean = false) {
        listOf(
            RecordingProgressComplicationService::class.java,
            QuickRecordComplicationService::class.java
        ).forEach { serviceClass ->
            try {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, serviceClass)).requestUpdateAll()
            } catch (e: Exception) {
                Log.w(TAG, "Update deferred for ${serviceClass.simpleName}")
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
                    val isHeartbeat = intent.getBooleanExtra(EXTRA_IS_HEARTBEAT, false)
                    val count = intent.getIntExtra(EXTRA_CHUNK_COUNT, 0)
                    val size = intent.getLongExtra(EXTRA_CURRENT_SIZE, 0L)
                    
                    sessionState.update(isRec, isPaused, count, size)
                    
                    config.isRecording = isRec
                    config.isPaused = isPaused
                    config.sessionChunkCount = count
                    config.isTransitioning = false
                    
                    guardian.refreshComplications(force = true)
                }
                ACTION_FILE_READY -> {
                    val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return
                    val transferManager = FileTransferManager(context)
                    transferManager.transferFile(File(context.filesDir, fileName))
                    transferManager.transferTelemetryLog()
                }
            }
        }
    }
}
