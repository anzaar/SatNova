package com.satnova.alpha

import java.util.Locale

object CatalogQuery {
    /** Filtering never changes the receiver's channel order. */
    fun channels(index:CatalogIndex,mode:Int=0,satellite:String?=null,tp:String?=null,term:String=""):List<CatalogIndex.Channel>{
        val query=term.trim().lowercase(Locale.ROOT)
        return index.channelRows.filter{c->
            (when(mode){1->!c.radio;2->c.radio;3->c.favorite;else->true}) &&
            (satellite==null || c.satellite==satellite) &&
            (tp==null || c.tp==tp) &&
            (query.isBlank() || c.search.contains(query))
        }
    }
    fun satellites(index:CatalogIndex):List<CatalogIndex.Satellite> =
        index.satelliteRows.sortedByDescending{index.channelsBySatellite[it.key]?.size?:0}
}
