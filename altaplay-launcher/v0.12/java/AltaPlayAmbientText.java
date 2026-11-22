package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Presentation-only translations for cached weather, Hijri and next-prayer text.
 * Intentionally never changes Habous/Zen data, receivers, network calls or timers.
 * The Arabic source remains the single canonical calculation.
 */
final class AltaPlayAmbientText {
    private AltaPlayAmbientText() {}

    private static String language(Context c) {
        String x=c.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
            .getString("language","ar");
        return "fr".equals(x)||"en".equals(x)?x:"ar";
    }
    private static String term(String lang,String fr,String en) {
        return "fr".equals(lang)?fr:en;
    }
    static String prayer(Context c,String original) {
        String lang=language(c);
        if("ar".equals(lang)||original==null)return original;
        String s=original
          .replace("مواقيت الصلاة الرسمية قيد التحديث…",
             term(lang,"Mise à jour des horaires officiels…",
                       "Updating official prayer times…"))
          .replace("مواقيت صلاة الغد قيد التحديث…",
             term(lang,"Mise à jour des horaires de demain…",
                       "Updating tomorrow's prayer times…"))
          .replace("غداً ",term(lang,"Demain : ","Tomorrow: "))
          .replace("الفجر","Fajr")
          .replace("الظهر","Dhuhr")
          .replace("العصر","Asr")
          .replace("المغرب","Maghrib")
          .replace("العشاء","Isha")
          .replace(" · بعد ",term(lang," · dans "," · in "));
        // Replace only numeric countdown units; do not mutate prayer names.
        s=s.replaceAll("(\\d+)س\\s*", "$1 h ")
           .replaceAll("(\\d+)د",term(lang,"$1 min","$1 min"));
        return s.trim();
    }
    static String hijri(Context c,String original) {
        String lang=language(c);
        if("ar".equals(lang)||original==null)return original;
        String s=original;
        String[] arabic={"جمادى الأولى","جمادى الآخرة","ربيع الأول","ربيع الآخر",
            "ذو القعدة","ذو الحجة","محرم","صفر","رجب","شعبان","رمضان","شوال"};
        String[] latin={"Jumada al-Ula","Jumada al-Akhira","Rabi al-Awwal",
            "Rabi al-Thani","Dhu al-Qidah","Dhu al-Hijjah","Muharram",
            "Safar","Rajab","Sha'ban","Ramadan","Shawwal"};
        for(int i=0;i<arabic.length;i++)s=s.replace(arabic[i],latin[i]);
        return s.replace(" هـ"," AH")
           .replace("التاريخ الهجري غير متاح",
               term(lang,"Date hégirienne indisponible","Hijri date unavailable"));
    }
    static String weather(Context c,String original) {
        String lang=language(c);
        if("ar".equals(lang)||original==null)return original;
        String s=original;
        SharedPreferences places=c.getSharedPreferences("altaplay_location",Context.MODE_PRIVATE);
        String arCity=places.getString("city_ar","الخميسات");
        String frCity=places.getString("city_fr","Khémisset");
        if(!arCity.isEmpty())s=s.replace(arCity,frCity);
        String[] ar={
            "سماء صافية ليلاً","سماء صافية نسبياً","عواصف رعدية",
            "غيوم متفرقة","غائم جزئياً","زخات مطر","زخات ثلج",
            "طقس متغير","ثلوج","أمطار","رذاذ","ضباب","غائم","صحو",
            "آخر حالة محفوظة","جاري تحديث الطقس…",
            "تأخر اتصال الطقس · إعادة المحاولة",
            "لا يوجد اتصال بخدمة الطقس",
            "تعذر الاتصال الآمن بخدمة الطقس",
            "الطقس غير متاح مؤقتاً"
        };
        String[] fr={
            "Ciel dégagé la nuit","Ciel plutôt dégagé","Orages",
            "Nuages épars","Partiellement nuageux","Averses de pluie",
            "Averses de neige","Temps variable","Neige","Pluie","Bruine",
            "Brouillard","Nuageux","Ensoleillé","Dernier relevé enregistré",
            "Mise à jour météo…","Délai météo dépassé · nouvel essai",
            "Service météo inaccessible","Connexion météo sécurisée impossible",
            "Météo temporairement indisponible"
        };
        String[] en={
            "Clear night sky","Mostly clear skies","Thunderstorms",
            "Scattered clouds","Partly cloudy","Rain showers","Snow showers",
            "Variable weather","Snow","Rain","Drizzle","Fog","Cloudy","Sunny",
            "Last saved conditions","Updating weather…",
            "Weather request timed out · retrying",
            "Weather service unavailable","Secure weather connection failed",
            "Weather temporarily unavailable"
        };
        for(int i=0;i<ar.length;i++)s=s.replace(ar[i],term(lang,fr[i],en[i]));
        return s.replace("°م", " °C");
    }
    static String weatherAccessibility(Context c,String original) {
        String lang=language(c);
        if("ar".equals(lang))return "الطقس: "+original+
            "، اضغط لتغيير المدينة أو اضغط مطوّلاً لتحديث الطقس";
        return term(lang,"Météo : ","Weather: ")+weather(c,original)+
            term(lang,". Appuyez pour changer de ville, maintenez pour actualiser.",
                       ". Select to change city; hold to refresh.");
    }
}
