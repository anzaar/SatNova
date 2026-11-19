#!/usr/bin/env bash
# Testing-only: ADB enables Accessibility on a disposable emulator to simulate
# the user's separate explicit consent. Production never enables this permission.
set -Eeuo pipefail
mkdir -p home-override-evidence
exec > >(tee home-override-evidence/test.log) 2>&1
PKG=com.satnova.tvhome.tvbridge
ACT=com.satnova.alpha.MinimalHomeActivity
SERVICE=com.satnova.alpha.Zen4HdmiRemoteService
APK=tvhome/build/outputs/apk/tvbridge/debug/tvhome-tvbridge-debug.apk
echo "== SatNova optional HOME override virtual integration =="
echo "Android API: $(adb shell getprop ro.build.version.sdk)"
echo "Android product: $(adb shell getprop ro.product.name)"
adb install -r "$APK"
adb shell pm path "$PKG"
adb shell dumpsys package "$PKG" > home-override-evidence/package.txt
# Keep stock HOME intact. This test must NOT disable or uninstall any launcher.
echo "HOME holder at baseline:"
adb shell cmd role get-role-holders android.app.role.HOME | tee home-override-evidence/role-before.txt
# App receives its opt-in preference before the service is enabled.
adb shell am force-stop "$PKG" || true
adb shell run-as "$PKG" sh -c 'mkdir -p shared_prefs'
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' \
  '<map><boolean name="home_override_enabled" value="true"/></map>' \
  | adb shell run-as "$PKG" sh -c 'cat > shared_prefs/zen4_hdmi_bridge.xml'
adb shell run-as "$PKG" cat shared_prefs/zen4_hdmi_bridge.xml | tee home-override-evidence/opt-in-test-preference.txt
echo "Enabling service as a diagnostic via ADB on the ephemeral emulator ONLY"
adb shell settings put secure enabled_accessibility_services "$PKG/$SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell dumpsys accessibility > home-override-evidence/accessibility.txt || true
grep -F "$SERVICE" home-override-evidence/accessibility.txt || true
adb logcat -c || true
echo "Open SatNova explicitly, then press HOME (which previously opened stock launcher)"
adb shell am start -W -n "$PKG/$ACT" | tee home-override-evidence/start.txt
sleep 3
adb shell input keyevent 3
sleep 8
adb shell dumpsys activity activities > home-override-evidence/activities-home.txt
adb exec-out screencap -p > home-override-evidence/home-after-override.png || true
grep -m1 'topResumedActivity=' home-override-evidence/activities-home.txt | tee home-override-evidence/home-focused.txt || true
if grep -Eq "topResumedActivity=.*$PKG" home-override-evidence/activities-home.txt; then
    echo "RESULT: PASS; SatNova took focus after physical-equivalent HOME event"
else
    echo "RESULT: FAIL; Google TV still focused or Accessibility blocked"
    adb logcat -d -t 2500 > home-override-evidence/logcat-home-fail.txt || true
    exit 41
fi
echo "Reboot disposable Google TV emulator and test HOME after auto-reconnect"
adb reboot
adb wait-for-device
for n in $(seq 1 100); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break
  sleep 2
done
sleep 7
adb shell input keyevent 3
sleep 8
adb shell dumpsys activity activities > home-override-evidence/activities-after-reboot.txt
adb shell dumpsys accessibility > home-override-evidence/accessibility-after-reboot.txt || true
adb exec-out screencap -p > home-override-evidence/reboot-home.png || true
grep -m1 'topResumedActivity=' home-override-evidence/activities-after-reboot.txt || true
if grep -Eq "topResumedActivity=.*$PKG" home-override-evidence/activities-after-reboot.txt; then
  echo "RESULT: PASS; opt-in fallback restored HOME after reboot"
else
  echo "RESULT: WARN; reboot HOME still not SatNova (OEM/Google TV restrictions)"
fi
adb logcat -d -b crash > home-override-evidence/crash-log.txt || true
if grep -Fq "Process: $PKG" home-override-evidence/crash-log.txt; then
  echo "RESULT: FAIL; SatNova crashed"
  exit 42
fi
echo "RESULT: done; never disabled stock HOME or required production ADB"
