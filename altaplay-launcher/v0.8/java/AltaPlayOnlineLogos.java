package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * External broadcaster logos, loaded lazily ONLY for visible channels.
 * Exact validated broadcaster alias -> vetted GitHub raw PNG, HTTPS only.
 * Keeps the former real-ProgramId artwork and packaged PNG fallback.
 * Offline cached, bounded in size and reads, and no WebView or screen scraping.
 */
final class AltaPlayOnlineLogos {
    private static final ExecutorService POOL=Executors.newFixedThreadPool(2);
    private static final Set<String> IN_FLIGHT=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
    private static final ConcurrentHashMap<String,Long> FAILURES=new ConcurrentHashMap<>();
    private static volatile Map<String,String> sourceIndex;
    private static final String PREFIX="https://raw.githubusercontent.com/";
    private static final long BAD_TTL=6*60*60_000L;
    private static final long MAX_TOTAL=14*1024*1024L;
    private static final int MAX_LOGO=350_000;
    private AltaPlayOnlineLogos(){}

    static String normalized(String raw){
        if(raw==null)return "";
        return raw.toLowerCase(Locale.ROOT)
            .replace('\u00a0',' ')
            .replaceAll("(?i)\\s+(4k|uhd|fhd|hd|sd)$","")
            .replaceAll("[^\\p{L}\\p{N}]+"," ")
            .replaceAll("\\s+"," ").trim();
    }

    private static Map<String,String> index(Context ctx){
        Map<String,String> cached=sourceIndex;
        if(cached!=null)return cached;
        synchronized(AltaPlayOnlineLogos.class){
            cached=sourceIndex;
            if(cached!=null)return cached;
            Map<String,String> map=new HashMap<>();
            try(BufferedReader input=new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("altaplay_logo_urls.tsv"),StandardCharsets.UTF_8))){
                String l;
                while((l=input.readLine())!=null){
                    int tab=l.indexOf('\t');
                    if(tab<1)continue;
                    String name=normalized(l.substring(0,tab));
                    String url=l.substring(tab+1).trim();
                    if(!name.isEmpty()&&safeUrl(url))map.put(name,url);
                }
            }catch(Exception e){Log.w("AltaPlayLogos","No network index (local logos still work)");}
            sourceIndex=Collections.unmodifiableMap(map);
            return sourceIndex;
        }
    }
    private static boolean safeUrl(String u){
        return u!=null&&u.startsWith(PREFIX)
            && u.endsWith(".png")
            && u.length()<350
            && !u.contains("..") && !u.contains("?")
            && !u.contains("#");
    }
    private static String hash(String name){
        try{
            byte[] b=MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder s=new StringBuilder(32);
            for(int i=0;i<16;i++)s.append(String.format(Locale.ROOT,"%02x",b[i]&255));
            return s.toString();
        }catch(Exception e){return "fallback";}
    }
    private static File dir(Context ctx){return new File(ctx.getFilesDir(),"altaplay-online-logos");}
    private static File target(Context ctx,String key){return new File(dir(ctx),hash(key)+".png");}

    static File cached(Context ctx,String name){
        String key=normalized(name);
        if(key.isEmpty())return null;
        File image=target(ctx,key);
        if(image.isFile()&&image.length()>100&&image.length()<=MAX_LOGO){
            image.setLastModified(System.currentTimeMillis());
            return image;
        }
        return null;
    }
    static void fetchForVisible(Activity a,String channel,ImageView img){
        final String normalized=normalized(channel);
        String source=index(a).get(normalized);
        if(source==null) return;
        File cached=cached(a,channel);
        if(cached!=null){putImage(a,img,cached);return;}
        Long failedAt=FAILURES.get(normalized);
        if(failedAt!=null && System.currentTimeMillis()-failedAt<BAD_TTL)return;
        if(!IN_FLIGHT.add(normalized))return;
        Context app=a.getApplicationContext();
        POOL.execute(()->{
            File result=null;
            try{
                File d=dir(app);
                if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Logo cache unavailable");
                URL url=new URL(source);
                HttpURLConnection connection=(HttpURLConnection)url.openConnection();
                try{
                    connection.setConnectTimeout(5500);connection.setReadTimeout(6000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent","AltaPlayLauncher/0.8");
                    if(connection.getResponseCode()!=200)throw new IOException("Logo HTTP status");
                    String mime=connection.getContentType();
                    if(mime!=null&&!mime.toLowerCase(Locale.ROOT).contains("image/"))
                        throw new IOException("Not an image");
                    File tmp=new File(d,hash(normalized)+".tmp");
                    try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(tmp)){
                        byte[] bytes=new byte[4096];int len,total=0;
                        while((len=in.read(bytes))!=-1){
                            total+=len;
                            if(total>MAX_LOGO)throw new IOException("Logo too large");
                            out.write(bytes,0,len);
                        }
                    }
                    BitmapFactory.Options probe=new BitmapFactory.Options();
                    probe.inJustDecodeBounds=true;
                    BitmapFactory.decodeFile(tmp.getAbsolutePath(),probe);
                    if(probe.outWidth<8||probe.outHeight<8||
                       probe.outWidth>2000||probe.outHeight>2000)throw new IOException("Invalid logo bounds");
                    File target=target(app,normalized);
                    if(!tmp.renameTo(target))throw new IOException("Logo cache rename failed");
                    result=target;
                }finally{connection.disconnect();}
                trimCache(d);
                FAILURES.remove(normalized);
            }catch(Exception e){FAILURES.put(normalized,System.currentTimeMillis());}
            finally{
                IN_FLIGHT.remove(normalized);
                if(result!=null){
                    File delivered=result;
                    a.runOnUiThread(()->{
                        if(!a.isDestroyed() && channel.equals(img.getTag()))
                            putImage(a,img,delivered);
                    });
                }
            }
        });
    }
    private static void putImage(Activity a,ImageView target,File file){
        try{
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inSampleSize=1;
            Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            if(bitmap!=null){
                target.setImageBitmap(bitmap);
                // PNGs contain transparent pixels. The fallback channel text
                // must be hidden, otherwise it bleeds through the logo.
                if(target.getParent() instanceof android.widget.FrameLayout){
                    android.widget.FrameLayout frame=(android.widget.FrameLayout)target.getParent();
                    if(frame.getChildCount()>0)frame.getChildAt(0).setVisibility(android.view.View.INVISIBLE);
                }
                target.setScaleType(ImageView.ScaleType.FIT_CENTER);
                target.setContentDescription("شعار القناة: "+target.getTag());
            }
        }catch(Exception ignored){}
    }
    private static void trimCache(File directory){
        File[] files=directory.listFiles((dir,n)->n.endsWith(".png"));
        if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        long bytes=0;for(File f:files)bytes+=f.length();
        for(File f:files){
            if(bytes<=MAX_TOTAL)break;
            bytes-=f.length();f.delete();
        }
    }
}
