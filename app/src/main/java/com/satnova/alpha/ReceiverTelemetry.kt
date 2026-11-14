package com.satnova.alpha

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Read-only G-MScreen SatFinder query: 403. Never substitute video/Wi-Fi metrics. */
object ReceiverTelemetry {
    const val SIGNAL_REQUEST=403
    data class Signal(val strength:Int?,val quality:Int?,val ber:String?)
    fun signal(text:String):Signal? {
        val root=try{JSONTokener(text).nextValue()}catch(_:Exception){return null}
        fun flatten(value:Any?,depth:Int=0):List<JSONObject> {
            if(depth>4)return emptyList()
            return when(value){
                is JSONArray -> (0 until value.length()).flatMap{flatten(value.opt(it),depth+1)}
                is JSONObject -> listOf(value)+value.keys().asSequence().toList().flatMap{
                    val child=value.opt(it)
                    if(child is JSONObject || child is JSONArray)flatten(child,depth+1) else emptyList()
                }
                else->emptyList()
            }
        }
        fun find(row:JSONObject,vararg names:String):String? {
            val keys=row.keys().asSequence().toList()
            return names.firstNotNullOfOrNull{n->keys.firstOrNull{it.equals(n,true)}?.let{
                row.optString(it,"").takeIf{v->v.isNotBlank() && v!="null"}
            }}
        }
        fun percentage(raw:String?):Int? {
            val v=raw?.trim()?.removeSuffix("%")?.trim()?.toDoubleOrNull()?:return null
            // Do not invent a conversion from receiver-specific scales or dB.
            return if(v.isFinite() && v in 0.0..100.0)v.toInt() else null
        }
        for(row in flatten(root)){
            val strength=percentage(find(row,"strength","SignalStrength","SignalLevel","Signal"))
            val quality=percentage(find(row,"quality","SignalQuality","QualityLevel"))
            if(strength!=null || quality!=null)return Signal(strength,quality,find(row,"BER"))
        }
        return null
    }
}
