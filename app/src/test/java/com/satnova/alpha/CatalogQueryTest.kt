package com.satnova.alpha

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogQueryTest {
    private fun data():CatalogIndex {
        val sats=listOf(JSONObject().put("SatIndex",1).put("SatName","Nilesat"),
            JSONObject().put("SatIndex",2).put("SatName","Badr"))
        val channels=listOf(
            JSONObject().put("ProgramId","C1").put("ServiceName","Zulu").put("SatIndex",2).put("TPIndex",10),
            JSONObject().put("ProgramId","C2").put("ServiceName","Alpha").put("SatIndex",1).put("TPIndex",20),
            JSONObject().put("ProgramId","C3").put("ServiceName","Bravo").put("SatIndex",2).put("TPIndex",11),
            JSONObject().put("ProgramId","C4").put("ServiceName","Radio").put("SatIndex",2).put("TPIndex",10).put("Radio",1))
        return CatalogIndex(channels,sats,emptyList())
    }
    @Test fun ranksMostPopulatedSatelliteFirst(){
        assertEquals(listOf("Badr","Nilesat"),CatalogQuery.satellites(data()).map{it.name})
    }
    @Test fun preservesReceiverOrderAcrossSatelliteAndTpFilters(){
        val filtered=CatalogQuery.channels(data(),satellite="2",tp="10")
        assertEquals(listOf("Zulu","Radio"),filtered.map{it.name})
        assertEquals(listOf(1,4),filtered.map{it.position})
        assertEquals(listOf("Zulu","Bravo"),CatalogQuery.channels(data(),mode=1,satellite="2").map{it.name})
    }
    @Test fun allChannelsRestoresOriginalOrderAndSearchDoesNotSort(){
        assertEquals(listOf("C1","C2","C3","C4"),CatalogQuery.channels(data()).map{it.id})
        assertEquals(listOf("C2","C3","C4"),CatalogQuery.channels(data(),term="a").map{it.id})
    }
}
