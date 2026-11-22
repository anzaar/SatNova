#!/usr/bin/env python3
"""When BOTH receiver shelf and controls are hidden, pause its LAN work.

Independent visual toggles already live in apply_v011_receiver_visibility.py.
Preserve all receiver IDs, logos, favorite/channel cache and accessibility
preferences, with automatic reconnect when the receiver feature is shown again.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
client=java/"ZenClient.java"; panel=java/"AltaPlayZenPanel.java"
service=java/"AltaPlayHdmiRemoteService.java"

def replace(s,a,b,label):
    n=s.count(a)
    if n!=1:raise RuntimeError(f"receiver idle {label}: anchor count {n} {a[:90]!r}")
    return s.replace(a,b,1)

s=client.read_text(encoding="utf-8")
s=replace(s,'''    boolean isOnline(){return online;}''',
'''    boolean isOnline(){return online;}
    boolean receiverFeatureActive(){
        android.content.SharedPreferences visible=
            context.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE);
        return visible.getBoolean("receiver_show_channels",true) ||
               visible.getBoolean("receiver_show_button",true);
    }
    void onReceiverVisibilityChanged(){
        if(!receiverFeatureActive()){
            // Prevent further work immediately; stale callbacks can finish but
            // cannot open another session while both options are hidden.
            worker.execute(()->{
                if(receiverFeatureActive())return;
                closeActive();
                setStatus("Receiver hidden",false);
            });
        }else{
            start();
            if(!online){
                if(ZenProtocol.privateAddress(host()))reconnectNow();
                else discoverNow();
            }
        }
    }''',
"receiver activation gate")
s=replace(s,'''        scheduler.scheduleWithFixedDelay(()->{
            if(online)return;''',
'''        scheduler.scheduleWithFixedDelay(()->{
            if(!receiverFeatureActive() || online)return;''',
"reconnect scheduler pause")
s=replace(s,'''        scheduler.scheduleWithFixedDelay(()->{
            if(!online)return;''',
'''        scheduler.scheduleWithFixedDelay(()->{
            if(!receiverFeatureActive() || !online)return;''',
"heartbeat scheduler pause")
s=replace(s,'''    void reconnectNow(){
        worker.execute(()->{closeActive();connect();});
    }''',
'''    void reconnectNow(){
        if(!receiverFeatureActive())return;
        worker.execute(()->{
            if(!receiverFeatureActive())return;
            closeActive();connect();
        });
    }''',
"no manual reconnect in hidden mode")
s=replace(s,'''    void discoverNow(){
        if(online || !discovering.compareAndSet(false,true))return;''',
'''    void discoverNow(){
        if(!receiverFeatureActive() || online ||
           !discovering.compareAndSet(false,true))return;''',
"no scan in hidden mode")
s=replace(s,'''                if(found!=null && !online){''',
'''                if(found!=null && !online && receiverFeatureActive()){''',
"ignore delayed LAN scan when feature hidden")
s=replace(s,'''                }else if(found==null && !online){''',
'''                }else if(found==null && !online && receiverFeatureActive()){''',
"no hidden UI scan status")
s=replace(s,'''    private void connect(){
        if(online || !connecting.compareAndSet(false,true))return;''',
'''    private void connect(){
        if(!receiverFeatureActive() || online ||
           !connecting.compareAndSet(false,true))return;''',
"connect guard")
s=replace(s,'''            active=s;s.setSoTimeout(0);''',
'''            if(!receiverFeatureActive())throw new IOException("Receiver feature disabled");
            active=s;s.setSoTimeout(0);''',
"abort in-flight handshake")
client.write_text(s,encoding="utf-8")

s=service.read_text(encoding="utf-8")
s=replace(s,'''        if(prefs==null)return false;''',
'''        if(prefs==null)return false;
        // Hiding BOTH sections means the TV remote stays owned by Android.
        // Do not alter the user's previous HDMI remote enabling preference.
        if(!ZenClient.get(this).receiverFeatureActive())return false;''',
"accessibility remote gate")
service.write_text(s,encoding="utf-8")

s=panel.read_text(encoding="utf-8")
s=replace(s,'''                // Rebuild only the local UI, not data, receiver or Android HOME.
                activity.recreate();''',
'''                // Pause or restore LAN clients without deleting any saved data.
                client.onReceiverVisibilityChanged();
                // Update only the visible receiver area: a full Activity
                // recreation would re-decode wallpaper and flash the Home.
                if(receiverShelf!=null)
                    receiverShelf.setVisibility(showReceiverChannels()?View.VISIBLE:View.GONE);
                if(zenPill!=null){
                    zenPill.setVisibility(showReceiverButton()?View.VISIBLE:View.GONE);
                    zenPill.setFocusable(showReceiverButton());
                }
                if(showReceiverChannels()){
                    renderedSignature=Integer.MIN_VALUE;render();
                }
                // The Settings entry always remains, even if both are hidden.
                View settingsEntry=activity.findViewById(R.id.greeting);
                if(settingsEntry!=null && settingsEntry.isFocusable())
                    settingsEntry.requestFocus();''',
"pause background and update receiver views without black-screen recreation")
panel.write_text(s,encoding="utf-8")
print("PASS hidden receiver both controls -> no LAN polling, heartbeat or remote capture; instant UI update without full Activity recreate")
