package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class ReceiverWorkModeTest {
    @Test fun startupLeavesSignalAndLargeCatalogueIdleUntilActualPlayback(){
        val mode=ReceiverWorkMode()
        assertTrue(mode.mayStartVideo())
        assertFalse(mode.mayReadSignal())
        assertFalse(mode.mayReadCatalogue(false))
        mode.videoStarted()
        assertTrue(mode.mayReadCatalogue(false))
        assertFalse(mode.mayReadCatalogue(true))
        assertFalse(mode.mayReadSignal())
    }
    @Test fun signalModeWaitsForHttpReaderToCloseAndCanReturnToVideo(){
        val mode=ReceiverWorkMode();mode.videoStarted()
        mode.measure(true)
        assertFalse(mode.mayStartVideo())
        assertFalse(mode.mayReadSignal())
        mode.readerClosed()
        assertTrue(mode.mayReadSignal())
        assertTrue(mode.mayReadCatalogue(false))
        mode.measure(false)
        assertFalse(mode.mayReadSignal())
        assertTrue(mode.mayStartVideo())
        assertFalse(mode.mayReadCatalogue(false))
        mode.videoStarted()
        assertTrue(mode.mayReadCatalogue(false))
    }
    @Test fun delayedPlaybackOrReaderCallbacksCannotEnableConflictingMode(){
        val mode=ReceiverWorkMode()
        mode.measure(true);mode.videoStarted()
        assertFalse(mode.mayStartVideo())
        assertFalse(mode.mayReadCatalogue(false))
        mode.readerClosed();mode.reset();mode.readerClosed()
        assertFalse(mode.mayReadSignal())
        assertTrue(mode.mayStartVideo())
        assertFalse(mode.mayReadCatalogue(false))
    }
}
