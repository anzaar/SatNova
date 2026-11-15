package com.satnova.alpha

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** ID comes from Zen 4, never from list position or a guessed service number. */
internal data class RemoteChannel(
    val id: String, val name: String, val position: Int, val tvState: Int,
    val receiverFavorite: Boolean, val satellite: String?, val frequency: String?
)

internal data class FavoriteSlot(val key: String, val label: String = "", val preferredId: String = "")

internal object ChannelGrouping {
    private val quality = Regex("(?i)(?:\\s|[-_.()])+((?:UHD|FHD|FULL\\s*HD|HD|SD|4K|HEVC|H265|BACKUP|BKP|FEED|LOW|HIGH)(?:\\s|[-_.()]*)?)+$")
    private val whitespace = Regex("[\\s._-]+")
    private val mbc = Regex("(?i)^MBC(?:\\b|[0-9])")
    private val arr = Regex("(?i)^(?:arriyadia|arryadia|arradia|الرياضية)(?:\\b|[0-9]|\\s|$)")
    private val beinsport = Regex("(?i)^(?:be[\\s._-]*in|bein)[\\s._-]*sports?(?:\\b|[0-9])")
    private val bein = Regex("(?i)^(?:be[\\s._-]*in|bein)(?:\\b|[0-9])")
    private val aljazeera = Regex("(?i)^(?:al[\\s._-]*jazeera|aljazeera|الجزيرة)(?:\\b|[0-9]|\\s|$)")
    private val ssc = Regex("(?i)^ssc(?:\\b|[0-9])")

    fun clean(name: String): String {
        var result = name.trim()
        repeat(3) { result = result.replace(quality, "").trim().trimEnd('-','_','.', ' ') }
        return result.replace(Regex("(?i)^MBC\\s*([0-9]+)"), "MBC $1")
            .replace(whitespace, " ").trim()
    }

    /** Automatic families are user-adjustable via manual groups; similar names are only suggestions. */
    fun key(name: String): String {
        val n = clean(name)
        return when {
            mbc.containsMatchIn(n) -> "family:mbc"
            arr.containsMatchIn(n) -> "family:arryadia"
            beinsport.containsMatchIn(n) -> "family:bein-sports"
            bein.containsMatchIn(n) -> "family:bein-other"
            aljazeera.containsMatchIn(n) -> "family:aljazeera"
            ssc.containsMatchIn(n) -> "family:ssc"
            else -> "channel:" + n.lowercase(Locale.ROOT)
        }
    }

    /** Radio is a different namespace: it can never join a television favorite accidentally. */
    fun key(channel: RemoteChannel): String =
        (if (channel.tvState == 1) "radio:" else "") + key(channel.name)

    fun display(key: String, firstName: String): String = when(key.removePrefix("radio:")) {
        "family:mbc" -> "MBC"
        "family:arryadia" -> "Arryadia"
        "family:bein-sports" -> "beIN Sports"
        "family:bein-other" -> "beIN • Autres"
        "family:aljazeera" -> "Al Jazeera"
        "family:ssc" -> "SSC"
        else -> clean(firstName)
    }

    /** A family contains channels; within each channel there may be HD/SD/backup variants. */
    fun variants(channels: List<RemoteChannel>): List<Pair<String, List<RemoteChannel>>> =
        channels.groupBy { clean(it.name).lowercase(Locale.ROOT) }
            .values.map { group -> clean(group.first().name) to group.sortedBy { it.position } }
            .sortedBy { group -> group.second.minOf { it.position } }

    fun byGroup(channels: List<RemoteChannel>): Map<String, List<RemoteChannel>> =
        channels.groupBy { key(it) }
}

/**
 * Up to 10 favorite groups per receiver AND per type (TV vs radio).
 * Preserves old TV favorites saved under slots:<host>.
 * Custom groups persist an explicit set of ProgramIds, not fragile name matches.
 */
internal class FavoriteProfile(context: Context) {
    private val prefs=context.getSharedPreferences("satnova_remote_favorites",Context.MODE_PRIVATE)
    private var profile=""
    private var slots=mutableListOf<FavoriteSlot>()
    private var initialized=false

