package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class ReceiverLoadPolicyTest {
    @Test fun hiddenCatalogueDoesNotCompeteWithKnownChannelPlayback(){
        assertFalse(ReceiverLoadPolicy.catalogue(true,false,false,false,true))
        assertTrue(ReceiverLoadPolicy.catalogue(true,true,false,false,true))
        assertFalse(ReceiverLoadPolicy.catalogue(true,true,true,false,true))
        assertFalse(ReceiverLoadPolicy.catalogue(true,true,false,true,true))
    }
    @Test fun missingChannelCanBeIdentifiedAndSignalSatelliteBrowsingCanLoad(){
        assertTrue(ReceiverLoadPolicy.catalogue(true,false,false,false,false))
        assertTrue(ReceiverLoadPolicy.catalogue(false,false,false,false,true))
    }
}
