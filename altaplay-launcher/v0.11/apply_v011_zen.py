#!/usr/bin/env python3
"""AltaPlay v0.11 — LAN rediscovery, heartbeat and non-blocking Zen controls.

Apply to the already patched v0.10 upstream checkout. Never clears the user's
channel/cache files on a routine network error, nor changes HOME/accessibility.
"""
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
src=java/"ZenClient.java"
s=src.read_text(encoding="utf-8")
def once(old,new,label):
    global s
    n=s.count(old)
    if n!=1: raise RuntimeError(f"Zen v0.11 {label} anchor={n}: {old[:120]!r}")
    s=s.replace(old,new,1)

once('    private final Object writeLock=new Object();',
'''    private final java.util.concurrent.atomic.AtomicBoolean connecting=
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean discovering=
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ExecutorService discoveryWorker=Executors.newSingleThreadExecutor(
        r->new Thread(r,"Zen4-discovery"));
    private volatile long lastReplyAt, lastDiscoveryAt;
    private volatile String lastError="Not connected";
    private final Object writeLock=new Object();''',"new synchronization fields")

once('    private static String identity(byte[] raw) {',
     '    static String identity(byte[] raw) {',"identity visible for verified discovery")

once('    private void setStatus(String s,boolean connected){status=s;online=connected;notifyUi();}',
'''    private void setStatus(String s,boolean connected){
        boolean changed=online!=connected || !status.equals(s);
        status=s;online=connected;
        if(changed)notifyUi();
    }''',"reduce UI updates")

once('''    void start() {
        if(running)return;
        running=true;
        scheduler.scheduleWithFixedDelay(()->{
            if(!online && ZenProtocol.privateAddress(host()))worker.execute(this::connect);
        },0,12,TimeUnit.SECONDS);
    }''',
'''    void start() {
        if(running)return;
        running=true;
        scheduler.scheduleWithFixedDelay(()->{
            if(online)return;
            if(ZenProtocol.privateAddress(host()))worker.execute(this::connect);
            long now=android.os.SystemClock.elapsedRealtime();
            if(lastDiscoveryAt==0 || now-lastDiscoveryAt>=60000L)discoverNow();
        },0,12,TimeUnit.SECONDS);
        // Real socket liveness: an open TCP socket is not proof the box is awake.
        // Allow long pauses during receiver channel/satellite operations.
        scheduler.scheduleWithFixedDelay(()->{
            if(!online)return;
            long now=android.os.SystemClock.elapsedRealtime();
            if(lastReplyAt>0 && now-lastReplyAt>75000L){
                lastError="Zen no response for over 75 seconds";
                closeActive();
                setStatus("Zen 4 · لا يستجيب · البحث وإعادة الاتصال",false);
                return;
            }
            try{send(ZenProtocol.state(3,jsonMode,0,0));}
            catch(Exception ex){
                lastError="Heartbeat failed: "+ex.getClass().getSimpleName();
                closeActive();
                setStatus("Zen 4 · انقطع الاتصال · إعادة المحاولة",false);
            }
        },20,20,TimeUnit.SECONDS);
    }

    void reconnectNow(){
        worker.execute(()->{closeActive();connect();});
    }
    void discoverNow(){
        if(online || !discovering.compareAndSet(false,true))return;
        lastDiscoveryAt=android.os.SystemClock.elapsedRealtime();
        discoveryWorker.execute(()->{
            try{
                String uuid=prefs.getString("uuid","");
                if(uuid.isEmpty()){uuid=UUID.randomUUID().toString();prefs.edit().putString("uuid",uuid).apply();}
                final String found=ZenLanDiscovery.scan(prefs.getString("receiver_id",""),uuid);
                if(found!=null && !online){
                    if(!found.equals(host())){
                        prefs.edit().putString("zen_ip",found).apply();
                    }
                    lastError="Receiver found on local network";
                    worker.execute(this::connect);
                }else if(found==null && !online){
                    lastError="Zen 4 not found in local network; verify Wi-Fi, power and LAN";
                    notifyUi();
                }
            }finally{discovering.set(false);}
        });
    }
    String diagnostics(){
        long seconds=lastReplyAt==0 ? -1:
            (android.os.SystemClock.elapsedRealtime()-lastReplyAt)/1000;
        return (online?"Connected":"Disconnected")+" · Zen 4"
            +"\\nIP: "+(host().isEmpty()?"Not configured":host())
            +"\\nLast response: "+(seconds<0?"Never":seconds+" s")
            +"\\nScanning LAN: "+(discovering.get()?"Yes":"No")
            +"\\nLast issue: "+lastError
            +"\\nChannels cached: "+channels().size()+"/"+total;
    }
    void refreshCatalogue(){
        synchronized(catalogueLock){nextIndex=0;requestedTo=-1;}
        if(online)worker.execute(this::requestPage);
    }''',"autodiscovery and heartbeat")

once('''    private void connect(){
        if(online)return;''',
'''    private void connect(){
        if(online || !connecting.compareAndSet(false,true))return;''',"one connection attempt at a time")

start=s.index("    private void connect(){")
end=s.index("    private void send(",start)
method=s[start:end]
if not method.endswith("        }\n    }\n"):
    raise RuntimeError("Cannot locate end of Zen connect()")
method=method[:-len("        }\n    }\n")]+'''            lastError=e.getClass().getSimpleName()
                +((e.getMessage()!=null&&e.getMessage().length()<80)?": "+e.getMessage():"");
        }finally{
            connecting.set(false);
        }
    }
'''
# body above adds lastError inside catch immediately before closing bracket.
method=method.replace('active=s;s.setSoTimeout(0);',
     'active=s;s.setSoTimeout(0);\n            lastReplyAt=android.os.SystemClock.elapsedRealtime();\n            lastError="Connected";')
s=s[:start]+method+s[end:]

once('''                ZenProtocol.Response r=ZenProtocol.readResponse(session.getInputStream());
''',
'''                ZenProtocol.Response r=ZenProtocol.readResponse(session.getInputStream());
                lastReplyAt=android.os.SystemClock.elapsedRealtime();
''',"socket frame response timestamp")

# Make the UI entirely independent from transient receiver connection status.
# Channel tiles are retained; status pill updated independently by render().
# Connection status must not trigger full re-creation of hundreds of image views.
panel=java/"AltaPlayZenPanel.java"
p=panel.read_text(encoding="utf-8")
old='if(sign==renderedSignature&&status.equals(renderedStatus))return;'
assert p.count(old)==1
p=p.replace(old,'if(sign==renderedSignature)return; // keep channel cards and keyboard focus on network changes',1)
panel.write_text(p,encoding="utf-8")

src.write_text(s,encoding="utf-8")
shutil.copyfile(Path(__file__).parent/"java/ZenLanDiscovery.java",java/"ZenLanDiscovery.java")
print("PASS v0.11 automatic same-receiver LAN rediscovery, 75s heartbeat, no offline flicker")
