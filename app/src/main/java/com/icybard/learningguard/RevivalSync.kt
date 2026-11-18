package com.icybard.learningguard

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Lets Android's SyncManager restart the guard after a firmware force-stop.
 * Settings writes schedule this local adapter; no account data or media data is synced.
 */
object RevivalSync {
    private const val AUTHORITY = "settings"

    fun ensure(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val accountType = BuildConfig.APPLICATION_ID
            val manager = AccountManager.get(appContext)
            val account = manager.getAccountsByType(accountType).firstOrNull()
                ?: Account("Learning Guard", accountType).also {
                    if (!manager.addAccountExplicitly(it, null, null)) return
                }
            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            }
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) {
                ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
            }
        }
    }
}

class RevivalSyncService : Service() {
    private val adapter by lazy { Adapter(applicationContext) }

    override fun onBind(intent: Intent): IBinder = adapter.syncAdapterBinder

    private class Adapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
        override fun onPerformSync(
            account: Account,
            extras: Bundle,
            authority: String,
            provider: ContentProviderClient,
            result: SyncResult,
        ) {
            GuardWatchdogService.ensureServices(context)
            GuardWatchdogService.start(context)
            AdbWatchdog.ensure(context)
            Handler(Looper.getMainLooper()).postDelayed({
                GuardWatchdogService.rebindIfStalled(context)
            }, 5_000L)
        }
    }
}

/** The local account type exists only to hold the sync adapter; it cannot sign in anywhere. */
class RevivalAccountService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent): IBinder = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = null
        override fun addAccount(
            response: AccountAuthenticatorResponse,
            accountType: String,
            authTokenType: String?,
            requiredFeatures: Array<out String>?,
            options: Bundle,
        ) = null

        override fun confirmCredentials(
            response: AccountAuthenticatorResponse,
            account: Account,
            options: Bundle,
        ) = null

        override fun getAuthToken(
            response: AccountAuthenticatorResponse,
            account: Account,
            authTokenType: String,
            options: Bundle,
        ) = null

        override fun getAuthTokenLabel(authTokenType: String) = null
        override fun updateCredentials(
            response: AccountAuthenticatorResponse,
            account: Account,
            authTokenType: String?,
            options: Bundle,
        ) = null

        override fun hasFeatures(
            response: AccountAuthenticatorResponse,
            account: Account,
            features: Array<out String>,
        ) = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
