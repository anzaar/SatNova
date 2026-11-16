package com.satnova.alpha

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Read-only protocol snapshot for investigating CHMAX/channel logos.
 * Never exports login bytes, receiver identity, LAN IP, serial numbers,
 * Wi-Fi credentials, stream links, passwords or unrestricted raw packets.
 */
internal class Zen4Diagnostic {
    private data class Reply(
        var count: Int = 0,
        var status: Int = 0,
        val fields: MutableSet<String> = sortedSetOf(),
        val samples: MutableList<JSONObject> = mutableListOf()
    )

    private val lock = Any()
    private val replies = linkedMapOf<Int, Reply>()

    private val privateKey = Regex(
        "(?i)(password|passwd|passphrase|pwd|pin|token|secret|credential|cookie|" +
            "serial|uuid|chip|macaddress|macaddr|auth|login|username|user(name)?|" +
            "account|email|ipaddress|localip|wifi|ssid|accesskey|session|apikey)"
    )
    private val relevantKey = Regex(
        "(?i)(logo|icon|picon|chmax|image|picture|artwork|thumbnail|path|" +
            "name|channel|service|program|fav|number|index|provider|group|radio|scrambl|hd|definition|frequency|tpindex|" +
            "type|id|model|version|count|total|format|data)"
    )

    fun observe(kind: Int, status: Int, rows: List<JSONObject>) {
        // Cap memory use even if the receiver reports a very large catalogue.
        synchronized(lock) {
            val item = replies.getOrPut(kind) { Reply() }
            item.count = (item.count + 1).coerceAtMost(100000)
            item.status = status
            val maxSamples = if (kind == 0) 60 else 2
            for (row in rows.take(70)) {
                val keys = row.keys().asSequence().take(140).toList()
                item.fields.addAll(keys)
                if (item.samples.size < maxSamples) {
                    val sample = JSONObject()
                    for (key in keys) {
                        if (privateKey.containsMatchIn(key) || !relevantKey.containsMatchIn(key)) continue
                        if (sample.length() >= 50) break
                        val value = row.opt(key)
                        if (value == null || value == JSONObject.NULL) continue
                        if (value is JSONObject || value is JSONArray) continue
                        sanitized(value.toString())?.let { sample.put(key, it) }
                    }
                    item.samples.add(sample)
                }
            }
        }
    }

    private fun sanitized(value: String): String? {
        val v = value.trim().take(300)
        if (v.isBlank()) return null
        if (v.contains("\n") || v.contains("\r")) return "[multi-ligne masqué]"
        if (v.contains("://")) {
            return try {
                val uri = URI(v)
                val host = uri.host.orEmpty()
                if (host.isBlank()) "[lien masqué]"
                else {
                    val local = Regex("^(10\\.|192\\.168\\.|172\\.|127\\.|169\\.254\\.)")
                        .containsMatchIn(host) || host.equals("localhost", true)
                    val authority = if (local) "[adresse locale masquée]" else host
                    // Query/fragment/userinfo may contain credentials: never export them.
                    (uri.scheme.orEmpty() + "://" + authority + uri.path.orEmpty().take(160)).take(240)
                }
            } catch (_: Exception) { "[lien masqué]" }
        }
        // Prevent accidental export of local/private addresses in unexpected keys.
        if (Regex("(?:^|\\s)(?:10|127|192|172)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?:\\s|$)")
                .containsMatchIn(v)) return "[adresse locale masquée]"
        return v.take(180)
    }

    fun export(online: Boolean, cachedChannels: List<ZenChannel>): String {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val root = JSONObject()
            .put("schema", "satnova-zen4-chmax-diagnostic-v1")
            .put("generated_utc", utc)
            .put("connected_at_export", online)
            .put("collection_method", "Observed read-only G-MScreen responses: 0, 14, 15, 3; no extra receiver commands")
            .put("channel_count_in_app", cachedChannels.size)
            .put("warning", "CHMAX images might not be exposed through G-MScreen channel metadata")
        synchronized(lock) {
            val types = JSONArray()
            replies.forEach { (kind, item) ->
                types.put(JSONObject()
                    .put("response_type", kind)
                    .put("observed_count", item.count)
                    .put("last_status", item.status)
                    .put("field_names", JSONArray(item.fields.toList()))
                    .put("samples", JSONArray(item.samples)))
            }
            root.put("observed_receiver_responses", types)
        }
        val catalogue = JSONArray()
        cachedChannels.take(100).forEach { channel ->
            val row = JSONObject()
                .put("name", channel.name.take(120))
                .put("position", channel.position)
                .put("radio", channel.radio)
                .put("receiver_logo_url", channel.logoUrl?.let(::sanitized) ?: JSONObject.NULL)
                .put("available_public_logo_candidates", JSONArray(ChannelLogoSource.urls(channel).mapNotNull(::sanitized)))
            catalogue.put(row)
        }
        root.put("channel_examples", catalogue)
        return root.toString(2)
    }
}
