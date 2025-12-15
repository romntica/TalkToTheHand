package com.jinn.talktothehand.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jinn.talktothehand.R

class VoiceRecorderService : Service() {

    private val binder = LocalBinder()
    // VoiceRecorder is now managed by the Service, keeping it alive
    var recorder: VoiceRecorder? = null
        private set
        
    private val CHANNEL_ID = "VoiceRecorderChannel"
    private val NOTIFICATION_ID = 101
    
    inner class LocalBinder : Binder() {
        fun getService(): VoiceRecorderService = this@VoiceRecorderService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        recorder = VoiceRecorder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START_FOREGROUND) {
            startForegroundService()
        } else if (action == ACTION_STOP_FOREGROUND) {
            stopForegroundService()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        recorder?.release()
        recorder = null
    }

    private fun startForegroundService() {
        // Create an intent that opens the main activity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in Progress")
            .setContentText("TalkToTheHand is recording...")
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure you have a valid icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // ID must not be 0
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Voice Recorder Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }
    
    companion object {
        const val ACTION_START_FOREGROUND = "com.jinn.talktothehand.action.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.jinn.talktothehand.action.STOP_FOREGROUND"
    }
}
