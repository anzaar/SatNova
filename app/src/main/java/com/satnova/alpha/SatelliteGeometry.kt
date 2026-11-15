package com.satnova.alpha

import kotlin.math.*

/** Pure calculations for geostationary satellites; no receiver commands are issued. */
object SatelliteGeometry {
    data class Look(val azimuth:Double,val elevation:Double,val distanceKm:Double)
    data class Orbital(val label:String,val longitude:Double)
    val common=listOf(
        Orbital("Nilesat / Eutelsat • 7°W",-7.0),
        Orbital("Eutelsat • 5°W",-5.0),
        Orbital("Thor / Intelsat • 0.8°W",-0.8),
        Orbital("Hot Bird • 13°E",13.0),
        Orbital("Astra • 19.2°E",19.2),
        Orbital("Badr / Arabsat • 26°E",26.0),
        Orbital("Türksat • 42°E",42.0)
    )
    fun orbitalFromName(raw:String):Double? {
        val name=raw.trim()
        val match=Regex("""(\d{1,3}(?:[.,]\d+)?)\s*(?:°|deg)?\s*([EeWw])\b""").find(name)
        if(match!=null){
            val magnitude=match.groupValues[1].replace(',','.').toDoubleOrNull()
            if(magnitude!=null && magnitude<=180.0)
                return if(match.groupValues[2].equals("W",true)) -magnitude else magnitude
        }
        val lower=name.lowercase()
        return when {
            "nilesat" in lower || "7 west" in lower -> -7.0
            "hotbird" in lower || "hot bird" in lower -> 13.0
            "astra" in lower && "19" in lower -> 19.2
            "badr" in lower || "arabsat" in lower -> 26.0
            "turksat" in lower || "türksat" in lower -> 42.0
            else -> null
        }
    }
    fun look(latitude:Double,longitude:Double,orbitalLongitude:Double):Look {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                orbitalLongitude in -180.0..180.0)
        val lat=Math.toRadians(latitude)
        val lon=Math.toRadians(longitude)
        val orb=Math.toRadians(orbitalLongitude)
        val earth=6378.137
        val geo=42164.0
        val dx=geo*cos(orb)-earth*cos(lat)*cos(lon)
        val dy=geo*sin(orb)-earth*cos(lat)*sin(lon)
        val dz=-earth*sin(lat)
        val east=-sin(lon)*dx+cos(lon)*dy
        val north=-sin(lat)*cos(lon)*dx-sin(lat)*sin(lon)*dy+cos(lat)*dz
        val up=cos(lat)*cos(lon)*dx+cos(lat)*sin(lon)*dy+sin(lat)*dz
        val az=(Math.toDegrees(atan2(east,north))+360.0)%360.0
        val el=Math.toDegrees(atan2(up,hypot(east,north)))
        return Look(az,el,sqrt(dx*dx+dy*dy+dz*dz))
    }
    /** Theoretical linear-polarisation angle (degrees), signed by satellite minus site longitude. */
    fun lnbSkew(latitude:Double,siteLongitude:Double,satelliteLongitude:Double):Double {
        require(latitude in -90.0..90.0 && siteLongitude in -180.0..180.0 &&
                satelliteLongitude in -180.0..180.0)
        val delta=Math.toRadians(satelliteLongitude-siteLongitude)
        val lat=Math.toRadians(latitude)
        if(kotlin.math.abs(kotlin.math.tan(lat))<1e-10)
            return when {
                kotlin.math.sin(delta)>0.0 -> 90.0
                kotlin.math.sin(delta)<0.0 -> -90.0
                else -> 0.0
            }
        return Math.toDegrees(kotlin.math.atan(kotlin.math.sin(delta)/kotlin.math.tan(lat)))
    }
    /** Two camera sightlines at the same position to endpoints of a known vertical span. */
    fun horizontalDistance(heightMetres:Double,topElevation:Double,bottomElevation:Double):Double? {
        if(!heightMetres.isFinite() || heightMetres<=0.0 ||
            !topElevation.isFinite() || !bottomElevation.isFinite())return null
        if(kotlin.math.abs(topElevation)>=85.0 || kotlin.math.abs(bottomElevation)>=85.0)return null
        val difference=kotlin.math.abs(kotlin.math.tan(Math.toRadians(topElevation))-
            kotlin.math.tan(Math.toRadians(bottomElevation)))
        if(difference<0.005)return null
        return (heightMetres/difference).takeIf{it.isFinite() && it<10000.0}
    }
    fun signedDelta(target:Double,current:Double):Double =
        ((target-current+540.0)%360.0)-180.0
}
