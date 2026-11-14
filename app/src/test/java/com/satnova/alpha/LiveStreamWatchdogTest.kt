package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class LiveStreamWatchdogTest {
    @Test fun prolongedZeroBufferingProducesOneRecoveryInsteadOfWaitingForever(){
        val timer=LiveStreamWatchdog()
        timer.buffering(true,0)
        timer.buffering(true,8000)
        assertFalse(timer.takeStall(14999))
        assertTrue(timer.takeStall(15000))
        assertFalse(timer.takeStall(30000))
    }
    @Test fun successfulPlaybackAndSessionResetCancelPendingRecovery(){
        val timer=LiveStreamWatchdog()
        timer.buffering(true,1000);timer.buffering(false,2000)
        assertFalse(timer.takeStall(20000))
        timer.buffering(true,21000);timer.reset()
        assertFalse(timer.takeStall(50000))
    }
}
