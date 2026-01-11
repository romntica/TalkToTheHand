package com.jinn.talktothehand.presentation

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * A truly transparent bridge activity to satisfy Android 14+ FGS restrictions.
 */
class TransparentServiceLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Remove transition animations for a seamless "background-like" feel
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        
        super.onCreate(savedInstanceState)
        
        val action = intent.action
        if (action != null) {
            val serviceIntent = Intent(this, VoiceRecorderService::class.java).apply {
                this.action = action
            }
            // Starting FGS while this activity is in the foreground is allowed
            startForegroundService(serviceIntent)
        }
        
        finish()
    }

    override fun finish() {
        super.finish()
        // Ensure exit transition is also disabled
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}
