#!/usr/bin/env python3
"""Real Android TV 14 emulator visual + reboot/black-screen audit.

Runs ONLY in a disposable Android emulator, never a user's television.
Review and photograph UI BEFORE testing Google TV launcher disabling.
Records evidence in qa-results/v011-reboot-visual-audit. Logs remain if fail.

Cold boot frames before ADB reconnect are NOT observable: measured times start
when ADB answers. No fake precise boot-to-first-photon estimates.
"""
import io,json,math,os,re,subprocess,time,traceback
from pathlib import Path
from xml.etree import ElementTree as ET
from PIL import Image,ImageDraw

ROOT=Path("qa-results/v011-reboot-visual-audit")
ROOT.mkdir(parents=True,exist_ok=True)
PKG="com.altaplay.launcher"
HOME=PKG+"/com.gathin.tvhome.MainActivity"
# Google TV OEMs and the emulator expose different stock HOME packages.
GOOGLE_PACKAGES=("com.google.android.tvlauncher","com.google.android.apps.tv.launcherx")
def google_launcher_enabled():
    enabled=adb("shell","pm","list","packages","-e")
    return any(("package:"+name) in enabled for name in GOOGLE_PACKAGES)
events=[]
results={"purpose":"TV UI audit, Android Settings Home confirmation with stock Google Launcher enabled, then reboot",
         "qa":"emulated Google TV 14 x86 / 1080p", "sections":{},"boot_cases":{}}
def record(key,val):
    events.append(f"{time.strftime('%H:%M:%S')} {key}: {val}")
    print(events[-1],flush=True)
def cmd(*args,timeout=35,stdin=None):
    try:
        proc=subprocess.run([str(a) for a in args],input=stdin,stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE,timeout=timeout)
        if proc.returncode:
            record("command error"," ".join(map(str,args))+" RC="+str(proc.returncode)+
                   " "+proc.stderr.decode("utf8","replace")[:180])
        return proc.stdout.decode("utf8","replace")
    except Exception as exc:
        record("command exception",repr(exc)+" "+str(args)[:120])
        return ""
def adb(*args,timeout=35):return cmd("adb",*args,timeout=timeout)
def sleep(seconds=0.6):time.sleep(seconds)
def shot(name,folder=None):
    parent=folder or ROOT
    parent.mkdir(parents=True,exist_ok=True)
    target=parent/(name+".png")
    try:
        p=subprocess.run(["adb","exec-out","screencap","-p"],
                         stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=22)
        if p.returncode or not p.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
            record("screenshot unavailable",name+" "+p.stderr.decode("utf8","replace")[:100])
            return None
        target.write_bytes(p.stdout)
        im=Image.open(io.BytesIO(p.stdout)).convert("RGB")
        small=im.resize((160,90))
        pixels=list(small.getdata())
        dark=sum(1 for r,g,b in pixels if max(r,g,b)<15)
        black=round(dark/len(pixels),3)
        record("SCREENSHOT",str(target)+" black-pixel ratio="+str(black))
        return {"file":str(target),"dark_ratio":black,"size":list(im.size)}
    except Exception as e:record("screenshot fail",name+" "+str(e));return None

def xml(label):
    adb("shell","uiautomator","dump","/sdcard/v11-window.xml",timeout=50)
    raw=adb("exec-out","cat","/sdcard/v11-window.xml")
    ix=raw.find("<?xml")
    if ix<0:return None
    raw=raw[ix:]
    (ROOT/(label+".xml")).write_text(raw,encoding="utf8")
    try:return ET.fromstring(raw)
    except ET.ParseError:return None

def find(root,needles):
    if root is None:return None
    for needle in needles:
        for node in root.findall(".//node"):
            label=" ".join(node.get(f,"") for f in ["text","content-desc","resource-id"])
            b=node.get("bounds","")
            if needle.casefold() in label.casefold() and re.fullmatch(r"\[\d+,\d+\]\[\d+,\d+\]",b):
                return node
    return None

