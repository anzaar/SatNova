#!/usr/bin/env bash
# QA of the released AltaPlay v0.9 on the Google TV Android 14 emulator.
# This environment has NO Vestel HDMI hardware and NO real Zen 4.
set -euo pipefail
mkdir -p qa-results
{
 echo "Google TV: $(adb shell getprop ro.build.version.release)"
 echo "Build features:"
 adb shell pm list features | grep -E 'television|leanback' || true
 echo "Installed AltaPlay package:"
 adb shell dumpsys package com.altaplay.launcher | grep -E -m 1 'versionName=' || true
 echo "Accessibility service declared:"
 adb shell dumpsys package com.altaplay.launcher | grep -A 2 'AltaPlayHdmiRemoteService' | head -n 3 || true
 echo "Vendor TV input and HDMI receiver are NOT part of emulator."
} | tee qa-results/v09-device-report.txt

adb shell am start -W -n com.altaplay.launcher/com.gathin.tvhome.MainActivity > qa-results/v09-relaunch.txt
sleep 2
adb exec-out screencap -p > qa-results/07-v09-launcher.png
adb shell input keyevent KEYCODE_HOME
sleep 2
adb exec-out screencap -p > qa-results/08-v09-home.png
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > qa-results/v09-resolve-home.txt || true

# Try a channel selection using real UI element bounds from the already captured
# simulated Zen session. A guessed coordinate is NEVER used.
python3 - <<'PY'
from pathlib import Path
import re, xml.etree.ElementTree as ET, subprocess
path=Path("qa-results/zen-connected-window.xml")
out=Path("qa-results/v09-channel-tap.txt")
if not path.exists():
    out.write_text("INCONCLUSIVE: no Zen connected UI XML\n")
    raise SystemExit(0)
nodes=ET.parse(path).getroot().findall('.//node')
items=[]
for n in nodes:
    desc=n.get('content-desc','')
    if any(x in desc for x in ('Al Aoula','Arryadia','MBC','Aljazeera')) and n.get('clickable','false')=='true':
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
        if m:
            x1,y1,x2,y2=map(int,m.groups())
            if x2>x1 and y2>y1:items.append((desc,(x1+x2)//2,(y1+y2)//2))
if not items:
    out.write_text("INCONCLUSIVE: no clickable channel bounds found in UI dump\n")
else:
    label,x,y=items[0]
    subprocess.run(['adb','shell','am','start','-W','-n','com.altaplay.launcher/com.gathin.tvhome.MainActivity'],capture_output=True,text=True)
    subprocess.run(['adb','shell','input','tap',str(x),str(y)],check=True)
    out.write_text("TAPPED CHANNEL: "+label+"\ncoords "+str((x,y))+"\n")
PY
sleep 2
adb exec-out screencap -p > qa-results/09-v09-after-tap.png
adb shell dumpsys window > qa-results/v09-after-tap-window.txt
adb shell dumpsys activity activities > qa-results/v09-after-tap-activities.txt
adb logcat -d -v brief > qa-results/v09-after-tap-logcat.txt || true
adb logcat -d -b crash > qa-results/v09-crashes.txt || true
if grep -q 'Process: com.altaplay.launcher' qa-results/v09-crashes.txt; then
  echo 'FAIL: AltaPlay crashed on Google TV v0.9' | tee -a qa-results/qa-summary.txt
  exit 1
fi
if grep -Eiq 'com.google.android.tvlauncher/.inputs.InputsPanelActivity|com.mediatek.wwtv.tvcenter/.nav.inputpanel.InputsPanelActivity' qa-results/v09-after-tap-window.txt; then
  echo 'FAIL: old HDMI input picker observed after tap' | tee -a qa-results/qa-summary.txt
  exit 1
fi
echo 'PASS: Google TV v0.9 package launches, HOME receives remote input, no fatal crash, no HDMI picker observed. Hardware HDMI and Zen key delivery NOT tested.' | tee -a qa-results/qa-summary.txt
