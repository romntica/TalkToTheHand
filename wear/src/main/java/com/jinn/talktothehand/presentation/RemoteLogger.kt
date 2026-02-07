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
 * Optimized to prevent Startup ANR by lazy initialization.
 */
class RemoteLogger(private val context: Context) {

    private val activityManager by lazy { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }

    // Lazy battery check to avoid blocking the main thread during startup
    private fun getBatteryLevel(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level != -1 && scale != -1) (level * 100 / scale.toFloat()) else -1f
        } catch (e: Exception) {
            -1f
        }
    }

    /**
     * Explicitly setup crash handler. Should be called from Application.onCreate() 
     * or a background thread, not during sensitive service startups.
     */
    fun setupCrashHandler() {
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (currentHandler is GlobalCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(context, currentHandler))
    }

    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log("ERROR", tag, message, throwable)
    }

    private fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val systemInfo = getSystemMetadata()
        val stacktrace = throwable?.let { "\n[Stacktrace]\n${Log.getStackTraceString(it)}" } ?: ""

        val payload = "[$timestamp] [$level] [$tag] $message\n[SystemInfo] $systemInfo$stacktrace"
        
        when (level) {
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, "$message$stacktrace")
        }

        saveToTelemetryFile(payload)
    }

    private fun getSystemMetadata(): String {
        val batteryPct = getBatteryLevel()
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

    private class GlobalCrashHandler(
        private val context: Context,
        private val defaultHandler: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                // Use a fresh instance for crash logging
                val logger = RemoteLogger(context)
                logger.log(
                    level = "ERROR",
                    tag = "UncaughtException",
                    message = "FATAL EXCEPTION: ${thread.name}",
                    throwable = throwable
                )
            } catch (e: Exception) {
                Log.e("CrashHandler", "Error logging crash", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
