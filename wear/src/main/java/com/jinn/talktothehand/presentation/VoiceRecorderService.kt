package com.jinn.talktothehand.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.jinn.talktothehand.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Wear OS Foreground Service for persistent recording and background chunk management.
 * Optimized for resilience during chunk handovers.
 */
class VoiceRecorderService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var isSplitting = false
    
    var recorder: VoiceRecorder? = null
        private set
        
    private lateinit var sessionLock: SessionLock
    private lateinit var remoteLogger: RemoteLogger
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var config: RecorderConfig
    
    private val CHANNEL_ID = "VoiceRecorderChannel"
    private val NOTIFICATION_ID = 101
    
    inner class LocalBinder : Binder() {
        fun getService(): VoiceRecorderService = this@VoiceRecorderService
        fun getRecorder(): VoiceRecorder? = recorder
    }

    override fun onCreate() {
        super.onCreate()
        config = RecorderConfig(applicationContext)
        sessionLock = SessionLock(applicationContext.filesDir)
        remoteLogger = RemoteLogger(applicationContext)
        recorder = VoiceRecorder(applicationContext)
        fileTransferManager = FileTransferManager(applicationContext)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == ACTION_START_FOREGROUND) {
            startForegroundWithMicrophone()
            if (recorder?.isRecording != true) {
                startNewSession("User Action via Intent")
            }
            startMonitoring() // Ensure monitoring starts
        } else if (action == ACTION_STOP_FOREGROUND) {
            stopRecordingAndForeground()
        } else if (intent == null) {
            handleAutoRecovery()
        }

        return START_STICKY 
    }

    fun startNewSession(reason: String): Boolean {
        val timestamp = DateFormat.format("yyyyMMdd_HHmmss", Date()).toString()
        val file = File(applicationContext.filesDir, "${timestamp}_temp.aac")
        
        sessionLock.lock(reason, file.absolutePath)
        val started = recorder?.start(file, reason) ?: false
        
        if (!started) {
            sessionLock.unlock()
        }
        return started
    }

    /**
     * Resilient monitor loop. Does not exit during transient recording pauses (e.g., splitting).
     */
    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                val rec = recorder
                if (rec == null) break

                // Pet the watchdog
                sessionLock.updateTick()

                // Only check for split if we are recording and NOT already in a split process
                if (rec.isRecording && !isSplitting) {
                    if (rec.currentFileSize >= config.maxChunkSizeBytes) {
                        remoteLogger.info(TAG, "Chunk limit reached (${rec.currentFileSize} bytes). Splitting...")
                        performSplit()
                    }
                }
                
                // Keep the loop alive even if isRecording is momentarily false during split
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private suspend fun performSplit() {
        val rec = recorder ?: return
        isSplitting = true
        try {
            val duration = rec.durationMillis
            val oldFile = rec.currentFile
            
            // 1. Stop current job (this is a suspend join)
            rec.stopRecording("Chunk split")
            
            // 2. Finalize
            oldFile?.let { file ->
                val seconds = TimeUnit.MILLISECONDS.toSeconds(duration)
                val timestamp = file.name.substringBefore("_temp")
                val newFile = File(file.parent, "${timestamp}_${seconds}s.aac")
                if (file.renameTo(newFile)) {
                    fileTransferManager.transferFile(newFile)
                }
            }
            
            // 3. Start new session
            startNewSession("Chunk split continuation")
        } finally {
            isSplitting = false
        }
    }

    private fun handleAutoRecovery() {
        if (sessionLock.isLocked) {
            val lastFile = sessionLock.getLastFilePath()?.let { File(it) }
            if (lastFile != null && lastFile.exists()) {
                val recoveredFile = File(lastFile.parent, lastFile.name.replace("_temp", "_recovered"))
                if (lastFile.renameTo(recoveredFile)) {
                    fileTransferManager.transferFile(recoveredFile)
                }
                sessionLock.unlock()
                startForegroundWithMicrophone()
                startNewSession("Auto-Recovery Resumption")
                startMonitoring()
            }
        }
    }

    private fun startForegroundWithMicrophone() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TalkToTheHand: Active")
            .setContentText("Continuous recording is protected.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopRecordingAndForeground() {
        monitorJob?.cancel()
        serviceScope.launch {
            recorder?.stopRecording("Stop Foreground Request")
            finalizeOnStop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finalizeOnStop() {
        val rec = recorder ?: return
        val file = rec.currentFile ?: return
        if (file.exists()) {
            val seconds = TimeUnit.MILLISECONDS.toSeconds(rec.durationMillis)
            val timestamp = file.name.substringBefore("_temp")
            val newFile = File(file.parent, "${timestamp}_${seconds}s.aac")
            file.renameTo(newFile)
            fileTransferManager.transferFile(newFile)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Voice Recorder", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    
    companion object {
        private const val TAG = "VoiceRecorderService"
        private const val MONITOR_INTERVAL_MS = 5000L // Increased frequency for better response
        const val ACTION_START_FOREGROUND = "com.jinn.talktothehand.action.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.jinn.talktothehand.action.STOP_FOREGROUND"
    }
}
