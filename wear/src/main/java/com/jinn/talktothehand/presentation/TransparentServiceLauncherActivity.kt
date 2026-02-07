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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimalist bridge activity that manages the Recording Engine state.
 * Prevents race conditions using config.isTransitioning lock.
 */
class TransparentServiceLauncherActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateReceiver: BroadcastReceiver? = null
    
    companion object {
        private const val TAG = "LauncherActivity"
        private const val CONFIRMATION_TIMEOUT_MS = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val config = RecorderConfig(applicationContext)
        val guardian = RecordingGuardian(applicationContext)

        // [Race Condition Guard] 
        // If a transition is already in progress, ignore new touch events.
        if (config.isTransitioning) {
            Log.w(TAG, "Transition in progress. Ignoring touch event.")
            finish()
            return
        }

        val targetIsStarting = !config.isRecording

        if (targetIsStarting) {
            // --- STARTING CASE: Show UI and wait for Engine Ack ---
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
                        scope.launch { delay(500); safeFinish() }
                        return
                    }

                    if (newState == true) {
                        safeFinish()
                    }
                }
            }
            registerReceiver(stateReceiver, IntentFilter(RecordingGuardian.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)

            scope.launch {
                guardian.reconcileWithAction(isStarting = true)
                
                // Fallback: If no response, ensure we unlock and finish
                delay(CONFIRMATION_TIMEOUT_MS)
                if (isActive) {
                    Log.w(TAG, "Sync timeout. Forcing unlock.")
                    config.isTransitioning = false 
                    safeFinish()
                }
            }
        } else {
            // --- STOPPING CASE: No UI, finish immediately ---
            guardian.reconcileWithAction(isStarting = false)
            finish()
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
