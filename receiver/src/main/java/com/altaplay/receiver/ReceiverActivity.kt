package com.altaplay.receiver

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.satnova.alpha.Zen4Client
import com.satnova.alpha.ZenChannel

/** Standalone first alpha screen using the verified 0.5.12 G-MScreen transport. */
class ReceiverActivity : Activity(), Zen4Client.Listener {
    private lateinit var client: Zen4Client
    private lateinit var status: TextView
    private lateinit var channelList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 16, 29)
        window.navigationBarColor = Color.rgb(5, 16, 29)
        window.decorView.setBackgroundColor(Color.rgb(5, 16, 29))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        val heading = TextView(this).apply {
            text = "AltaPlay Receiver"
            textSize = 30f
            setTextColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = "Initialisation du lien Zen 4…"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 16)
        }
        val scan = Button(this).apply {
            text = "Relancer la recherche du Zen 4"
            setOnClickListener {
                isEnabled = false
                client.stop()
                client = Zen4Client(this@ReceiverActivity, this@ReceiverActivity)
                client.start()
                postDelayed({ isEnabled = true }, 1800)
            }
        }
        val section = TextView(this).apply {
            text = "Chaînes réellement reçues du Zen 4"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 12)
        }
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(channelList) }
        root.addView(heading)
        root.addView(status)
        root.addView(scan)
        root.addView(section)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        client = Zen4Client(applicationContext, this)
        client.start()
    }

    override fun state(connected: Boolean, message: String) {
        status.text = message
        status.setTextColor(if (connected) Color.rgb(100, 220, 150) else Color.rgb(245, 190, 110))
    }

    override fun catalogue(channels: List<ZenChannel>) {
        channelList.removeAllViews()
        if (channels.isEmpty()) {
            channelList.addView(TextView(this).apply {
                text = "En attente du catalogue transmis par le récepteur…"
                setTextColor(Color.LTGRAY)
            })
            return
        }
        channels.forEach { channel ->
            val item = Button(this).apply {
                text = "${channel.position}. ${channel.name}" +
                    (if (channel.radio) " · RADIO" else "") +
                    (channel.definition?.let { " · $it" } ?: "") +
                    (if (channel.encrypted == true) " · 🔒" else "")
                textSize = 15f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    val result = client.tuneDetailed(channel)
                    status.text = when (result) {
                        Zen4Client.TuneResult.SENT -> "Commande envoyée · ${channel.name}"
                        Zen4Client.TuneResult.DISCONNECTED -> "Zen 4 déconnecté"
                        Zen4Client.TuneResult.CHANNEL_NOT_FOUND -> "Chaîne absente du catalogue actuel"
                        Zen4Client.TuneResult.SEND_FAILED -> "Échec d'envoi de la commande"
                    }
                }
            }
            channelList.addView(item, LinearLayout.LayoutParams(-1, 58))
        }
    }

    override fun playing(channelId: String) {}
    override fun tuneConfirmed(channel: ZenChannel) {
        status.text = "Chaîne confirmée · ${channel.name}"
    }
    override fun tuneFailed(channel: ZenChannel, reason: String) {
        status.text = "Échec · ${channel.name} · $reason"
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.stop()
        super.onDestroy()
    }
}
