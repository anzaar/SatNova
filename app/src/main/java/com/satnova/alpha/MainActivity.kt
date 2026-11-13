package com.satnova.alpha

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

// Prototype: compatibility with Echolink Zen 4 is NOT confirmed until real-device testing.
class MainActivity : Activity() {
    private val bg = Color.rgb(10, 16, 32)
    private val surface = Color.rgb(23, 32, 53)
    private val fg = Color.rgb(239, 244, 255)
    private val muted = Color.rgb(155, 172, 197)
    private val accent = Color.rgb(156, 155, 255)
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var ip: EditText
    private lateinit var connectButton: Button
    private val work = Executors.newSingleThreadExecutor()
    private val discoveryWork = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var verified = false
    @Volatile private var jsonMode = true
    private val clientId by lazy {
        getSharedPreferences("satnova", Context.MODE_PRIVATE).let { p ->
            p.getString("uuid", null) ?: UUID.randomUUID().toString().also { p.edit().putString("uuid", it).apply() }
        }
    }

    private fun label(value: String, size: Float = 16f, color: Int = fg): TextView {
        return TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            setPadding(12, 10, 12, 10)
        }
    }
    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12, 12, 12, 12)
    }
    private fun row(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(textValue: String, parent: LinearLayout, weight: Float = 1f, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            setTextColor(fg)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(surface))
            val params = LinearLayout.LayoutParams(0, 57.dp, weight)
            params.setMargins(3.dp, 3.dp, 3.dp, 3.dp)
            parent.addView(this, params)
            setOnClickListener { action() }
        }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun report(value: String) = runOnUiThread {
        if (::status.isInitialized) status.text = value
    }
    private fun setVerified(value: Boolean) = runOnUiThread {
        verified = value
        if (::connectButton.isInitialized) connectButton.text = if (value) "Disconnect" else "Connect"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val root = column()
        scroll.addView(root)
        val title = label("SATNOVA", 29f).apply { setTypeface(null, Typeface.BOLD) }
        root.addView(title)
        root.addView(label("SMART SATELLITE CONTROL  /  ALPHA 0.1", 12f, accent))
        root.addView(label("Echolink Zen 4 · Local network prototype", 13f, muted))
        status = label("Disconnected. Connect the phone and receiver to the same Wi-Fi.", 15f, accent)
        root.addView(status)
        ip = EditText(this).apply {
            setSingleLine(true); setText("192.168.1.2")
            setTextColor(fg); setHintTextColor(muted); hint = "Receiver local IP"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        root.addView(ip)
        val connection = row(root)
        button("Discover", connection) { discover() }
        connectButton = button("Connect", connection) {
            if (verified) work.execute { disconnect(); report("Disconnected") }
            else connect(ip.text.toString().trim())
        }
        root.addView(label("REMOTE CONTROL", 21f))
        val power = row(root)
        button("Power", power) { sendKey(42) }
        button("Mute", power) { sendKey(23) }
        button("EPG", power) { sendKey(32) }
        val volume = row(root)
        button("Volume +", volume) { sendKey(35) }
        button("Up", volume) { sendKey(1) }
        button("CH +", volume) { sendKey(69) }
        val nav = row(root)
        button("Left", nav) { sendKey(3) }
        button("OK", nav) { sendKey(5) }
        button("Right", nav) { sendKey(4) }
        val down = row(root)
        button("Volume -", down) { sendKey(36) }
        button("Down", down) { sendKey(2) }
        button("CH -", down) { sendKey(70) }
        val menu = row(root)
        button("Menu", menu) { sendKey(6) }
        button("Exit", menu) { sendKey(7) }
        button("Info", menu) { sendKey(57) }
        button("Fav", menu) { sendKey(33) }
        val colors = row(root)
        button("Red", colors) { sendKey(8) }
        button("Green", colors) { sendKey(9) }
        button("Yellow", colors) { sendKey(10) }
        button("Blue", colors) { sendKey(11) }
        listOf(listOf(1,2,3), listOf(4,5,6), listOf(7,8,9)).forEach { digits ->
            val r = row(root)
            digits.forEach { number -> button("$number", r) { sendKey(number+12) } }
        }
        val last = row(root)
        button("TV/R", last) { sendKey(22) }
        button("0", last) { sendKey(12) }
        button("Back", last) { sendKey(29) }
        root.addView(label("DIAGNOSTICS", 19f))
        diagnostics = label("Login not checked. Port 20000 is not proof of compatibility.", 13f, muted)
        root.addView(diagnostics)
        root.addView(label("No ads. No cloud. Keep port 20000 private. Debug build — not hardware tested.", 12f, muted))
        setContentView(scroll)
    }
    private fun isPrivate(host: String): Boolean {
        val p = host.split('.')
        if (p.size != 4) return false
        val n = p.map { t ->
            if (t.length !in 1..3 || (t.length > 1 && t.startsWith("0"))) return false
            val v = t.toIntOrNull() ?: return false
            if (v !in 0..255) return false
            v
        }
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
    private fun frame(payload: String): ByteArray {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        return ("Start" + body.size.toString().padStart(7, '0') + "End").toByteArray(StandardCharsets.US_ASCII) + body
    }
    private fun read(input: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var index = 0
        while (index < count) {
            val n = input.read(data, index, count-index)
            if (n < 0) throw IllegalStateException("Receiver closed the connection")
            index += n
        }
        return data
    }
    private fun decode(raw: ByteArray): Triple<String,Boolean,Boolean> {
        if (raw.size != 108) throw IllegalStateException("Unexpected login size")
        val bytes = raw.reversedArray().map { (it.toInt() xor 0x5B).toByte() }.toByteArray()
        val signature = String(bytes,0,12,StandardCharsets.US_ASCII)
        if (signature != "39WwijOog54a") throw IllegalStateException("Not a recognized G-MScreen device")
        val modelBytes = bytes.copyOfRange(20,52)
        val end = modelBytes.indexOf(0.toByte()).let { if (it < 0) modelBytes.size else it }
        val model = String(modelBytes,0,end,StandardCharsets.UTF_8).ifBlank { "Receiver" }
        val flags = bytes[84].toInt() and 255
        return Triple(model,flags and 0x40 != 0,flags and 0x01 != 0)
    }
    private fun connect(host: String) = work.execute {
        if (!isPrivate(host)) { report("Use a private LAN IPv4 address, e.g. 192.168.1.2"); return@execute }
        disconnect()
        report("Connecting to $host:20000 …")
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, 20000), 3500)
            s.soTimeout = 4500
            report("TCP is open. Verifying receiver login …")
            val xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"998\"><data>SatNova Alpha</data><uuid>$clientId</uuid></Command>"
            s.getOutputStream().write(frame(xml))
            s.getOutputStream().flush()
            val (model,useJson,full) = decode(read(s.getInputStream(),108))
            if (full) throw IllegalStateException("Client slots full. Close SAT Chef first.")
            jsonMode = useJson
            socket = s
            setVerified(true)
            report("LOGIN VERIFIED: $model · " + if(useJson) "JSON" else "XML")
            runOnUiThread { diagnostics.text = "Protocol login verified; test Volume+ while watching the TV. $model" }
            s.soTimeout = 0
            Thread {
                try {
                    val input = s.getInputStream()
                    while (socket === s) {
                        val header = read(input,16)
                        if (String(header,0,4,StandardCharsets.US_ASCII) != "GCDH") throw IllegalStateException("Unknown reply")
                        val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.position(4)
                        val length = bb.int
                        if (length !in 0..2000000) throw IllegalStateException("Response too large")
                        read(input,length)
                    }
                } catch (e: Exception) {
                    if (socket === s) { socket = null; setVerified(false); report("Connection lost: ${e.message}") }
                } finally { try { s.close() } catch (_: Exception) { } }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            disconnect()
            report("Connection/login failed: ${e.message}")
        }
    }
    private fun sendKey(value: Int) = work.execute {
        val s = socket
        if (!verified || s == null) { report("Connect and verify receiver first"); return@execute }
        try {
            val payload = if (jsonMode) "{\"request\":\"1040\",\"array\":[{\"KeyValue\":\"$value\"}]}"
                else "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1040\"><parm><KeyValue>$value</KeyValue></parm></Command>"
            s.getOutputStream().write(frame(payload)); s.getOutputStream().flush()
            report("Sent key $value. Check the receiver screen.")
        } catch (e: Exception) { disconnect(); report("Failed to send key: ${e.message}") }
    }
    private fun discover() = discoveryWork.execute {
        report("Listening for receiver announcements (6 seconds) …")
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifi.createMulticastLock("satnova").apply { setReferenceCounted(false) }
            lock.acquire()
            try {
                DatagramSocket(null).use { udp ->
                    udp.reuseAddress = true
                    udp.bind(InetSocketAddress(25860))
                    udp.soTimeout = 900
                    val until = System.nanoTime() + 6_000_000_000L
                    var found = false
                    while (!found && System.nanoTime() < until) {
                        val bytes = ByteArray(256)
                        val p = DatagramPacket(bytes,bytes.size)
                        try {
                            udp.receive(p)
                            if (p.length == 108) {
                                val decoded = decode(p.data.copyOfRange(p.offset,p.offset+p.length))
                                val host = p.address.hostAddress ?: ""
                                if (isPrivate(host)) {
                                    found = true
                                    runOnUiThread { ip.setText(host) }
                                    report("Found ${decoded.first} at $host")
                                }
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                        catch (_: IllegalStateException) { }
                    }
                    if (!found) report("No announcements found. Enter IP manually.")
                }
            } finally { if(lock.isHeld) lock.release() }
        } catch (e: Exception) { report("Discovery unavailable: ${e.message}") }
    }
    private fun disconnect() {
        verified = false
        val s = socket; socket = null
        try { s?.close() } catch (_: Exception) { }
        setVerified(false)
    }
    override fun onDestroy() {
        disconnect()
        work.shutdownNow()
        discoveryWork.shutdownNow()
        super.onDestroy()
    }
}
