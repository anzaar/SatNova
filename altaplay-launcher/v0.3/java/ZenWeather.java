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
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same weather data provider used by CleanYourTV's weather add-on: Open-Meteo.
 * The city selection is shared with official Habous prayers (no location permission). */
final class ZenWeather {
    private final SharedPreferences p;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean working=new AtomicBoolean(false);
    private volatile String error="";
    ZenWeather(Context c){p=c.getApplicationContext().getSharedPreferences("altaplay_location",Context.MODE_PRIVATE);}
    private int cityId(){return p.getInt("city_id",2);}
    private String cityAr(){return p.getString("city_ar","الخميسات");}
    String text(){
        String prefix="الطقس · "+cityAr()+" · ";
        if(p.getInt("weather_result_city",-1)==cityId()){
            String value=p.getString("weather_last_value","");
            if(!value.isEmpty())return prefix+value;
        }
        return prefix+(error.isEmpty()?"قيد التحديث…":error);
    }
    void refresh(boolean force,Runnable onChange){
        long now=System.currentTimeMillis();
        long last=p.getLong("weather_checked",0);
        boolean present=p.getInt("weather_result_city",-1)==cityId()&&!p.getString("weather_last_value","").isEmpty();
        long next=present?30*60_000L:5*60_000L;
        if(!force&&now>=last&&now-last<next)return;
        if(!working.compareAndSet(false,true))return;
        final int id=cityId();
        final String name=p.getString("city_fr","Khémissat");
        p.edit().putLong("weather_checked",now).apply();
        io.execute(()->{
            String problem="";
            try{
                double lat,lon;
                if(id==2){lat=33.824;lon=-6.066;}
                else if(p.getInt("weather_geocode_city",-1)==id){
                    lat=Double.longBitsToDouble(p.getLong("weather_lat",Double.doubleToRawLongBits(Double.NaN)));
                    lon=Double.longBitsToDouble(p.getLong("weather_lon",Double.doubleToRawLongBits(Double.NaN)));
                }else{
                    String url="https://geocoding-api.open-meteo.com/v1/search?name="+
                        URLEncoder.encode(name,"UTF-8")+"&count=10&language=fr&countryCode=MA";
                    JSONObject geo=new JSONObject(download(url,45_000));
                    JSONArray found=geo.optJSONArray("results");
                    if(found==null||found.length()==0)throw new IllegalStateException("المدينة غير موجودة في خدمة الطقس");
                    JSONObject chosen=null;
                    for(int i=0;i<found.length();i++){
                        JSONObject row=found.getJSONObject(i);
                        if(!"MA".equalsIgnoreCase(row.optString("country_code")))continue;
                        if(chosen==null)chosen=row;
                        if(row.optString("name").equalsIgnoreCase(name)){chosen=row;break;}
                    }
                    if(chosen==null)throw new IllegalStateException("تعذر تحديد المدينة المغربية");
                    lat=chosen.getDouble("latitude");lon=chosen.getDouble("longitude");
                    if(lat<20||lat>37||lon< -18||lon> -0.5)throw new IllegalStateException("إحداثيات المدينة غير صحيحة");
                    if(cityId()==id)p.edit().putInt("weather_geocode_city",id)
                        .putLong("weather_lat",Double.doubleToRawLongBits(lat))
                        .putLong("weather_lon",Double.doubleToRawLongBits(lon)).apply();
                }
                if(Double.isNaN(lat)||Double.isNaN(lon))throw new IllegalStateException("تعذر تحديد الإحداثيات");
                String endpoint=String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,weather_code,is_day&timezone=Africa%%2FCasablanca",lat,lon);
                JSONObject current=new JSONObject(download(endpoint,45000)).getJSONObject("current");
                double temperature=current.getDouble("temperature_2m");
                if(!Double.isFinite(temperature)||temperature< -70||temperature>65)throw new IllegalStateException("درجة حرارة غير صحيحة");
                int weather=current.optInt("weather_code",-1);
                String description;
                if(weather==0)description="صحو";
                else if(weather==1||weather==2||weather==3)description="غيوم";
                else if(weather==45||weather==48)description="ضباب";
                else if(weather>=51&&weather<=67||weather>=80&&weather<=82)description="أمطار";
                else if(weather>=71&&weather<=77||weather>=85&&weather<=86)description="ثلوج";
                else if(weather>=95&&weather<=99)description="عواصف";
                else description="طقس متغير";
                if(cityId()==id){
                    p.edit().putString("weather_last_value",String.format(Locale.US,"%.0f°م · %s",temperature,description))
                        .putInt("weather_result_city",id).apply();
                    error="";
                }
            }catch(Exception e){problem=e.getMessage()==null?"البيانات غير متوفرة":e.getMessage();}
            if(cityId()==id&&!problem.isEmpty())error=problem.contains("HTTP")?"خدمة الطقس لا تستجيب":problem;
            working.set(false);
            main.post(()->{if(onChange!=null)onChange.run();});
        });
    }
    private static String download(String endpoint,int limit)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        try{
            c.setConnectTimeout(5500);c.setReadTimeout(6500);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","AltaPlayLauncher/0.3");
            if(c.getResponseCode()!=200)throw new IllegalStateException("HTTP "+c.getResponseCode());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] b=new byte[2048];int n;
                while((n=in.read(b))!=-1){
                    if(out.size()+n>limit)throw new IllegalStateException("البيانات كبيرة جداً");
                    out.write(b,0,n);
                }
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }finally{c.disconnect();}
    }
}
