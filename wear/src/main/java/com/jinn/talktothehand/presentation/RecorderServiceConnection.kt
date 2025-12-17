package com.jinn.talktothehand.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the connection to the VoiceRecorderService.
 * Exposes the VoiceRecorder instance as a reactive StateFlow.
 * This decouples the ViewModel from the Service binding lifecycle boilerplate.
 */
class RecorderServiceConnection(private val context: Context) {

    private val _recorderFlow = MutableStateFlow<VoiceRecorder?>(null)
    val recorderFlow: StateFlow<VoiceRecorder?> = _recorderFlow.asStateFlow()

    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG, "Service Connected")
            
            // Safe cast to avoid ClassCastException if the service is not what we expect
            val binder = service as? VoiceRecorderService.LocalBinder
            if (binder == null) {
                Log.e(TAG, "Unexpected binder type: $service")
                return
            }
            
            // Safely get the recorder instance.
            // Since VoiceRecorder is now init with App Context, this is safe to hold.
            _recorderFlow.value = binder.getRecorder()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.d(TAG, "Service Disconnected")
            _recorderFlow.value = null
            isBound = false
        }
    }

    fun bind() {
        if (!isBound) {
            val intent = Intent(context, VoiceRecorderService::class.java)
            // Bind service to get the interface
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbind() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
            _recorderFlow.value = null
        }
    }
    
    companion object {
        private const val TAG = "RecorderServiceConn"
    }
}
