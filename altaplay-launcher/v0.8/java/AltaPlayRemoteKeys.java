package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Android TV key -> existing proven ZenClient.key(...) protocol.
 * Green is Shahid direct link, as verified on the Lifemaxx installation.
 * G-MScreen AUDIO candidate 54 comes from a third-party compatible receiver,\n * not yet proven on the user's Zen 4. The user can override/disable it.
 */
final class AltaPlayRemoteKeys {
    private AltaPlayRemoteKeys(){}
    private static int audioCode(Context c){
        return c.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE)
            .getInt("zen_audio_key",54);
    }
    static boolean handle(Context context,int androidKey){
        if(androidKey==KeyEvent.KEYCODE_PROG_GREEN){
            openShahid(context);
            return true;
        }
        int code=zenCode(context,androidKey);
        if(code<0)return false;
        ZenClient client=ZenClient.get(context);
        if(!client.isOnline()){
            Toast.makeText(context,"Zen 4 غير متصل",Toast.LENGTH_SHORT).show();
            return true;
        }
        client.key(code);
        return true;
    }
    static int zenCode(Context c,int key){
        switch(key){
            case KeyEvent.KEYCODE_DPAD_UP:return 1;
            case KeyEvent.KEYCODE_DPAD_DOWN:return 2;
            case KeyEvent.KEYCODE_DPAD_LEFT:return 3;
            case KeyEvent.KEYCODE_DPAD_RIGHT:return 4;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:return 5;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_PROG_RED:return 6;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_PROG_YELLOW:return 7;
            case KeyEvent.KEYCODE_CHANNEL_UP:return 69;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:return 70;
            case KeyEvent.KEYCODE_GUIDE:return 32;
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:return audioCode(c);
            default:return -1;
        }
    }
    static boolean colored(int key){
        return key==KeyEvent.KEYCODE_PROG_RED ||
            key==KeyEvent.KEYCODE_PROG_GREEN ||
            key==KeyEvent.KEYCODE_PROG_YELLOW ||
            key==KeyEvent.KEYCODE_PROG_BLUE;
    }
    static void openShahid(Context context){
        Intent link=new Intent(Intent.ACTION_VIEW,Uri.parse("shahid://"));
        link.addCategory(Intent.CATEGORY_BROWSABLE);
        link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{
            context.startActivity(link);
            return;
        }catch(Exception ignored){}
        try{
            Intent app=context.getPackageManager().getLeanbackLaunchIntentForPackage("net.mbc.shahidTV");
            if(app==null)app=context.getPackageManager().getLaunchIntentForPackage("net.mbc.shahidTV");
            if(app!=null){app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(app);return;}
        }catch(Exception ignored){}
        Toast.makeText(context,"تعذر فتح شاهد · تأكد أن التطبيق مثبت",Toast.LENGTH_LONG).show();
    }
}
