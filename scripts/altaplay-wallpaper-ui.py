#!/usr/bin/env python3
"""Interact with Secreto's own wallpaper settings in a REAL Android TV 14 AVD.

Every captured PNG comes from adb exec-out screencap, not a reconstruction.
Only declares success if the user-selected image can be verified visually
against the initial HOME screenshot, otherwise writes explicit UNVERIFIED.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageChops, ImageStat

ROOT=Path("altaplay-emulator-evidence")
SCREENS=ROOT/"screens"
ROOT.mkdir(exist_ok=True,parents=True)
SCREENS.mkdir(exist_ok=True,parents=True)
PKG="com.altaplay.launcher"
MAIN="com.gathin.tvhome.MainActivity"

def adb(*args, timeout=24):
    try:
        p=subprocess.run(["adb",*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
        return p.stdout.decode("utf-8","replace")
    except Exception as e:
        return "ERR: "+str(e)

def tick(delay=1.5):
    time.sleep(delay)

def screen(name):
    try:
        data=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=18).stdout
        if data.startswith(b"\x89PNG"):
            (SCREENS/(name+".png")).write_bytes(data)
            im=Image.open(SCREENS/(name+".png"))
            print("CAPTURE",name,im.size,"bytes",len(data),flush=True)
        else:
            print("CAPTURE FAILED",name,len(data),flush=True)
    except Exception as e: print("CAPTURE ERROR",name,str(e),flush=True)
    (ROOT/(name+"-activity.txt")).write_text(adb("shell","dumpsys","activity","activities",timeout=16)[-35000:])

def ui(name):
    # UIAutomator is the accessibility tree, not image OCR. This only operates on emulator.
    dump=adb("shell","uiautomator","dump","/sdcard/window.xml",timeout=22)
    raw=adb("exec-out","cat","/sdcard/window.xml",timeout=12)
    if not raw.lstrip().startswith("<?xml"):
        print("UI DUMP UNAVAILABLE",name,dump[:260],raw[:200],flush=True)
        (ROOT/(name+"-ui.txt")).write_text("UI dump failed: "+dump[:1400]+"\n"+raw[:500])
        return []
    (ROOT/(name+"-ui.txt")).write_text(raw)
    try:
        root=ET.fromstring(raw)
    except Exception as e:
        print("UI XML BAD",name,e,flush=True)
        return []
    nodes=[]
    for n in root.iter("node"):
        a=n.attrib
        info={"text":a.get("text",""),"desc":a.get("content-desc",""),
              "bounds":a.get("bounds",""),"cls":a.get("class",""),
              "clickable":a.get("clickable","false"),"pkg":a.get("package","")}
        if info["text"] or info["desc"]: nodes.append(info)
    print("UI",name,"nodes",len(nodes),[(x["text"] or x["desc"])[:85] for x in nodes[:85]],flush=True)
    return nodes

def tap(x,y):
    print("TAP",x,y,flush=True)
    print(adb("shell","input","tap",str(x),str(y))[:120],flush=True)
    tick(2.5)

def click(nodes,words):
    for w in words:
        for n in nodes:
            content=" ".join([n["text"],n["desc"]]).lower()
            if w.lower() in content:
                bounds=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",n["bounds"])
                if not bounds: continue
                x1,y1,x2,y2=map(int,bounds.groups())
                print("MATCH",repr(w),repr(content[:100]),flush=True)
                tap((x1+x2)//2,(y1+y2)//2)
                return True
    return False

def stage(label):
    tick(1)
    screen(label)
    return ui(label)

def home():
    adb("shell","am","start","-W","-n",PKG+"/"+MAIN)
    tick(4)

def size():
    s=adb("shell","wm","size")
    m=re.search(r"(\d+)x(\d+)",s)
    if m: return (int(m.group(1)),int(m.group(2)))
    return (1920,1080)

def check_change():
    p=SCREENS/"20_home_before_wallpaper.png"
    q=SCREENS/"29_home_after_wallpaper.png"
    if not p.is_file() or not q.is_file(): return "unverified: missing screenshots"
    try:
        i=Image.open(p).convert("RGB").resize((320,180))
        j=Image.open(q).convert("RGB").resize((320,180))
        stat=ImageStat.Stat(ImageChops.difference(i,j))
        value=sum(stat.mean)/3
        print("PIXEL DIFFERENCE",round(value,2),flush=True)
        if value<5: return "unchanged_or_unverified"
        return "VISUAL_CHANGE_FOUND_NOT_PROOF_OF_CORRECT_IMAGE"
    except Exception as e: return "unverified: "+str(e)

def main():
    w,h=size()
    print("EMULATOR RESOLUTION",w,h,flush=True)
    print("ASSET SIZE",Image.open("tvhome/src/main/assets/satnova_wallpaper.webp").size,flush=True)
    print("COPY SAVED ISLAMIC WALLPAPER TO TV PICTURES",flush=True)
    print(adb("push","tvhome/src/main/assets/satnova_wallpaper.webp",
              "/sdcard/Pictures/AltaPlay-Islamic.webp",timeout=25)[:400],flush=True)
    print(adb("shell","am","broadcast","-a",
              "android.intent.action.MEDIA_SCANNER_SCAN_FILE","-d",
              "file:///sdcard/Pictures/AltaPlay-Islamic.webp")[:350],flush=True)
    home()
    nodes=stage("20_home_before_wallpaper")
    if not click(nodes,["Settings","Paramètres","الإعدادات"]):
        # Actual official screenshot has Settings pill at the upper right.
        tap(int(.88*w),int(.15*h))
    nodes=stage("21_settings_menu")
    if not click(nodes,["Launcher settings","Paramètres du lanceur","Paramètres launcher"]):
        # The official Secreto settings popup first row is just below the button.
        tap(int(.82*w),int(.255*h))
    nodes=stage("22_launcher_settings")
    chosen=click(nodes,["Wallpaper Studio","Wallpaper","Wallpapers",
                        "Fond d’écran","Fond d'ecran","Arrière-plan","Background"])
    if not chosen:
        for i in range(12):
            print("SCROLL SETTINGS",i,flush=True)
            adb("shell","input","swipe","1350","850","1350","220","350")
            tick(1.0)
            nodes=stage("22_scroll"+str(i))
            if click(nodes,["Wallpaper Studio","Wallpaper","Wallpapers",
                            "Fond d’écran","Fond d'ecran","Background"]):
                chosen=True
                print("FOUND WALLPAPER AT SCROLL",i,flush=True)
                break
    if not chosen:
        print("FAILED TO LOCATE WALLPAPER STUDIO AFTER SCROLL",flush=True)
    nodes=stage("23_wallpaper_studio")
    # REAL Secreto UI: Single personal photo -> Choose image from device.
    nodes=stage("23_wallpaper_studio")
    personal = click(nodes,["Single personal photo"])
    nodes=stage("24_single_personal_photo")
    picker = click(nodes,["Choose image from device","Choose image","Import photo"])
    nodes=stage("25_device_picker_opened")
    selected=False
    if picker:
        for step in range(12):
            if click(nodes,["AltaPlay-Islamic.webp","AltaPlay-Islamic"]):
                selected=True
                nodes=stage("26_islamic_image_tapped")
                # Some pickers ask to confirm this image, others return immediately.
                click(nodes,["Use this photo","Use photo","Select","Done",
                             "Apply","Open","OK"])
                nodes=stage("27_after_image_selection")
                break
            # Android Photo Picker often has Photos/Albums/Browse tabs.
            if click(nodes,["Pictures","Images","Browse files","Browse",
                            "On this device","Files","Photos","Albums",
                            "Downloads","Internal storage"]):
                nodes=stage("25_picker_step"+str(step))
                continue
            print("IMAGE FILE NOT YET VISIBLE; SCROLL PHOTO PICKER",step,flush=True)
            adb("shell","input","swipe","1340","840","1340","220","360")
            nodes=stage("25_picker_scroll"+str(step))
    else:
        print("CHOOSER BUTTON WAS NOT AVAILABLE",flush=True)
    # App can show a preview after picking. Use Secreto's own action
    # and never claim the wallpaper was applied solely from click.
    if selected:
        if click(nodes,["Use saved selection","Apply wallpaper","Use photo"]):
            nodes=stage("28_personal_wallpaper_applied")
    (ROOT/"wallpaper-attempt.txt").write_text(
        "Studio personal photo selected: "+str(personal)+"\n"+
        "Device chooser opened: "+str(picker)+"\n"+
        "Exact AltaPlay Islamic image selected: "+str(selected)+"\n")
    # Exit the settings the same way the remote would: back to original launcher.
    for i in range(4):
        adb("shell","input","keyevent","KEYCODE_BACK")
        tick(0.7)
    home()
    stage("29_home_after_wallpaper")
    result=check_change()
    (ROOT/"wallpaper-result.txt").write_text(
        result+"\n"+(ROOT/"wallpaper-attempt.txt").read_text())
    print("WALLPAPER RESULT",result,flush=True)
    adb("shell","settings","put","secure","enabled_accessibility_services",
        PKG+"/com.satnova.alpha.Zen4HdmiRemoteService")
    adb("shell","settings","put","secure","accessibility_enabled","1")
    tick(2)
    home()
    stage("30_home_with_zen_accessibility")


if __name__=="__main__":
    main()
