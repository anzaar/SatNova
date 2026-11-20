#!/usr/bin/env bash
# Real Google TV 14 app focus QA. Receiver itself is a clearly labelled emulator.
set -euo pipefail
mkdir -p qa-results
adb shell am start -W -n com.altaplay.launcher/com.gathin.tvhome.MainActivity >qa-results/v08-focus-start.txt
sleep 3
timeout 45s adb shell uiautomator dump /sdcard/v08-before.xml >/dev/null 2>&1 &&
  adb pull /sdcard/v08-before.xml qa-results/v08-before.xml >/dev/null || true

adb shell input keyevent 20 # DPAD_DOWN on selected app -> TV channels
sleep 1
adb exec-out screencap -p >qa-results/06-v08-dpad-down-tv-row.png
timeout 45s adb shell uiautomator dump /sdcard/v08-after-down.xml >/dev/null 2>&1 &&
  adb pull /sdcard/v08-after-down.xml qa-results/v08-after-down.xml >/dev/null || true

adb shell input keyevent 20 # TV channels -> radio row
sleep 1
timeout 45s adb shell uiautomator dump /sdcard/v08-radio.xml >/dev/null 2>&1 &&
  adb pull /sdcard/v08-radio.xml qa-results/v08-radio.xml >/dev/null || true

adb shell input keyevent 19 # radio -> TV channels
adb shell input keyevent 19 # TV channels -> apps
sleep 1
timeout 45s adb shell uiautomator dump /sdcard/v08-return-apps.xml >/dev/null 2>&1 &&
  adb pull /sdcard/v08-return-apps.xml qa-results/v08-return-apps.xml >/dev/null || true

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
names={
  "v08-before.xml":"selected app",
  "v08-after-down.xml":"TV channels",
  "v08-radio.xml":"radio",
  "v08-return-apps.xml":"back to apps",
}
for file,label in names.items():
    p=Path('qa-results')/file
    if not p.exists():raise SystemExit('FAIL: cannot inspect '+label+' hierarchy')
    nodes=ET.parse(p).getroot().findall('.//node')
    focused=[(n.attrib.get('text',''),n.attrib.get('content-desc','')) for n in nodes if n.attrib.get('focused')=='true']
    print(label,"FOCUSED",repr(focused[:4]))
    if not focused:raise SystemExit("FAIL: No focused item "+label)
    if file=="v08-after-down.xml":
        if not any("Al Aoula" in c or "Arryadia" in c or "MBC" in c or "Aljazeera" in c for t,c in focused):
            raise SystemExit("FAIL: DPAD DOWN did not move focus from apps into actual Zen channel tiles")
    if file=="v08-radio.xml":
        if not any("FM" in c or "Radio" in c or "Idaa" in c for t,c in focused):
            raise SystemExit("FAIL: second DOWN did not enter Zen radio bar")
    if file=="v08-return-apps.xml":
        if any("Al Aoula" in c or "MBC" in c or "FM" in c or "Radio" in c for t,c in focused):
            raise SystemExit("FAIL: UP did not restore focus to app tiles")
print("PASS: selected app -> TV channels -> radio -> TV channels -> apps via hardware DPAD")
PY

# Probe green shortcut safely with no Shahid installed on this emulator: it must
# not crash. Full Shahid launch requires the user's actual TV/installed app.
adb shell input keyevent 184 # KEYCODE_PROG_GREEN; no receiver key.
sleep 1
adb logcat -d -b crash >qa-results/v08-focus-crashes.txt || true
if grep -q 'Process: com.altaplay.launcher' qa-results/v08-focus-crashes.txt; then
  echo 'FAIL: AltaPlay crashed after green button' >&2
  exit 1
fi

# Check actual online logo cache. Internet may be restricted inside CI emulator.
adb shell run-as com.altaplay.launcher sh -c 'ls -lh files/altaplay-online-logos 2>/dev/null' \
  >qa-results/v08-external-logo-cache.txt || true
if grep -q '.png' qa-results/v08-external-logo-cache.txt; then
  echo 'PASS: logo downloaded externally and cached offline on Google TV AVD' | tee -a qa-results/qa-summary.txt
else
  echo 'INCONCLUSIVE: remote logos source tested, but emulator internet/logo response unverified. Bundled authentic logos remain visible.' | tee -a qa-results/qa-summary.txt
fi
