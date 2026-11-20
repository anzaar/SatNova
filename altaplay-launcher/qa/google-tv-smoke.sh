#!/usr/bin/env bash
# Real Google TV 14 / Android TV AVD smoke test of the *released* APK.
# The ZEN 4 hardware and HDMI receiver are intentionally absent.
set -euo pipefail
mkdir -p qa-results

{
  echo 'Google TV version:'; adb shell getprop ro.build.version.release
  echo 'Device characteristics:'; adb shell getprop ro.build.characteristics
  echo 'TV model:'; adb shell getprop ro.product.model
  echo 'TV features:'; adb shell pm list features | grep -E 'television|leanback' || true
  echo 'TV packages:'; adb shell pm list packages | grep -E 'google.tv|android.tv' || true
} | tee qa-results/device.txt

adb shell input keyevent KEYCODE_HOME
sleep 5
adb exec-out screencap -p > qa-results/01-google-tv-original-home.png

echo 'Installing the released AltaPlay Launcher v0.2 APK...'
adb install -r -t qa-results/AltaPlay-Launcher-v0.2-debug.apk | tee qa-results/install.txt
adb shell pm path com.altaplay.launcher | tee qa-results/package.txt
grep -q 'package:' qa-results/package.txt

echo 'Opening AltaPlay Launcher Activity...'
adb shell am start -W -n com.altaplay.launcher/com.gathin.tvhome.MainActivity | tee qa-results/start.txt
sleep 9
adb exec-out screencap -p > qa-results/02-altaplay-first-launch-offline-zen.png
test -s qa-results/02-altaplay-first-launch-offline-zen.png
adb shell dumpsys activity activities > qa-results/activity-before.txt
grep -Fq 'com.altaplay.launcher' qa-results/activity-before.txt

# Accessibility/UI dump is optional: Google setup can block it on first boot.
timeout 45s adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 &&
  adb pull /sdcard/window.xml qa-results/first-window.xml >/dev/null || true

echo 'Testing Google TV remote D-pad + BACK navigation...'
for key in KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_DPAD_LEFT KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER KEYCODE_BACK; do
  adb shell input keyevent "$key"
  sleep 1
done
adb exec-out screencap -p > qa-results/03-altaplay-after-remote-navigation.png

echo 'Testing optional HOME selection in disposable emulator...'
adb shell cmd package set-home-activity com.altaplay.launcher/com.gathin.tvhome.MainActivity > qa-results/set-default-home.txt 2>&1 || true
adb shell input keyevent KEYCODE_HOME
sleep 5
adb exec-out screencap -p > qa-results/04-home-key-after-default.png
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > qa-results/resolved-home.txt 2>&1 || true
adb shell dumpsys window > qa-results/window.txt

adb logcat -d -v time > qa-results/logcat.txt || true
adb logcat -d -b crash -v time > qa-results/crash-buffer.txt || true
if grep -q -E 'Process: com.altaplay.launcher|ANR in com.altaplay.launcher' qa-results/crash-buffer.txt; then
  echo 'FAIL: AltaPlay Launcher crash/ANR detected' | tee qa-results/qa-summary.txt
  exit 2
fi

if grep -q 'com.altaplay.launcher' qa-results/resolved-home.txt; then
  echo 'PASS: APK installed and launched, Google TV remote actions sent, launcher resolved as HOME, no AltaPlay crash/ANR in crash buffer. ZEN 4 / HDMI hardware untested.' | tee qa-results/qa-summary.txt
else
  echo 'PARTIAL: APK installed and launched, remote events sent, no AltaPlay crash/ANR. HOME resolution inconclusive. ZEN 4 / HDMI hardware untested.' | tee qa-results/qa-summary.txt
fi
