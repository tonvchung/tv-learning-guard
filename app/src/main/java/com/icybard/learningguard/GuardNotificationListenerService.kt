package com.icybard.learningguard

import android.service.notification.NotificationListenerService

class GuardNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        @Volatile var connected: Boolean = false
            private set
    }
}
