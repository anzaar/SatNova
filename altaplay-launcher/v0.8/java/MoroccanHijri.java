package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;

/** Approximate Hijri date (Umm al-Qura engine), optionally shifted ±1 day
 * to agree with Moroccan moon-sighting announcements. Do not label it official.
 */
final class MoroccanHijri {
    private static final String[] MONTHS={
       "محرم","صفر","ربيع الأول","ربيع الآخر","جمادى الأولى","جمادى الآخرة",
       "رجب","شعبان","رمضان","شوال","ذو القعدة","ذو الحجة"
    };
    private MoroccanHijri(){}
    static String display(LocalDate moroccoToday,int adjustment){
        int safe=Math.max(-2,Math.min(2,adjustment));
        try{
            HijrahDate h=HijrahDate.from(moroccoToday.plusDays(safe));
            int d=h.get(ChronoField.DAY_OF_MONTH);
            int m=h.get(ChronoField.MONTH_OF_YEAR);
            int y=h.get(ChronoField.YEAR_OF_ERA);
            return d+" "+MONTHS[m-1]+" "+y+" هـ";
        }catch(Exception e){
            return "التاريخ الهجري غير متاح";
        }
    }
}
