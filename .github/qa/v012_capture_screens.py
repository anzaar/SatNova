#!/usr/bin/env python3
"""Capture Google TV 14 emulator screenshots for AltaPlay v0.12 (no receiver).

Real Google TV 14 emulator QA. Navigation is driven by virtual remote DPAD,
HOME, BACK and ENTER keys; screenshots and UI hierarchy infer results. Zen is
a local protocol simulator; this does not certify real HDMI hardware.
"""
import os, re, struct, zlib, time, hashlib, subprocess
from pathlib import Path
from xml.etree import ElementTree as ET

OUT=Path("qa-results/v012-gallery")
OUT.mkdir(parents=True,exist_ok=True)
REPORT=[]
PKG="com.altaplay.launcher"

def cmd(*args,timeout=70,check=False,stdin=None):
    try:
        p=subprocess.run(list(args), input=stdin, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT,timeout=timeout,check=check)
        text=p.stdout.decode("utf-8","replace")
        if p.returncode:REPORT.append("COMMAND ERR "+str(p.returncode)+" "+repr(args)+" "+text[-320:])
        return text
    except Exception as exc:
        REPORT.append("COMMAND EXCEPTION "+repr(args)+": "+repr(exc))
        return ""

def adb(*args,timeout=70):
    return cmd("adb",*args,timeout=timeout)

def shot(name):
    p=subprocess.run(["adb","exec-out","screencap","-p"],stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE,timeout=35)
    if p.returncode==0 and p.stdout.startswith(b"\x89PNG"):
        f=OUT/(name+".png")
        f.write_bytes(p.stdout)
        REPORT.append("SCREENSHOT "+f.name+" "+str(len(p.stdout))+" bytes")
        return True
    REPORT.append("SCREENSHOT FAILED "+name+" "+p.stderr.decode("utf8","replace")[:120])
    return False

def tree(label):
    adb("shell","uiautomator","dump","/sdcard/v012-qa-window.xml",timeout=55)
    xml=adb("exec-out","cat","/sdcard/v012-qa-window.xml",timeout=30)
    xml=xml[xml.find("<?xml"):] if "<?xml" in xml else ""
    if not xml:return None
    (OUT/(label+".xml")).write_text(xml)
    try:return ET.fromstring(xml)
    except Exception as e:REPORT.append("XML FAILED "+repr(e));return None

def find(root,keys,attrs=("text","content-desc","resource-id"),clickable=False):
    if root is None:return None
    nodes=root.findall(".//node")
    for key in keys:
        for n in nodes:
            if clickable and n.get("clickable")!="true":continue
            if any(
                key.casefold() == (n.get(a) or "").strip().casefold()
                or (a == "text" and (n.get(a) or "").strip().casefold().startswith(key.casefold() + " "))
                or (a == "resource-id" and key.casefold() in (n.get(a) or "").casefold())
                for a in attrs
            ):
                bounds=n.get("bounds","")
                if re.match(r"\[\d+,\d+\]\[\d+,\d+\]",bounds):return n
    return None

