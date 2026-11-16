package com.satnova.alpha

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manually editable viewing groups. Overrides live only in SatNova Home:
 * no G-MScreen command is used to change receiver favourites or channel lists.
 */
internal class ChannelGroupEditor(private val prefs: SharedPreferences) {
    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length())
            .mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.distinct()

    private fun record(): JSONObject = try {
        JSONObject(prefs.getString("tv_group_overrides", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun custom(): List<String> = try {
        strings(JSONArray(prefs.getString("tv_custom_groups", "[]") ?: "[]"))
    } catch (_: Exception) { emptyList() }

    private fun hidden(): Set<String> = try {
        strings(JSONArray(prefs.getString("tv_hidden_groups", "[]") ?: "[]")).toSet()
    } catch (_: Exception) { emptySet() }

    fun disabled(): List<String> = hidden().filter { it in ChannelOrganization.knownFamilies() }

    fun names(channels: List<ZenChannel>): List<String> {
        val present = channels.mapNotNull(ChannelOrganization::family).distinct()
        val all = (ChannelOrganization.knownFamilies().filter { it in present } + custom()).distinct()
        return all.filterNot { it in hidden() }
    }

    fun members(group: String, channels: List<ZenChannel>): List<ZenChannel> {
        val o = record().optJSONObject(group)
        val added = strings(o?.optJSONArray("include")).toSet()
        val removed = strings(o?.optJSONArray("exclude")).toSet()
        return channels.filter { !it.radio && it.id !in removed &&
            (ChannelOrganization.family(it) == group || it.id in added) }
    }

    /** For the TV row, each channel has only one top-level tile. */
    fun primaryGroup(channel: ZenChannel, activeGroups: List<String>, allTv: List<ZenChannel>): String? {
        val auto = ChannelOrganization.family(channel)
        if (auto != null && auto in activeGroups &&
            members(auto, allTv).any { it.id == channel.id }) return auto
        return activeGroups.firstOrNull { group ->
            members(group, allTv).any { it.id == channel.id }
        }
    }

    fun update(group: String, selectedIds: Set<String>, allTv: List<ZenChannel>) {
        val suggested = allTv.filter { ChannelOrganization.family(it) == group }
            .map(ZenChannel::id).toSet()
        val available = allTv.map(ZenChannel::id).toSet()
        val selected = selectedIds.intersect(available)
        val added = selected - suggested
        val removed = suggested - selected
        val root = record()
        root.put(group, JSONObject()
            .put("include", JSONArray(added.toList()))
            .put("exclude", JSONArray(removed.toList())))
        prefs.edit().putString("tv_group_overrides", root.toString()).apply()
        setHidden(group, false)
    }

    fun create(title: String): Boolean {
        val name = title.trim().take(36)
        if (name.length < 2 || name.contains('\n') || name.contains('\r') ||
            name in (ChannelOrganization.knownFamilies() + custom())) return false
        prefs.edit().putString("tv_custom_groups",
            JSONArray(custom() + name).toString()).apply()
        setHidden(name, false)
        return true
    }

    fun deleteOrHide(group: String) {
        if (group in custom()) {
            prefs.edit().putString("tv_custom_groups",
                JSONArray(custom().filterNot { it == group }).toString()).apply()
            val root = record()
            root.remove(group)
            prefs.edit().putString("tv_group_overrides", root.toString()).apply()
        } else setHidden(group, true)
    }

    fun setHidden(group: String, enabled: Boolean) {
        val next = hidden().toMutableSet()
        if (enabled) next.add(group) else next.remove(group)
        prefs.edit().putString("tv_hidden_groups", JSONArray(next.toList()).toString()).apply()
    }
}
