package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opt-in Android accessibility forwarding: receives remote keys only while
 * a user-confirmed HDMI activity package is foreground, never on system UI
 * and never on unrelated apps. A TV may handle HDMI keys below Android's
 * accessibility layer; in that case physical hardware verification is required.
 *
 * This service DOES NOT enable itself. The user must explicitly enable it
 * in Android TV Settings > Accessibility and configure HDMI package name.
 */
public final class AltaPlayHdmiRemoteService extends AccessibilityService {
    private String activePackage="";
    private SharedPreferences prefs;

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        prefs=getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE);
        AccessibilityServiceInfo info=getServiceInfo();
        if(info!=null){
            info.flags|=AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        ZenClient.get(this).start();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null)return;
        int type=event.getEventType();
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
           type==AccessibilityEvent.TYPE_WINDOWS_CHANGED){
            CharSequence p=event.getPackageName();
            if(p!=null)activePackage=p.toString();
        }
    }
    @Override public void onInterrupt(){activePackage="";}

    @Override protected boolean onKeyEvent(KeyEvent e){
        if(prefs==null)return false;
        if(!prefs.getBoolean("hdmi_remote_enabled",false))return false;
        String expected=prefs.getString("hdmi_package","").trim();
        if(expected.isEmpty()||!expected.equals(activePackage))return false;
        int code=e.getKeyCode();
        if(code==KeyEvent.KEYCODE_HOME||code==KeyEvent.KEYCODE_POWER||
           code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN||
           code==KeyEvent.KEYCODE_VOLUME_MUTE||code==KeyEvent.KEYCODE_TV_INPUT)
            return false;
        if(code!=KeyEvent.KEYCODE_PROG_GREEN && AltaPlayRemoteKeys.zenCode(this,code)<0)
            return false;
        // Consume both key-down and key-up to prevent duplicate Android actions;
        // send a single Zen command on key-down and ignore key-repeat.
        if(e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0)
            AltaPlayRemoteKeys.handle(this,code);
        return true;
    }
}
