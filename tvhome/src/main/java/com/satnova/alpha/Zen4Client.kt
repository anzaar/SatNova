package com.satnova.alpha

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.InflaterInputStream

/** Zen 4 control channel from the TV itself; it never uses the phone's app data. */
internal data class ZenChannel(
    val id: String, val name: String, val position: Int, val radio: Boolean,
    val logoUrl: String? = null,
    val encrypted: Boolean? = null,
    val definition: String? = null,
    val frequencyMhz: Int? = null,
    val tpIndex: String? = null
)

internal class Zen4Client(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun state(connected: Boolean, message: String)
        fun catalogue(channels: List<ZenChannel>)
        fun playing(channelId: String)
        fun tuneConfirmed(channel: ZenChannel)
        fun tuneFailed(channel: ZenChannel, reason: String) { }
    }

    private val prefs = context.getSharedPreferences("zen4_tv", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor()
    private val discovery = Executors.newFixedThreadPool(12)
    private val remote = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(24), ThreadPoolExecutor.DiscardOldestPolicy())
    private val active = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var session: Socket? = null
    @Volatile private var authorized = false
    @Volatile private var jsonMode = true
    @Volatile private var connectedHost = ""
    @Volatile private var pendingTune = ""
    @Volatile private var pendingTuneAt = 0L
    @Volatile private var current = ""
    private var lastStatePoll = 0L
    private var lastChannelPoll = 0L
    private var requestedThrough = -1
    private var knownCount = 0
    private val collected = linkedMapOf<String, ZenChannel>()
    private val diagnostic = Zen4Diagnostic()
    private val tpFrequencies = mutableMapOf<String, Int>()
    private var tpRequested = false
    private val uuid: String by lazy {
        prefs.getString("uuid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("uuid", it).apply()
        }
    }

    val connected: Boolean get() = authorized && session != null
    val lastIp: String get() = prefs.getString("last_receiver_ip", "") ?: ""
    /** Only the host of the currently authenticated LAN session may be probed. */
    val verifiedHost: String? get() = connectedHost.takeIf { connected && privateIp(it) }
    val channels: List<ZenChannel> get() = synchronized(collected) { collected.values.sortedBy { it.position } }

    /** Export only observed safe metadata. No commands are sent by this function. */
    fun exportDiagnostic(): String = diagnostic.export(connected, channels)

    private fun tell(online: Boolean, msg: String) = main.post { listener.state(online, msg) }
    private fun publish() {
        val snapshot = channels
        main.post { listener.catalogue(snapshot) }
    }

    /** Keep the same authenticated G-MScreen login as the working phone app.
     * A failed IP is never considered a receiver merely because port 20000 opened.
     * Every candidate must pass the 108-byte signed login before it is saved. */
    fun start() {
        if (!active.compareAndSet(false, true)) return
        restoreCache()
        control.execute {
            while (active.get()) {
                try {
                    val tried = linkedSetOf<String>()
                    var lastError: String? = null
                    fun attempt(host: String): Boolean {
                        if (!active.get() || !tried.add(host)) return false
                        tell(false, "Connexion Zen 4 · $host…")
                        return try {
                            connectAndListen(host)
                            true
                        } catch (e: Exception) {
                            authorized = false
                            session?.let { try { it.close() } catch (_: Exception) {} }
                            session = null
                            val reason = when {
                                e.message?.contains("session", true) == true ->
                                    "session Zen 4 occupée (fermer SatNova original)"
                                e.message?.contains("autre récepteur", true) == true ->
                                    "identité Zen 4 différente"
                                e is SocketTimeoutException -> "délai réseau dépassé"
                                else -> e.javaClass.simpleName + ": " +
                                    (e.message ?: "connexion refusée").take(75)
                            }
                            lastError = "$host · $reason"
                            false
                        }
                    }

                    // First try the receiver explicitly selected or previously authenticated.
                    val remembered = lastIp.takeIf(::privateIp)
                    if (remembered != null && attempt(remembered)) continue

                    // Match the working phone app: first listen for G-MScreen
                    // receiver advertisements on UDP 25860, then scan TCP 20000.
                    tell(false, "Recherche Zen 4 · annonce réseau UDP…")
                    var connectedOnce = false
                    for (host in findUdpReceivers()) {
                        if (!active.get()) break
                        if (attempt(host)) { connectedOnce = true; break }
                    }
                    if (connectedOnce) continue
                    // Do not stop at the first host with an open port. Every
                    // candidate must pass an authenticated receiver handshake.
                    tell(false, "Recherche de Zen 4 sur le Wi-Fi…")
                    val found = findOnLan()
                    // The user might enter a new IP during discovery.
                    val currentManual = lastIp.takeIf(::privateIp)
                    if (currentManual != null && attempt(currentManual)) continue
                    for (host in found) {
                        if (!active.get()) break
                        if (attempt(host)) { connectedOnce = true; break }
                    }
                    if (connectedOnce) continue
                    tell(false, lastError?.let { "Zen 4 non connecté · $it" }
                        ?: "Zen 4 introuvable · vérifiez le Wi-Fi ou saisissez l'IP")
                    Thread.sleep(4500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    tell(false, "Recherche Zen 4 interrompue · " +
                        e.javaClass.simpleName)
                    try { Thread.sleep(4500) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    fun stop() {
        active.set(false)
        authorized = false
        try { session?.close() } catch (_: Exception) { }
        session = null
        control.shutdownNow()
        discovery.shutdownNow()
        remote.shutdownNow()
    }

    fun setHost(host: String): Boolean {
        if (!privateIp(host)) return false
        prefs.edit().putString("last_receiver_ip", host).apply()
        try { session?.close() } catch (_: Exception) { }
        return true
    }

    enum class TuneResult { SENT, DISCONNECTED, CHANNEL_NOT_FOUND, SEND_FAILED }

    private fun tuningOutcome(channel: ZenChannel, status: TuneResult): TuneResult {
        diagnostic.recordTune(channel, status.name)
        return status
    }

    /** Socket writes must never run on the Android UI thread. */
    fun tuneDetailed(channel: ZenChannel): TuneResult {
        val sock = session
        if (!authorized || sock == null || sock.isClosed || !sock.isConnected)
            return tuningOutcome(channel, TuneResult.DISCONNECTED)
        if (channels.none { it.id == channel.id })
            return tuningOutcome(channel, TuneResult.CHANNEL_NOT_FOUND)
        pendingTune = channel.id
        pendingTuneAt = SystemClock.elapsedRealtime()
        try {
            remote.execute {
                try {
                    if (!authorized || session !== sock || sock.isClosed) {
                        main.post { listener.tuneFailed(channel, "Connexion Zen 4 interrompue") }
                        return@execute
                    }
                    val req = if (jsonMode) JSONObject().put("request", "1000")
                        .put("array", JSONArray().put(JSONObject()
                            .put("TvState", if (channel.radio) "1" else "0")
                            .put("ProgramId", channel.id))).toString()
                    else "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1000\"><parm><TvState>" +
                        (if (channel.radio) "1" else "0") + "</TvState><ProgramId>" + channel.id +
                        "</ProgramId></parm></Command>"
                    synchronized(writeLock) {
                        if (session !== sock || sock.isClosed) throw java.io.IOException("Session interrompue")
                        sendFrame(sock, req)
                    }
                } catch (e: Exception) {
                    pendingTune = ""
                    main.post { listener.tuneFailed(channel, e.message ?: "Échec réseau") }
                }
            }
        } catch (_: Exception) {
            pendingTune = ""
            return tuningOutcome(channel, TuneResult.SEND_FAILED)
        }
        return tuningOutcome(channel, TuneResult.SENT)
    }

    fun tune(channel: ZenChannel): Boolean = tuneDetailed(channel) == TuneResult.SENT

    /**
     * Control receiver keys with the same G-MScreen request 1040 used by
     * phone SatNova. Only accepted codes from the explicit remote map.
     * Never blocks an AccessibilityService key callback on network I/O.
     */
    fun sendRemoteKey(keyValue: Int): Boolean {
        if (keyValue !in (1..22) && keyValue !in listOf(23, 32, 35, 36, 54, 69, 70))
            return false
        val sock = session ?: return false
        if (!authorized || sock.isClosed || !sock.isConnected || !active.get()) return false
        try {
            remote.execute {
                if (!authorized || session !== sock || sock.isClosed) return@execute
                try {
                    val payload = if (jsonMode) JSONObject().put("request", "1040")
                        .put("array", JSONArray().put(JSONObject()
                            .put("KeyValue", keyValue.toString()))).toString()
                    else "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
                        "<Command request=\"1040\"><parm><KeyValue>" +
                        keyValue + "</KeyValue></parm></Command>"
                    synchronized(writeLock) {
                        if (session === sock && authorized) sendFrame(sock, payload)
                    }
                } catch (_: Exception) {
                    try { sock.close() } catch (_: Exception) {}
                }
            }
            return true
        } catch (_: RejectedExecutionException) { return false }
    }

    private fun privateIp(ip: String): Boolean {
        val v = ip.split('.').map { it.toIntOrNull() }
        if (v.size != 4 || v.any { it == null || it !in 0..255 }) return false
        val a = v[0]!!; val b = v[1]!!
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** Prefer the real Wi-Fi / Ethernet transport, never a VPN, cellular or
     * tunnel address. An IP range is only a discovery hint, not authentication. */
    private fun lanPrefixes(): List<String> {
        val result = linkedSetOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI,
                    NetworkCapabilities.TRANSPORT_ETHERNET)) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(transport)) continue
                    val props = cm.getLinkProperties(network) ?: continue
                    for (link in props.linkAddresses) {
                        val ip = (link.address as? Inet4Address)?.hostAddress ?: continue
                        if (privateIp(ip)) result.add(ip.substringBeforeLast('.'))
                    }
                }
            }
        } catch (_: Exception) { }
        if (result.isNotEmpty()) return result.toList()
        // Firmware fallback: never accidentally scan mobile rmnet or VPN tun networks.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val net = interfaces.nextElement()
                val name = net.name.lowercase()
                if (!net.isUp || net.isLoopback ||
                    !(name.startsWith("wlan") || name.startsWith("eth") ||
                      name.startsWith("en") || name.startsWith("swlan"))) continue
                val addresses = net.inetAddresses
                while (addresses.hasMoreElements()) {
                    val ip = (addresses.nextElement() as? Inet4Address)
                        ?.hostAddress ?: continue
                    if (privateIp(ip)) result.add(ip.substringBeforeLast('.'))
                }
            }
        } catch (_: Exception) { }
        return result.toList()
    }

    /** 108-byte G-MScreen broadcast identical to the phone app's discovery.
     * The beacon only identifies candidates; it does not authorize commands
     * or pin the device identity. Only TCP login performs authentication. */
    private fun findUdpReceivers(): List<String> {
        val hosts = linkedSetOf<String>()
        var multicast: WifiManager.MulticastLock? = null
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wifi.createMulticastLock("satnova-home-zen4").apply {
                setReferenceCounted(false)
                acquire()
            }
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(25860))
                sock.soTimeout = 650
                val end = SystemClock.elapsedRealtime() + 2500L
                while (active.get() && SystemClock.elapsedRealtime() < end) {
                    val data = ByteArray(256)
                    val packet = DatagramPacket(data, data.size)
                    try {
                        sock.receive(packet)
                        val host = packet.address.hostAddress ?: continue
                        if (packet.length != 108 || !privateIp(host)) continue
                        val raw = packet.data.copyOfRange(
                            packet.offset, packet.offset + packet.length)
                        val decoded = raw.reversedArray()
                            .map { (it.toInt() xor 0x5B).toByte() }.toByteArray()
                        if (String(decoded, 0, 12, StandardCharsets.US_ASCII)
                                == "39WwijOog54a") hosts.add(host)
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) { }
        finally { try { if (multicast?.isHeld == true) multicast.release() }
            catch (_: Exception) { } }
        return hosts.toList()
    }

    /** Bounded TCP discovery, followed by an authenticated login in start(). */
    private fun findOnLan(): List<String> {
        val hosts = linkedSetOf<String>()
        for (prefix in lanPrefixes().take(2)) {
            if (!active.get()) break
            val jobs = (1..254).map { number -> discovery.submit<String?> {
                val host = "${prefix}.${number}"
                try {
                    Socket().use { probe ->
                        probe.connect(InetSocketAddress(host, 20000), 450)
                        host
                    }
                } catch (_: Exception) { null }
            } }
            for (job in jobs) {
                if (!active.get()) break
                try { job.get()?.let(hosts::add) }
                catch (_: Exception) { }
            }
        }
        return hosts.toList()
    }

    private fun readExact(sock: Socket, n: Int): ByteArray {
        val result = ByteArray(n)
        var i = 0
        while (i < n) {
            val count = sock.getInputStream().read(result, i, n - i)
            if (count < 0) throw EOFException("Connexion fermée")
            i += count
        }
        return result
    }

    private fun sendFrame(sock: Socket, payload: String) {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        val header = ("Start" + body.size.toString().padStart(7, '0') + "End")
            .toByteArray(StandardCharsets.US_ASCII)
        sock.getOutputStream().write(header + body)
        sock.getOutputStream().flush()
    }

    private fun decodeLogin(block: ByteArray): Pair<String, Boolean> {
        require(block.size == 108)
        val raw = block.reversedArray().map { (it.toInt() xor 0x5B).toByte() }.toByteArray()
        require(String(raw, 0, 12, StandardCharsets.US_ASCII) == "39WwijOog54a")
        require(raw[84].toInt() and 1 == 0) { "Aucune session disponible" }
        val identity = MessageDigest.getInstance("SHA-256")
            .digest(raw.copyOfRange(12, 20) + raw.copyOfRange(52, 60) + raw.copyOfRange(20, 52))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val remembered = prefs.getString("receiver_id", "")
        require(remembered.isNullOrBlank() || remembered == identity) { "Un autre récepteur a été détecté" }
        prefs.edit().putString("receiver_id", identity).apply()
        return identity to (raw[84].toInt() and 0x40 != 0)
    }

    private fun connectAndListen(host: String) {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, 20000), 3500)
            // Match the verified phone app's login timeout.
            sock.soTimeout = 4500
            val login = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"998\"><data>SatNova Home</data><uuid>$uuid</uuid></Command>"
            synchronized(writeLock) { sendFrame(sock, login) }
            val (_, json) = decodeLogin(readExact(sock, 108))
            jsonMode = json
            session = sock
            connectedHost = host
            authorized = true
            prefs.edit().putString("last_receiver_ip", host).apply()
            tell(true, "Connecté · Zen 4")
            knownCount = 0
            requestedThrough = -1
            tpRequested = false
            lastStatePoll = 0
            lastChannelPoll = 0
            while (active.get() && session === sock) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastStatePoll > 5000) {
                    request(sock, 3)
                    request(sock, 15)
                    lastStatePoll = now
                }
                if (!tpRequested && now - lastStatePoll < 2500L) {
                    // Read-only transponder list, already used by phone SatNova.
                    request(sock, 24)
                    tpRequested = true
                }
                if (now - lastChannelPoll > 3500) {
                    val size = synchronized(collected) { collected.size }
                    if (knownCount == 0 || size < knownCount) {
                        val start = if (requestedThrough < 0) 0 else requestedThrough + 1
                        if (start < 20000) {
                            val end = (start + 99).coerceAtMost(19999)
                            request(sock, 0, start, end)
                            requestedThrough = end
                        }
                    }
                    lastChannelPoll = now
                }
                try { readReply(sock) } catch (_: SocketTimeoutException) { }
                if (pendingTune.isNotBlank() && SystemClock.elapsedRealtime() - pendingTuneAt > 9000) {
                    pendingTune = ""
                    tell(true, "Changement de chaîne non confirmé")
                }
            }
        } finally {
            if (session === sock) {
                session = null
                authorized = false
            }
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private fun request(sock: Socket, type: Int, from: Int = 0, to: Int = 0) {
        val payload = if (jsonMode) JSONObject().put("request", type.toString()).also {
            if (type == 0) it.put("FromIndex", from.toString()).put("ToIndex", to.toString())
        }.toString() else if (type == 0)
            "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"0\"><FromIndex>$from</FromIndex><ToIndex>$to</ToIndex></Command>"
        else "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"$type\" />"
        synchronized(writeLock) { sendFrame(sock, payload) }
    }

    private fun rows(text: String): List<JSONObject> = try {
        when (val obj = JSONTokener(text).nextValue()) {
            is JSONArray -> (0 until obj.length()).mapNotNull(obj::optJSONObject)
            is JSONObject -> {
                val arr = obj.optJSONArray("array") ?: obj.optJSONArray("Array")
                if (arr == null) listOf(obj) else (0 until arr.length()).mapNotNull(arr::optJSONObject)
            }
            else -> emptyList()
        }
    } catch (_: Exception) { emptyList() }

    private fun value(obj: JSONObject, vararg names: String): String? =
        names.firstNotNullOfOrNull { key -> obj.optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" } }

    private fun flag(value: String?): Boolean? = when (value?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    private fun frequency(raw: String?): Int? {
        val v = raw?.trim()?.replace(",", ".")?.toDoubleOrNull() ?: return null
        if (!v.isFinite() || v <= 0) return null
        val mhz = if (v > 50000) v / 1000.0 else v
        return mhz.toInt().takeIf { mhz in 1000.0..20000.0 }
    }

    private fun tpKey(row: JSONObject): String? =
        value(row, "TPIndex", "TpIndex", "TPId", "TPID", "TransponderId")
            ?.toIntOrNull()?.toString()

    private fun channelTpKey(row: JSONObject, id: String): String? =
        tpKey(row) ?: id.takeIf { it.length >= 9 }
            ?.substring(4, 9)?.toIntOrNull()?.toString()

    private fun definitionOf(row: JSONObject, name: String): String? {
        val label = name.uppercase()
        if (Regex("(?<![A-Z0-9])(?:4K|UHD)(?![A-Z0-9])").containsMatchIn(label)) return "4K"
        if (Regex("(?<![A-Z0-9])(?:HD|FHD)(?![A-Z0-9])").containsMatchIn(label)) return "HD"
        if (Regex("(?<![A-Z0-9])SD(?![A-Z0-9])").containsMatchIn(label)) return "SD"
        return flag(value(row, "HD", "IsHD", "HighDefinition"))?.let { if (it) "HD" else "SD" }
    }

    private fun readReply(sock: Socket) {
        val header = readExact(sock, 16)
        require(String(header, 0, 4, StandardCharsets.US_ASCII) == "GCDH")
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val length = buffer.int
        val kind = buffer.int
        val status = buffer.int
        require(length in 0..2_000_000)
        val bytes = if (length > 0) readExact(sock, length) else ByteArray(0)
        val decoded = try { InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } }
            catch (_: Exception) { bytes }
        val items = rows(decoded.toString(StandardCharsets.UTF_8))
        diagnostic.observe(kind, status, items)
        if (kind == 15 || kind == 14) {
            items.firstOrNull()?.let { obj ->
                knownCount = value(obj, "ChannelNum")?.toIntOrNull()?.coerceIn(0, 20000) ?: knownCount
            }
        }
        if (kind == 24 && status == 0) {
            val groups = items.mapNotNull { row ->
                val k = tpKey(row) ?: return@mapNotNull null
                val f = frequency(value(row, "Frequency", "Freq", "TPFreq", "FrequencyMHz", "TPFrequency"))
                    ?: return@mapNotNull null
                k to f
            }.groupBy({ it.first }, { it.second })
            synchronized(collected) {
                tpFrequencies.clear()
                groups.forEach { (key, values) ->
                    if (values.distinct().size == 1) tpFrequencies[key] = values.first()
                }
                var updated = false
                collected.toMap().forEach { (id, ch) ->
                    val freq = ch.frequencyMhz ?: ch.tpIndex?.let(tpFrequencies::get)
                    if (freq != null && freq != ch.frequencyMhz) {
                        collected[id] = ch.copy(frequencyMhz = freq)
                        updated = true
                    }
                }
                if (updated) { persistCache(); publish() }
            }
        }
        if (kind == 0 && status == 0) {
            var changed = false
            synchronized(collected) {
                items.forEachIndexed { i, obj ->
                    val id = value(obj, "ServiceID", "ProgramId", "ProgramID") ?: return@forEachIndexed
                    val name = value(obj, "ServiceName", "ChannelName", "Name", "ProgramName") ?: return@forEachIndexed
                    val position = value(obj, "ChannelNumber", "ChannelNo", "ChNum", "ProgramNumber")?.toIntOrNull()
                        ?: value(obj, "ChannelIndex", "Index")?.toIntOrNull()?.plus(1)
                        ?: collected.size + 1
                    val receiverLogo = ChannelLogoSource.verifiedHttpLogo(value(obj,
                        "LogoUrl", "ChannelLogoUrl", "IconUrl", "ChannelIconUrl",
                        "PiconUrl", "Logo", "ChannelIcon", "Icon", "Picon"))
                    val index = channelTpKey(obj, id)
                    val encrypted = flag(value(obj, "Scramble", "Scrambled", "Encrypted", "IsScrambled"))
                    val mhz = frequency(value(obj, "Frequency", "Freq", "TPFreq", "FrequencyMHz"))
                        ?: index?.let(tpFrequencies::get)
                    collected[id] = ZenChannel(
                        id, name, position, value(obj, "Radio", "TvState") == "1",
                        receiverLogo ?: collected[id]?.logoUrl,
                        encrypted ?: collected[id]?.encrypted,
                        definitionOf(obj, name) ?: collected[id]?.definition,
                        mhz ?: collected[id]?.frequencyMhz,
                        index ?: collected[id]?.tpIndex
                    )
                    changed = true
                }
            }
            if (changed) {
                persistCache()
                publish()
            }
        }
        if (kind == 3) {
            val id = items.firstOrNull()?.let { value(it, "Data", "ProgramId", "ServiceID") }
            if (!id.isNullOrBlank()) {
                current = id
                main.post { listener.playing(id) }
                if (id == pendingTune) {
                    pendingTune = ""
                    channels.firstOrNull { it.id == id }?.let { channel ->
                        main.post { listener.tuneConfirmed(channel) }
                    }
                }
            }
        }
        if (kind == 1000 && status != 0) {
            pendingTune = ""
            tell(true, "Changement refusé par le récepteur ($status)")
        }
    }

    private fun persistCache() {
        val array = JSONArray()
        channels.take(20000).forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("position", it.position).put("radio", it.radio)
                .put("logoUrl", it.logoUrl)
                .put("encrypted", it.encrypted)
                .put("definition", it.definition)
                .put("frequencyMhz", it.frequencyMhz)
                .put("tpIndex", it.tpIndex))
        }
        val tmp = java.io.File(context.filesDir, "zen-catalogue.tmp")
        val dest = java.io.File(context.filesDir, "zen-catalogue.json")
        try {
            tmp.writeText(array.toString())
            if (!tmp.renameTo(dest)) {
                dest.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    private fun restoreCache() {
        try {
            val raw = java.io.File(context.filesDir, "zen-catalogue.json").readText()
            val array = JSONArray(raw)
            synchronized(collected) {
                (0 until array.length()).forEach {
                    val obj = array.optJSONObject(it) ?: return@forEach
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        collected[id] = ZenChannel(
                            id, name, obj.optInt("position", it + 1),
                            obj.optBoolean("radio"),
                            ChannelLogoSource.verifiedHttpLogo(obj.optString("logoUrl", "")),
                            if (obj.isNull("encrypted")) null else obj.optBoolean("encrypted"),
                            obj.optString("definition", "").ifBlank { null },
                            if (obj.isNull("frequencyMhz")) null else obj.optInt("frequencyMhz"),
                            obj.optString("tpIndex", "").ifBlank { null })
                    }
                }
            }
            publish()
        } catch (_: Exception) { }
    }
}