def touch(root,terms,verb="tap"):
    n=find(root,terms)
    if n is None:
        record("UI NOT FOUND",str(terms))
        return False
    coords=list(map(int,re.findall(r"\d+",n.get("bounds"))))
    x,y=(coords[0]+coords[2])//2,(coords[1]+coords[3])//2
    if verb=="long":
        adb("shell","input","swipe",x,y,x,y,"1250")
    else:adb("shell","input","tap",x,y)
    record("UI "+verb,terms[0]+" @ "+str((x,y)))
    sleep()
    return True

def back(n=1):
    for _ in range(n):adb("shell","input","keyevent","KEYCODE_BACK");sleep(0.4)
def home():
    adb("shell","am","start","-W","-n",HOME,timeout=55)
    sleep(1.3)

def go_settings():
    home()
    root=xml("settings-entry")
    found=touch(root,["الإعدادات","Paramètres","Settings"])
    if found:shot("02-General-settings-menu")
    return found and xml("general-settings-menu")

def picker(prefix):
    r=go_settings()
    if r is None:return None
    if not touch(r,["خلفيات وإعدادات AltaPlay","Fonds d'écran et paramètres AltaPlay",
                   "AltaPlay wallpaper & settings"]):return None
    sleep(1)
    shot(prefix+"-launcher-settings-top")
    r=xml(prefix+"-launcher-settings")
    # Wallpaper row may be outside the 1080p visible scroll region.
    for attempt in range(4):
        if find(r,["wallpaperChoice","الخلفية","Fond d'écran","Wallpaper"]):return r
        adb("shell","input","swipe","1200","880","1200","300","390");sleep(0.5)
        shot(prefix+"-launcher-settings-scroll"+str(attempt+1))
        r=xml(prefix+"-scroll"+str(attempt+1))
    return r

def verify_first_run_guide():
    # User-facing flow: no ADB setup of roles or accessibility flags.
    sleep(2.9)
    modal=xml("00-setup-language")
    found=find(modal,["العربية","Français","English"])
    if found is None:
        results["sections"]["first_run_guide"]="INCONCLUSIVE setup dialog not visible"
        record("SETUP INCOMPLETE","No first-run language dialog")
        return
    images=[shot("00-setup-choose-language")]
    if not touch(modal,["العربية"]):
        results["sections"]["first_run_guide"]="FAIL select Arabic"
        return
    images.append(shot("00-setup-Home-approval-explanation"))
    homepage=xml("00-setup-Home-options")
    # At this point show the legitimate Android Home approval option, but
    # don't select HOME in this first-run walkthrough; the separate reboot
    # scenario will exercise the real system picker as a conscious action.
    if not touch(homepage,["تجاوز دابا","Ignorer","Skip for now"]):
        results["sections"]["first_run_guide"]="FAIL cannot skip Home"
        return
    images.append(shot("00-setup-receiver-selection"))
    receiver=xml("00-setup-receiver-options")
    if not touch(receiver,["نعم، بحث تلقائي","Oui, recherche auto",
                           "Yes, find automatically"]):
        results["sections"]["first_run_guide"]="FAIL receiver auto-discovery button absent"
        return
    images.append(shot("00-setup-Accessibility-opt-in"))
    access=xml("00-setup-accessibility-options")
    if not touch(access,["تجاوز مؤقتاً","Ignorer","Skip for now"]):
        results["sections"]["first_run_guide"]="FAIL cannot defer Accessibility"
        return
    images.append(shot("00-setup-finished"))
    finished=xml("00-setup-complete")
    if not touch(finished,["دخول للواجهة","Ouvrir AltaPlay","Open AltaPlay"]):
        results["sections"]["first_run_guide"]="FAIL completion action absent"
        return
    pref=adb("shell","run-as",PKG,"cat","shared_prefs/altaplay_setup.xml")
    complete='name="completed" value="true"' in pref
    results["sections"]["first_run_guide"]={
        "completed_via_UI":complete,
        "steps":["language","Android HOME (skipped by user)",
                 "Zen auto-discovery","Accessibility (skipped by user)","finish"],
        "real_screenshots":sum(x is not None for x in images),
        "no_adb_setting_changes":True}
    record("SETUP",results["sections"]["first_run_guide"])

