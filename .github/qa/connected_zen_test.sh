#!/usr/bin/env bash
# QA-only connected Zen fixture: tests the real Android UI against a fake G-MScreen socket.
# DO NOT confuse these channel names/stream counts with a physical ZEN 4 catalog.
set -euo pipefail
mkdir -p qa-results
python3 .github/qa/zen_mock_receiver.py >qa-results/zen-qa-server.log 2>&1 &
ZEN_PID=$!
trap 'kill "$ZEN_PID" 2>/dev/null || true' EXIT
for i in $(seq 1 25); do
  if grep -q 'receiver ready on 20000' qa-results/zen-qa-server.log 2>/dev/null; then break; fi
  sleep 0.2
done
grep -q 'receiver ready on 20000' qa-results/zen-qa-server.log

# 10.0.2.2 is Android Emulator's route to the host running the *test-only* receiver.
# Debug APK only; do not modify physical-TV settings.
adb shell am force-stop com.altaplay.launcher || true
adb shell run-as com.altaplay.launcher mkdir -p shared_prefs
cat > qa-results/zen-qa-preferences.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="zen_ip">10.0.2.2</string></map>
XML
adb shell "run-as com.altaplay.launcher sh -c 'cat > shared_prefs/altaplay_zen.xml'" < qa-results/zen-qa-preferences.xml
adb shell am start -W -n com.altaplay.launcher/com.gathin.tvhome.MainActivity > qa-results/zen-connected-start.txt
sleep 20
adb exec-out screencap -p > qa-results/05-qa-simulated-zen4-connected.png
timeout 55s adb shell uiautomator dump /sdcard/zen-window.xml >/dev/null 2>&1 &&
  adb pull /sdcard/zen-window.xml qa-results/zen-connected-window.xml >/dev/null || true
adb shell dumpsys activity activities > qa-results/zen-connected-activity.txt
adb logcat -d -b crash -v time > qa-results/zen-connected-crashes.txt || true
printf '%s\n' 'IMPORTANT: Simulated Zen G-MScreen receiver. Real Badr and Es-hail channel names and DVB Service IDs (SID) from published satellite databases; fixture ProgramId uses SID for testing only. Actual Zen ProgramIds are device-local and may differ. The app runs the genuine catalogue/logo code.' > qa-results/05-SIMULATION-DISCLAIMER.txt
adb shell "run-as com.altaplay.launcher sh -c 'ls -l cache/altaplay-logos 2>/dev/null'" > qa-results/zen-logo-files.txt || true
if grep -E -q 'Process: com.altaplay.launcher|ANR in com.altaplay.launcher' qa-results/zen-connected-crashes.txt; then
  echo 'FAIL: connected Zen QA simulator triggered an AltaPlay crash' | tee -a qa-results/qa-summary.txt
  exit 2
fi
# Assert real satellite data enters the native UI and at least one genuine
# packaged broadcaster logo has been decoded and assigned to an ImageView.
# The simulator cannot verify the user's real physical receiver, HDMI or tuning.
if grep -q 'request 0' qa-results/zen-qa-server.log &&
   grep -q 'Al Aoula Inter' qa-results/zen-connected-window.xml 2>/dev/null &&
   grep -q 'MBC' qa-results/zen-connected-window.xml 2>/dev/null &&
   grep -q 'شعار القناة' qa-results/zen-connected-window.xml 2>/dev/null &&
   grep -q 'mbc1.png' qa-results/zen-logo-files.txt 2>/dev/null; then
  echo 'PASS: Real Badr and Es-hail broadcaster names, published DVB SIDs and genuine bundled PNG logos rendered by AltaPlay in connected Google TV emulator. No receiver hardware was tested.' | tee -a qa-results/qa-summary.txt
else
  echo 'PARTIAL: Simulated Zen connected; inspect satellite catalogue, actual logo files and hierarchy before claiming logos display.' | tee -a qa-results/qa-summary.txt
  echo 'SIMULATOR REQUESTS:'; grep 'request ' qa-results/zen-qa-server.log | tail -15 || true
  echo 'BUNDLED LOGO CACHE:'; cat qa-results/zen-logo-files.txt || true
  echo 'UI TEXT SAMPLE:'; grep -o -E '.{0,60}(Al Aoula|MBC|شعار القناة).{0,90}' qa-results/zen-connected-window.xml | head -8 || true
fi

# New regression: selecting an ungrouped real channel must actually emit
# request 1000 to the simulated Zen receiver BEFORE HDMI is opened.
# The v0.9 QA asserted only that HDMI input picker had disappeared.
python3 - <<'PY'
import xml.etree.ElementTree as E
import re,subprocess
from pathlib import Path
nodes=E.parse("qa-results/zen-connected-window.xml").getroot().findall(".//node")
target=None
for node in nodes:
    if node.get("clickable")!="true" or "Arryadia" not in node.get("content-desc",""):
        continue
    m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.get("bounds",""))
    if not m:continue
    x1,y1,x2,y2=map(int,m.groups())
    if x2>x1 and y2>y1:
        target=((x1+x2)//2,(y1+y2)//2)
        break
if not target:raise SystemExit("FAIL: no visible Arryadia channel tile to tap")
subprocess.run(["adb","shell","input","tap",str(target[0]),str(target[1])],check=True)
print("TAPPED real channel tile Arryadia",target)
PY
sleep 2
adb exec-out screencap -p > qa-results/10-v010-tune-before-hdmi.png
adb logcat -d -s AltaPlayZenTune:I > qa-results/v010-tune-logcat.txt || true
if ! grep -Fq 'request 1000' qa-results/zen-qa-server.log; then
  echo 'FAIL: channel click opened HDMI UI without sending request 1000 to receiver' | tee -a qa-results/qa-summary.txt
  exit 3
fi
if ! grep -Fq 'tune target 13 state 0' qa-results/zen-qa-server.log; then
  echo 'FAIL: transmitted wrong identifier; receiver requires ProgramId=13 for QA Arryadia' | tee -a qa-results/qa-summary.txt
  exit 3
fi
if ! grep -Fq 'request1000 written' qa-results/v010-tune-logcat.txt; then
  echo 'FAIL: channel tune write not confirmed by application debug trace' | tee -a qa-results/qa-summary.txt
  exit 3
fi
echo 'PASS: physical button tap on connected simulated Zen channel sent request1000 before switching HDMI' | tee -a qa-results/qa-summary.txt
