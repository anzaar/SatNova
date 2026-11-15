package com.satnova.alpha

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Phone-to-Android-TV remote. No G-MScreen handshake, receiver or TV stream required. */
class TvRemoteActivity : Activity() {
    private val background = Color.rgb(8, 12, 25)
    private val panel = Color.rgb(27, 35, 57)
    private val primary = Color.rgb(180, 172, 255)
    private lateinit var ip: EditText
    private lateinit var code: EditText
    private lateinit var codeRow: LinearLayout
    private lateinit var state: TextView
    private lateinit var client: AndroidTvRemoteClient
    private val remoteKeys = mutableListOf<Button>()
    private var multicast: WifiManager.MulticastLock? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val Int.dp get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        client = AndroidTvRemoteClient(
            this,
            status = { state.text = it },
            enterCode = { codeRow.visibility = View.VISIBLE },
            readyChanged = { ready -> remoteKeys.forEach { it.isEnabled = ready } }
        )
        val outer = ScrollView(this).apply { setBackgroundColor(background); isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 20.dp, 16.dp, 24.dp)
        }
        outer.addView(content, ViewGroup.LayoutParams(-1, -2))
        setContentView(outer)

        content.addView(label("SATNOVA V2  •  TÉLÉCOMMANDE TV", 22f, Color.WHITE, true))
        content.addView(label("Lifemaxx / Android TV 14 • commande Wi-Fi indépendante du récepteur satellite.",
            13f, primary), margin(top = 10, bottom = 14))
        val connectCard = group(content)
        connectCard.addView(label("Téléviseur Android TV", 17f, Color.WHITE, true))
        state = label("Recherchez Lifemaxx sur le même Wi-Fi.", 13f, primary)
        connectCard.addView(state, margin(top = 9, bottom = 12))
        ip = EditText(this).apply {
            setSingleLine(true)
            hint = "Adresse IP de Lifemaxx (pas celle du Zen 4)"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setText(client.lastHost())
        }
        connectCard.addView(ip, margin())
        addButtons(connectCard,
            listOf("⌕  Rechercher" to { discoverTv() },
                "Associer" to { startConnection(true) },
                "Reconnecter" to { startConnection(false) }),
            top = 8, remote = false)

        codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        codeRow.addView(label("Recopiez le code à 6 caractères affiché sur la TV.", 13f, primary))
        code = EditText(this).apply {
            setSingleLine(true)
            hint = "Code TV (ex. A1B2C3)"
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        codeRow.addView(code, margin())
        addButtons(codeRow, listOf("Confirmer le code" to {
            client.submitCode(code.text.toString())
            codeRow.visibility = View.GONE
        }), top = 6, remote = false)
        connectCard.addView(codeRow, margin(top = 8))

        val remoteCard = group(content)
        remoteCard.addView(label("COMMANDES DE LA TÉLÉVISION", 17f, Color.WHITE, true),
            margin(bottom = 11))
        addRemoteRow(remoteCard, listOf(
            "⏻" to KeyEvent.KEYCODE_POWER,
            "INPUT" to KeyEvent.KEYCODE_TV_INPUT,
            "HOME" to KeyEvent.KEYCODE_HOME,
            "BACK" to KeyEvent.KEYCODE_BACK))
        addRemoteRow(remoteCard, listOf("VOL −" to KeyEvent.KEYCODE_VOLUME_DOWN,
            "MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
            "VOL +" to KeyEvent.KEYCODE_VOLUME_UP))
        addRemoteRow(remoteCard, listOf("CH −" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "MENU" to KeyEvent.KEYCODE_MENU,
            "CH +" to KeyEvent.KEYCODE_CHANNEL_UP))
        addRemoteRow(remoteCard, listOf("▲" to KeyEvent.KEYCODE_DPAD_UP))
        addRemoteRow(remoteCard, listOf("◀" to KeyEvent.KEYCODE_DPAD_LEFT,
            "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
            "▶" to KeyEvent.KEYCODE_DPAD_RIGHT))
        addRemoteRow(remoteCard, listOf("▼" to KeyEvent.KEYCODE_DPAD_DOWN))
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { digits ->
            addRemoteRow(remoteCard, digits.map { it.toString() to (KeyEvent.KEYCODE_0 + it) })
        }
        addRemoteRow(remoteCard, listOf("0" to KeyEvent.KEYCODE_0,
            "PLAY/PAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        content.addView(label("Les touches INPUT, CH et POWER dépendent du firmware Lifemaxx. " +
            "L'association se fait par le code affiché sur la TV. Aucun ADB, root ou décodeur requis.",
            12f, Color.LTGRAY), margin(top = 13, bottom = 8))
        val previous = client.lastHost()
        if (previous.isNotBlank() && client.isPaired(previous)) {
            ip.setText(previous)
            client.connect(previous)
        }
    }

    private fun validHost(): String? {
        val host = ip.text.toString().trim()
        val parts = host.split(".")
        val nums = parts.mapNotNull { it.toIntOrNull()?.takeIf { num -> num in 0..255 } }
        val privateLan = nums.size == 4 && parts.size == 4 &&
            (nums[0] == 10 || nums[0] == 192 && nums[1] == 168 ||
                nums[0] == 172 && nums[1] in 16..31)
        if (!privateLan) {
            state.text = "Adresse invalide. Entrez l'IP Wi-Fi privée de Lifemaxx (ex. 192.168.1.10)."
            return null
        }
        return host
    }

    private fun startConnection(pair: Boolean) {
        val host = validHost() ?: return
        if (pair) {
            codeRow.visibility = View.GONE
            client.pair(host)
        } else client.connect(host)
    }

    private fun discoverTv() {
        if (discovery != null) { state.text = "Recherche déjà en cours…"; return }
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            multicast = wifi?.createMulticastLock("satnova_tv_discovery")?.apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {}
        state.text = "Recherche des téléviseurs Android TV sur le Wi-Fi…"
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { state.text = "Recherche TV indisponible ($errorCode). Saisissez l'IP manuellement." }
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        if (!host.contains(":")) runOnUiThread {
                            ip.setText(host)
                            state.text = "TV trouvée : " + info.serviceName + " • " + host +
                                "\nAppuyez sur Associer pour afficher le code sur la télévision."
                        }
                    }
                })
            }
        }
        discovery = listener
        try {
            manager.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            state.text = "Recherche impossible : " + (e.message ?: "utilisez l'adresse IP")
            stopDiscovery()
        }
    }

    private fun stopDiscovery() {
        val listener = discovery
        discovery = null
        if (listener != null) try {
            (getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener)
        } catch (_: Exception) {}
        try { multicast?.release() } catch (_: Exception) {}
        multicast = null
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun margin(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, top.dp, 0, bottom.dp) }

    private fun group(parent: LinearLayout): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 15.dp, 14.dp, 15.dp)
            setBackgroundColor(panel)
        }
        parent.addView(group, margin(top = 12, bottom = 6))
        return group
    }

    private fun addButtons(
        parent: LinearLayout, buttons: List<Pair<String, () -> Unit>>,
        top: Int = 4, remote: Boolean = true
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(row, margin(top = top))
        for ((label, action) in buttons) {
            val item = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                minHeight = 0
                setOnClickListener { action() }
                if (remote) { isEnabled = false; remoteKeys.add(this) }
            }
            row.addView(item, LinearLayout.LayoutParams(0, 52.dp, 1f))
        }
    }

    private fun addRemoteRow(parent: LinearLayout, keys: List<Pair<String, Int>>) =
        addButtons(parent, keys.map { (label, key) -> label to { client.press(key) } })

    override fun onDestroy() {
        stopDiscovery()
        client.close()
        super.onDestroy()
    }
}
