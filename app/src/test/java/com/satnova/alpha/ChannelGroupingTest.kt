package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class ChannelGroupingTest {
    private fun channel(id:String,name:String,position:Int) =
        RemoteChannel(id,name,position,0,false,null,null)

    @Test fun entireMbcFamilyIsExactlyOneFavoriteGroup() {
        val channels=listOf(
            channel("1","MBC 1 HD",1),
            channel("2","MBC 1 SD",2),
            channel("3","MBC 2 HD",3),
            channel("4","MBC 3",4),
            channel("5","MBC Action",5)
        )
        val group=ChannelGrouping.byGroup(channels)
        assertEquals(1,group.size)
        assertTrue(group.containsKey("family:mbc"))
        assertEquals(5,group.getValue("family:mbc").size)
        assertEquals(4,ChannelGrouping.variants(group.getValue("family:mbc")).size)
        assertEquals("MBC",ChannelGrouping.display("family:mbc","MBC 1"))
    }

    @Test fun unrelatedChannelsAndQualityVersionsDoNotMergeIncorrectly() {
        val group=ChannelGrouping.byGroup(listOf(
            channel("a","France 24 HD",1),
            channel("b","France 24 SD",2),
            channel("c","France 2 HD",3)
        ))
        assertEquals(2,group.size)
        assertEquals(2,group.getValue("channel:france 24").size)
        assertEquals(1,group.getValue("channel:france 2").size)
    }
}
