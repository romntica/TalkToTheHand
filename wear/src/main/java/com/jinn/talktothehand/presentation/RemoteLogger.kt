package com.jinn.talktothehand.presentation

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RemoteLogger(private val context: Context) {
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    // Use SupervisorJob so one failure doesn't cancel the scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val config = RecorderConfig(context)

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(tag, "ERROR", message, throwable)
    }
    
    fun info(tag: String, message: String) {
        log(tag, "INFO", message, null)
    }

    private fun log(tag: String, level: String, message: String, throwable: Throwable?) {
        // 1. Log locally always
        if (level == "ERROR") {
            Log.e(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }

        // 2. Check if telemetry is enabled
        if (!config.isTelemetryEnabled) {
            return
        }

        // 3. Prepare payload
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("[$timestamp] [$level] [$tag] $message")
        if (throwable != null) {
            sb.append("\nStacktrace:\n")
            sb.append(Log.getStackTraceString(throwable))
        }
        val payload = sb.toString().toByteArray(StandardCharsets.UTF_8)

        // 4. Send to connected phone
        scope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, "/telemetry/error", payload)
                }
            } catch (e: Exception) {
                // Fail silently to avoid infinite recursion of error logging
                Log.w("RemoteLogger", "Failed to send telemetry", e)
            }
        }
    }
}
