package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moroccan prayer times only from the *published* Ministry of Habous table.
 * No astronomical calculator, Aladhan fallback, guessed calendar or silent stale data.
 */
final class HabousPrayerSchedule {
    static final String SOURCE="https://habous.gov.ma/prieres/horaire_hijri_2.php?ville=";
    static final ZoneId ZONE=ZoneId.of("Africa/Casablanca");
    static final String[] KEYS={"fajr","sunrise","dhuhr","asr","maghrib","isha"};
    static final String[] NAMES={"الفجر","الشروق","الظهر","العصر","المغرب","العشاء"};
    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching=new AtomicBoolean(false);
    private volatile String lastError="";
    private volatile boolean checking=false;
    private volatile List<City> directory;

    static final class City {
        final int id;final String ar;final String fr;
        City(int id,String ar,String fr){this.id=id;this.ar=ar;this.fr=fr;}
    }

    HabousPrayerSchedule(Context context){
        app=context.getApplicationContext();
        prefs=app.getSharedPreferences("altaplay_location",Context.MODE_PRIVATE);
        directory=loadCities();
    }
    City selected(){
        int id=prefs.getInt("city_id",2);
        for(City c:directory)if(c.id==id)return c;
        return new City(id,prefs.getString("city_ar","الخميسات"),prefs.getString("city_fr","Khémissat"));
    }
    List<City> cities(){return new ArrayList<>(directory);}
    void select(City c){
        if(c==null||c.id<1||c.id>999)return;
        prefs.edit().putInt("city_id",c.id).putString("city_ar",c.ar).putString("city_fr",c.fr)
            .remove("weather_geocode_city").remove("weather_lat").remove("weather_lon")
            .remove("weather_last_value").remove("weather_checked").apply();
        lastError="";
    }

    private List<City> loadCities(){
        try{
            JSONObject root;
            String saved=prefs.getString("habous_cities_live","");
            if(!saved.isEmpty())root=new JSONObject(saved);
            else{
                try(InputStream in=app.getAssets().open("altaplay_habous_cities.json")){
                    root=new JSONObject(new String(readBounded(in,100_000),StandardCharsets.UTF_8));
                }
            }
            JSONArray source=root.getJSONArray("cities");
            List<City> out=new ArrayList<>();
            for(int i=0;i<source.length();i++){
                JSONObject row=source.getJSONObject(i);
                int id=row.getInt("id");String ar=row.getString("ar").trim();String fr=row.optString("fr",ar).trim();
                if(id>0&&id<1000&&!ar.isEmpty())out.add(new City(id,ar,fr));
            }
            if(out.size()>=100)return Collections.unmodifiableList(out);
        }catch(Exception ignored){}
        return Collections.singletonList(new City(2,"الخميسات","Khémissat"));
    }

