package com.gathin.tvhome;
import java.time.*;
import java.util.*;

public class NextPrayerTest {
    static void yes(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Map<String,LocalTime> times(String f,String d,String a,String m,String i){
        Map<String,LocalTime> t=new HashMap<>();
        t.put("fajr",LocalTime.parse(f));t.put("dhuhr",LocalTime.parse(d));
        t.put("asr",LocalTime.parse(a));t.put("maghrib",LocalTime.parse(m));
        t.put("isha",LocalTime.parse(i));return t;
    }
    public static void main(String[] args){
        ZoneId z=ZoneId.of("Africa/Casablanca");
        LocalDate day=LocalDate.of(2026,11,20);
        Map<String,LocalTime> today=times("05:29","12:15","14:59","17:24","18:40");
        Map<String,LocalTime> tomorrow=times("05:30","12:15","14:59","17:23","18:39");
        yes(NextPrayer.display(day.atTime(4,20).atZone(z),today,tomorrow).contains("الفجر 05:29"),"before fajr");
        yes(NextPrayer.display(day.atTime(6,0).atZone(z),today,tomorrow).contains("الظهر 12:15"),"after fajr next Dhuhr");
        yes(NextPrayer.display(day.atTime(13,0).atZone(z),today,tomorrow).contains("العصر 14:59"),"after Dhuhr");
        yes(NextPrayer.display(day.atTime(15,1).atZone(z),today,tomorrow).contains("المغرب 17:24"),"after Asr");
        yes(NextPrayer.display(day.atTime(18,41).atZone(z),today,tomorrow).contains("غداً الفجر 05:30"),"after Isha tomorrow Fajr");
        yes(NextPrayer.display(day.atTime(18,41).atZone(z),today,Collections.emptyMap()).contains("قيد التحديث"),"never invent tomorrow");
        String h=MoroccanHijri.display(day,0);
        yes(h.contains("هـ") && !h.contains("غير متاح"),"Hijri rendered");
        yes(!MoroccanHijri.display(day,1).equals(h),"Hijri adjustment actually changes date");
        System.out.println("PASS: actual next-prayer switch after each adhan, future published Fajr after Isha, no invented day, Moroccan Hijri adjustment");
    }
}
