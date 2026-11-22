#!/usr/bin/env python3
"""Capture *real* Google TV 14 emulator screenshots for AltaPlay v0.12.

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
            if any(key.casefold() in (n.get(a) or "").casefold() for a in attrs):
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
REPORT.append("INSTALL: Success" if re.search(r"(?m)^Success\\s*$",install_output) else "INSTALL: FAIL "+install_output.replace("\\n"," | "))
launch()
shot("01-offline-home-default-wallpaper")
root=tree("01-offline-ui")

# Virtual-remote journey: HOME, Apps, enter/exit launcher, return HOME.
key("KEYCODE_HOME",1.2)
remote_sequence("17-google-tv-home",["KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_DOWN"])
focus_report("Google TV home focus",tree("17-google-tv-focus"))
root=tree("17-apps-entry")
if click(root,["Apps","التطبيقات"],clickable=True):
    shot("18-google-tv-apps-screen")
    apps=tree("18-apps-hierarchy")
    focus_report("Apps screen",apps)
    if find(apps,["AltaPlay"],clickable=True):
        click(apps,["AltaPlay"],clickable=True)
        time.sleep(2);shot("19-launcher-opened-from-apps")
        remote_sequence("20-launcher-dpad-navigation",["KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_LEFT","KEYCODE_DPAD_DOWN","KEYCODE_DPAD_UP"])
        back();shot("21-exit-launcher-back")
key("KEYCODE_HOME",1.5)
shot("22-home-key-default-resolution")
focus_report("default HOME after key",tree("22-home-key-hierarchy"))
key("KEYCODE_HOME",1)
shot("23-home-resolver-or-default")
resolver=tree("23-home-resolver")
labels=[n.get("text","") for n in resolver.findall(".//node")] if resolver is not None else []
REPORT.append("HOME RESOLVER LABELS "+repr([x for x in labels if x]))
if any("AltaPlay" in x or "Google TV" in x for x in labels):
    REPORT.append("HOME chooser presented; inspect options and persistence after emulator reboot")
else:
    REPORT.append("No HOME chooser surfaced; default launcher selection not proven")

# Header -> Zen menu -> Connection diagnosis
menu=open_zen("02-zen-settings-disconnected")
if menu is not None:
    if click(menu,["تشخيص الاتصال","Diagnostic de connexion","Connection diagnostics"]):
        shot("03-zen-connection-diagnostics")
        back()
    # Language controls shown from Zen menu.
    back()

# Capture language picker and English/French/Arabic header; these are
# independent UI interactions and can be verified by text in XML.
for lang,findword,option in [
  ("english",["اللغة","Langue","Language"],["English"]),
  ("french",["اللغة","Langue","Language"],["Français"]),
  ("arabic",["اللغة","Langue","Language"],["العربية"]),
]:
    menu=open_zen("04-"+lang+"-zen-settings")
    if menu is not None and click(menu,findword):
        shot("05-"+lang+"-language-picker")
        langtree=tree("05-"+lang+"-language-list")
        if click(langtree,option):
            time.sleep(2)
            shot("06-"+lang+"-home")
            current=tree("06-"+lang+"-home")
            if current is not None:
                labels=[n.get("text","") for n in current.findall(".//node")]
                labels=[x for x in labels if x]
                (OUT/("06-"+lang+"-visible-text.txt")).write_text("\\n".join(labels),encoding="utf-8")
                if lang in ("english","french"):
                    # UI must not show Arabic-only prayer/weather prose in LTR.
                    # Hijri and broadcaster names are allowed as proper names.
                    bad=["مواقيت الصلاة الرسمية", "الفجر ", "الطقس غير متاح", "سماء صافية نسبياً"]
                    found=[x for x in bad if any(x in t for t in labels)]
                    REPORT.append("LTR localization "+lang+": "+("FAIL unexpected Arabic prose "+str(found) if found else "PASS no Arabic-only ambient prose"))
                else:
                    REPORT.append("RTL language: Arabic chosen; check screenshot for mirrored controls")
        else:back(2)

# Simulated connected ZEN: source contains channel IDs but never hardware HDMI.
sim=subprocess.Popen(["python3",".github/qa/zen_mock_receiver.py"],
                     stdout=(OUT/"simulated-zen.log").open("wb"),
                     stderr=subprocess.STDOUT)
try:
    for i in range(35):
        time.sleep(0.3)
        if "receiver ready on 20000" in (OUT/"simulated-zen.log").read_text(errors="replace"):
            break
    adb("shell","am","force-stop",PKG)
    adb("shell","run-as",PKG,"mkdir","-p","shared_prefs")
    pref=b"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"zen_ip\">10.0.2.2</string></map>"
    cmd("adb","shell","run-as",PKG,"sh","-c","cat > shared_prefs/altaplay_zen.xml",stdin=pref)
    launch()
    time.sleep(7)
    shot("07-simulated-zen-connected-home")
    chan=tree("07-simulated-zen-channels")
    remote_sequence("07a-channel-dpad-traverse",["KEYCODE_DPAD_DOWN","KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_RIGHT","KEYCODE_DPAD_LEFT","KEYCODE_DPAD_UP","KEYCODE_ENTER"])
    focus_report("channel traversal",tree("07a-channel-dpad-traverse"))
    if long_click(chan,["Arryadia","Al Aoula","MBC","Aljazeera"]):
        shot("08-long-press-channel-menu")
        cm=tree("08-channel-menu")
        if click(cm,["لوغو يدوي","Logo via URL","Manual logo"]):
            shot("09-manual-logo-url-or-ftp")
            back()
        back()
        chan=tree("08-channel-menu-return")
        if long_click(chan,["Arryadia","Al Aoula","MBC","Aljazeera"]):
            cm=tree("08-channel-menu-rename")
            if click(cm,["تغيير اسم القناة","Renommer","Rename"]):
                shot("10-rename-channel-dialog")
                back(2)
    menu=open_zen("11-zen-settings-connected")
    if menu:
        if click(menu,["اللغة","Langue","Language"]):
            shot("12-language-choices-connected")
            back()
        back()
finally:
    sim.terminate()
    try:sim.wait(timeout=5)
    except Exception:sim.kill()

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
    if click(settings,["wallpaperChoice","الخلفية","Fond d'écran","Wallpaper"]):
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
