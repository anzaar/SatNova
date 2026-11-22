package com.gathin.tvhome;

import android.content.Context;
import com.batoulapps.adhan.CalculationMethod;
import com.batoulapps.adhan.CalculationParameters;
import com.batoulapps.adhan.Coordinates;
import com.batoulapps.adhan.PrayerTimes;
import com.batoulapps.adhan.data.DateComponents;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/**
 * Saudi Umm al-Qura calculation for configured cities OUTSIDE Morocco only.
 * No API needed after country/city coordinates are chosen.
 * From adhan-java v1.2.1 (MIT): Fajr 18.5°, Isha 90min after Maghrib,
 * +30 minutes for Isha during an estimated Hijri Ramadan.
 * Public calculations do not claim to be that country's official timetable.
 */
final class AltaPlaySaudiPrayers {
    private final Context app;
    private LocalDate cachedDate;
    private String cachedLocation="";
    private Map<String,LocalTime> cache=Collections.emptyMap();

    AltaPlaySaudiPrayers(Context c){app=c.getApplicationContext();}

    ZoneId zone(){
        String id=AltaPlayLocation.zone(app);
        if(id.isEmpty())return ZoneId.systemDefault();
        try{return ZoneId.of(id);}catch(Exception ignored){return ZoneId.systemDefault();}
    }

    Map<String,LocalTime> forDate(LocalDate day){
        if(!AltaPlayLocation.hasCity(app) || AltaPlayLocation.isMorocco(app))
            return Collections.emptyMap();
        double latitude=AltaPlayLocation.latitude(app);
        double longitude=AltaPlayLocation.longitude(app);
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude))return Collections.emptyMap();
        ZoneId zone=zone();
        String key=latitude+","+longitude+"|"+zone;
        if(day.equals(cachedDate)&&key.equals(cachedLocation))return cache;
        try {
            CalculationParameters params=CalculationMethod.UMM_AL_QURA.getParameters();
            // Saudi Umm al-Qura: Isha is 120 minutes after Maghrib in Ramadan.
            // HijrahDate here is calculated, subject to national moon sighting.
            boolean ramadan=HijrahDate.from(day).get(ChronoField.MONTH_OF_YEAR)==9;
            if(ramadan) params.adjustments.isha+=30;
            PrayerTimes p=new PrayerTimes(
                new Coordinates(latitude,longitude),
                new DateComponents(day.getYear(),day.getMonthValue(),day.getDayOfMonth()),
                params);
            Map<String,LocalTime> out=new LinkedHashMap<>();
            out.put("fajr",p.fajr.toInstant().atZone(zone).toLocalTime().withSecond(0).withNano(0));
            out.put("sunrise",p.sunrise.toInstant().atZone(zone).toLocalTime().withSecond(0).withNano(0));
            out.put("dhuhr",p.dhuhr.toInstant().atZone(zone).toLocalTime().withSecond(0).withNano(0));
            out.put("asr",p.asr.toInstant().atZone(zone).toLocalTime().withSecond(0).withNano(0));
            out.put("maghrib",p.maghrib.toInstant().atZone(zone).toLocalTime().withSecond(0).withNano(0));
            out.put("isha",p.isha.toInstant().atZone(zone).toLocalTime().withSecond(0).withNano(0));
            // Do not silently show bad astronomical dates at extreme latitudes.
            if(out.values().stream().anyMatch(v->v==null))return Collections.emptyMap();
            cachedDate=day;cachedLocation=key;
            cache=Collections.unmodifiableMap(out);
            return cache;
        } catch(Exception e){
            return Collections.emptyMap();
        }
    }
}