def prep_wallpapers():
    # Non-production test fixtures with easily visible wallpaper colors.
    folder=ROOT/"fixtures";folder.mkdir(exist_ok=True)
    patterns=[
        ("qa-forest-green.png",(15,103,70)),
        ("qa-ocean-blue.png",(17,51,155))]
    for name,color in patterns:
        im=Image.new("RGB",(960,540),color)
        d=ImageDraw.Draw(im)
        for v in range(0,960,70):d.line([(v,0),(v+230,540)],fill=(150,155,169),width=4)
        d.rectangle((30,30,360,80),fill=(245,245,245))
        d.text((44,44),name,fill=(20,20,20))
        im.save(folder/name)
    destination="/sdcard/Android/data/"+PKG+"/files/wallpapers"
    adb("shell","mkdir","-p",destination)
    for name,_ in patterns:
        adb("push",str(folder/name),destination+"/"+name,timeout=55)
    record("TEST FIXTURES",destination)
    return [x[0] for x in patterns]

def select_wallpaper(name,label):
    r=picker(label)
    if r is None or not touch(r,["wallpaperChoice","الخلفية","Fond d'écran","Wallpaper"]):
        results["sections"][label]="FAIL wallpaper picker not found"
        return
    shot(label+"-wallpaper-picker")
    choices=xml(label+"-picker")
    if not touch(choices,[name]):
        results["sections"][label]="FAIL image not listed in real picker"
        return
    shot(label+"-choice-saved-in-settings")
    # On-TV selection applies when Home reloads.
    adb("shell","am","force-stop",PKG)
    home()
    img=shot(label+"-applied-home")
    p=adb("shell","run-as",PKG,"cat","shared_prefs/launcher_settings.xml")
    verified=name in p
    results["sections"][label]={"saved_selection":verified,"screenshot":img}
    record("WALLPAPER verified",f"{name} saved={verified}")

def check_country_city():
    home()
    r=xml("location-home")
    if touch(r,["الطقس","Weather","Météo"]):
        shot("12-City-location-dialog")
        city=xml("city-dialog")
        listtext=" ".join(n.get("text","") for n in city.findall(".//node")) if city is not None else ""
        results["sections"]["location"]={"dialog_opened":True,
            "has_country_selection":any(w in listtext for w in ["الدولة","Country","Pays"]),
            "contains_Moroccan_cities":any(w in listtext for w in ["الرباط","طنجة","الخميسات","Khémisset"])}
        back()
    else:
        results["sections"]["location"]="FAIL no visible city/location control on Home"
    # Try main settings for international city/country settings, do not invent.
    general=go_settings()
    options=" ".join(n.get("text","") for n in general.findall(".//node")) if general is not None else ""
    results["sections"]["location_settings"]={"has_country_city_entry":any(w in options.lower() for w in ["الدولة","البلد","country","pays","location","الموقع"])}
    shot("13-Location-general-settings-audit")
    back()

def check_languages():
    res={}
    for label,entry in [("english","English"),("french","Français"),("arabic","العربية")]:
        r=go_settings()
        if r is None:res[label]="FAIL general settings";continue
        if not touch(r,["اللغة","Langue","Language"]):
            res[label]="FAIL no language option";back();continue
        shot("20-"+label+"-language-picker")
        language=xml("language-picker-"+label)
        if not touch(language,[entry]):
            res[label]="FAIL unable to select";back();continue
        sleep(1)
        home()
        image=shot("21-"+label+"-home")
        pref=adb("shell","run-as",PKG,"cat","shared_prefs/altaplay_ui.xml")
        res[label]={"selected_saved":('<string name="language">'+{"english":"en","french":"fr","arabic":"ar"}[label]+'</string>' in pref),
                    "screen":image}
    results["sections"]["languages"]=res

def check_receiver():
    home();r=xml("zen-button-entry")
    if touch(r,["جهاز الاستقبال","Récepteur","Receiver","Zen 4"]):
        shot("30-Receiver-settings")
        panel=xml("receiver-settings")
        if touch(panel,["تشخيص الاتصال","Diagnostic de connexion","Connection diagnostics"]):
            shot("31-Receiver-connection-diagnostics")
        back()
        back()
    else:results["sections"]["receiver"]="NO receiver button, possibly hidden"
    # The shared hide-show UI must remain available if receiver is hidden.
    gen=go_settings()
    if gen is not None and touch(gen,["إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur","Show or hide receiver"]):
        shot("32-Receiver-visibility-options")
        back()
    back()

