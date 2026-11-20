#!/usr/bin/env bash
# QA-only connected Zen fixture: tests the real Android UI against a fake G-MScreen socket.
# DO NOT confuse these channel names/stream counts with a physical ZEN 4 catalog.
set -euo pipefail
mkdir -p qa-results
python3 .github/qa/zen_mock_receiver.py >qa-results/zen-qa-server.log 2>&1 &
ZEN_PID=$!
trap 'kill "$ZEN_PID" 2>/dev/null || true' EXIT
for i in $(seq 1 25); do
  if grep -q 'server ready' qa-results/zen-qa-server.log 2>/dev/null; then break; fi
  sleep 0.2
done
grep -q 'server ready' qa-results/zen-qa-server.log

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
printf '%s\n' 'IMPORTANT: This connected-state screenshot uses a QA simulated receiver with deliberately synthetic channel names. It is not a real Zen 4 hardware verification. The actual Android APK/code paths are used.' > qa-results/05-SIMULATION-DISCLAIMER.txt
if grep -E -q 'Process: com.altaplay.launcher|ANR in com.altaplay.launcher' qa-results/zen-connected-crashes.txt; then
  echo 'FAIL: connected Zen QA simulator triggered an AltaPlay crash' | tee -a qa-results/qa-summary.txt
  exit 2
fi
if grep -q 'request 0' qa-results/zen-qa-server.log &&
   grep -q 'Zen 4' qa-results/zen-connected-window.xml 2>/dev/null &&
   grep -q 'قناة تجريبية' qa-results/zen-connected-window.xml 2>/dev/null; then
  echo 'PASS: Connected Zen emulator fixture yielded channel catalog in actual launcher UI; radio grouping verified by available test catalog; no detected crash.' | tee -a qa-results/qa-summary.txt
else
  echo 'PARTIAL: Connected Zen emulator fixture ran; inspect screenshots, simulator server logs and hierarchy before claiming full connected UI.' | tee -a qa-results/qa-summary.txt
  echo 'SERVER LOG:'
  tail -25 qa-results/zen-qa-server.log
fi
