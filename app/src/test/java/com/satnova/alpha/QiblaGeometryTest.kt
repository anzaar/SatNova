package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class QiblaGeometryTest {
    @Test fun qiblaFromMoroccoIsNearDueEast(){
        assertEquals(94.89,QiblaGeometry.bearing(33.824,-6.066),0.15)
        assertEquals(94.62,QiblaGeometry.bearing(34.02,-6.84),0.15)
    }
    @Test fun qiblaFromParisAndLondonPointsSouthEast(){
        assertEquals(119.16,QiblaGeometry.bearing(48.86,2.35),0.15)
        assertEquals(118.99,QiblaGeometry.bearing(51.507,-0.128),0.15)
    }
    @Test fun headingsWrapConsistently(){
        assertEquals(20.0,QiblaGeometry.relativeBearing(10.0,350.0),0.01)
        assertEquals(-20.0,QiblaGeometry.relativeBearing(350.0,10.0),0.01)
    }
    @Test fun invalidCoordinatesAreRejected(){
        try{
            QiblaGeometry.bearing(101.0,5.0)
            fail("Expected out-of-range latitude")
        }catch(_:IllegalArgumentException){}
        assertTrue(QiblaGeometry.bearing(
            QiblaGeometry.KAABA_LATITUDE,QiblaGeometry.KAABA_LONGITUDE).isNaN())
    }
}