    fun useReceiver(host:String,radio:Boolean=false) {
        val base=host.trim().ifEmpty{"unassigned"}
        val next=base + (if(radio)":radio" else ":tv")
        if(profile==next)return
        profile=next
        val raw=prefs.getString("slots:$next",null)
            ?: if(!radio)prefs.getString("slots:$base",null) else null
        initialized=raw!=null
        slots=mutableListOf()
        if(raw!=null)try {
            val array=JSONArray(raw)
            for(i in 0 until array.length()) {
                val obj=array.optJSONObject(i)?:continue
                val key=obj.optString("key","")
                if(key.isNotBlank() && slots.none{it.key==key})
                    slots.add(FavoriteSlot(key,obj.optString("label",""),obj.optString("preferred","")))
                if(slots.size>=10)break
            }
        }catch(_:Exception){initialized=false;slots.clear()}
        if(raw!=null)save() // Save migrated TV profile on its new key.
    }

    /** Reflect favorite edits made in the other (offline/online) remote surface. */
    fun reload() {
        if(profile.isBlank())return
        val radio=profile.endsWith(":radio")
        val host=profile.removeSuffix(":radio").removeSuffix(":tv")
        profile=""
        useReceiver(host,radio)
    }

    fun sync(channels:List<RemoteChannel>) {
        if(initialized || channels.isEmpty())return
        val radio=profile.endsWith(":radio")
        val relevant=channels.filter{(it.tvState==1)==radio}
        if(relevant.isEmpty())return
        val keys=LinkedHashSet<String>()
        (relevant.filter{it.receiverFavorite}+relevant).forEach{keys.add(ChannelGrouping.key(it))}
        slots=keys.take(10).map{FavoriteSlot(it)}.toMutableList()
        initialized=true;save()
    }

    fun list():List<FavoriteSlot> = slots.toList()

    fun add(key:String):Boolean {
        if(slots.size>=10 || slots.any{it.key==key})return false
        slots.add(FavoriteSlot(key));save();return true
    }

    fun createCustom(label:String):String? {
        val title=label.trim().take(36)
        if(title.isEmpty() || slots.size>=10)return null
        val key="custom:"+UUID.randomUUID().toString()
        slots.add(FavoriteSlot(key,title));save()
        return key
    }

    fun members(key:String):Set<String> {
        if(!key.startsWith("custom:") || profile.isBlank())return emptySet()
        val data=prefs.getString("members:$profile:$key","[]")?:return emptySet()
        return try {
            val array=JSONArray(data)
            (0 until array.length()).mapNotNull{array.optString(it).takeIf(String::isNotBlank)}.toSet()
        }catch(_:Exception){emptySet()}
    }

    fun addMember(key:String,id:String):Boolean {
        if(!key.startsWith("custom:") || id.isBlank() || slots.none{it.key==key})return false
        val selected=members(key).toMutableSet()
        if(!selected.add(id))return false
        saveMembers(key,selected);return true
    }

    fun removeMember(key:String,id:String) {
        val selected=members(key).toMutableSet()
        if(selected.remove(id))saveMembers(key,selected)
    }

    private fun saveMembers(key:String,ids:Set<String>) {
        val arr=JSONArray()
        ids.forEach{arr.put(it)}
        prefs.edit().putString("members:$profile:$key",arr.toString()).apply()
    }

    fun remove(key:String) {
        slots.removeAll{it.key==key}
        if(key.startsWith("custom:"))prefs.edit().remove("members:$profile:$key").apply()
        save()
    }
    fun rename(key:String,label:String) {
        slots.replaceAll{if(it.key==key)it.copy(label=label.trim().take(36)) else it}
        save()
    }
    fun choosePreferred(key:String,id:String) {
        slots.replaceAll{if(it.key==key)it.copy(preferredId=id) else it}
        save()
    }
    fun move(key:String,delta:Int) {
        val from=slots.indexOfFirst{it.key==key}
        val to=from+delta
        if(from<0 || to !in slots.indices)return
        slots.add(to,slots.removeAt(from));save()
    }
    private fun save() {
        if(profile.isBlank())return
        val arr=JSONArray()
        slots.forEach{arr.put(JSONObject().put("key",it.key).put("label",it.label)
            .put("preferred",it.preferredId))}
        prefs.edit().putString("slots:$profile",arr.toString()).apply()
    }
}
