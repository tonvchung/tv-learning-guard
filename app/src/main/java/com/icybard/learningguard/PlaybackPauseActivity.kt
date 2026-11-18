package com.icybard.learningguard

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** Forces the previous media activity through onPause/onStop behind the accessibility quiz UI. */
class PlaybackPauseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(View(this).apply { setBackgroundColor(Color.rgb(7, 17, 31)) })
        activeInstance = this
    }

    override fun onResume() {
        super.onResume()
        if (GuardAccessibilityService.activeInstance?.shouldKeepPlaybackPaused() != true) finish()
    }

    override fun onPause() {
        // This MIUI build force-stops an app that leaves an activity foreground while retaining
        // an accessibility window. Remove it before the transition; the service restores both.
        GuardAccessibilityService.activeInstance?.onPlaybackPauseActivityHidden()
        super.onPause()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var activeInstance: PlaybackPauseActivity? = null
            private set
    }
}
