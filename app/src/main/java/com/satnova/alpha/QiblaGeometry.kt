package com.satnova.alpha

import kotlin.math.*

/** Great-circle initial bearing to the Kaaba, clockwise from true north. */
object QiblaGeometry {
    const val KAABA_LATITUDE = 21.422487
    const val KAABA_LONGITUDE = 39.826206

    fun bearing(latitude:Double,longitude:Double):Double {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val a=Math.toRadians(latitude)
        val b=Math.toRadians(KAABA_LATITUDE)
        val delta=Math.toRadians(KAABA_LONGITUDE-longitude)
        val east=sin(delta)*cos(b)
        val north=cos(a)*sin(b)-sin(a)*cos(b)*cos(delta)
        if(abs(east)<1e-12 && abs(north)<1e-12)return Double.NaN
        return (Math.toDegrees(atan2(east,north))+360.0)%360.0
    }
    fun relativeBearing(target:Double,heading:Double):Double =
        ((target-heading+540.0)%360.0)-180.0
}
