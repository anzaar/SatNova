package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;

/**
 * Country suggestion comes from Android TV locale, never from an invented city.
 * User selections override the device suggestion and survive reboot.
 * Morocco always takes actual Habous city schedule, other countries Umm al-Qura.
 */
final class AltaPlayLocation {
    private AltaPlayLocation() {}
    static SharedPreferences prefs(Context c){
        return c.getApplicationContext().getSharedPreferences("altaplay_location",Context.MODE_PRIVATE);
    }
    static String country(Context c){
        SharedPreferences p=prefs(c);
        String saved=p.getString("country_iso","").trim().toUpperCase(Locale.ROOT);
        if(validCountry(saved))return saved;
        String system=Locale.getDefault().getCountry().toUpperCase(Locale.ROOT);
        return validCountry(system)?system:"";
    }
    static boolean validCountry(String iso){
        if(iso==null||!iso.matches("[A-Z]{2}"))return false;
        return Arrays.asList(Locale.getISOCountries()).contains(iso);
    }
    static String countryLabel(Context c,Locale locale){
        String code=country(c);
        if(code.isEmpty())return "";
        return new Locale("",code).getDisplayCountry(locale);
    }
    static boolean isMorocco(Context c){return "MA".equals(country(c));}
    static boolean hasCity(Context c){
        SharedPreferences p=prefs(c);
        if(isMorocco(c))return p.contains("city_id") && p.getInt("city_id",-1)>0;
        return !country(c).isEmpty()
            && !p.getString("custom_city","").trim().isEmpty()
            && p.contains("custom_lat") && p.contains("custom_lon")
            && !p.getString("custom_tz","").isEmpty();
    }
    static String city(Context c){
        SharedPreferences p=prefs(c);
        if(isMorocco(c))return hasCity(c)?p.getString("city_ar",p.getString("city_fr","")):"";
        return p.getString("custom_city","");
    }
    static String zone(Context c){
        if(isMorocco(c))return "Africa/Casablanca";
        String z=prefs(c).getString("custom_tz","");
        try{ZoneId.of(z);return z;}catch(Exception ignored){return "";}
    }
    static double latitude(Context c){
        return Double.longBitsToDouble(prefs(c).getLong("custom_lat",
            Double.doubleToLongBits(Double.NaN)));
    }
    static double longitude(Context c){
        return Double.longBitsToDouble(prefs(c).getLong("custom_lon",
            Double.doubleToLongBits(Double.NaN)));
    }
    static void selectCountry(Context c,String iso){
        iso=iso.trim().toUpperCase(Locale.ROOT);
        if(!validCountry(iso))throw new IllegalArgumentException("ISO country");
        SharedPreferences p=prefs(c);
        if(iso.equals(country(c)) && iso.equals(p.getString("country_iso","")))return;
        p.edit().putString("country_iso",iso)
            .remove("city_id").remove("city_ar").remove("city_fr")
            .remove("custom_city").remove("custom_lat").remove("custom_lon")
            .remove("custom_tz")
            .remove("weather_result_city").remove("weather_last_value")
            .remove("weather_observed_at").remove("weather_checked")
            .remove("weather_geocode_city").remove("weather_lat").remove("weather_lon")
            .apply();
    }
    static void selectWorldCity(Context c,String name,double lat,double lon,String tz){
        if(isMorocco(c))throw new IllegalStateException("Use Habous city list in Morocco");
        if(name==null||name.trim().isEmpty()||name.length()>95
          ||!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)
            throw new IllegalArgumentException("Invalid city coordinates");
        ZoneId.of(tz);
        prefs(c).edit()
            .putString("custom_city",name.trim())
            .putLong("custom_lat",Double.doubleToLongBits(lat))
            .putLong("custom_lon",Double.doubleToLongBits(lon))
            .putString("custom_tz",tz)
            .remove("weather_result_city").remove("weather_last_value")
            .remove("weather_observed_at").remove("weather_checked")
            .apply();
    }
}
