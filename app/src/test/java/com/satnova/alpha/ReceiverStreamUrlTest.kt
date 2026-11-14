package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class ReceiverStreamUrlTest {
    @Test fun staleReceiverNicIsReplacedWithoutChangingPortChannelOrToken(){
        assertEquals("http://192.168.1.2:8080/live%20tv.ts?id=00020013500007&token=x%2By",
            ReceiverStreamUrl.resolve("http://192.168.49.1:8080/live%20tv.ts?id=00020013500007&token=x%2By","192.168.1.2"))
    }
    @Test fun sameHostAndRtspArePreserved(){
        val url="rtsp://192.168.1.2:554/live?freq=11547"
        assertEquals(url,ReceiverStreamUrl.resolve(url,"192.168.1.2"))
    }
    @Test fun remoteStopIsSentOncePerOwnedStream(){
        val lease=ReceiverStreamLease()
        assertFalse(lease.takeStop())
        lease.opened();assertTrue(lease.takeStop());assertFalse(lease.takeStop())
        lease.opened();lease.reset();assertFalse(lease.takeStop())
        assertEquals(1012,ReceiverStreamLease.STOP)
    }
    @Test fun pendingReplyFromStoppedReaderCannotRevivePlayback(){
        val gate=LiveStreamGate();gate.desire("A")
        val old=gate.begin()!!
        gate.invalidatePlayback()
        assertFalse(gate.complete(old))
        assertFalse(gate.isActive(old))
        val fresh=gate.begin()!!
        assertTrue(gate.complete(fresh))
    }
}
