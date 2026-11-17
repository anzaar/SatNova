package com.satnova.alpha

import android.content.Context
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

    fun start() {
        if (!active.compareAndSet(false, true)) return
        restoreCache()
        control.execute {
            var failures = 0
            while (active.get()) {
                try {
                    var host = lastIp.takeIf(::privateIp)
                    if (host == null || failures >= 2) {
                        tell(false, "Recherche du récepteur…")
                        host = findOnLan()
                    }
                    if (host == null) {
                        tell(false, "Récepteur non connecté")
                        Thread.sleep(5500)
                        continue
                    }
                    connectAndListen(host)
                    failures = 0
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {
                    failures++
                    authorized = false
                    session?.let { try { it.close() } catch (_: Exception) { } }
                    session = null
                    tell(false, "Récepteur non connecté")
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
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

    /** Distinguish lost transport from a stale cached service ID or a write failure. */
    fun tuneDetailed(channel: ZenChannel): TuneResult {
        val sock = session
        if (!authorized || sock == null || sock.isClosed || !sock.isConnected)
            return tuningOutcome(channel, TuneResult.DISCONNECTED)
        if (channels.none { it.id == channel.id })
            return tuningOutcome(channel, TuneResult.CHANNEL_NOT_FOUND)
        try {
            val req = if (jsonMode) JSONObject().put("request", "1000")
                .put("array", JSONArray().put(JSONObject()
                    .put("TvState", if (channel.radio) "1" else "0")
                    .put("ProgramId", channel.id))).toString()
            else "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1000\"><parm><TvState>" +
                (if (channel.radio) "1" else "0") + "</TvState><ProgramId>" + channel.id +
                "</ProgramId></parm></Command>"
            synchronized(writeLock) {
                if (session !== sock || sock.isClosed) return tuningOutcome(channel, TuneResult.DISCONNECTED)
                sendFrame(sock, req)
            }
            pendingTune = channel.id
            pendingTuneAt = SystemClock.elapsedRealtime()
            return tuningOutcome(channel, TuneResult.SENT)
        } catch (_: Exception) {
            pendingTune = ""
            try { sock.close() } catch (_: Exception) {}
            return tuningOutcome(channel, TuneResult.SEND_FAILED)
        }
    }

    fun tune(channel: ZenChannel): Boolean = tuneDetailed(channel) == TuneResult.SENT

    /**
     * Control receiver keys with the same G-MScreen request 1040 used by
     * phone SatNova. Only accepted codes from the explicit remote map.
     * Never blocks an AccessibilityService key callback on network I/O.
     */
    fun sendRemoteKey(keyValue: Int): Boolean {
        if (keyValue !in (1..22) && keyValue !in listOf(23, 32, 35, 36, 69, 70))
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

    private fun prefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>().mapNotNull { a ->
                a.hostAddress?.takeIf(::privateIp)?.substringBeforeLast('.')
            }.firstOrNull()
    } catch (_: Exception) { null }

    /** Port probing only; authenticated login happens once on the chosen candidate. */
    private fun findOnLan(): String? {
        val p = prefix() ?: return null
        val jobs = (1..254).map { num -> discovery.submit<String?> {
            val host = "$p.$num"
            try {
                Socket().use {
                    it.connect(InetSocketAddress(host, 20000), 420)
                    host
                }
            } catch (_: Exception) { null }
        } }
        val candidates = jobs.mapNotNull { future ->
            try { future.get() } catch (_: Exception) { null }
        }
        return candidates.firstOrNull()
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
        sock.connect(InetSocketAddress(host, 20000), 2800)
        sock.soTimeout = 2100
        try {
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
