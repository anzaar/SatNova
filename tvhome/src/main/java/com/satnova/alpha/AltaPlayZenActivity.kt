package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Zen settings only: never becomes HOME or changes Secreto's APK/preferences. */
class AltaPlayZenActivity : Activity() {
    private val prefs by lazy {
        getSharedPreferences("zen4_hdmi_bridge", Context.MODE_PRIVATE)
    }
    private val logos by lazy { ChannelLogoStore(this) }
    private var client: Zen4Client? = null
    private var cached: List<ZenChannel> = emptyList()
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val observer = object : Zen4Client.Listener {
        override fun state(connected: Boolean, message: String) {
            status.text = if (connected) "Zen 4 connecté"
                else "Zen 4 hors ligne · reconnexion automatique"
        }
        override fun catalogue(channels: List<ZenChannel>) {
            if (channels.isNotEmpty() || cached.isEmpty()) cached = channels
            renderChannels()
        }
        override fun playing(channelId: String) {}
        override fun tuneConfirmed(channel: ZenChannel) {}
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
    }
    private fun button(value: String, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { click() }
    }
    private fun openSystem(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {
            Toast.makeText(this, "Action indisponible", Toast.LENGTH_LONG).show()
        }
    }
    private fun receiverIp() {
        val edit = EditText(this).apply {
            hint = "192.168.1.100"
            setText(client?.lastIp.orEmpty())
            isSingleLine = true
        }
        AlertDialog.Builder(this).setTitle("Zen 4 · IP")
            .setView(edit).setPositiveButton("Connecter") { _, _ ->
                if (client?.setHost(edit.text.toString().trim()) != true) {
                    Toast.makeText(this, "Adresse locale invalide", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Annuler", null).show()
    }
    private fun secretoPackage() {
        val edit = EditText(this).apply {
            hint = "Laisser vide pour détection automatique"
            setText(prefs.getString("secreto_home_package", ""))
            isSingleLine = true
        }
        AlertDialog.Builder(this).setTitle("Secreto HOME · Package")
            .setMessage("Laissez vide pour la détection automatique. La valeur exacte est vérifiable par ADB.")
            .setView(edit).setPositiveButton("Enregistrer") { _, _ ->
                prefs.edit().putString("secreto_home_package", edit.text.toString().trim()).apply()
                Toast.makeText(this, "Réactivez le service Accessibilité pour appliquer", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Annuler", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 28, 35))
            setPadding(dp(22), dp(16), dp(22), dp(16))
        }
        root.addView(label("AltaPlay Launcher · Zen 4", 27f))
        root.addView(label("Secreto reste HOME : adhan, météo, fonds et apps inchangés.", 15f))
        status = label("Recherche Zen 4…")
        root.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("IP Zen 4") { receiverIp() })
        buttons.addView(button("Accessibilité HDMI") {
            openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        buttons.addView(button("Identifier Secreto") { secretoPackage() })
        root.addView(buttons)
        root.addView(label("Sur Secreto : GUIDE pour sélectionner, flèches pour parcourir, OK pour choisir, BACK pour fermer. HOME reste natif.", 14f))
        root.addView(button("Fond islamique pour Secreto Wallpaper Studio") {
            openSystem(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://raw.githubusercontent.com/anzaar/SatNova/feature/altaplay-secreto-preserved-zen-addon-20261119/tvhome/src/main/assets/satnova_wallpaper.webp")))
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderChannels()
        window.decorView.post {
            if (isFinishing) return@post
            client = Zen4SessionHub.attach(this, this, observer)
            try {
                val service = Intent(this, Zen4PersistentService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service)
                else startService(service)
            } catch (_: Exception) {}
        }
    }

    private fun renderChannels() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val channels = cached.filter { !it.radio }.sortedBy { it.position }
        list.addView(label("Chaînes TV en cache : " + channels.size, 19f))
        if (channels.isEmpty()) {
            list.addView(label("Première synchronisation Zen 4 nécessaire pour les chaînes et logos."))
        }
        for (channel in channels.take(80)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(image, LinearLayout.LayoutParams(dp(56), dp(38)))
            logos.bind(image, channel)
            row.addView(label("  " + channel.name))
            list.addView(row)
        }
    }

    override fun onDestroy() {
        Zen4SessionHub.detach(this)
        client = null
        logos.close()
        super.onDestroy()
    }
}