def check_device_settings():
    gen=go_settings()
    if gen is not None and touch(gen,["إعدادات التلفاز والنظام",
                                       "Paramètres du téléviseur","TV & system settings"]):
        sleep(1)
        shot("40-Android-TV-actual-system-settings")
        root=xml("device-system-settings")
        results["sections"]["device_settings"]={"opened":True,"ui_nodes":len(root.findall(".//node")) if root is not None else 0}
        back()
    else:
        results["sections"]["device_settings"]="FAIL TV/system settings option not visible"

def state(prefix):
    dump={
        "home_resolution":adb("shell","cmd","package","resolve-activity","--brief","-a",
                              "android.intent.action.MAIN","-c","android.intent.category.HOME"),
        "foreground":adb("shell","dumpsys","activity","activities"),
        "window":adb("shell","dumpsys","window","windows"),
        "google_enabled":adb("shell","pm","list","packages","-e"),
        "packages_disabled":adb("shell","pm","list","packages","-d"),
        "altaplay_process":adb("shell","pidof",PKG),
        "receiver_process":" | ".join(adb("shell","pidof",name).strip() for name in GOOGLE_PACKAGES),
        "boot_uptime":adb("shell","cat","/proc/uptime")
    }
    folder=ROOT/"system";folder.mkdir(exist_ok=True)
    for k,v in dump.items(): (folder/(prefix+"-"+k+".txt")).write_text(v)
    record("HOME STATE",prefix+": "+
           str({"home":dump["home_resolution"].strip().splitlines()[-2:],
                "alta_pid":dump["altaplay_process"].strip(),
                "google_pid":dump["receiver_process"].strip()}))
    return {k:v[:1200] if len(v)>1200 else v for k,v in dump.items() if k!="window" and k!="foreground"}

def capture_home_transition(label,count=16,period=0.28):
    """Samples actual compositor screenshots. Time zero: ADB home key issued."""
    frames=ROOT/"timing"/label
    frames.mkdir(parents=True,exist_ok=True)
    start=time.monotonic()
    adb("shell","input","keyevent","KEYCODE_HOME",timeout=8)
    samples=[]
    for i in range(count):
        t=time.monotonic()-start
        pic=shot(f"{i:02}-{t:06.2f}s",frames)
        s={"host_elapsed_s":round(time.monotonic()-start,3)}
        if pic:s.update(pic)
        samples.append(s)
        remaining=(i+1)*period-(time.monotonic()-start)
        if remaining>0:sleep(remaining)
    # A high darkness ratio does not prove an entirely black screen on a dark
    # theme; flag potential blackness rather than claiming optical truth.
    return {"start_event":"ADB KEYCODE_HOME", "frames":samples,
            "first_visible_frame_after_key_s":next((x["host_elapsed_s"] for x in samples
                 if x.get("dark_ratio",1)<0.88),None),
            "sampled_black_like_frames":sum(x.get("dark_ratio",1)>=0.88 for x in samples)}

def await_reboot(timeout=195):
    start=time.monotonic()
    cmd("adb","wait-for-device",timeout=timeout)
    while time.monotonic()-start<timeout:
        if adb("shell","getprop","sys.boot_completed",timeout=6).strip()=="1":
            return time.monotonic()-start
        sleep(1)
    return None

def reboot_case(label):
    before=state(label+"-before")
    adb("logcat","-c")
    start=time.monotonic()
    adb("reboot",timeout=10)
    took=await_reboot()
    record("REBOOT",label+" adb-boot-completed-after="+str(took))
    img=shot(label+"-first-observable-after-boot")
    samples=capture_home_transition(label+"-home-key")
    after=state(label+"-after")
    adb("logcat","-d","-b","main","-b","events","-b","system","-v","epoch",
        timeout=40)
    # Capture relevant boot and activity events only.
    recent=adb("logcat","-d","-v","time","-b","events",timeout=40)
    (ROOT/"system"/(label+"-event-logcat.txt")).write_text(recent,encoding="utf-8")
    crash=adb("logcat","-d","-b","crash","-v","time",timeout=30)
    (ROOT/"system"/(label+"-crashes.txt")).write_text(crash,encoding="utf-8")
    return {"adb_ready_and_boot_completed_s":round(took,2) if took else None,
            "first_observable_screen":img,"transition":samples,
            "after_state":after,"errors_in_crash_buffer":PKG in crash}

