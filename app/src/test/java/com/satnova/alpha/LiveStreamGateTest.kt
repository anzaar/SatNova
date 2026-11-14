package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class LiveStreamGateTest {
    @Test fun onlyOneUrlRequestCanBeOutstanding(){
        val gate=LiveStreamGate();gate.reset();gate.desire("A")
        val a=gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.complete(a))
        assertTrue(gate.isActive(a))
    }
    @Test fun lateOldChannelUrlIsDrainedAndNeverPlayed(){
        val gate=LiveStreamGate();gate.desire("A")
        val a=gate.begin()!!
        gate.desire("B")
        assertNull(gate.begin())
        assertFalse(gate.complete(a))
        val b=gate.begin()!!
        assertEquals("B",b.channel)
        assertTrue(gate.complete(b))
        assertFalse(gate.isActive(a))
    }
    @Test fun previousTimeoutCannotCancelNewRequest(){
        val gate=LiveStreamGate();gate.desire("A")
        val a=gate.begin()!!
        gate.desire("B");gate.complete(a)
        val b=gate.begin()!!
        assertFalse(gate.fail(a))
        assertEquals(b,gate.pending())
        assertTrue(gate.complete(b))
    }
    @Test fun reconnectRejectsOldSessionEvenWhenChannelIsUnchanged(){
        val gate=LiveStreamGate();gate.desire("A")
        val old=gate.begin()!!
        gate.reset();gate.desire("A")
        val fresh=gate.begin()!!
        assertFalse(gate.complete(old))
        assertEquals(fresh,gate.pending())
        assertTrue(gate.complete(fresh))
        assertFalse(gate.isActive(old))
    }
    @Test fun queuedPlaybackIsInvalidAfterChannelChangeOrBackground(){
        val gate=LiveStreamGate();gate.desire("A")
        val old=gate.begin()!!;gate.complete(old)
        gate.desire("B")
        assertFalse(gate.isActive(old))
        val b=gate.begin()!!;gate.complete(b)
        gate.reset()
        assertFalse(gate.isActive(b))
        assertNull(gate.begin())
    }
}