def xy(node):
    b=list(map(int,re.findall(r"\d+",node.get("bounds",""))))
    return ((b[0]+b[2])//2,(b[1]+b[3])//2)

def click(root,keys,clickable=False):
    n=find(root,keys,clickable=clickable)
    if n is None:
        REPORT.append("NOT FOUND "+repr(keys))
        return False
    x,y=xy(n);adb("shell","input","tap",str(x),str(y));time.sleep(0.8)
    REPORT.append("TAP "+repr(keys)+" @ "+str((x,y))+" node="+str(n.attrib))
    return True

def long_click(root,keys):
    n=find(root,keys,clickable=True)
    if n is None:
        REPORT.append("LONGPRESS NOT FOUND "+repr(keys))
        return False
    x,y=xy(n);adb("shell","input","swipe",str(x),str(y),str(x),str(y),"1350")
    time.sleep(0.8);REPORT.append("LONGPRESS "+repr(keys)+" @ "+str((x,y)));return True

def back(n=1):
    for _ in range(n):adb("shell","input","keyevent","KEYCODE_BACK");time.sleep(0.5)

def key(name,delay=0.65):
    adb("shell","input","keyevent",name);time.sleep(delay)

def remote_sequence(label,keys,delay=0.7):
    REPORT.append("REMOTE SEQUENCE "+label+" "+repr(keys))
    for k in keys:key(k,delay)
    shot(label)
    return tree(label)

def focus_report(label,root):
    if root is None:
        REPORT.append("FOCUS "+label+": hierarchy unavailable");return
    focused=[n.attrib for n in root.findall(".//node") if n.get("focused")=="true"]
    REPORT.append("FOCUS "+label+": "+repr(focused[:8]))

def launch():
    adb("shell","am","start","-W","-n",PKG+"/com.gathin.tvhome.MainActivity",timeout=70)
    time.sleep(2)

def open_zen(label):
    root=tree(label+"_before")
    if click(root,["Zen 4"],clickable=True):
        time.sleep(0.3)
        shot(label)
        return tree(label+"_menu")
    return None

def png(path,r,g,b):
    # Tiny labeled-by-filename sample wallpapers. Flat distinct colored test
    # backgrounds make before/after assertion objective; not production art.
    w,h=960,540
    rows=[]
    for y in range(h):
        row=bytearray()
        for x in range(w):
            row.extend((max(0,min(255,r+(x-y)//22)),
                        max(0,min(255,g+(y//18))),
                        max(0,min(255,b+(x//26)))))
        rows.append(b"\0"+bytes(row))
    data=zlib.compress(b"".join(rows),6)
    def chunk(typ,body):return struct.pack(">I",len(body))+typ+body+struct.pack(">I",zlib.crc32(typ+body)&0xffffffff)
    path.write_bytes(b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",w,h,8,2,0,0,0))+chunk(b"IDAT",data)+chunk(b"IEND",b""))

REPORT.append("Emulator model: "+adb("shell","getprop","ro.product.model").strip())
REPORT.append("TV version: "+adb("shell","getprop","ro.build.version.release").strip())
apk="qa-results/AltaPlay-Launcher-v0.12-debug.apk"
install_output=adb("install","-r","-t",apk,timeout=90).strip()
REPORT.append("INSTALL: Success" if re.search(r"(?m)^Success\s*$",install_output) else "INSTALL: FAIL "+install_output.replace("\n"," | "))

# Provision only this disposable CI emulator. Google TV's first-boot wizard
# otherwise intercepts HOME and prevents the launcher journey from starting.
adb("wait-for-device",timeout=120)
for _ in range(90):
    if adb("shell","getprop","sys.boot_completed").strip()=="1": break
    time.sleep(2)
REPORT.append("BOOT COMPLETED: "+adb("shell","getprop","sys.boot_completed").strip())
for namespace,keyname,value in [
    ("global","device_provisioned","1"),
    ("secure","user_setup_complete","1"),
]:
    out=adb("shell","settings","put",namespace,keyname,value).strip()
    REPORT.append("EMULATOR PROVISIONING "+namespace+"."+keyname+"=1 "+out)
adb("shell","am","force-stop","com.google.android.tv.setupwizard")
adb("shell","am","force-stop","com.google.android.tungsten.setupwraith")
key("KEYCODE_HOME",1.5)
shot("01-offline-home-default-wallpaper")
root=tree("01-offline-ui")

# Launch AltaPlay once and walk the first-run assistant using the remote.
# The HOME-assignment step is deliberately skipped: the test must never
# silently set AltaPlay as HOME or disable Google TV on the user's behalf.
launch()
shot("02-first-run-step-1")
setup=tree("02-first-run-step-1")
if setup is not None and any("1/5" in (n.get("text") or "") for n in setup.findall(".//node")):
    if click(setup,["Français","English","العربية"]):
        time.sleep(1)
        shot("03-first-run-step-2")
        setup=tree("03-first-run-step-2")
for step in range(2,6):
    if setup is None: break
    nodes=setup.findall(".//node")
    title_text=" ".join((n.get("text") or "") for n in nodes)
    if not re.search(rf"{step}/5",title_text): break
    # Step 3 offers receiver discovery, manual IP, or a safe no-receiver path.
    # Select only the explicit no-receiver option on this disposable emulator;
    # never start network discovery or change any HOME role.
    if step == 3:
        selected=click(setup,["SANS RÉCEPTEUR","Sans récepteur","No receiver","بدون جهاز استقبال"],clickable=True)
        if not selected:
            REPORT.append("FIRST-RUN STEP 3: safe no-receiver choice not found; stopping")
            break
    else:
        selected=click(setup,["IGNORER","Ignorer","Passer","Plus tard","LATER","Skip","تجاوز دابا","تخطي"],clickable=True)
        if not selected:
            REPORT.append("FIRST-RUN STEP "+str(step)+": no safe skip control found; stopping wizard automation")
            break
    time.sleep(1.2)
    shot("0"+str(3+step)+"-first-run-step-"+str(step+1))
    setup=tree("first-run-after-"+str(step))
wizard_visible=setup is not None and any(re.search(r"\b[1-5]/5\b",(n.get("text") or "")) for n in setup.findall(".//node"))
REPORT.append("FIRST-RUN FINAL STATE: "+("wizard remains visible" if wizard_visible else "wizard not detected"))

# App-transition journey; HOME is deliberately excluded.
REPORT.append("APP TRANSITION TEST: HOME key intentionally excluded")
activity_query=adb("shell","cmd","package","query-activities","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER")
packages=[]
for line in activity_query.splitlines():
    line=line.strip()
    if "/" in line:
        pkg=line.split("/",1)[0]
        if pkg not in packages and pkg not in {PKG,"com.android.shell"}:
            packages.append(pkg)
REPORT.append("DISCOVERED LAUNCHER PACKAGES: "+repr(packages))
preferred=["com.altaplay.app","com.google.android.youtube.tv","com.android.tv"]
candidates=[p for p in preferred if p in packages]
candidates += [p for p in packages if p not in candidates
               and not p.startswith(("com.google.android.tv.setup","com.google.android.tungsten"))
               and not p.startswith(("com.android.settings","com.android.systemui"))]
tested=0
for pkg in candidates[:4]:
    REPORT.append("APP TRANSITION CANDIDATE: "+pkg)
    launch_out=adb("shell","monkey","-p",pkg,"-c","android.intent.category.LAUNCHER","1",timeout=45)
    time.sleep(2)
    foreground=adb("shell","dumpsys","activity","activities")
    is_foreground=(pkg in foreground and any(x in foreground for x in ("mResumedActivity","topResumedActivity","ResumedActivity")))
    REPORT.append("APP LAUNCH "+pkg+": "+("foreground confirmed" if is_foreground else "foreground not confirmed")+"; "+launch_out[-180:].replace("\\n"," | "))
    safe=re.sub(r"[^A-Za-z0-9_.-]","_",pkg)
    shot("app-"+str(tested+1)+"-"+safe+"-opened")
    app_tree=tree("app-"+str(tested+1)+"-hierarchy")
    focus_report("app "+pkg,app_tree)
    back()
    time.sleep(1.2)
    state=adb("shell","dumpsys","activity","activities")
    returned=PKG in state and any(x in state for x in ("mResumedActivity","topResumedActivity","ResumedActivity"))
    REPORT.append("BACK RETURN "+pkg+": "+("AltaPlay foreground confirmed" if returned else "AltaPlay foreground not confirmed"))
    shot("app-"+str(tested+1)+"-"+safe+"-after-back")
    tested+=1
    if tested>=2: break
REPORT.append("APP TRANSITION COUNT: "+str(tested))
if not tested: REPORT.append("APP TRANSITION RESULT: no eligible installed app; not tested")
# Verify DPAD focus changes inside AltaPlay; no HOME key events are sent.
launch()
shot("04-launcher-offline-home")
root=tree("04-launcher-offline-hierarchy")
focus_report("offline launcher initial focus",root)
for index,k in enumerate(["KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_DOWN","KEYCODE_DPAD_LEFT","KEYCODE_DPAD_UP"],1):
    key(k,0.8)
    current=tree("dpad-"+str(index))
    focused=[n.attrib for n in current.findall(".//node") if n.get("focused")=="true"] if current is not None else []
    REPORT.append("DPAD FOCUS "+k+": "+repr(focused[:3]))
    shot("dpad-"+str(index))
key("KEYCODE_BACK",0.8)
shot("09-offline-back-navigation")
launch()
shot("10-launcher-restored-after-back")
# Actual two-image wallpaper picker test: add user-selectable images to
# emulator's own app external-file collection. No installed TV is touched.
sample=Path("qa-results")
png(sample/"qa-wallpaper-forest.png",15,66,42)
png(sample/"qa-wallpaper-ocean.png",12,50,112)
dir="/sdcard/Android/data/com.altaplay.launcher/files/wallpapers"
adb("shell","mkdir","-p",dir)
adb("push",str(sample/"qa-wallpaper-forest.png"),dir+"/qa-wallpaper-forest.png")
adb("push",str(sample/"qa-wallpaper-ocean.png"),dir+"/qa-wallpaper-ocean.png")
launch()
root=tree("13-before-wallpaper-settings")
if click(root,["com.altaplay.launcher:id/btnSettings","الإعدادات","Settings"],clickable=True):
    shot("13-open-launcher-settings")
    settings=tree("13-launcher-settings")
    # Settings is a category list; enter the AltaPlay wallpapers/settings
    # category first, then inspect the actual available wallpaper controls.
    if click(settings,["خلفيات وإعدادات AltaPlay"]):
        shot("14-wallpaper-category")
        wallpaper_menu=tree("14-wallpaper-category")
        visible=[n.get("text","") for n in wallpaper_menu.findall(".//node")] if wallpaper_menu is not None else []
        REPORT.append("WALLPAPER CATEGORY ITEMS: "+repr([x for x in visible if x]))
        if click(wallpaper_menu,["اختيار الخلفية","تغيير الخلفية","الخلفية","Wallpaper","Fond d'écran"]):
            shot("14-wallpaper-picker-two-options")
        choices=tree("14-wallpaper-options")
        if click(choices,["qa-wallpaper-forest.png"]):
            back()
            launch()
            time.sleep(2)
            shot("15-wallpaper-changed-forest")
            root=tree("15-forest-ui")
            if click(root,["com.altaplay.launcher:id/btnSettings","الإعدادات","Settings"],clickable=True):
                settings=tree("15-settings-again")
                if click(settings,["wallpaperChoice","الخلفية","Wallpaper"]):
                    choices=tree("15-picker-again")
                    if click(choices,["qa-wallpaper-ocean.png"]):
                        back()
                        launch()
                        time.sleep(2)
                        shot("16-wallpaper-changed-ocean")
                        tree("16-ocean-ui")

cmd("adb","logcat","-d","-b","crash",timeout=60)
(OUT/"qa-capture-report.txt").write_text("\n".join(REPORT)+"\n",encoding="utf-8")
print("\n".join(REPORT))
print("CAPTURED",len(list(OUT.glob("*.png"))),"screenshots")
