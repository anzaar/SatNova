#!/usr/bin/env python3
"""Capture ONLY real Google TV emulator screens, no synthetic UI screenshots.
All interactions use on-device UI hierarchy + touchscreen-key equivalents.
A missing feature is recorded as SKIP, not painted as a successful screenshot.
"""
import os, subprocess, re, json, time, xml.etree.ElementTree as ET
from pathlib import Path
OUT=Path("home-override-evidence/screens")
OUT.mkdir(parents=True,exist_ok=True)
PKG="com.satnova.tvhome.tvbridge"
ACT="com.satnova.alpha.MinimalHomeActivity"
results=[]
def sh(*a,check=False,timeout=35):
    p=subprocess.run(["adb",*a],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if check and p.returncode: raise RuntimeError(p.stderr.decode(errors="replace"))
    return p.stdout.decode(errors="replace"), p.returncode
def a(*args):
    return sh("shell",*args)
def pause(t=1.1): time.sleep(t)
def hierarchy(name):
    try:
        sh("shell","uiautomator","dump","/sdcard/satnova-tour.xml",timeout=20)
        xml,_=a("cat","/sdcard/satnova-tour.xml")
        (OUT/(name+".xml")).write_text(xml)
        return ET.fromstring(xml)
    except (ET.ParseError, subprocess.TimeoutExpired, RuntimeError) as exc:
        print("UI dump unavailable:",str(exc)[:100],flush=True)
        return ET.Element("empty")
def pic(name,caption):
    p=subprocess.run(["adb","exec-out","screencap","-p"],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=25)
    if p.returncode==0 and p.stdout.startswith(b"\x89PNG"):
        (OUT/(name+".png")).write_bytes(p.stdout)
        results.append(dict(name=name,caption=caption,screenshot=True))
        print("PHOTO",name,caption,flush=True)
    else:
        results.append(dict(name=name,caption=caption,screenshot=False))
        print("PHOTO FAILED",name,p.stderr.decode(errors="replace")[:160],flush=True)
def hit(target,which=0,scroll=False):
    t=target.lower()
    attempts=4 if scroll else 1
    for attempt in range(attempts):
        root=hierarchy("find-"+re.sub(r"[^a-zA-Z0-9]","_",target)[:48])
        cands=[]
        for elem in root.iter("node"):
            val=" ".join(elem.attrib.get(k,"") for k in ("text","content-desc")).lower()
            if t not in val: continue
            m=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",elem.attrib.get("bounds",""))
            if m: cands.append(tuple(map(int,m.groups())))
        if len(cands)>which:
            x1,y1,x2,y2=cands[which]
            a("input","tap",str((x1+x2)//2),str((y1+y2)//2));pause()
            results.append(dict(action="click",target=target,ok=True))
            print("CLICK",target,flush=True)
            return True
        if scroll and attempt+1<attempts:
            a("input","swipe","760","640","760","210","410")
            pause(.6)
    print("SKIP can't find",target,flush=True)
    results.append(dict(action="click",target=target,ok=False))
    return False
def key(v):
    a("input","keyevent",str(v));pause(.7)
def restart():
    a("am","force-stop",PKG)
    a("am","start","-W","-n",f"{PKG}/{ACT}")
    pause(3)
def menu():
    # Prefer a verified UI element. A first-start dialog or OEM overlay can
    # leave the accessibility hierarchy without the top-bar icon.
    if hit("Paramètres") or hit("Settings") or hit("الإعدادات"):
        return True
    # Bring SatNova to the foreground explicitly, not the stock Android HOME.
    a("am", "start", "-W", "-n", f"{PKG}/{ACT}")
    pause(1.2)
    if hit("Paramètres") or hit("Settings") or hit("الإعدادات"):
        return True
    # Fallback to the real cog's layout position: 48dp outer padding and
    # 52dp button at the far right; the top bar starts at 24dp.
    size, _ = a("wm", "size")
    density, _ = a("wm", "density")
    m = re.search(r"(?:Override|Physical) size:\s*(\d+)x(\d+)", size)
    d = re.search(r"(?:Override|Physical) density:\s*(\d+)", density)
    if not m:
        print("SKIP settings tap; unknown display size:", size[:120], flush=True)
        return False
    width, _ = map(int, m.groups())
    scale = int(d.group(1)) / 160.0 if d else 1.0
    x, y = width - round(74 * scale), round(50 * scale)
    a("input", "tap", str(x), str(y))
    pause(1.0)
    root = hierarchy("settings-cog-fallback")
    values = [" ".join(node.attrib.get(k, "") for k in ("text", "content-desc")).lower()
              for node in root.iter("node")]
    tokens = ["thèmes et fond", "themes & wallpaper", "الخلفية والثيم"]
    verified = any(any(token in value for token in tokens) for value in values)
    if verified:
        print("CLICK Settings cog by measured TV coordinates", x, y, flush=True)
        return True
    print("SKIP settings fallback; foreground hierarchy:",
          [v[:70] for v in values if v][:16], flush=True)
    return False
def enter_option(*names):
    if not menu(): return False
    for name in names:
        if hit(name,scroll=True): return True
    key("4");return False
def back():
    key("4")
def screen(name,label):
    pic(name,label)
    # Capture raw device pixels; UI XML is collected before each click.
try:
    restart()
    screen("01_first_start","Initial first-run screen, before granting any permissions")
    back()
    screen("02_islamic_home","Original Islamic theme and live TV app grid")
    if menu():
        screen("03_settings","Full TV launcher settings menu")
        back()
    if enter_option("Thèmes et fond","Themes & wallpaper","الخلفية والثيم"):
        screen("04_theme_picker","Available Islamic, Atlas Lions sport, and custom wallpapers")
        if hit("Stade Moulay Abdellah") or hit("الرياضي") or hit("Sports"):
            pause(2)
            screen("05_atlas_lions_sport","Actual 1080p Atlas Lions sports wallpaper and Moroccan colors")
            a("input","swipe","860","760","840","400","440");pause(2)
            screen("06_sports_lower","Sport mode after scrolling TV, radios and Quran")
            a("input","swipe","860","360","860","890","440");pause(1)
    if enter_option("Ville météo et prières","Weather & prayer city","مدينة الطقس والصلاة"):
        screen("07_city_picker","Prayer-time and worldwide weather city list")
        back()
    if enter_option("Saint Coran","Holy Quran","القرآن الكريم"):
        screen("08_quran_114","Real Quran 114-surah selection dialog")
        back()
    if enter_option("Audio de l'adhan","Adhan audio","صوت الأذان"):
        screen("09_adhan","Adhan reciters and enable/disable options")
        back()
    if enter_option("Télécommande ZEN 4 via HDMI","ZEN 4 remote via HDMI","تيليكوموند ZEN 4 عبر HDMI"):
        screen("10_hdmi_remote","Real HDMI and receiver-remote settings")
        back()
    if enter_option("Contournement HOME (facultatif)","Optional Home override","تجاوز الواجهة الأصلية اختيارياً"):
        screen("11_home_fallback","Explicit, reversible Google TV Home Accessibility option")
        back()
    if enter_option("Diagnostic HOME","HOME diagnostics","تشخيص HOME"):
        screen("12_home_diagnostics","HOME role holder and resolution diagnostics")
        back()
    if enter_option("Applications favorites","Favorite apps","التطبيقات المفضلة"):
        screen("13_app_chooser","Real TV app favorites selection")
        back()
    if enter_option("Langue","Language","اللغة"):
        screen("14_languages","Language options")
        if hit("English"):
            screen("15_home_en","English-language home, sports mode retained")
    if enter_option("Language","Langue","اللغة"):
        if hit("العربية"):
            screen("16_home_ar","Arabic-language home, sports mode retained")
    key("3")
    screen("17_home_key","Actual HOME button outcome, not a simulated launcher")
    restart()
    screen("18_final_home","SatNova after returning from stock Google TV")

except Exception as error:
    print("TOUR EXCEPTION",str(error),flush=True)
    results.append(dict(error=str(error)))
finally:
    (OUT/"index.json").write_text(json.dumps(results,ensure_ascii=False,indent=2))
    print("SCREENSHOTS:",sum(1 for r in results if r.get("screenshot")),flush=True)
