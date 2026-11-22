package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit user-only imports. Never download when launcher merely loses Zen. */
final class ZenManualLogo {
    interface Completed { void done(boolean ok); }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(
        r->new Thread(r,"Zen4-manual-logo"));
    private ZenManualLogo(){}

    static void importUrl(Activity activity,String programId,String raw,Completed completed){
        if(!programId.matches("[0-9]{1,20}")){completed.done(false);return;}
        String url=raw==null?"":raw.trim();
        if(!(url.startsWith("https://")||url.startsWith("http://")||
             url.startsWith("ftp://"))){
            Toast.makeText(activity,"استعمل HTTPS أو HTTP أو FTP",Toast.LENGTH_LONG).show();
            completed.done(false);return;
        }
        IO.execute(()->{
            boolean ok=false;
            File target=new File(new File(activity.getFilesDir(),"zen-logos"),programId+".png");
            try{
                URLConnection connection=new URL(url).openConnection();
                connection.setConnectTimeout(5000);connection.setReadTimeout(7000);
                if(connection instanceof HttpURLConnection){
                    HttpURLConnection http=(HttpURLConnection)connection;
                    http.setInstanceFollowRedirects(false);
                    if(http.getResponseCode()!=200)throw new java.io.IOException("HTTP "+http.getResponseCode());
                }
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=connection.getInputStream()){
                    byte[] buffer=new byte[8192];int n,total=0;
                    while((n=in.read(buffer))!=-1){
                        total+=n;
                        if(total>950000)throw new java.io.IOException("Logo must be below 950 KB");
                        bytes.write(buffer,0,n);
                    }
                }
                byte[] data=bytes.toByteArray();
                BitmapFactory.Options bounds=new BitmapFactory.Options();
                bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                if(bounds.outWidth<8||bounds.outHeight<8||
                   bounds.outWidth>2500||bounds.outHeight>2500)
                    throw new java.io.IOException("Invalid image dimensions");
                Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
                if(bitmap==null)throw new java.io.IOException("Invalid image format");
                int w=bitmap.getWidth(),h=bitmap.getHeight();
                double ratio=Math.min(1.0,480.0/Math.max(w,h));
                Bitmap scaled=ratio<1?Bitmap.createScaledBitmap(bitmap,
                    Math.max(1,(int)(w*ratio)),Math.max(1,(int)(h*ratio)),true):bitmap;
                File dir=target.getParentFile();
                if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("Logo directory unavailable");
                File temp=new File(dir,programId+".tmp");
                try(FileOutputStream out=new FileOutputStream(temp)){
                    if(!scaled.compress(Bitmap.CompressFormat.PNG,100,out))
                        throw new java.io.IOException("PNG conversion failed");
                    out.flush();out.getFD().sync();
                }
                if(temp.length()>512000L){temp.delete();throw new java.io.IOException("PNG too large");}
                if(!temp.renameTo(target)){temp.delete();throw new java.io.IOException("Save failed");}
                ok=true;
            }catch(Exception e){
                android.util.Log.w("ZenManualLogo","User logo import failed: "+e.getClass().getSimpleName());
            }
            final boolean success=ok;
            new Handler(Looper.getMainLooper()).post(()->{
                if(!activity.isDestroyed()){
                    Toast.makeText(activity,success?"تم حفظ لوغو القناة":"تعذر تحميل اللوغو، تحقق من الرابط",Toast.LENGTH_LONG).show();
                    completed.done(success);
                }
            });
        });
    }
}
