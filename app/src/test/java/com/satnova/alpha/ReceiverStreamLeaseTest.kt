package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class ReceiverStreamLeaseTest {
    @Test fun stopOnlyBelongsToAnExplicitlyOwnedRtspSetup(){
        val lease=ReceiverStreamLease()
        assertFalse(lease.takeStop())
        lease.opened();assertTrue(lease.takeStop());assertFalse(lease.takeStop())
        lease.opened();lease.reset();assertFalse(lease.takeStop())
        assertEquals(1012,ReceiverStreamLease.STOP)
    }
    @Test fun replyFromStoppedReaderCannotRevivePlaybackOnTheSameChannel(){
        val gate=LiveStreamGate();gate.desire("A")
        val old=gate.begin()!!
        gate.invalidatePlayback()
        assertFalse(gate.complete(old))
        assertFalse(gate.isActive(old))
        val fresh=gate.begin()!!
        assertTrue(gate.complete(fresh))
    }
}