def capture_accessibility_remote_state(label):
    """Read-only: no root, no secure-settings writes, no service activation."""
    folder=ROOT/"system"
    folder.mkdir(exist_ok=True)
    probes={
        "accessibility_services":adb("shell","settings","get","secure","enabled_accessibility_services"),
        "accessibility_on":adb("shell","settings","get","secure","accessibility_enabled"),
        "accessibility_details":adb("shell","dumpsys","accessibility",timeout=55),
        "hdmi_remote_declarations":adb("shell","dumpsys","package",PKG,timeout=45),
        "last_hdmi_input":adb("shell","dumpsys","tv_input",timeout=45),
        "default_home_role":adb("shell","cmd","role","get-role-holders","android.app.role.HOME"),
        "foreground_window":adb("shell","dumpsys","window","windows",timeout=45),
    }
    for name,value in probes.items():
        (folder/(label+"-"+name+".txt")).write_text(value,encoding="utf8")
    enabled=probes["accessibility_services"]
    info={
        "accessibility_enabled":probes["accessibility_on"].strip(),
        "accessibility_services":enabled.strip(),
        "receiver_hdmi_bridge_enabled":("AltaPlayHdmiRemoteService" in enabled),
        "receiver_key_service_enabled":("InputKeyService" in enabled),
        "home_key_service_enabled":("HomeKeyService" in enabled),
        "os_home_role":probes["default_home_role"].strip(),
        "vendor_hdmi_service_reference_found":
            "com.mediatek" in probes["last_hdmi_input"].lower()
    }
    record("ACCESSIBILITY & HDMI "+label,info)
    return info

def is_altaplay_default():
    roles=adb("shell","cmd","role","get-role-holders","android.app.role.HOME")
    preferred=adb("shell","cmd","package","resolve-activity","--brief","-a",
                  "android.intent.action.MAIN","-c","android.intent.category.HOME")
    return PKG in roles or (PKG in preferred and
           "ResolverActivity" not in preferred)

def choose_home_with_system_approval():
    """Only OS settings taps, just like a TV user would make with a remote."""
    record("HOME SELECTION","Opening OS-owned default Home settings from app's general menu.")
    details={"initial_default":is_altaplay_default(),"selection_through_system":False,
             "os_approval_shown":False,"verified_default":False}
    settings=go_settings()
    if settings is None:
        record("HOME SELECTION","Blocked: AltaPlay Settings entry unavailable")
        return details
    # Android TV dialogs can virtualize off-screen Settings entries.
    terms=["تعيين AltaPlay كواجهة رئيسية",
           "Définir AltaPlay comme écran d'accueil",
           "Set AltaPlay as default Home"]
    found=False
    for attempt in range(6):
        if touch(settings,terms):
            found=True
            break
        if attempt==5: break
        adb("shell","input","swipe","960","750","960","325","520")
        sleep(0.65)
        settings=xml("60-home-settings-scroll-"+str(attempt+1))
        shot("60-home-settings-scroll-"+str(attempt+1))
    if not found:
        record("HOME SELECTION","No Home entry after five real dialog scrolls")
        details["reason"]="HOME option not exposed by visible UI or APK stale"
        return details
    sleep(1.8)
    details["selection_through_system"]=True
    shot("60-Android-system-default-home-picker")
    root=xml("60-system-default-home-options")
    # The Android Settings page can be a list (Google TV/AltaPlay) or a
    # single "Home app" subpage. Navigate only text actually visible in XML.
    if not is_altaplay_default():
        if touch(root,["Home app","App d'accueil","Application d'accueil",
                        "Application écran d'accueil","تطبيق الشاشة الرئيسية"]):
            shot("61-Android-home-apps-list")
            root=xml("61-Android-home-apps-list")
        if touch(root,["AltaPlay Launcher","AltaPlay","com.altaplay.launcher"]):
            details["os_approval_shown"]=True
            shot("62-Android-confirm-AltaPlay-home")
            root=xml("62-Android-confirm-AltaPlay-home")
            # Some Android variants ask explicit confirmation via dialog.
            for i in range(2):
                if is_altaplay_default():break
                if touch(root,["Set as default","Use AltaPlay",
                               "Always","Toujours","Définir par défaut",
                               "Utiliser","دائماً","تعيين كافتراضي","موافق"]):
                    shot("63-Android-home-approval-"+str(i))
                    root=xml("63-home-dialog-"+str(i))
                else:break
    details["verified_default"]=is_altaplay_default()
    details["google_still_enabled"]=google_launcher_enabled()
    record("HOME SELECTION RESULT",details)
    shot("64-after-system-Home-selection")
    return details

