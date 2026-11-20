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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the Ministry's actual monthly data files, e.g.:
 * https://www.habous.gov.ma/data/horaires-2026-11.js
 *
 * No astronomical approximation, iframes, third-party "Morocco methods", unsafe SSL,
 * or JavaScript evaluation. Displayed official times are used as printed (Moroccan GMT).
 */
final class HabousPrayerSchedule {
    private static final String OFFICIAL="https://www.habous.gov.ma/data/horaires-";
    private static final ZoneId ZONE=ZoneId.of("Africa/Casablanca");
    private static final DateTimeFormatter MONTH=DateTimeFormatter.ofPattern("yyyy-MM",Locale.ROOT);
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);
    static final String[] KEYS={"fajr","sunrise","dhuhr","asr","maghrib","isha"};
    static final String[] NAMES={"الفجر","الشروق","الظهر","العصر","المغرب","العشاء"};

    private final Context app;
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
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
    void select(City city){
        if(city==null||city.id<1||city.id>999)return;
        prefs.edit()
            .putInt("city_id",city.id).putString("city_ar",city.ar).putString("city_fr",city.fr)
            .remove("weather_geocode_city").remove("weather_lat").remove("weather_lon")
            .remove("weather_last_value").remove("weather_checked")
            .remove("weather_result_city").apply();
        lastError="";
    }

    private List<City> loadCities(){
        try{
            String live=prefs.getString("habous_cities_live","");
            JSONObject list;
            if(!live.isEmpty())list=new JSONObject(live);
            else{
                try(InputStream stream=app.getAssets().open("altaplay_habous_cities.json")){
                    list=new JSONObject(new String(readBounded(stream,100000),StandardCharsets.UTF_8));
                }
            }
            JSONArray entries=list.getJSONArray("cities");
            List<City> cities=new ArrayList<>();
            for(int i=0;i<entries.length();i++){
                JSONObject entry=entries.getJSONObject(i);
                int id=entry.getInt("id");
                String ar=entry.getString("ar").trim();
                String fr=entry.optString("fr",ar).trim();
                if(id>0&&id<1000&&ar.length()>1) cities.add(new City(id,ar,fr));
            }
            if(cities.size()>=100)return Collections.unmodifiableList(cities);
        }catch(Exception ignored){}
        return Collections.singletonList(new City(2,"الخميسات","Khémissat"));
    }

    private static byte[] readBounded(InputStream input,int max)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[8192];int n;
        while((n=input.read(b))>=0){
            if(n==0)continue;
            if(out.size()+n>max)throw new IllegalArgumentException("حجم الملف الرسمي غير متوقع");
            out.write(b,0,n);
        }
        return out.toByteArray();
    }
    private static String http(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        try{
            c.setConnectTimeout(6500);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","AltaPlayLauncher/0.4 (official ministry monthly data; Android TV)");
            if(c.getResponseCode()!=200)throw new IllegalStateException("HTTP "+c.getResponseCode());
            if(!c.getURL().getProtocol().equals("https"))throw new SecurityException("Insecure location");
            try(InputStream in=c.getInputStream()){
                return new String(readBounded(in,850000),StandardCharsets.UTF_8);
            }
        }finally{c.disconnect();}
    }
    private static String monthKey(YearMonth m){return m.format(MONTH);}
    private static String officialUrl(YearMonth m){return OFFICIAL+monthKey(m)+".js";}
    private File cache(YearMonth month){return new File(app.getFilesDir(),"altaplay_habous_"+monthKey(month)+".json");}
    private String fromAsset(YearMonth month){
        try(InputStream in=app.getAssets().open("altaplay_habous_"+monthKey(month)+".json")){
            return new String(readBounded(in,850000),StandardCharsets.UTF_8);
        }catch(Exception ignored){return "";}
    }
    private static JSONObject parseOfficial(String file,YearMonth expected)throws Exception{
        if(file==null||file.length()<1000||file.length()>850000)throw new IllegalArgumentException("بيانات الأوقاف غير مكتملة");
        String value=file.trim();
        // The ministry ships window.HORAIRES_MOIS.push(JSON). Unwrap without evaluating JS.
        if(!value.startsWith("{")){
            int push=value.indexOf(".push("), left=value.indexOf('{');
            int right=value.lastIndexOf('}');
            if(push<0||left<push||right<left)throw new IllegalArgumentException("صيغة جدول الوزارة تبدلت");
            value=value.substring(left,right+1);
        }
        JSONObject o=new JSONObject(value);
        if(o.getInt("annee")!=expected.getYear()||o.getInt("mois")!=expected.getMonthValue())
            throw new IllegalArgumentException("الشهر غير مطابق");
        if(!"GMT".equals(o.getString("fuseau")))throw new IllegalArgumentException("توقيت غير متوقع");
        JSONArray cols=o.getJSONArray("cols");
        String[] names={"Fajr","Chorouq","Dhuhr","Asr","Maghrib","Ishae"};
        if(cols.length()!=6)throw new IllegalArgumentException("الأعمدة ناقصة");
        for(int i=0;i<6;i++)if(!names[i].equals(cols.getString(i)))
            throw new IllegalArgumentException("ترتيب الأعمدة مختلف");
        if(o.getJSONArray("villes").length()<150)throw new IllegalArgumentException("لائحة المدن ناقصة");
        return o;
    }
    private static JSONArray findCity(JSONObject month,int cityId)throws Exception{
        JSONArray cities=month.getJSONArray("villes");
        for(int i=0;i<cities.length();i++){
            JSONObject c=cities.getJSONObject(i);
            if(c.getInt("c")==cityId) return c.getJSONArray("h");
        }
        return null;
    }
    private static Map<String,LocalTime> times(JSONObject root,YearMonth month,LocalDate date,int cityId)throws Exception{
        if(!YearMonth.from(date).equals(month))return Collections.emptyMap();
        JSONArray rows=findCity(root,cityId);
        if(rows==null||rows.length()!=month.lengthOfMonth())return Collections.emptyMap();
        JSONArray day=rows.getJSONArray(date.getDayOfMonth()-1);
        if(day.length()!=6)return Collections.emptyMap();
        Map<String,LocalTime> result=new LinkedHashMap<>();
        LocalTime previous=null;
        for(int i=0;i<6;i++){
            String raw=day.getString(i);
            if(!raw.matches("[0-2][0-9]:[0-5][0-9]"))return Collections.emptyMap();
            LocalTime time=LocalTime.parse(raw,CLOCK);
            if(previous!=null&&!time.isAfter(previous))return Collections.emptyMap();
            result.put(KEYS[i],time);
            previous=time;
        }
        return result;
    }
    private JSONObject loadedMonth(YearMonth month)throws Exception{
        File disk=cache(month);
        if(disk.isFile()&&disk.length()<850000){
            try{
                String saved=new String(Files.readAllBytes(disk.toPath()),StandardCharsets.UTF_8);
                return parseOfficial(saved,month);
            }catch(Exception ignored){}
        }
        String asset=fromAsset(month);
        return asset.isEmpty()?null:parseOfficial(asset,month);
    }
    Map<String,LocalTime> today(){
        try{
            LocalDate date=LocalDate.now(ZONE);
            YearMonth month=YearMonth.from(date);
            JSONObject source=loadedMonth(month);
            return source==null?Collections.emptyMap():times(source,month,date,selected().id);
        }catch(Exception e){return Collections.emptyMap();}
    }
    String label(){
        City city=selected();
        Map<String,LocalTime> t=today();
        if(t.isEmpty()){
            String reason=checking?"جاري تحديث الحصة الرسمية…":
                (lastError.isEmpty()?"الحصة الرسمية لهذا الشهر غير متوفرة":lastError);
            return "الأوقاف · "+city.ar+" · "+reason;
        }
        LocalTime now=LocalTime.now(ZONE);
        int[] five={0,2,3,4,5};
        for(int i:five){
            LocalTime time=t.get(KEYS[i]);
            if(time!=null&&now.isBefore(time))return "الأوقاف · "+city.ar+" · "+NAMES[i]+" "+time;
        }
        return "الأوقاف · "+city.ar+" · العشاء "+t.get("isha")+" (آخر صلاة اليوم)";
    }
    void refresh(boolean force,Runnable onChange){
        LocalDate date=LocalDate.now(ZONE);
        YearMonth month=YearMonth.from(date);
        long now=System.currentTimeMillis();
        String marker="habous_checked_"+monthKey(month);
        long last=prefs.getLong(marker,0);
        long period=today().isEmpty()?10*60_000L:12*60*60_000L;
        if(!force&&now>=last&&now-last<period)return;
        if(!fetching.compareAndSet(false,true))return;
        prefs.edit().putLong(marker,now).apply();
        checking=true;
        worker.execute(()->{
            String error="";
            try{
                String payload=http(officialUrl(month));
                JSONObject data=parseOfficial(payload,month);
                JSONArray cities=data.getJSONArray("villes");
                // Validate selected city's full month before writing cache.
                int id=selected().id;
                JSONArray days=findCity(data,id);
                if(days==null||days.length()!=month.lengthOfMonth())
                    throw new IllegalArgumentException("المدينة غير متوفرة هذا الشهر");
                for(int d=1;d<=month.lengthOfMonth();d++)
                    if(times(data,month,month.atDay(d),id).size()!=6)
                        throw new IllegalArgumentException("يوم ناقص في جدول الأوقاف");
                File temporary=File.createTempFile("habous-month-", ".tmp",app.getFilesDir());
                try{
                    try(FileOutputStream out=new FileOutputStream(temporary)){
                        out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                    }
                    try{Files.move(temporary.toPath(),cache(month).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(java.nio.file.AtomicMoveNotSupportedException e){
                        Files.move(temporary.toPath(),cache(month).toPath(),StandardCopyOption.REPLACE_EXISTING);
                    }
                }finally{temporary.delete();}
                syncCities(cities);
            }catch(Exception e){
                error=e.getMessage()==null?"تعذر الوصول إلى موقع الوزارة":e.getMessage();
                if(error.startsWith("HTTP"))error="الموقع الرسمي غير متاح";
            }
            lastError=error;
            checking=false;
            fetching.set(false);
            main.post(()->{if(onChange!=null)onChange.run();});
        });
    }
    private void syncCities(JSONArray all){
        try{
            Map<Integer,City> existing=new HashMap<>();
            for(City c:directory)existing.put(c.id,c);
            List<City> updated=new ArrayList<>();
            JSONArray rows=new JSONArray();
            for(int i=0;i<all.length();i++){
                JSONObject j=all.getJSONObject(i);
                int id=j.getInt("c");
                String name=j.getString("n").trim();
                if(id<1||id>999||name.isEmpty())continue;
                City old=existing.get(id);
                City c=new City(id,name,old==null?name:old.fr);
                updated.add(c);
                rows.put(new JSONObject().put("id",id).put("ar",c.ar).put("fr",c.fr));
            }
            if(updated.size()<150)return;
            directory=Collections.unmodifiableList(updated);
            prefs.edit().putString("habous_cities_live",new JSONObject()
                .put("source",OFFICIAL).put("cities",rows).toString()).apply();
        }catch(Exception ignored){}
    }
}
