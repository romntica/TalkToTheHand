package com.jinn.talktothehand.presentation

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jinn.talktothehand.R
import kotlinx.coroutines.*
import java.io.File
import java.util.Date

/**
 * DATA PLANE: Isolated recording engine running in ':recorder' process.
 * Now resets stats to zero upon stopping to sync UI correctly.
 */
class VoiceRecorderService : Service() {

    companion object {
        private const val TAG = "RecorderEngine"
        const val ACTION_START_FOREGROUND = "com.jinn.talktothehand.action.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.jinn.talktothehand.action.STOP_FOREGROUND"
        const val ACTION_TOGGLE_RECORDING = "com.jinn.talktothehand.action.TOGGLE_RECORDING"
        const val ACTION_SYNC_STATS = "com.jinn.talktothehand.action.SYNC_STATS"
        
        const val MSG_REGISTER_CLIENT = 1
        const val MSG_UNREGISTER_CLIENT = 2
        const val MSG_SET_RECORDING = 3
        const val MSG_STATUS_UPDATE = 4
        const val MSG_PAUSE = 5
        const val MSG_RESUME = 6
        const val MSG_FORCE_WAKEUP = 7

        private val VIBRATION_STOP = VibrationEffect.createOneShot(100, 80)
        private const val HEARTBEAT_INTERVAL_MS = 30000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorder: VoiceRecorder? = null
    private val config by lazy { RecorderConfig(applicationContext) }
    private val sessionLock by lazy { SessionLock(applicationContext.filesDir) }
    private val sessionState by lazy { SessionState(applicationContext) }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    
    private val clients = mutableListOf<Messenger>()
    private lateinit var messenger: Messenger
    private var heartbeatCount = 0L

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SYNC_STATS) {
                reportStateToGuardian(recorder?.isRecording == true, isPaused = recorder?.isPaused == true, isHeartbeat = false)
            }
        }
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_REGISTER_CLIENT -> {
                    msg.replyTo?.let { 
                        clients.add(it) 
                        notifyStatusToClients(recorder?.isRecording == true, recorder?.isPaused == true)
                    }
                }
                MSG_UNREGISTER_CLIENT -> clients.remove(msg.replyTo)
                MSG_SET_RECORDING -> {
                    val start = msg.arg1 == 1
                    serviceScope.launch { if (start) startEngine() else stopEngine() }
                }
                MSG_PAUSE -> {
                    recorder?.pause()
                    updateSyncState()
                    notifyStatusToClients(recorder?.isRecording == true, true)
                }
                MSG_RESUME -> {
                    recorder?.resume()
                    updateSyncState()
                    notifyStatusToClients(recorder?.isRecording == true, false)
                }
                MSG_FORCE_WAKEUP -> recorder?.forceWakeup()
                else -> super.handleMessage(msg)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        recorder = VoiceRecorder(applicationContext)
        messenger = Messenger(IncomingHandler())
        createNotificationChannel()
        registerReceiver(syncReceiver, IntentFilter(ACTION_SYNC_STATS), RECEIVER_NOT_EXPORTED)
        updateSyncState()
    }

    override fun onDestroy() {
        try { unregisterReceiver(syncReceiver) } catch (_: Exception) {}
        updateSyncState(false, false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            RecordingGuardian(applicationContext).handleStartup(isBoot = false)
            return START_STICKY
        }

        val action = intent.action
        startForegroundWithMicrophone()

        serviceScope.launch {
            when (action) {
                ACTION_TOGGLE_RECORDING -> if (recorder?.isRecording == true) stopEngine() else startEngine()
                ACTION_START_FOREGROUND -> startEngine()
                ACTION_STOP_FOREGROUND -> stopEngine()
            }
        }
        return START_STICKY
    }

    private suspend fun startEngine() {
        if (recorder?.isRecording == true) return
        
        val timestamp = DateFormat.format("yyyyMMdd_HHmmss", Date()).toString()
        val file = File(applicationContext.filesDir, "${timestamp}_temp.aac")
        
        sessionLock.lock("EngineStart", file.absolutePath)
        if (recorder?.start(file, "EngineStart") == true) {
            config.isRecording = true
            updateSyncState()
            reportStateToGuardian(true, isPaused = false, isHeartbeat = true)
            startMonitoringLoop()
        } else {
            val error = recorder?.lastError ?: "Hardware Init Failed"
            sessionLock.unlock()
            config.isRecording = false
            updateSyncState(false, false)
            reportStateToGuardian(false, isPaused = false, isHeartbeat = false, error = error)
        }
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            var lastReportedSize = 0L
            var lastHeartbeatTime = System.currentTimeMillis()
            heartbeatCount = 0
            
            while (isActive && recorder?.isRecording == true) {
                val currentSize = recorder?.currentFileSize ?: 0L
                val isPaused = recorder?.isPaused == true
                
                updateSyncState()

                if (powerManager.isInteractive) {
                    val sizeThreshold = config.maxChunkSizeBytes / 10 
                    if (currentSize - lastReportedSize > sizeThreshold) {
                        reportStateToGuardian(true, isPaused = isPaused, isHeartbeat = false)
                        lastReportedSize = currentSize
                    }
                }
                
                if (System.currentTimeMillis() - lastHeartbeatTime >= HEARTBEAT_INTERVAL_MS) {
                    heartbeatCount++
                    sessionLock.updateTick() 
                    reportStateToGuardian(true, isPaused = isPaused, isHeartbeat = true)
                    lastHeartbeatTime = System.currentTimeMillis()
                    lastReportedSize = currentSize 
                }
                
                notifyStatusToClients(true, isPaused)
                
                if (currentSize > config.maxChunkSizeBytes) {
                    performSplit()
                    break 
                }
                delay(1000)
            }
        }
    }

    private suspend fun performSplit() {
        val oldFile = recorder?.currentFile
        val duration = recorder?.durationMillis ?: 0L
        recorder?.stopRecording("Split")
        if (oldFile != null) finalizeFile(oldFile, duration)
        startEngine()
    }

    private suspend fun stopEngine() {
        val oldFile = recorder?.currentFile
        val duration = recorder?.durationMillis ?: 0L
        recorder?.stopRecording("UserStop")
        if (oldFile != null) finalizeFile(oldFile, duration)
        
        config.sessionChunkCount = 0
        config.isRecording = false
        updateSyncState(false, false)
        sessionLock.unlock()
        vibrate(VIBRATION_STOP)
        
        // Final report with zeros to clear UI
        reportStateToGuardian(false, isPaused = false, isHeartbeat = true)
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finalizeFile(file: File, durationMillis: Long) {
        if (durationMillis < 2000L) {
            file.delete()
            return
        }
        val seconds = durationMillis / 1000
        val finalFile = File(file.parent, "${file.name.substringBefore("_temp")}_${seconds}s.aac")
        if (file.renameTo(finalFile)) {
            config.sessionChunkCount += 1
            sendBroadcast(Intent(RecordingGuardian.ACTION_FILE_READY).apply {
                putExtra(RecordingGuardian.EXTRA_FILE_NAME, finalFile.name)
                setPackage(packageName)
            })
        }
    }

    private fun updateSyncState(forcedRecording: Boolean? = null, forcedPaused: Boolean? = null) {
        val recording = forcedRecording ?: (recorder?.isRecording == true)
        val paused = forcedPaused ?: (recorder?.isPaused == true)
        sessionState.update(
            isRecording = recording,
            isPaused = paused,
            chunkCount = config.sessionChunkCount,
            sizeBytes = if (recording) (recorder?.currentFileSize ?: 0L) else 0L
        )
    }

    private fun reportStateToGuardian(isRecording: Boolean, isPaused: Boolean, isHeartbeat: Boolean, error: String? = null) {
        val intent = Intent(RecordingGuardian.ACTION_STATE_CHANGED).apply {
            putExtra(RecordingGuardian.EXTRA_IS_RECORDING, isRecording)
            putExtra("is_paused", isPaused)
            putExtra(RecordingGuardian.EXTRA_CHUNK_COUNT, config.sessionChunkCount)
            // Send 0 if not recording
            putExtra(RecordingGuardian.EXTRA_CURRENT_SIZE, if (isRecording) (recorder?.currentFileSize ?: 0L) else 0L)
            if (error != null) putExtra(RecordingGuardian.EXTRA_ERROR_MESSAGE, error)
            
            if (isHeartbeat) {
                putExtra(RecordingGuardian.EXTRA_HEARTBEAT_COUNT, heartbeatCount)
                putExtra(RecordingGuardian.EXTRA_IS_HEARTBEAT, true)
            }
            setPackage(packageName)
        }
        sendBroadcast(intent)
        notifyStatusToClients(isRecording, isPaused)
    }

    private fun notifyStatusToClients(isRecordingNow: Boolean, isPausedNow: Boolean) {
        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                val msg = Message.obtain(null, MSG_STATUS_UPDATE).apply {
                    arg1 = if (isRecordingNow) 1 else 0
                    arg2 = if (isPausedNow) 1 else 0
                    data = Bundle().apply {
                        // Reset to 0 when stopped
                        putLong("elapsed_ms", if (isRecordingNow) (recorder?.durationMillis ?: 0L) else 0L)
                        putLong("size_bytes", if (isRecordingNow) (recorder?.currentFileSize ?: 0L) else 0L)
                    }
                }
                client.send(msg)
            } catch (e: Exception) { iterator.remove() }
        }
        config.isRecording = isRecordingNow
        config.isPaused = isPausedNow
        config.currentChunkSizeBytes = if (isRecordingNow) (recorder?.currentFileSize ?: 0L) else 0L
    }

    private fun startForegroundWithMicrophone() {
        val notification = NotificationCompat.Builder(this, "RecorderChannel")
            .setContentTitle("TalkToTheHand")
            .setContentText("Recording engine active.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(101, notification)
        }
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel("RecorderChannel", "Engine", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun vibrate(effect: VibrationEffect) {
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
