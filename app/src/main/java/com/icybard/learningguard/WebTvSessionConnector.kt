package com.icybard.learningguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.lang.ref.WeakReference

/** Connects directly to one WebTV package without relying on notification-listener discovery. */
class WebTvSessionConnector(
    private val context: Context,
    private val packageName: String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onConnected(packageName: String, controller: MediaController)
        fun onDisconnected(packageName: String)
    }

    private var bound = false
    private var service: Messenger? = null
    private var controller: MediaController? = null
    private val replies = Messenger(IncomingHandler(this))
    private val controllerCallback = object : MediaController.Callback() {
        override fun onSessionDestroyed() = disconnect()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            val request = Message.obtain(null, WebTvPlaybackContract.MSG_GET_SESSION_TOKEN).apply {
                replyTo = replies
            }
            runCatching { service?.send(request) }.onFailure { disconnect() }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnect()
        override fun onNullBinding(name: ComponentName) = disconnect()
        override fun onBindingDied(name: ComponentName) = disconnect()
    }

    fun refresh() {
        if (bound || controller != null) return
        val intent = Intent(WebTvPlaybackContract.ACTION).setPackage(packageName)
        bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    fun close() {
        disconnect()
        callbacks.onDisconnected(packageName)
    }

    private fun acceptToken(message: Message) {
        message.data.classLoader = MediaSession.Token::class.java.classLoader
        @Suppress("DEPRECATION")
        val token = message.data.getParcelable<MediaSession.Token>(
            WebTvPlaybackContract.EXTRA_SESSION_TOKEN,
        ) ?: return
        runCatching {
            controller?.unregisterCallback(controllerCallback)
            controller = MediaController(context, token).also {
                it.registerCallback(controllerCallback, Handler(Looper.getMainLooper()))
                callbacks.onConnected(packageName, it)
            }
        }.onFailure { disconnect() }
    }

    private fun disconnect() {
        service = null
        controller?.let { runCatching { it.unregisterCallback(controllerCallback) } }
        controller = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        callbacks.onDisconnected(packageName)
    }

    private class IncomingHandler(connector: WebTvSessionConnector) : Handler(Looper.getMainLooper()) {
        private val reference = WeakReference(connector)

        override fun handleMessage(message: Message) {
            if (message.what == WebTvPlaybackContract.MSG_SESSION_TOKEN) {
                reference.get()?.acceptToken(message)
            }
        }
    }
}
