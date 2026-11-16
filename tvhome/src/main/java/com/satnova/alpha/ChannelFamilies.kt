package com.satnova.alpha

import android.content.SharedPreferences
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Local presentation-only channel families, never modifying Zen 4 ordering or favourites. */
internal class ChannelFamilies(private val prefs: SharedPreferences) {
    data class Tile(val key: String?, val label: String, val members: List<ZenChannel>) {
        val first: ZenChannel get() = members.first()
        val isGroup: Boolean get() = key != null && (members.size > 1 || key.startsWith("custom:"))
    }
    private val defaults = linkedMapOf(
        "auto:alaoula" to "Al Aoula", "auto:2m" to "2M",
        "auto:arryadia" to "Arryadia", "auto:beinsports" to "beIN SPORTS",
        "auto:natgeo" to "National Geographic", "auto:aljazeera" to "Al Jazeera",
        "auto:mbc" to "MBC", "auto:alkass" to "Alkass"
    )
    private val overrideKey = "channel_group_assignments_v1"
    private val labelsKey = "channel_group_labels_v1"
    private val solo = "solo"

    private fun assignments(): JSONObject = try { JSONObject(prefs.getString(overrideKey, "{}") ?: "{}") }
        catch (_: Exception) { JSONObject() }
    private fun labels(): JSONObject = try { JSONObject(prefs.getString(labelsKey, "{}") ?: "{}") }
        catch (_: Exception) { JSONObject() }

    fun nameOf(key: String): String {
        if (key.startsWith("duplicate:"))
            return key.removePrefix("duplicate:").replaceFirstChar { it.titlecase(Locale.ROOT) }
        return defaults[key] ?: labels().optString(key, key.removePrefix("custom:"))
    }

    private fun normalized(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    private fun familyOf(c: ZenChannel): String? {
        val name = normalized(c.name)
        return when {
            Regex("^(?:al aoula|alaoula|aloula|al oula|الاولى)(?:\\b|$)").containsMatchIn(name) -> "auto:alaoula"
            Regex("^2m(?:\\b|$)").containsMatchIn(name) -> "auto:2m"
            Regex("^(?:arryadia|arriadia|aryadia|الرياضية)(?:\\b|$)").containsMatchIn(name) -> "auto:arryadia"
            Regex("^bein\\s*sports?(?:\\b|$)").containsMatchIn(name) -> "auto:beinsports"
            Regex("^(?:nat\\s*geo|national\\s*geographic)(?:\\b|$)").containsMatchIn(name) -> "auto:natgeo"
            Regex("^(?:al\\s*jazeera|aljazeera|الجزيرة)(?:\\b|$)").containsMatchIn(name) -> "auto:aljazeera"
            Regex("^mbc\\s*\\d|^mbc\\s*(?:action|drama|max|bollywood|plus)\\b").containsMatchIn(name) -> "auto:mbc"
            Regex("^al\\s*kass\\b|^alkass\\b").containsMatchIn(name) -> "auto:alkass"
            else -> null
        }
    }

    fun keyFor(c: ZenChannel): String? = when (val assigned = assignments().optString(c.id, "")) {
        solo -> null
        "" -> familyOf(c)
        else -> assigned
    }

    /**
     * Works for ALL programmes, not only names in the built-in family list.
     * Only format markers are removed: beIN SPORTS 1 and 2 remain different
     * subchannels within their known family; unrelated base names never merge.
     */
    private fun duplicateBase(name: String): String {
        val base = normalized(name)
            .replace(Regex("\\b(?:hd|sd|uhd|4k|fhd|fullhd|h265|hevc|mpeg4|tnt)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return base.takeIf { it.length >= 3 && it !in setOf("tv", "test", "radio", "channel") } ?: ""
    }

    fun tiles(channels: List<ZenChannel>): List<Tile> {
        val sorted = channels.filter { !it.radio }.sortedBy { it.position }
        val assignments = assignments()
        val bases = sorted.groupingBy { duplicateBase(it.name) }.eachCount()
        fun key(c: ZenChannel): String? {
            return when (val assigned = assignments.optString(c.id, "")) {
                solo -> null
                "" -> {
                    val predefined = familyOf(c)
                    if (predefined != null) predefined
                    else {
                        val base = duplicateBase(c.name)
                        if (base.isNotEmpty() && (bases[base] ?: 0) >= 2)
                            "duplicate:$base"
                        else null
                    }
                }
                else -> assigned
            }
        }
        val grouped = sorted.groupBy(::key)
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Tile>()
        sorted.forEach { c ->
            val k = key(c)
            if (k == null) result.add(Tile(null, c.name, listOf(c)))
            else if (seen.add(k)) {
                val members = grouped[k].orEmpty()
                if (members.size == 1 && !k.startsWith("custom:"))
                    result.add(Tile(null, c.name, listOf(c)))
                else result.add(Tile(k, nameOf(k), members))
            }
        }
        return result
    }

    fun keys(channels: List<ZenChannel>): List<Pair<String, String>> {
        val present = channels.mapNotNull(::keyFor).toSet()
        val active = tiles(channels).mapNotNull(Tile::key)
        val custom = labels().keys().asSequence().toList()
        return (defaults.keys + custom + active).distinct()
            .filter { it in present || it in active || it.startsWith("custom:") }
            .map { it to nameOf(it) }.sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    fun create(label: String): String? {
        val value = label.trim().take(40)
        if (value.isBlank() || value.contains('\n')) return null
        val list = labels()
        val key = "custom:" + UUID.randomUUID().toString()
        list.put(key, value)
        prefs.edit().putString(labelsKey, list.toString()).apply()
        return key
    }

    fun assign(id: String, group: String?) {
        val data = assignments()
        if (group == null) data.remove(id) else data.put(id, group)
        prefs.edit().putString(overrideKey, data.toString()).apply()
    }

    fun removeFromGroup(id: String) = assign(id, solo)

    fun ungroup(tile: Tile) {
        tile.members.forEach { removeFromGroup(it.id) }
        if (tile.key?.startsWith("custom:") == true) {
            val data = labels()
            data.remove(tile.key)
            prefs.edit().putString(labelsKey, data.toString()).apply()
        }
    }

    fun resetAll() { prefs.edit().remove(overrideKey).remove(labelsKey).apply() }
}

internal object ChannelDescription {
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun tnt(c: ZenChannel): Boolean {
        if (c.frequencyMhz != 11180 || c.radio) return false
        val name = norm(c.name)
        val moroccan = listOf("al aoula", "alaoula", "aloula", "al oula",
            "2m", "arryadia", "arriadia", "aryadia", "athaqafia", "atthaqafia",
            "assadissa", "aflam", "tamazight", "al maghribia", "medi1", "medi 1",
            "laayoune", "الاولى", "الرياضية", "الثقافية")
        return moroccan.any { prefix -> name == prefix || name.startsWith("$prefix ") }
    }

    fun summary(c: ZenChannel): String {
        val tags = mutableListOf<String>()
        if (!c.radio) c.definition?.let(tags::add)
        when (c.encrypted) {
            true -> tags.add("Cryptée")
            false -> tags.add("Libre")
            null -> {}
        }
        if (tnt(c)) tags.add("TNT")
        return tags.ifEmpty { listOf(if (c.radio) "Radio" else "Chaîne") }.joinToString(" · ")
    }

    fun full(c: ZenChannel): String = buildString {
        append("N° ${c.position} · ${summary(c)}")
        c.frequencyMhz?.let { append(" · $it MHz") }
    }
}
