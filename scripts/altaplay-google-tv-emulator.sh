#!/usr/bin/env bash
# Real Android 14 Google TV AVD screenshots for experimental Secreto+Zen APK.
# No HDMI hardware/Zen receiver in emulator: do not claim receiver tests.
set -Eeuo pipefail
OUT="altaplay-emulator-evidence"
mkdir -p "$OUT/screens"
exec > >(tee "$OUT/test.log") 2>&1
PKG="com.altaplay.launcher"
ACT="com.gathin.tvhome.MainActivity"
ZEN="com.satnova.alpha.AltaPlayZenActivity"
SVC="com.satnova.alpha.Zen4HdmiRemoteService"
APK="build-secreto/AltaPlay-Launcher-Secreto-v1.3.2-Zen-TEST.apk"
capture() {
  local label="$1"
  echo "-- Capture $label --"
  adb exec-out screencap -p > "$OUT/screens/$label.png" || true
  adb shell dumpsys activity activities > "$OUT/$label-activities.txt" || true
  grep -m 1 'topResumedActivity=' "$OUT/$label-activities.txt" || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/window.xml > "$OUT/$label-ui.xml" 2>/dev/null || true
}
echo "Google TV Android API: $(adb shell getprop ro.build.version.sdk)"
echo "Android product: $(adb shell getprop ro.product.name)"
adb shell cmd role get-role-holders android.app.role.HOME > "$OUT/role-before.txt" || true
capture 00_stock_google_tv
adb install -r "$APK"
adb shell pm path "$PKG"
adb shell dumpsys package "$PKG" > "$OUT/package.txt"
echo "Launch original Secreto MainActivity (rebranded AltaPlay) explicitly."
adb shell am start -W -n "$PKG/$ACT" | tee "$OUT/launch-home.txt" || true
sleep 7
capture 01_altaplay_original_home
echo "D-pad app navigation"
adb shell input keyevent KEYCODE_DPAD_DOWN || true
sleep 2
capture 02_altaplay_down_apps
adb shell input keyevent KEYCODE_BACK || true
sleep 2
echo "Test HOME assignment without disabling the stock launcher"
adb shell cmd package set-home-activity "$PKG/$ACT" | tee "$OUT/set-home-activity.txt" || true
adb shell input keyevent KEYCODE_HOME
sleep 6
capture 03_altaplay_after_home
adb shell cmd role get-role-holders android.app.role.HOME > "$OUT/role-after.txt" || true
echo "Open Zen 4 settings (no physical Zen receiver present)"
adb shell am start -W -n "$PKG/$ZEN" | tee "$OUT/launch-zen.txt" || true
sleep 6
capture 04_zen_settings_offline
echo "Enable ADB-assisted Accessibility on disposable test emulator only"
adb shell settings put secure enabled_accessibility_services "$PKG/$SVC" || true
adb shell settings put secure accessibility_enabled 1 || true
sleep 5
adb shell dumpsys accessibility > "$OUT/accessibility.txt" || true
adb shell am start -W -n "$PKG/$ACT" > "$OUT/relauch-home.txt" 2>&1 || true
sleep 5
capture 05_altaplay_overlay_offline
echo "Test GUIDE channel selection, should not affect native HOME"
adb shell input keyevent KEYCODE_GUIDE || true
sleep 3
capture 06_guide_zen_mode
adb shell input keyevent KEYCODE_BACK || true
adb shell input keyevent KEYCODE_HOME || true
sleep 5
capture 07_home_after_guide
echo "Reboot to verify HOME survives restart (AVD only)"
adb reboot || true
adb wait-for-device || true
boot=0
for n in $(seq 1 100); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then boot=1; break; fi
  sleep 2
done
if [ "$boot" -eq 1 ]; then
  sleep 8
  adb shell input keyevent KEYCODE_HOME || true
  sleep 7
  capture 08_home_after_reboot
  adb shell cmd role get-role-holders android.app.role.HOME > "$OUT/role-after-reboot.txt" || true
else
  echo "RESULT: FAIL boot incomplete"
fi
adb logcat -d -b crash > "$OUT/crash.log" || true
adb logcat -d -t 8000 > "$OUT/logcat-tail.txt" || true
if grep -q "Process: $PKG" "$OUT/crash.log"; then
  echo "RESULT: FAIL AltaPlay crash in logcat"
else
  echo "RESULT: No $PKG process crash in crash buffer"
fi
echo "RESULT: Captured $(find "$OUT/screens" -name '*.png' | wc -l) authentic emulator screenshots."
echo "RESULT: Physical Zen4 channel tuning and real HDMI remote are NOT emulated."
