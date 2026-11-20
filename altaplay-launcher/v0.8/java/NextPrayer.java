package com.gathin.tvhome;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Next prayer, not permanently Fajr. Works with the published Habous tables.
 * Reads tomorrow's actual table after Isha; never invents tomorrow's Fajr.
 */
final class NextPrayer {
    static final String[] KEYS={"fajr","dhuhr","asr","maghrib","isha"};
    static final String[] NAMES={"الفجر","الظهر","العصر","المغرب","العشاء"};
    private static final DateTimeFormatter HH=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);
    private NextPrayer(){}

    static String display(ZonedDateTime now,Map<String,LocalTime> today,Map<String,LocalTime> tomorrow){
        if(today==null || today.isEmpty())return "مواقيت الصلاة الرسمية قيد التحديث…";
        for(int offset=0;offset<=1;offset++){
            Map<String,LocalTime> day=offset==0?today:tomorrow;
            if(day==null||day.isEmpty())continue;
            LocalDate date=now.toLocalDate().plusDays(offset);
            for(int i=0;i<KEYS.length;i++){
                LocalTime target=day.get(KEYS[i]);
                if(target==null)continue;
                ZonedDateTime time=date.atTime(target).atZone(now.getZone());
                if(!time.isAfter(now))continue;
                long minutes=Math.max(1,Duration.between(now,time).toMinutes());
                String remaining=(minutes>=60?minutes/60+"س ":"")+(minutes%60)+"د";
                return (offset==1?"غداً ":"")+NAMES[i]+" "+HH.format(target)+" · بعد "+remaining;
            }
        }
        return "مواقيت صلاة الغد قيد التحديث…";
    }
}
