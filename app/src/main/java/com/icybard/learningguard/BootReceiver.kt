package com.icybard.learningguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.getSharedPreferences("guard_state", Context.MODE_PRIVATE)
                .edit()
                .putLong("lastBootOrUpgradeEventAt", System.currentTimeMillis())
                .putBoolean("restoreAfterBoot", true)
                .apply()
            // START_STICKY covers ordinary process recovery; the accessibility service restores task state.
            GuardWatchdogService.start(context)
            AdbWatchdog.ensure(context)
        }
    }
}
