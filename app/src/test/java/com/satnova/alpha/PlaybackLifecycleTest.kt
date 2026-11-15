package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class PlaybackLifecycleTest {
    @Test fun quickReturnWaitsForActualNativeTeardown(){
        val lifecycle=PlaybackLifecycle()
        val stop=lifecycle.background()
        assertFalse(lifecycle.foreground())
        assertFalse(lifecycle.ready())
        assertTrue(lifecycle.closed(stop))
        assertTrue(lifecycle.ready())
        assertFalse(lifecycle.closed(stop))
    }
    @Test fun normalReturnReopensOnlyInForeground(){
        val lifecycle=PlaybackLifecycle()
        val stop=lifecycle.background()
        assertFalse(lifecycle.closed(stop))
        assertTrue(lifecycle.foreground())
        assertFalse(lifecycle.foreground())
    }
    @Test fun oldStopCannotStartPlaybackAfterAnotherExit(){
        val lifecycle=PlaybackLifecycle()
        val first=lifecycle.background()
        lifecycle.foreground()
        val second=lifecycle.background()
        assertFalse(lifecycle.closed(first))
        assertFalse(lifecycle.foreground())
        assertTrue(lifecycle.closed(second))
    }
    @Test fun initialLaunchDoesNotReconnectTwice(){
        assertFalse(PlaybackLifecycle().foreground())
    }
}
