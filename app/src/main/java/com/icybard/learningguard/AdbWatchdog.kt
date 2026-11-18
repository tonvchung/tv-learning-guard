package com.icybard.learningguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/** Opt-in network ADB recovery for the reviewed Android 9 TV initializer. */
object AdbWatchdog {
    const val KEEP_ADB_SETTING = "learning_guard_keep_adb"
    private const val ADB_PORT = 5555
    private val lock = Any()

    @Volatile
    private var restoring = false

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || restoring) return
        val appContext = context.applicationContext
        if (!keepEnabled(appContext)) return
        if (appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = appContext.contentResolver
        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 0) {
            runCatching { Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1) }
                .onFailure { Log.w(TAG, "Could not restore ADB", it) }
            return
        }
        // This firmware can leave adb_enabled=1 while adbd is no longer listening.
        Thread {
            if (adbdListens()) return@Thread
            synchronized(lock) {
                if (restoring || adbdListens() || !keepEnabled(appContext)) return@synchronized
                restoring = true
                try {
                    runCatching {
                        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
                        Thread.sleep(1_000)
                        if (keepEnabled(appContext)) Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
                        Log.i(TAG, "Restarted network ADB listener")
                    }.onFailure { Log.w(TAG, "Could not restart network ADB listener", it) }
                } finally {
                    restoring = false
                }
            }
        }.apply {
            name = "LearningGuardAdbRecovery"
            isDaemon = true
        }.start()
    }

    private fun keepEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, KEEP_ADB_SETTING, 0) == 1

    private fun adbdListens(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", ADB_PORT), 400) }
    }.isSuccess

    private const val TAG = "GuardAdb"
}
