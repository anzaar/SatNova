#!/usr/bin/env python3
"""v0.10: finish Zen tune network WRITE before opening the hardware HDMI input.
Preserve live remote bridge, local channel cache and both launcher themes.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
client=java/"ZenClient.java"
panel=java/"AltaPlayZenPanel.java"
assert client.is_file() and panel.is_file(), "First apply base + v0.9 source overlays"

def once(src,old,new,tag):
    count=src.count(old)
    if count!=1:
        raise RuntimeError(tag+" anchor count="+str(count))
    return src.replace(old,new,1)

s=client.read_text(encoding="utf-8")
s=once(s,
    "    interface Listener { void changed(); }",
    """    interface Listener { void changed(); }
    /** Runs on Android main thread, only once the request 1000 bytes have
     * successfully been written to the authenticated Zen socket. This is NOT
     * a receiver channel-change acknowledgement. */
    interface TuneListener { void onCommandWritten(boolean sent); }""",
    "tune listener")
s=once(s,
    '    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"Zen4-control"));',
    '''    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"Zen4-control"));
    // Physical TV can launch HDMI immediately; channel commands must not sit
    // behind the large satellite catalogue paging queue on worker.
    private final ExecutorService tuneWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"Zen4-tune"));''',
    "dedicated tune executor")
s=once(s,
    '''    void tune(Channel item){worker.execute(()->{
        try{send(ZenProtocol.tune(item.id,item.tvState,jsonMode));}
        catch(Exception e){setStatus("Zen 4 غير متصل • تعذّر تغيير القناة",false);closeActive();}
    });}''',
    '''    void tune(Channel item,TuneListener listener){
        if(item==null || !online){
            if(listener!=null)ui.post(()->listener.onCommandWritten(false));
            return;
        }
        // Do not open the HDMI viewer until the command has been dispatched.
        // Unlike catalogue loads, tuning is latency-critical for remote UX.
        tuneWorker.execute(()->{
            boolean success=false;
            try{
                send(ZenProtocol.tune(item.id,item.tvState,jsonMode));
                success=true;
                android.util.Log.i("AltaPlayZenTune",
                    "request1000 written, program="+item.id+" tvState="+item.tvState);
            }catch(Exception e){
                android.util.Log.w("AltaPlayZenTune",
                    "request1000 send failed: "+e.getClass().getSimpleName());
                setStatus("Zen 4 غير متصل • تعذّر تغيير القناة",false);
                closeActive();
            }
            final boolean sent=success;
            if(listener!=null)ui.post(()->listener.onCommandWritten(sent));
            // Query current service after tune; helps diagnose firmware
            // rejecting an unknown ProgramId without opening a second stream.
            if(sent){
                scheduler.schedule(()->{
                    try{send(ZenProtocol.state(3,jsonMode,0,0));}
                    catch(Exception e){
                        android.util.Log.w("AltaPlayZenTune","state3 query failed");
                    }
                },400,TimeUnit.MILLISECONDS);
            }
        });
    }''',
    "tune-before-HDMI")
s=once(s,
    '                if(r.status!=0 || r.body.isEmpty())continue;',
    '''                if(r.kind==1000 && r.status!=0)
                    android.util.Log.w("AltaPlayZenTune","receiver rejected tune, status="+r.status);
                if(r.kind==3 && r.status==0 && !r.body.isEmpty()){
                    List<JSONObject> stateItems=records(r.body);
                    JSONObject state=stateItems.isEmpty()?null:stateItems.get(0);
                    String playing=get(state,"Data","ProgramId","ServiceID");
                    if(!playing.isEmpty())
                        android.util.Log.i("AltaPlayZenTune","current program="+playing);
                }
                if(r.status!=0 || r.body.isEmpty())continue;''',
    "receiver confirmation logging")
s=once(s,
    'String id=get(o,"ServiceID","ProgramId","ProgramID");',
    'String id=get(o,"ProgramId","ProgramID","ServiceID");',
    "prefer receiver ProgramId")
client.write_text(s,encoding="utf-8")

s=panel.read_text(encoding="utf-8")
start=s.index("    private void tune(ZenClient.Channel item){")
end=s.index("    private GradientDrawable tileBackground(",start)
source=s[start:end]
if "client.tune(item);" not in source or "AltaPlayHdmi1.open(activity)" not in source:
    raise RuntimeError("Unexpected v0.9 tune implementation")
s=s[:start]+'''    private void tune(ZenClient.Channel item){
        // The old code opened HW5 before the asynchronous request 1000 was
        // actually dispatched. Keep the working HDMI bridge and remote intact.
        client.tune(item, sent->{
            if(activity.isFinishing()||activity.isDestroyed())return;
            if(!sent){
                Toast.makeText(activity,"Zen 4: لم يصل أمر تغيير القناة",
                    Toast.LENGTH_LONG).show();
                return;
            }
            if(!AltaPlayHdmi1.open(activity)){
                Toast.makeText(activity,
                    "تعذر فتح HDMI 1 تلقائياً · استعمل زر مصادر الإدخال",
                    Toast.LENGTH_LONG).show();
            }
        });
    }
'''+s[end:]
panel.write_text(s,encoding="utf-8")
print("PASS v0.10: Zen request1000 dispatched ahead of HDMI, connection errors surfaced, receiver state logged")
