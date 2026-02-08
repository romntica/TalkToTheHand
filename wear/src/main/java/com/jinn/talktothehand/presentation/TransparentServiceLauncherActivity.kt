package com.jinn.talktothehand.presentation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.*

/**
 * A minimalist bridge activity that manages the Recording Engine state.
 * Shows a tiny visual indicator only while starting, then auto-closes immediately.
 */
class TransparentServiceLauncherActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateReceiver: BroadcastReceiver? = null
    
    companion object {
        private const val TAG = "LauncherActivity"
        private const val SYNC_TIMEOUT_MS = 4000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val config = RecorderConfig(applicationContext)
        val guardian = RecordingGuardian(applicationContext)

        // Safety: Clear transition lock when user intent is explicitly triggered via activity
        config.isTransitioning = false

        val isRecording = config.isRecording
        
        // STOP CASE: Finish immediately
        if (isRecording && intent.action == VoiceRecorderService.ACTION_TOGGLE_RECORDING) {
            guardian.reconcileWithAction(isStarting = false)
            finish()
            return
        }

        // START CASE: Show minimal indicator until engine ACKs
        var uiMessage by mutableStateOf("● . . .")
        var uiColor by mutableStateOf(Color(0xFFE91E63)) 

        setContent {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = uiMessage,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = uiColor,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }

        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newState = intent.getBooleanExtra(RecordingGuardian.EXTRA_IS_RECORDING, false)
                val error = intent.getStringExtra(RecordingGuardian.EXTRA_ERROR_MESSAGE)

                if (error != null) {
                    uiMessage = "! Error"
                    uiColor = Color.Red
                    scope.launch { delay(800); safeFinish() }
                    return
                }

                // UI Requirement: Disappear immediately once recording starts
                if (newState) {
                    safeFinish()
                }
            }
        }
        
        registerReceiver(
            stateReceiver, 
            IntentFilter(RecordingGuardian.ACTION_STATE_CHANGED), 
            Context.RECEIVER_NOT_EXPORTED
        )

        scope.launch {
            // Reconcile state
            guardian.reconcileWithAction(isStarting = true)
            
            // Watchdog: Ensure the bridge closes even if service fails to report
            delay(SYNC_TIMEOUT_MS)
            if (isActive) {
                config.isTransitioning = false 
                safeFinish()
            }
        }
    }

    private fun safeFinish() {
        if (!isFinishing) {
            try {
                stateReceiver?.let { unregisterReceiver(it) }
                stateReceiver = null
            } catch (_: Exception) {}
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
