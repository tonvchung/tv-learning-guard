package com.icybard.learningguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.icybard.learningguard.core.ServiceComponentList

/** Keeps ordinary process recovery and service rebinding independent of the overlay lifecycle. */
class GuardWatchdogService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            ensureGuardServices()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        RevivalSync.ensure(this)
        handler.post(check)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureGuardServices()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun ensureGuardServices() = ensureServices(this)

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "学习守护后台服务", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_guard)
            .setContentTitle("学习守护运行中")
            .setContentText("保持播放监测与答题服务")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guard_background"
        private const val NOTIFICATION_ID = 1041
        private const val CHECK_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, GuardWatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun ensureServices(context: Context) {
            val component = "${context.packageName}/${GuardAccessibilityService::class.java.name}"
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (!ServiceComponentList.contains(current, component)) {
                runCatching {
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        ServiceComponentList.merge(current, component),
                    )
                }
            }
            runCatching { Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) }
            val listener = "${context.packageName}/${GuardNotificationListenerService::class.java.name}"
            val listeners = Settings.Secure.getString(resolver, "enabled_notification_listeners")
            if (!ServiceComponentList.contains(listeners, listener)) {
                runCatching {
                    Settings.Secure.putString(
                        resolver,
                        "enabled_notification_listeners",
                        ServiceComponentList.merge(listeners, listener),
                    )
                }
            }
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, GuardNotificationListenerService::class.java),
                )
            }
        }

        fun rebindIfStalled(context: Context) {
            if (GuardAccessibilityService.activeInstance != null) return
            val component = "${context.packageName}/${GuardAccessibilityService::class.java.name}"
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (!ServiceComponentList.contains(current, component)) return
            runCatching {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ServiceComponentList.remove(current, component),
                )
            }
            Handler(Looper.getMainLooper()).postDelayed({
                val latest = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                runCatching {
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        ServiceComponentList.merge(latest, component),
                    )
                }
            }, 1_500L)
        }
    }
}
