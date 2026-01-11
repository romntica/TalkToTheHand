package com.jinn.talktothehand.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wear OS Foreground Service for persistent voice recording.
 */
class VoiceRecorderService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var isSplitting = false
    private val isProcessingAction = AtomicBoolean(false)
    
    var recorder: VoiceRecorder? = null
        private set
        
    private lateinit var sessionLock: SessionLock
    private lateinit var remoteLogger: RemoteLogger
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var config: RecorderConfig
    
    // Caching system services for performance
    private lateinit var vibrator: Vibrator
    private lateinit var notificationManager: NotificationManager
    
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
        
        // Initialize and cache system services
        notificationManager = getSystemService(NotificationManager::class.java)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == ACTION_TOGGLE_RECORDING || action == ACTION_START_FOREGROUND || intent == null) {
            startForegroundWithMicrophone()
        }

        if (isProcessingAction.getAndSet(true)) return START_STICKY

        serviceScope.launch {
            try {
                when (action) {
                    ACTION_TOGGLE_RECORDING -> handleToggleAction()
                    ACTION_START_FOREGROUND -> handleStartAction()
                    ACTION_STOP_FOREGROUND -> stopRecordingAndForeground()
                    null -> handleAutoRecovery()
                }
            } finally {
                isProcessingAction.set(false)
            }
        }

        return START_STICKY 
    }

    private suspend fun handleToggleAction() {
        if (recorder?.isRecording == true || config.isRecording) {
            vibrate(VIBRATION_STOP)
            stopRecordingAndForeground()
        } else {
            vibrate(VIBRATION_START)
            config.sessionChunkCount = 0
            startNewSession("Toggle")
            startMonitoring()
        }
    }

    private fun handleStartAction() {
        if (recorder?.isRecording != true) {
            config.sessionChunkCount = 0
            startNewSession("Manual")
        }
        startMonitoring()
    }

    fun startNewSession(reason: String): Boolean {
        val timestamp = DateFormat.format("yyyyMMdd_HHmmss", Date()).toString()
        val file = File(applicationContext.filesDir, "${timestamp}_temp.aac")
        
        sessionLock.lock(reason, file.absolutePath)
        val started = recorder?.start(file, reason) ?: false
        
        if (started) {
            config.isRecording = true
            updateComplications()
        } else {
            sessionLock.unlock()
        }
        return started
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                val rec = recorder ?: break
                sessionLock.updateTick()

                if (rec.isRecording && !isSplitting) {
                    config.currentChunkSizeBytes = rec.currentFileSize
                    if (rec.currentFileSize >= config.maxChunkSizeBytes) {
                        performSplit()
                    }
                    updateComplications()
                }
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
            
            rec.stopRecording("Split")
            oldFile?.let { processFinalFile(it, duration) }
            
            startNewSession("Continuation")
        } finally {
            isSplitting = false
        }
    }

    private fun handleAutoRecovery() {
        if (sessionLock.isLocked) {
            val lastFile = sessionLock.getLastFilePath()?.let { File(it) }
            if (lastFile?.exists() == true) {
                val recoveredFile = File(lastFile.parent, lastFile.name.replace("_temp", "_recovered"))
                if (lastFile.renameTo(recoveredFile)) {
                    fileTransferManager.transferFile(recoveredFile)
                    config.sessionChunkCount += 1
                }
            }
            sessionLock.unlock()
            startForegroundWithMicrophone()
            startNewSession("Recovery")
            startMonitoring()
        }
    }

    private fun startForegroundWithMicrophone() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TalkToTheHand: Active")
            .setContentText("Recording in progress...")
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

    private suspend fun stopRecordingAndForeground() {
        config.isRecording = false
        config.currentChunkSizeBytes = 0L
        updateComplications()
        
        monitorJob?.cancel()
        recorder?.let { 
            val duration = it.durationMillis
            val file = it.currentFile
            it.stopRecording("Stop")
            file?.let { f -> processFinalFile(f, duration) }
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processFinalFile(file: File, durationMillis: Long) {
        if (durationMillis < MIN_VALID_DURATION_MS) {
            if (file.exists()) file.delete()
            return
        }

        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis)
        val timestamp = file.name.substringBefore("_temp")
        val newFile = File(file.parent, "${timestamp}_${seconds}s.aac")
        
        if (file.renameTo(newFile)) {
            config.sessionChunkCount += 1
            fileTransferManager.transferFile(newFile)
        }
    }

    private fun updateComplications() {
        val requester = ComplicationDataSourceUpdateRequester.create(this, ComponentName(this, RecordingProgressComplicationService::class.java))
        requester.requestUpdateAll()
        val quickRequester = ComplicationDataSourceUpdateRequester.create(this, ComponentName(this, QuickRecordIconComplicationService::class.java))
        quickRequester.requestUpdateAll()
    }

    private fun vibrate(effect: VibrationEffect) {
        if (::vibrator.isInitialized && vibrator.hasVibrator()) {
            vibrator.vibrate(effect)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Voice Recorder", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }
    
    companion object {
        private const val TAG = "VoiceRecorderService"
        private const val MONITOR_INTERVAL_MS = 5000L
        private const val MIN_VALID_DURATION_MS = 2000L
        
        const val ACTION_START_FOREGROUND = "com.jinn.talktothehand.action.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.jinn.talktothehand.action.STOP_FOREGROUND"
        const val ACTION_TOGGLE_RECORDING = "com.jinn.talktothehand.action.TOGGLE_RECORDING"
        
        private val VIBRATION_START = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        private val VIBRATION_STOP = VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
    }
}
