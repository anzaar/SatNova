package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Offline logos sourced from authentic broadcaster logo PNGs.
 * Uses exact whole-channel-name aliases, NOT frequency/SID alone: SID collides
 * across different satellites and transponders, and Zen ProgramId is local.
 * User-imported logos by real ProgramId always take precedence in ZenClient.
 */
final class AltaPlayChannelLogos {
    private AltaPlayChannelLogos(){}

    private static String key(String raw){
        if(raw==null)return "";
        String n=raw.toLowerCase(Locale.ROOT).replace('\u00a0',' ')
            .replace('_',' ').replaceAll("\\s+"," ").trim();
        // Suffix only, never remove "HD" from inside a genuine name.
        return n.replaceAll("(?i)\\s+(4k|uhd|fhd|hd|sd)$","").trim();
    }
    private static String asset(String name){
        String n=key(name);
        if(n.equals("al aoula") || n.equals("al aoula inter") ||
           n.equals("الأولى") || n.equals("الاولى") || n.equals("al-aoula"))
            return "al-aoula";
        if(n.equals("arryadia") || n.equals("الرياضية") || n.equals("arryadia 3"))
            return "arryadia";
        if(n.equals("mbc") || n.equals("mbc 1") || n.equals("mbc1"))return "mbc1";
        if(n.equals("mbc 2") || n.equals("mbc2"))return "mbc2";
        if(n.equals("mbc 3") || n.equals("mbc3"))return "mbc3";
        if(n.equals("mbc 4") || n.equals("mbc4"))return "mbc4";
        if(n.equals("mbc action"))return "mbc-action";
        if(n.equals("mbc max"))return "mbc-max";
        if(n.equals("aljazeera mubasher") || n.equals("al jazeera mubasher")
           || n.equals("الجزيرة مباشر"))return "aljazeera-mubasher";
        if(n.equals("aljazeera channel") || n.equals("aljazeera") ||
           n.equals("al jazeera") || n.equals("aljazeera english") ||
           n.equals("aljazeera documentary") || n.equals("الجزيرة") ||
           n.equals("الجزيرة الوثائقية"))
            return "aljazeera"; // Shared parent broadcaster brand.
        return null;
    }
    static File fromName(Context context,String channelName){
        String safe=asset(channelName);
        if(safe==null)return null;
        File cache=new File(context.getCacheDir(),"altaplay-logos");
        File target=new File(cache,safe+".png");
        if(target.isFile()&&target.length()>100&&target.length()<500_000)return target;
        try{
            if(!cache.isDirectory()&&!cache.mkdirs())return null;
            try(InputStream input=context.getAssets().open("altaplay_channel_logos/"+safe+".png");
                FileOutputStream output=new FileOutputStream(target)){
                byte[] buffer=new byte[8192];int n,total=0;
                while((n=input.read(buffer))!=-1){
                    total+=n;
                    if(total>500_000)throw new IllegalStateException("Logo too large");
                    output.write(buffer,0,n);
                }
            }
            if(target.length()<100)return null;
            return target;
        }catch(Exception e){
            target.delete();
            return null;
        }
    }
}
