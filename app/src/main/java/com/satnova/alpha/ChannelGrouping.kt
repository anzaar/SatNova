package com.satnova.alpha

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Channel identity is its real Zen 4 ProgramId, not a guessed number or logo. */
internal data class RemoteChannel(
    val id: String, val name: String, val position: Int, val tvState: Int,
    val receiverFavorite: Boolean, val satellite: String?, val frequency: String?
)

internal data class FavoriteSlot(val key: String, val label: String = "", val preferredId: String = "")

internal object ChannelGrouping {
    private val quality = Regex("(?i)(?:\\s|[-_.()])+((?:UHD|FHD|FULL\\s*HD|HD|SD|4K|HEVC|H265|BACKUP|BKP|FEED|LOW|HIGH)(?:\\s|[-_.()]*)?)+$")
    private val whitespace = Regex("[\\s._-]+")
    private val mbcFamily = Regex("(?i)^MBC(?:[\\s._-]|[0-9]|$)")
    private val mbcSpacing = Regex("(?i)^MBC\\s*([0-9]+)")

    fun clean(name: String): String {
        var result = name.trim()
        repeat(3) { result = result.replace(quality, "").trim().trimEnd('-','_','.', ' ') }
        return result.replace(mbcSpacing, "MBC $1").replace(whitespace, " ").trim()
    }

    fun key(name: String): String {
        val cleaned = clean(name)
        return if (mbcFamily.containsMatchIn(cleaned)) "family:mbc"
        else "channel:" + cleaned.lowercase(Locale.ROOT)
    }

    /** MBC 1, MBC 2, MBC Action etc. are ONE family; each keeps its own HD/SD versions. */
    fun display(key: String, firstName: String): String =
        if (key == "family:mbc") "MBC" else clean(firstName)

    fun variants(channels: List<RemoteChannel>): List<Pair<String, List<RemoteChannel>>> =
        channels.groupBy { clean(it.name).lowercase(Locale.ROOT) }
            .values.map { group -> clean(group.first().name) to group.sortedBy { it.position } }
            .sortedBy { group -> group.second.minOf { it.position } }

    fun byGroup(channels: List<RemoteChannel>): Map<String, List<RemoteChannel>> =
        channels.groupBy { key(it.name) }
}

/**
 * User-defined ten-slot remote layout, persisted per receiver.
 * The user can remove, rename, reorder and choose a preferred service/variant.
 * A removed default does not reappear after a channel-list refresh.
 */
internal class FavoriteProfile(context: Context) {
    private val prefs = context.getSharedPreferences("satnova_remote_favorites", Context.MODE_PRIVATE)
    private var profile = ""
    private var slots = mutableListOf<FavoriteSlot>()
    private var initialized = false

    fun useReceiver(host: String) {
        val next = host.trim().ifEmpty { "unassigned" }
        if (profile == next) return
        profile = next
        val raw = prefs.getString("slots:$profile", null)
        initialized = raw != null
        slots = mutableListOf()
        if (raw != null) try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val key = row.optString("key")
                if (key.isNotBlank() && slots.none { it.key == key }) {
                    slots.add(FavoriteSlot(key, row.optString("label"), row.optString("preferred")))
                }
                if (slots.size == 10) break
            }
        } catch (_: Exception) { initialized = false }
    }

    fun sync(channels: List<RemoteChannel>) {
        if (initialized || channels.isEmpty()) return
        val buckets = ChannelGrouping.byGroup(channels)
        val keys = LinkedHashSet<String>()
        // Prefer user's favorites from the receiver where available, otherwise its normal ordering.
        channels.filter { it.receiverFavorite }.plus(channels).forEach {
            val key = ChannelGrouping.key(it.name)
            if (key in buckets) keys.add(key)
        }
        slots = keys.take(10).map { FavoriteSlot(it) }.toMutableList()
        initialized = true
        save()
    }

    fun list(): List<FavoriteSlot> = slots.toList()

    fun add(key: String): Boolean {
        if (slots.size >= 10 || slots.any { it.key == key }) return false
        slots.add(FavoriteSlot(key)); save(); return true
    }

    fun remove(key: String) { slots.removeAll { it.key == key }; save() }

    fun rename(key: String, label: String) {
        slots.replaceAll { if (it.key == key) it.copy(label = label.trim().take(36)) else it }; save()
    }

    fun choosePreferred(key: String, channelId: String) {
        slots.replaceAll { if (it.key == key) it.copy(preferredId = channelId) else it }; save()
    }

    fun move(key: String, delta: Int) {
        val source = slots.indexOfFirst { it.key == key }
        val dest = source + delta
        if (source < 0 || dest !in slots.indices) return
        val value = slots.removeAt(source)
        slots.add(dest, value); save()
    }

    private fun save() {
        if (profile.isBlank()) return
        val array = JSONArray()
        slots.forEach { array.put(JSONObject().put("key", it.key).put("label", it.label)
            .put("preferred", it.preferredId)) }
        prefs.edit().putString("slots:$profile", array.toString()).apply()
    }
}
