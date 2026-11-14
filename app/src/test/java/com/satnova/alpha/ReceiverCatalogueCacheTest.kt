package com.satnova.alpha

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiverCatalogueCacheTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun login(serial:Int,ip:Int=2):ByteArray {
        val raw=ByteArray(108)
        "39WwijOog54a".toByteArray().copyInto(raw)
        raw[12]=serial.toByte();raw[68]=ip.toByte()
        "RECEIVER".toByteArray().copyInto(raw,20)
        return raw.map{(it.toInt() xor 0x5B).toByte()}.reversed().toByteArray()
    }
    private fun row(id:String)=JSONObject().put("ServiceID",id).put("ServiceName","Channel $id")
    @Test fun savedPartialCatalogueRestoresOrderMetadataAndNextPageAfterNewInstance(){
        val key=ReceiverCatalogueCache.identity(login(1))!!
        val directory=temporary.newFolder()
        ReceiverCatalogueCache(directory).save(key,ReceiverCatalogueCache.Snapshot(300,100,
            listOf(row("B"),row("A")),listOf(JSONObject().put("Name","Badr")),listOf(JSONObject().put("Frequency",11547))))
        val reopened=ReceiverCatalogueCache(directory).load(key)!!
        assertEquals(300,reopened.total);assertEquals(100,reopened.next)
        assertEquals(listOf("B","A"),reopened.channels.map{it.getString("ServiceID")})
        assertEquals("Badr",reopened.satellites.single().getString("Name"))
        assertEquals(11547,reopened.transponders.single().getInt("Frequency"))
    }
    @Test fun changedIpKeepsCacheButDifferentReceiverAtSameIpDoesNot(){
        val original=ReceiverCatalogueCache.identity(login(1,2))!!
        assertEquals(original,ReceiverCatalogueCache.identity(login(1,50)))
        val other=ReceiverCatalogueCache.identity(login(2,2))!!
        assertNotEquals(original,other)
        val cache=ReceiverCatalogueCache(temporary.newFolder())
        cache.save(original,ReceiverCatalogueCache.Snapshot(1,1,listOf(row("A")),emptyList(),emptyList()))
        assertNotNull(cache.load(original));assertNull(cache.load(other))
    }
    @Test fun malformedCacheIsIgnoredAndValidSnapshotCanReplaceIt(){
        val key=ReceiverCatalogueCache.identity(login(1))!!
        val directory=temporary.newFolder();val cache=ReceiverCatalogueCache(directory)
        File(directory,"$key.json").writeText("truncated {")
        assertNull(cache.load(key))
        cache.save(key,ReceiverCatalogueCache.Snapshot(1,1,listOf(row("A")),emptyList(),emptyList()))
        assertEquals(1,cache.load(key)!!.next)
    }
    @Test fun unknownHardwareIdentityCannotBorrowAnotherReceiversCatalogue(){
        assertNull(ReceiverCatalogueCache.identity(login(0)))
        assertNull(ReceiverCatalogueCache.identity(ByteArray(107)))
    }
}
