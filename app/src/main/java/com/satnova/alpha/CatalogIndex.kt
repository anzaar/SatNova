package com.satnova.alpha

import org.json.JSONObject
import java.util.Locale

/** Immutable associations built on a worker, never by scanning TP lists during UI layout. */
class CatalogIndex(channels:List<JSONObject>,satellites:List<JSONObject>,tps:List<JSONObject>) {
    data class Channel(val id:String,val name:String,val satellite:String?,val tp:String?,
        val frequency:String?,val polarization:String?,val symbolRate:String?,
        val radio:Boolean,val favorite:Boolean,val epg:String?,val next:String?,val record:JSONObject) {
        val search=(name+" "+(satellite?:"")).lowercase(Locale.ROOT)
    }
    data class Satellite(val key:String,val name:String,val record:JSONObject)
    data class Transponder(val key:String,val satellite:String?,val label:String,val record:JSONObject)
    companion object {
        fun value(row:JSONObject,vararg names:String):String?=names.firstNotNullOfOrNull{
            row.optString(it,"").trim().takeIf{v->v.isNotEmpty() && v!="null"}
        }
        fun number(v:String?):String?=v?.let{it.toLongOrNull()?.toString()?:it}
        fun tpId(row:JSONObject):String?=number(value(row,"TPIndex","TpIndex","TPId","TPID","TransponderId"))
            ?:value(row,"ServiceID","ProgramId","ProgramID")?.takeIf{it.length>=9}?.substring(4,9)?.let{number(it)}
        fun satId(row:JSONObject):String?=number(value(row,"SatIndex","SatelliteIndex","SatelliteId","SatelliteID","SatID","SatId"))
        fun satName(row:JSONObject)=value(row,"SatelliteName","SatName","Satellite")
        fun freq(row:JSONObject)=value(row,"Frequency","Freq","TPFreq","FrequencyMHz","TPFrequency")
        fun pol(row:JSONObject):String?=when(val p=value(row,"Polarization","Pol","POL","Polarity")){"0"->"H";"1"->"V";else->p}
        fun sr(row:JSONObject)=value(row,"SymbolRate","SR","Symbol","SymRate")
        private fun normalized(v:String)=v.trim().lowercase(Locale.ROOT)
    }
    val satelliteRows=satellites.mapIndexed{i,r->Satellite(satId(r)?:"name:"+normalized(satName(r)?:value(r,"Name")?:"$i"),
        satName(r)?:value(r,"Name")?:"Satellite",r)}
    private val satellitesByName=satelliteRows.associateBy{normalized(it.name)}
    private fun satelliteKey(r:JSONObject):String?=satId(r)?:satName(r)?.let{satellitesByName[normalized(it)]?.key?:"name:"+normalized(it)}
    val transponders=tps.mapIndexed{i,r->
        Transponder(tpId(r)?:"record:$i",satelliteKey(r),
            listOfNotNull(freq(r)?.let{"$it MHz"},pol(r),sr(r)?.let{"SR $it"}).joinToString(" · ").ifBlank{"Fréquence non reçue"},r)
    }
    // Only unambiguous TP indexes may supply a channel's satellite.
    private val uniqueTp=transponders.groupBy{it.key}.filterValues{it.size==1}.mapValues{it.value.first()}
    val channelRows=channels.mapNotNull{r->
        val id=value(r,"ServiceID","ProgramId","ProgramID")?:return@mapNotNull null
        val tp=tpId(r)
        val tr=uniqueTp[tp]
        val sat=satelliteKey(r)?:tr?.satellite
        val fav=value(r,"Fav","Favor","FavFlag","Favorite","FavoriteFlag","Favourite","IsFavourite","IsFavorite")
        Channel(id,value(r,"ServiceName","Name","ChannelName","ProgramName")?:"Chaîne $id",sat,tp,
            freq(r)?:tr?.record?.let{freq(it)},pol(r)?:tr?.record?.let{pol(it)},sr(r)?:tr?.record?.let{sr(it)},
            value(r,"Radio","TvState")=="1",fav=="1"||fav.equals("true",true),
            value(r,"EventName","EventTitle","CurrentEvent","NowPlaying","EpgTitle","EPGName"),
            value(r,"NextEvent","NextEventTitle","NextProgramme"),r)
    }
    val tpsBySatellite=transponders.filter{it.satellite!=null}.groupBy{it.satellite!!}
    val channelsBySatellite=channelRows.filter{it.satellite!=null}.groupBy{it.satellite!!}
    val channelsByTp=channelRows.filter{it.satellite!=null && it.tp!=null}.groupBy{it.satellite!! to it.tp!!}
}
