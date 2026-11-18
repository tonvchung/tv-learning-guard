package com.icybard.learningguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-only, ADB-triggered view capture that bypasses the TV's system compositor. */
class DebugCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_CAPTURE) {
            GuardAccessibilityService.activeInstance?.captureQuizForValidation()
        }
    }

    companion object {
        const val ACTION_CAPTURE = "com.icybard.learningguard.DEBUG_CAPTURE"
    }
}