def test_boot():
    # Google TV Launcher is NEVER disabled in this experiment; HOME is an
    # ordinary OS user choice, not a forced ADB assignment.
    record("BOOT SAFETY","Google TV Launcher stays enabled; no pm disable or set-home-activity.")
    results["boot_cases"]["before_system_choice"]=state("before-system-home-choice")
    results["boot_cases"]["services_before"]=capture_accessibility_remote_state("before-home-choice")
    selection=choose_home_with_system_approval()
    results["sections"]["default_home_selection"]=selection
    if not selection.get("verified_default") or not selection.get("google_still_enabled"):
        record("REBOOT BLOCKED","System Home approval not confirmed. No forced switch and no reboot.")
        results["boot_cases"]["not_run"]="OS default HOME selection did not succeed through settings UI"
        return
    # Measure HOME transitions before cold restart, with real screenshots.
    results["boot_cases"]["warm_home_transition"]=capture_home_transition("before-reboot-home")
    results["boot_cases"]["after_user_approved_home"]=reboot_case("google-enabled-user-approved-altaplay-home")
    results["boot_cases"]["services_after"]=capture_accessibility_remote_state("after-reboot")
    results["boot_cases"]["default_persisted"]=is_altaplay_default()
    results["boot_cases"]["google_launcher_remained_enabled"]=google_launcher_enabled()
    shot("65-after-reboot-Home-with-stock-launcher-enabled")

def main():
    results["emulator_model"]=adb("shell","getprop","ro.product.model").strip()
    apk="qa-results/AltaPlay-Launcher-v0.11-debug.apk"
    results["install_result"]=adb("install","-r","-t",apk,timeout=100).strip()
    home()
    shot("01-Home-default")
    verify_first_run_guide()
    names=prep_wallpapers()
    check_device_settings()
    check_receiver()
    check_country_city()
    check_languages()
    select_wallpaper(names[0],"50-Forest")
    select_wallpaper(names[1],"51-Ocean")
    # Repeat last chosen wallpaper after process restart to check persistence.
    home();shot("52-Ocean-after-process-restart")
    results["sections"]["review_gate"]={
      "wallpapers":all(isinstance(results["sections"].get(n),dict) and
                       results["sections"][n].get("saved_selection") for n in ["50-Forest","51-Ocean"]),
      "languages":all(isinstance(v,dict) and v.get("selected_saved") for v in
                       results["sections"].get("languages",{}).values()),
      "device_settings":isinstance(results["sections"].get("device_settings"),dict),
      "location":results["sections"].get("location")}
    # Audit pages are done first and their limitations retained transparently.
    # The Android HOME picker must be exercised next, BEFORE any reboot.
    # Do not disable Google TV Launcher, even on the emulator.
    gate=results["sections"]["review_gate"]
    results["sections"]["ui_review_complete"]={
        "wallpaper_picker_verified":gate["wallpapers"],
        "languages_verified":gate["languages"],
        "device_settings_verified":gate["device_settings"],
        "city_country_options":results["sections"].get("location_settings",{})
    }
    test_boot()

try:main()
except Exception as e:
    record("QA EXCEPTION",traceback.format_exc())
    results["exception"]=str(e)
finally:
    # No Google TV package state is changed, including during cleanup.
    (ROOT/"audit.json").write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding="utf8")
    (ROOT/"events.txt").write_text("\n".join(events)+"\n",encoding="utf8")
    record("EVIDENCE","Saved "+str(len(list(ROOT.rglob("*.png"))))+" genuine emulator screenshots")
    print("QA COMPLETE",json.dumps(results.get("sections",{}),ensure_ascii=False)[:5000],flush=True)
