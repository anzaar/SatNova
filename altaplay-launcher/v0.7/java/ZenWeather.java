package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Weather source: Open-Meteo, as already used by CleanYourTV. No API key or
 * location permission. Official Habous city selection is shared with weather.
 *
 * Resilience improvements:
 *  - never render exception.getMessage() (e.g. "Read timed out") in UI;
 *  - keep successful data for up to 12 hours when offline;
 *  - classify timeouts/TLS/network errors in Arabic, with bounded retries;
 *  - persist a next-attempt time so the 1-min UI ticker doesn't flood API;
 *  - geocode non-Khemisset cities only once, cache verified Morocco coords;
 *  - ignore results of an old city after the user switches city mid-request.
 */
final class ZenWeather {
    private static final long MAX_CACHE_AGE=12*60*60_000L;
    private static final String ROOT="https://api.open-meteo.com/v1/forecast";
    private final SharedPreferences p;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean working=new AtomicBoolean(false);
    private volatile String error="";
    private volatile boolean fetching=false;

    ZenWeather(Context context){
        p=context.getApplicationContext().getSharedPreferences("altaplay_location",Context.MODE_PRIVATE);
    }
    private int cityId(){return p.getInt("city_id",2);}
    private String cityAr(){return p.getString("city_ar","الخميسات");}

    String text(){
        final int city=cityId();
        String latest=city==p.getInt("weather_result_city",-1) ? p.getString("weather_last_value","") : "";
        long at=p.getLong("weather_observed_at",p.getLong("weather_checked",0L));
        long age=System.currentTimeMillis()-at;
        if(at<=0L)age=-1;
        return "الطقس · "+cityAr()+" · "+ZenWeatherText.status(latest,age,fetching,error);
    }

    void refresh(boolean force,Runnable onChange){
        final long now=System.currentTimeMillis();
        final int city=cityId();
        long next=p.getLong("weather_next_attempt",0L);
        if(!force && next>now && next-now<30*60*60_000L)return;
        if(!working.compareAndSet(false,true))return;
        fetching=true;
        final String cityFr=p.getString("city_fr","Khémissat");
        if(onChange!=null)main.post(onChange);
        io.execute(()->{
            boolean ok=false;
            Throwable failure=null;
            try{
                double[] coords=resolveCity(city,cityFr);
                final double lat=coords[0],lon=coords[1];
                String endpoint=String.format(Locale.ROOT,
                    ROOT+"?latitude=%.5f&longitude=%.5f&current=temperature_2m,weather_code,is_day&timezone=Africa%%2FCasablanca",
                    lat,lon);
                JSONObject root=null;
                // A second attempt helps slow/unstable Google TV networks. A hard
                // maximum is enforced by both URLConnection timeouts.
                for(int attempt=0;attempt<2;attempt++){
                    try{
                        root=new JSONObject(download(endpoint,45_000)).getJSONObject("current");
                        break;
                    }catch(Exception e){
                        if(attempt==1)throw e;
                        // Only retry recoverable transport errors, not bad JSON,
                        // invalid coordinates or an exhausted API allowance.
                        if(!(e instanceof java.io.IOException))throw e;
                        try{Thread.sleep(850L);}catch(InterruptedException interrupted){
                            Thread.currentThread().interrupt();throw interrupted;
                        }
                    }
                }
                if(root==null)throw new IllegalStateException("No current weather data");
                final double temperature=root.getDouble("temperature_2m");
                if(Double.isNaN(temperature)||Double.isInfinite(temperature)||
                   temperature< -70 || temperature>65)
                    throw new IllegalArgumentException("Out of range temperature");
                int weatherCode=root.optInt("weather_code",-1);
                int day=root.optInt("is_day",1);
                if(weatherCode<0||weatherCode>99)
                    throw new IllegalArgumentException("Unsupported WMO code");
                String result=String.format(Locale.ROOT,"%.0f°م · %s",
                    temperature,ZenWeatherText.condition(weatherCode,day));
                if(cityId()==city){
                    p.edit().putInt("weather_result_city",city)
                        .putString("weather_last_value",result)
                        .putLong("weather_observed_at",System.currentTimeMillis())
                        .putInt("weather_failures",0)
                        .putLong("weather_next_attempt",System.currentTimeMillis()+ZenWeatherText.retryDelay(0))
                        .apply();
                    error="";
                }
                ok=true;
            }catch(Throwable e){
                // Keep technical details out of the UI; no raw English messages.
                failure=e;
            }finally{
                if(!ok && cityId()==city){
                    int failures=Math.min(10,p.getInt("weather_failures",0)+1);
                    p.edit().putInt("weather_failures",failures)
                        .putLong("weather_next_attempt",System.currentTimeMillis()+ZenWeatherText.retryDelay(failures))
                        .apply();
                    error=ZenWeatherText.problem(failure);
                }
                fetching=false;
                working.set(false);
                main.post(()->{if(onChange!=null)onChange.run();});
            }
        });
    }

    private double[] resolveCity(int city,String name)throws Exception{
        if(city==2)return new double[]{33.824,-6.066}; // Khémisset
        if(p.getInt("weather_geocode_city",-1)==city){
            double lat=Double.longBitsToDouble(p.getLong("weather_lat",
                Double.doubleToRawLongBits(Double.NaN)));
            double lon=Double.longBitsToDouble(p.getLong("weather_lon",
                Double.doubleToRawLongBits(Double.NaN)));
            if(inMorocco(lat,lon))return new double[]{lat,lon};
        }
        String query="https://geocoding-api.open-meteo.com/v1/search?name="+
            URLEncoder.encode(name,"UTF-8")+"&count=10&language=fr&countryCode=MA";
        JSONObject response=new JSONObject(download(query,65_000));
        JSONArray results=response.optJSONArray("results");
        if(results==null||results.length()==0)
            throw new IllegalStateException("No matching Moroccan city");
        JSONObject selected=null;
        for(int i=0;i<results.length();i++){
            JSONObject row=results.getJSONObject(i);
            if(!"MA".equalsIgnoreCase(row.optString("country_code")))continue;
            double lat=row.optDouble("latitude",Double.NaN);
            double lon=row.optDouble("longitude",Double.NaN);
            if(!inMorocco(lat,lon))continue;
            if(selected==null)selected=row;
            if(row.optString("name").equalsIgnoreCase(name)){
                selected=row;break;
            }
        }
        if(selected==null)throw new IllegalStateException("No valid Moroccan geocode");
        double lat=selected.getDouble("latitude"),lon=selected.getDouble("longitude");
        if(cityId()==city){
            p.edit().putInt("weather_geocode_city",city)
                .putLong("weather_lat",Double.doubleToRawLongBits(lat))
                .putLong("weather_lon",Double.doubleToRawLongBits(lon)).apply();
        }
        return new double[]{lat,lon};
    }
    private static boolean inMorocco(double lat,double lon){
        return !Double.isNaN(lat)&&!Double.isNaN(lon)&&lat>=20&&lat<=37&&lon>= -18&&lon<= -0.5;
    }

    private static String download(String endpoint,int limit)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
        try{
            connection.setConnectTimeout(11_000);
            connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("User-Agent","AltaPlayLauncher/0.7 (Android TV weather via Open-Meteo)");
            int status=connection.getResponseCode();
            if(status!=200)throw new java.io.IOException("Weather HTTP "+status);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()){
                byte[] bytes=new byte[4096];int length;
                while((length=in.read(bytes))!=-1){
                    if(out.size()+length>limit)throw new IllegalArgumentException("Weather body too large");
                    out.write(bytes,0,length);
                }
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }finally{connection.disconnect();}
    }
}
