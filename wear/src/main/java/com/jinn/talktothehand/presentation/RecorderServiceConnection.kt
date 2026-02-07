package com.jinn.talktothehand.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data class representing the real-time status of the recording engine.
 */
data class EngineStatus(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMillis: Long = 0L,
    val currentFileSize: Long = 0L,
    val error: String? = null
)

/**
 * Manages the connection to the VoiceRecorderService using Messenger for IPC.
 */
class RecorderServiceConnection(private val context: Context) {

    private val _engineStatus = MutableStateFlow(EngineStatus())
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    private var serviceMessenger: Messenger? = null
    private var isBound = false

    private val clientMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == VoiceRecorderService.MSG_STATUS_UPDATE) {
                val isRecording = msg.arg1 == 1
                val isPaused = msg.arg2 == 1
                val error = msg.obj as? String
                val elapsed = msg.data.getLong("elapsed_ms")
                val size = msg.data.getLong("size_bytes")
                
                _engineStatus.value = EngineStatus(isRecording, isPaused, elapsed, size, error)
            }
        }
    })

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            serviceMessenger = Messenger(service)
            isBound = true
            sendRegisterMessage(true)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            serviceMessenger = null
            isBound = false
            _engineStatus.value = EngineStatus()
        }
    }

    fun bind() {
        if (!isBound) {
            context.bindService(
                Intent(context, VoiceRecorderService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun unbind() {
        if (isBound) {
            sendRegisterMessage(false)
            context.unbindService(serviceConnection)
            isBound = false
            serviceMessenger = null
        }
    }

    private fun sendRegisterMessage(register: Boolean) {
        try {
            val what = if (register) VoiceRecorderService.MSG_REGISTER_CLIENT else VoiceRecorderService.MSG_UNREGISTER_CLIENT
            val msg = Message.obtain(null, what)
            msg.replyTo = clientMessenger
            serviceMessenger?.send(msg)
        } catch (_: RemoteException) { }
    }

    /**
     * Sends a control command to the recording engine.
     */
    fun sendCommand(what: Int, arg1: Int = 0) {
        try {
            val msg = Message.obtain(null, what, arg1, 0)
            msg.replyTo = clientMessenger
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e("ServiceConn", "Failed to send command $what", e)
        }
    }
}
