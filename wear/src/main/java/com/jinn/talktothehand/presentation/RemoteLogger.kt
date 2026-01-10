package com.jinn.talktothehand.presentation

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
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

/**
 * Advanced Diagnostic Telemetry Logger.
 * Automatically attaches system metadata (Battery, Storage, Memory) to error logs.
 */
class RemoteLogger(private val context: Context) {
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val config = RecorderConfig(context)

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(tag, "ERROR", message, throwable)
    }
    
    fun info(tag: String, message: String) {
        log(tag, "INFO", message, null)
    }

    private fun log(tag: String, level: String, message: String, throwable: Throwable?) {
        // Local logging
        if (level == "ERROR") Log.e(tag, message, throwable) else Log.i(tag, message)

        if (!config.isTelemetryEnabled) return

        scope.launch {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val metadata = if (level == "ERROR") getSystemMetadata() else ""
                
                val sb = StringBuilder().apply {
                    append("[$timestamp] [$level] [$tag] $message")
                    if (metadata.isNotEmpty()) append("\n[SystemInfo] $metadata")
                    throwable?.let {
                        append("\n[Stacktrace]\n")
                        append(Log.getStackTraceString(it))
                    }
                }

                val payload = sb.toString().toByteArray(StandardCharsets.UTF_8)
                val nodes = nodeClient.connectedNodes.await()
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, "/telemetry/error", payload)
                }
            } catch (e: Exception) {
                Log.w("RemoteLogger", "Telemetry delivery failed", e)
            }
        }
    }

    /**
     * Captures current device state for debugging.
     */
    private fun getSystemMetadata(): String {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        
        val stat = StatFs(Environment.getDataDirectory().path)
        val availableMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val lowMem = memInfo.lowMemory
        
        return "Battery: $level%, StorageFree: ${availableMb}MB, LowMem: $lowMem"
    }
}
