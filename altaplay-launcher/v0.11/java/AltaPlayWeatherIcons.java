package com.gathin.tvhome;

/** Small transparent Android vector assets (no PNG downloads, no animations).
 * Weather class is read from Open-Meteo's current.weather_code (WMO).
 */
final class AltaPlayWeatherIcons {
    private AltaPlayWeatherIcons() {}

    static int forCode(int code,boolean day) {
        if(code==0)return day?R.drawable.altaplay_weather_sun:
            R.drawable.altaplay_weather_moon;
        if(code==1||code==2)return R.drawable.altaplay_weather_partly;
        if(code==3)return R.drawable.altaplay_weather_cloud;
        if(code==45||code==48)return R.drawable.altaplay_weather_fog;
        if(code>=51&&code<=57)return R.drawable.altaplay_weather_drizzle;
        if((code>=61&&code<=67)||(code>=80&&code<=82))
            return R.drawable.altaplay_weather_rain;
        if((code>=71&&code<=77)||(code>=85&&code<=86))
            return R.drawable.altaplay_weather_snow;
        if(code>=95&&code<=99)return R.drawable.altaplay_weather_thunder;
        return R.drawable.altaplay_weather_cloud;
    }

    static int fromText(String text) {
        if(text==null)return R.drawable.altaplay_weather_cloud;
        String s=text.toLowerCase(java.util.Locale.ROOT);
        if(s.contains("عاصف")||s.contains("رعد")||s.contains("برق")||
           s.contains("thunder")||s.contains("orage"))
            return R.drawable.altaplay_weather_thunder;
        if(s.contains("ثلج")||s.contains("snow")||s.contains("neige"))
            return R.drawable.altaplay_weather_snow;
        if(s.contains("مطر")||s.contains("أمطار")||s.contains("pluie")||s.contains("rain"))
            return R.drawable.altaplay_weather_rain;
        if(s.contains("ضباب")||s.contains("fog")||s.contains("brume"))
            return R.drawable.altaplay_weather_fog;
        if(s.contains("صحو")||s.contains("clear")||s.contains("soleil")||s.contains("مشمس"))
            return R.drawable.altaplay_weather_sun;
        if(s.contains("غيوم")||s.contains("nuage")||s.contains("cloud"))
            return R.drawable.altaplay_weather_cloud;
        return R.drawable.altaplay_weather_partly;
    }
}