    private static byte[] readBounded(InputStream in,int limit)throws Exception {
        ByteArrayOutputStream buf=new ByteArrayOutputStream();
        byte[] b=new byte[4096];int n;
        while((n=in.read(b))!=-1){
            if(n==0)continue;
            if(buf.size()+n>limit)throw new IllegalArgumentException("Response too large");
            buf.write(b,0,n);
        }
        return buf.toByteArray();
    }
    private static String download(String url)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        try{
            c.setConnectTimeout(6500);c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept-Language","ar-MA,ar;q=0.9");
            c.setRequestProperty("User-Agent","AltaPlayLauncher/0.3 (official published times; Android TV)");
            if(c.getResponseCode()!=200)throw new IllegalStateException("HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream()){
                return new String(readBounded(in,750_000),StandardCharsets.UTF_8);
            }
        }finally{c.disconnect();}
    }
    private File cache(int id){return new File(app.getFilesDir(),"habous_published_"+id+".json");}
    Map<String,LocalTime> today(){
        City c=selected();
        Map<String,LocalTime> out=new LinkedHashMap<>();
        try{
            File f=cache(c.id);
            if(!f.isFile()||f.length()<10||f.length()>35000)return Collections.emptyMap();
            JSONObject root=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            if(root.optInt("city_id")!=c.id||!root.optString("source").startsWith("https://habous.gov.ma/"))return Collections.emptyMap();
            JSONObject day=root.optJSONObject("days");
            if(day==null)return Collections.emptyMap();
            JSONObject times=day.optJSONObject(LocalDate.now(ZONE).toString());
            if(times==null)return Collections.emptyMap();
            for(String name:KEYS)out.put(name,LocalTime.parse(times.getString(name)));
            return out;
        }catch(Exception ignored){return Collections.emptyMap();}
    }

    String label(){
        City c=selected();
        Map<String,LocalTime> t=today();
        if(t.isEmpty()){
            String status=checking?"جاري جلب الحصة الرسمية…":lastError.isEmpty()?"الحصة الرسمية لليوم غير متوفرة":lastError;
            return "الأوقاف · "+c.ar+" · "+status;
        }
        LocalTime now=LocalTime.now(ZONE);
        int[] order={0,2,3,4,5};
        for(int i:order)if(now.isBefore(t.get(KEYS[i])))
            return "الأوقاف · "+c.ar+" · "+NAMES[i]+" "+t.get(KEYS[i]);
        return "الأوقاف · "+c.ar+" · العشاء "+t.get("isha")+" (آخر صلاة اليوم)";
    }

    void refresh(boolean force,Runnable onChange){
        City selected=selected();
        long now=System.currentTimeMillis();
        long last=prefs.getLong("prayers_checked_"+selected.id,0);
        long delay=today().isEmpty()?10*60_000L:6*60*60_000L;
        if(!force&&now-last>=0&&now-last<delay)return;
        if(!fetching.compareAndSet(false,true))return;
        checking=true;
        prefs.edit().putLong("prayers_checked_"+selected.id,now).apply();
        worker.execute(()->{
            String error="";boolean ok=false;
            try{
                String html;
                try{html=download(SOURCE+selected.id);}
                catch(Exception e){html=download("https://www.habous.gov.ma/prieres/horaire_hijri_2.php?ville="+selected.id);}
                Map<LocalDate,String[]> all=HabousHtmlParser.schedule(html,LocalDate.now(ZONE));
                if(all.isEmpty())throw new IllegalStateException("تعذر قراءة جدول الأوقاف");
                // Historical/next-month official pages must not masquerade as today's published times.
                if(!all.containsKey(LocalDate.now(ZONE)))throw new IllegalStateException("حصة الأوقاف الحالية لم تُنشر بعد");
                JSONObject days=new JSONObject();
                for(Map.Entry<LocalDate,String[]> entry:all.entrySet()){
                    JSONObject prayers=new JSONObject();
                    for(int i=0;i<KEYS.length;i++)prayers.put(KEYS[i],entry.getValue()[i]);
                    days.put(entry.getKey().toString(),prayers);
                }
                JSONObject saved=new JSONObject().put("city_id",selected.id)
                    .put("source",SOURCE+selected.id).put("fetched_at",System.currentTimeMillis())
                    .put("days",days);
                File dest=cache(selected.id);
                File tmp=File.createTempFile("habous-", ".tmp",app.getFilesDir());
                try(FileOutputStream stream=new FileOutputStream(tmp)){
                    stream.write(saved.toString().getBytes(StandardCharsets.UTF_8));
                }
                try{Files.move(tmp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(java.nio.file.AtomicMoveNotSupportedException e){
                    Files.move(tmp.toPath(),dest.toPath(),StandardCopyOption.REPLACE_EXISTING);
                }finally{tmp.delete();}
                syncCities(html);
                ok=true;
            }catch(Exception e){error=e instanceof java.net.SocketTimeoutException?"المصدر الرسمي لا يستجيب":e.getMessage()==null?"تعذر جلب المواقيت":e.getMessage();}
            lastError=ok?"":error;
            checking=false;fetching.set(false);
            main.post(()->{if(onChange!=null)onChange.run();});
        });
    }
    private void syncCities(String html){
        List<HabousHtmlParser.CityName> official=HabousHtmlParser.cities(html);
        if(official.size()<150)return;
        boolean hasKhemisset=false;
        for(HabousHtmlParser.CityName c:official)if(c.id==2&&c.name.contains("الخميسات"))hasKhemisset=true;
        if(!hasKhemisset)return;
        Map<Integer,City> previous=new LinkedHashMap<>();
        for(City c:directory)previous.put(c.id,c);
        JSONArray list=new JSONArray();List<City> fresh=new ArrayList<>();
        for(HabousHtmlParser.CityName c:official){
            City old=previous.get(c.id);
            City city=new City(c.id,c.name,old==null?c.name:old.fr);
            fresh.add(city);
            JSONObject entry=new JSONObject();
            try{entry.put("id",city.id).put("ar",city.ar).put("fr",city.fr);}
            catch(Exception ignored){}
            list.put(entry);
        }
        JSONObject root=new JSONObject();
        try{root.put("cities",list).put("source","https://habous.gov.ma");}
        catch(Exception ignored){}
        directory=Collections.unmodifiableList(fresh);
        prefs.edit().putString("habous_cities_live",root.toString()).apply();
    }
}
