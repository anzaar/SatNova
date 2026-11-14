package com.satnova.alpha

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogIndexTest {
    @Test fun connectsPaddedServiceTpToSatelliteAndIndexesItsChannels(){
        val ch=JSONObject("""{"ServiceID":"00020131508703","ServiceName":"Programme réel","Radio":"0"}""")
        val tp=JSONObject("""{"TPIndex":"1315","SatIndex":"2","Freq":"12345","POL":"1","SR":"27500"}""")
        val sat=JSONObject("""{"SatIndex":"0002","SatName":"Satellite réel"}""")
        val index=CatalogIndex(listOf(ch),listOf(sat),listOf(tp))
        val channel=index.channelRows.single()
        assertEquals("Programme réel",channel.name)
        assertEquals("2",channel.satellite)
        assertEquals("1315",channel.tp)
        assertEquals("12345",channel.frequency)
        assertEquals("V",channel.polarization)
        assertEquals(1,index.channelsByTp["2" to "1315"]!!.size)
    }
    @Test fun doesNotGuessSatelliteWhenTpNumberIsAmbiguous(){
        val ch=JSONObject("""{"ServiceID":"00020131508703","ServiceName":"Test"}""")
        val tps=listOf(1,2).map{JSONObject().put("TPIndex",1315).put("SatIndex",it)}
        val index=CatalogIndex(listOf(ch),emptyList(),tps)
        assertNull(index.channelRows.single().satellite)
        assertTrue(index.channelsBySatellite.isEmpty())
    }
    @Test fun indexesLargeCatalogWithoutRepeatingTpSearchesForEachSatellite(){
        val sats=(0 until 80).map{JSONObject().put("SatIndex",it).put("SatName","S$it")}
        val tps=(0 until 8000).map{JSONObject().put("TPIndex",it).put("SatIndex",it%80)}
        val channels=(0 until 12000).map{JSONObject().put("ProgramId","C$it").put("TPIndex",it%8000).put("ServiceName","C$it")}
        val index=CatalogIndex(channels,sats,tps)
        assertEquals(12000,index.channelsBySatellite.values.sumOf{it.size})
        assertEquals(8000,index.tpsBySatellite.values.sumOf{it.size})
        assertEquals(80,index.satelliteRows.size)
    }
}
