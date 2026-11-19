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
    sh("shell","uiautomator","dump","/sdcard/satnova-tour.xml",timeout=35)
    xml,_=a("cat","/sdcard/satnova-tour.xml")
    (OUT/(name+".xml")).write_text(xml)
    try: return ET.fromstring(xml)
    except ET.ParseError: return ET.Element("empty")
def pic(name,caption):
    p=subprocess.run(["adb","exec-out","screencap","-p"],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=25)
    if p.returncode==0 and p.stdout.startswith(b"\x89PNG"):
        (OUT/(name+".png")).write_bytes(p.stdout)
        results.append(dict(name=name,caption=caption,screenshot=True))
        print("PHOTO",name,caption,flush=True)
    else:
        results.append(dict(name=name,caption=caption,screenshot=False))
        print("PHOTO FAILED",name,p.stderr.decode(errors="replace")[:160],flush=True)
def hit(target,which=0):
    root=hierarchy("find-"+re.sub(r"[^a-zA-Z0-9]","_",target)[:48])
    t=target.lower()
    cands=[]
    for elem in root.iter("node"):
        val=" ".join(elem.attrib.get(k,"") for k in ("text","content-desc")).lower()
        if t not in val: continue
        m=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",elem.attrib.get("bounds",""))
        if m: cands.append(tuple(map(int,m.groups())))
    if len(cands)<=which:
        print("SKIP can't find",target,"nodes",len(cands),flush=True)
        results.append(dict(action="click",target=target,ok=False))
        return False
    x1,y1,x2,y2=cands[which]
    a("input","tap",str((x1+x2)//2),str((y1+y2)//2));pause()
    results.append(dict(action="click",target=target,ok=True))
    print("CLICK",target,flush=True)
    return True
def key(v):
    a("input","keyevent",str(v));pause(.7)
def restart():
    a("am","force-stop",PKG)
    a("am","start","-W","-n",f"{PKG}/{ACT}")
    pause(3)
def menu():
    # Click the real system-settings cog in SatNova's top bar.
    return hit("Paramètres") or hit("Settings") or hit("الإعدادات")
def enter_option(*names):
    if not menu(): return False
    for name in names:
        if hit(name): return True
    key("4");return False
def back():
    key("4")
def screen(name,label):
    pic(name,label)
    hierarchy(name)
try:
    restart()
    screen("01_first_start","Initial first-run screen; permissions are not auto-granted")
    back()
    screen("02_islamic_home","Default original Islamic home layout")
    if menu():
        screen("03_settings","All TV launcher settings")
        back()
    if enter_option("Langue","Language","اللغة"):
        screen("04_languages","Language options")
        if hit("العربية"):
            screen("05_home_ar","Arabic labels in Islamic home")
        if enter_option("اللغة","Langue","Language"):
            if hit("English"):
                screen("06_home_en","English labels in Islamic home")
        if enter_option("Language","Langue","اللغة"):
            if hit("Français"):
                screen("07_home_fr","French labels in Islamic home")
    if enter_option("Thèmes et fond","Themes & wallpaper","الخلفية والثيم"):
        screen("08_theme_picker","Islamic, sports and custom theme options")
        if hit("Stade Moulay Abdellah") or hit("الرياضي") or hit("Sports"):
            pause(2)
            screen("09_atlas_lions_sport","Real 1080p Atlas Lions sport wallpaper and overlay")
            a("input","swipe","860","760","840","400","440");pause(2)
            screen("10_sport_lower","Sport mode scrolled apps, TV, radios, Quran")
            a("input","swipe","860","360","860","890","440");pause(1)
    if enter_option("Ville météo et prières","Weather & prayer city","مدينة الطقس والصلاة"):
        screen("11_city_picker","Weather and prayer city selector")
        back()
    if enter_option("Saint Coran","Holy Quran","القرآن الكريم"):
        screen("12_quran_114","Actual Quran surah selection dialog")
        back()
    if enter_option("Audio de l'adhan","Adhan audio","صوت الأذان"):
        screen("13_adhan","Adhan recitation settings")
        back()
    if enter_option("Télécommande ZEN 4 via HDMI","ZEN 4 remote via HDMI","تيليكوموند ZEN 4 عبر HDMI"):
        screen("14_hdmi_remote","HDMI remote configuration and status")
        back()
    if enter_option("Contournement HOME (facultatif)","Optional Home override","تجاوز الواجهة الأصلية اختيارياً"):
        screen("15_home_fallback","Optional and reversible Home Accessibility override")
        back()
    if enter_option("Diagnostic HOME","HOME diagnostics","تشخيص HOME"):
        screen("16_home_diagnostics","Current Android HOME resolution and ROLE_HOME")
        back()
    if enter_option("Applications favorites","Favorite apps","التطبيقات المفضلة"):
        screen("17_app_chooser","Actual TV app favorites chooser")
        back()
    # Pressing HOME here may return Google TV despite configured HOME.
    key("3")
    screen("18_key_home","Actual HOME-button outcome on stock Google TV")
    restart()
    screen("19_final_home","SatNova starts again after HOME comparison")
except Exception as error:
    print("TOUR EXCEPTION",str(error),flush=True)
    results.append(dict(error=str(error)))
finally:
    (OUT/"index.json").write_text(json.dumps(results,ensure_ascii=False,indent=2))
    print("SCREENSHOTS:",sum(1 for r in results if r.get("screenshot")),flush=True)
