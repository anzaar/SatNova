package com.satnova.alpha

import org.junit.Assert.*
import org.junit.Test

class SatelliteGeometryTest {
    @Test fun recognizedOrbitalPositions(){
        assertEquals(-7.0,SatelliteGeometry.orbitalFromName("Nilesat 7W")!!,0.001)
        assertEquals(19.2,SatelliteGeometry.orbitalFromName("ASTRA 19.2E")!!,0.001)
        assertEquals(13.0,SatelliteGeometry.orbitalFromName("Hot Bird")!!,0.001)
        assertNull(SatelliteGeometry.orbitalFromName("Satellite non communiqué"))
    }
    @Test fun geometryFromMorocco(){
        val nilesat=SatelliteGeometry.look(33.82,-6.06,-7.0)
        assertEquals(181.69,nilesat.azimuth,0.3)
        assertEquals(50.67,nilesat.elevation,0.3)
        val astra=SatelliteGeometry.look(33.82,-6.06,19.2)
        assertEquals(139.71,astra.azimuth,0.3)
        assertEquals(42.28,astra.elevation,0.3)
    }
    @Test fun lnbSkewAndCameraAngularRuler(){
        assertEquals(0.0,SatelliteGeometry.lnbSkew(33.82,-6.06,-6.06),0.0001)
        val skew=SatelliteGeometry.lnbSkew(33.82,-6.06,19.2)
        assertTrue(skew>20 && skew<40)
        assertEquals(3.0,SatelliteGeometry.horizontalDistance(3.0,45.0,0.0)!!,0.001)
        assertNull(SatelliteGeometry.horizontalDistance(-1.0,45.0,0.0))
        assertNull(SatelliteGeometry.horizontalDistance(3.0,20.0,20.0))
    }
    @Test fun hemisphereAndVisibility(){
        val geo=SatelliteGeometry.look(33.82,-6.06,-7.0)
        assertTrue(geo.elevation>45 && geo.elevation<55)
        assertTrue(SatelliteGeometry.lnbSkew(33.82,-6.06,-7.0)<0)
    }
    @Test fun wrappedCompassDifference(){
        assertEquals(20.0,SatelliteGeometry.signedDelta(10.0,350.0),0.01)
        assertEquals(-20.0,SatelliteGeometry.signedDelta(350.0,10.0),0.01)
    }
}