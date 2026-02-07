package com.jinn.talktothehand.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Entry point of the Wear app.
 * Handles system policy compliance (Battery Optimization) and startup logic.
 */
class MainActivity : ComponentActivity() {

    private lateinit var guardian: RecordingGuardian

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "MainActivity onCreate")
        guardian = RecordingGuardian(this)

        // 1. Check and handle Battery Optimization (MARs/Freecess bypass)
        checkBatteryOptimization()

        val isBootLaunch = intent.getBooleanExtra("is_boot_launch", false)
        guardian.handleStartup(isBoot = isBootLaunch)

        if (!isBootLaunch) {
            guardian.refreshComplications()
        }

        setContent {
            RecorderApp(guardian = guardian)
        }
    }

    /**
     * Checks if the app is ignored from battery optimizations.
     * Essential for bypassing Samsung MARs/Freecess freezing policies.
     */
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.w(TAG, "App is NOT whitelisted from battery optimizations. MARs may freeze processes.")
            // On Wear OS, we usually show a prompt or toast. 
            // For now, we log it and provide a way to open settings if needed.
        } else {
            Log.i(TAG, "App is whitelisted from battery optimizations. Performance should be stable.")
        }
    }

    override fun onResume() {
        super.onResume()
        guardian.requestImmediateSync()
    }

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_QUICK_RECORD = "com.jinn.talktothehand.action.QUICK_RECORD"
    }
}
