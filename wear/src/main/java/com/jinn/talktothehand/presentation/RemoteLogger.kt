package com.jinn.talktothehand.presentation

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic logger that sends structured logs to the phone companion.
 */
class RemoteLogger(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryStatus: Intent? by lazy {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val stackTrace = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        log("ERROR", tag, "$message$stackTrace")
    }

    private fun log(level: String, tag: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val systemInfo = getSystemMetadata()
        
        val payload = "[$timestamp] [$level] [$tag] $message\n[SystemInfo] $systemInfo"
        
        // Log to local Logcat for development
        when (level) {
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
        }

        saveToTelemetryFile(payload)
    }

    private fun getSystemMetadata(): String {
        // Named parameter 'intent' to avoid unresolved 'it' issues
        val batteryPct = batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) (level * 100 / scale.toFloat()) else -1f
        } ?: -1f

        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val stat = StatFs(context.filesDir.path)
        val availableStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)

        return "Battery: ${batteryPct}%, Free Storage: ${availableStorageMb}MB, Low Mem: ${memoryInfo.lowMemory}, SDK: ${Build.VERSION.SDK_INT}"
    }

    private fun saveToTelemetryFile(payload: String) {
        try {
            val logFile = java.io.File(context.filesDir, "telemetry.log")
            logFile.appendText("$payload\n---\n")
        } catch (e: Exception) {
            Log.e("RemoteLogger", "Failed to save telemetry", e)
        }
    }
}
