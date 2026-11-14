package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class PlaybackHealthTest {
    @Test fun frozenPictureIsDetectedEvenWhileAudioAndDecodingContinue(){
        val h=PlaybackHealth(0)
        assertEquals(PlaybackHealth.Result.PROGRESS,h.sample(1000,30,20,20))
        assertEquals(PlaybackHealth.Result.WAITING,h.sample(8999,240,20,240))
        assertEquals(PlaybackHealth.Result.STALLED,h.sample(9000,270,20,270))
        assertEquals(PlaybackHealth.Result.WAITING,h.sample(20000,300,20,300))
    }
    @Test fun startupAllowsBufferingButNotInfiniteZeroOutput(){
        val h=PlaybackHealth(0)
        assertEquals(PlaybackHealth.Result.WAITING,h.sample(14999,0,0,0))
        assertEquals(PlaybackHealth.Result.STALLED,h.sample(15000,0,0,0))
    }
    @Test fun radioAndStaticImagesWithAdvancingFramesRemainHealthy(){
        val radio=PlaybackHealth(0)
        val tv=PlaybackHealth(0)
        for(i in 1L..40L){
            assertEquals(PlaybackHealth.Result.PROGRESS,radio.sample(i*1000,0,0,i*20))
            assertEquals(PlaybackHealth.Result.PROGRESS,tv.sample(i*1000,i*25,i*25,0))
        }
    }
    @Test fun freshAttemptDoesNotInheritPreviousCounters(){
        val old=PlaybackHealth(0);old.sample(1000,100,100,100)
        assertEquals(PlaybackHealth.Result.PROGRESS,PlaybackHealth(5000).sample(6000,1,1,1))
    }
}
