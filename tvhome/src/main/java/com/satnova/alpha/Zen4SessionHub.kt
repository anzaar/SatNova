package com.satnova.alpha

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * One receiver socket for the TV launcher and its optional HDMI remote bridge.
 * Zen 4 can reject simultaneous logins; sharing one session avoids a second
 * independent control connection when the HDMI screen takes focus.
 */
internal object Zen4SessionHub {
    private val guard = Any()
    private val main = Handler(Looper.getMainLooper())
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
    private var client: Zen4Client? = null
    private val observers = linkedMapOf<Any, Zen4Client.Listener?>()
    private val dispatcher = object : Zen4Client.Listener {
        private fun snapshot(): List<Zen4Client.Listener> = synchronized(guard) {
            observers.values.filterNotNull()
        }
        override fun state(connected: Boolean, message: String) {
            onMain { snapshot().forEach { it.state(connected, message) } }
        }
        override fun catalogue(channels: List<ZenChannel>) {
            onMain { snapshot().forEach { it.catalogue(channels) } }
        }
        override fun playing(channelId: String) {
            onMain { snapshot().forEach { it.playing(channelId) } }
        }
        override fun tuneConfirmed(channel: ZenChannel) {
            onMain { snapshot().forEach { it.tuneConfirmed(channel) } }
        }
    }

    fun attach(context: Context, owner: Any, listener: Zen4Client.Listener? = null): Zen4Client {
        synchronized(guard) {
            val shared = client ?: Zen4Client(context.applicationContext, dispatcher).also {
                client = it
                it.start()
            }
            observers[owner] = listener
            // A new Activity may join an already-established background service.
            if (listener != null) {
                listener.catalogue(shared.channels)
                listener.state(shared.connected,
                    if (shared.connected) "Connecté · Zen 4" else "Recherche de Zen 4…")
            }
            return shared
        }
    }

    fun detach(owner: Any) {
        synchronized(guard) {
            observers.remove(owner)
            if (observers.isEmpty()) {
                client?.stop()
                client = null
            }
        }
    }
}
