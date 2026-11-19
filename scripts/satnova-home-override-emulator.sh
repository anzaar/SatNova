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
echo "Capture real UI screenshots BEFORE opt-in HOME testing."
# A visual tour failure must not prevent the separate HOME regression.
python3 scripts/satnova-ui-tour.py > home-override-evidence/ui-tour.log 2>&1 || true
# The tour may leave the main activity on a system screen; reset its focus.
adb shell am force-stop "$PKG" || true
# Keep stock HOME intact. This test must NOT disable or uninstall any launcher.
echo "HOME holder at baseline:"
adb shell cmd role get-role-holders android.app.role.HOME | tee home-override-evidence/role-before.txt
# App receives its opt-in preference before the service is enabled.
adb shell am force-stop "$PKG" || true
# Use run-as with direct executable arguments; adb shell strips sh -c
# quoting and the previous test accidentally passed an empty mkdir command.
adb shell run-as "$PKG" mkdir -p shared_prefs
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' \
  '<map><boolean name="home_override_enabled" value="true"/></map>' \
  | adb shell run-as "$PKG" tee shared_prefs/zen4_hdmi_bridge.xml > /dev/null
adb shell run-as "$PKG" cat shared_prefs/zen4_hdmi_bridge.xml | tee home-override-evidence/opt-in-test-preference.txt
echo "Enabling service as a diagnostic via ADB on the ephemeral emulator ONLY"
adb shell settings put secure enabled_accessibility_services "$PKG/$SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell dumpsys accessibility > home-override-evidence/accessibility.txt || true
adb shell settings get secure user_setup_complete > home-override-evidence/user-setup-complete.txt || true
grep -F "$SERVICE" home-override-evidence/accessibility.txt || true
adb logcat -c || true
echo "Open SatNova explicitly, then press HOME (which previously opened stock launcher)"
adb shell am start -W -n "$PKG/$ACT" | tee home-override-evidence/start.txt
sleep 3
adb shell input keyevent 3
sleep 8
adb shell dumpsys activity activities > home-override-evidence/activities-home.txt
adb exec-out screencap -p > home-override-evidence/home-after-override.png || true
adb logcat -d -s SatNovaHomeOverride:I ActivityTaskManager:W > home-override-evidence/home-override-events.txt || true
grep -m1 'topResumedActivity=' home-override-evidence/activities-home.txt | tee home-override-evidence/home-focused.txt || true
if grep -Eq "topResumedActivity=.*$PKG" home-override-evidence/activities-home.txt; then
    echo "RESULT: PASS; SatNova took focus after physical-equivalent HOME event"
else
    echo "RESULT: FAIL; Google TV still focused or Accessibility blocked"
    adb logcat -d -t 2500 > home-override-evidence/logcat-home-fail.txt || true
    echo "Look at home-override-events.txt for stock package and Android launch rejection details"
    exit 41
fi
echo "Reboot disposable Google TV emulator and test HOME after auto-reconnect"
adb reboot
adb wait-for-device
boot_ready=0
for n in $(seq 1 100); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then
    boot_ready=1
    break
  fi
  sleep 2
done
if [ "$boot_ready" -ne 1 ]; then
  echo "RESULT: FAIL; emulator did not complete reboot"
  exit 43
fi
# Record stock/default role plus accessibility state AFTER reboot;
# distinguish OEM launcher precedence from service lost at boot.
echo "HOME role holder after reboot:"
adb shell cmd role get-role-holders android.app.role.HOME \
  | tee home-override-evidence/role-after-reboot.txt || true
echo "Enabled accessibility services after reboot:"
adb shell settings get secure enabled_accessibility_services \
  | tee home-override-evidence/enabled-services-after-reboot.txt || true
echo "Accessibility global flag after reboot:"
adb shell settings get secure accessibility_enabled \
  | tee home-override-evidence/accessibility-flag-after-reboot.txt || true
adb shell dumpsys accessibility > home-override-evidence/accessibility-after-reboot.txt || true
adb shell run-as "$PKG" cat shared_prefs/zen4_hdmi_bridge.xml \
  > home-override-evidence/opt-in-after-reboot.txt || true
adb shell dumpsys activity activities > home-override-evidence/activities-boot.txt
adb logcat -d -s SatNovaHomeOverride:V ActivityTaskManager:W \
  > home-override-evidence/home-override-events-after-reboot.txt || true
grep -m1 'topResumedActivity=' home-override-evidence/activities-boot.txt || true

sleep 7
echo "Testing FIRST HOME press after reboot"
adb shell input keyevent 3
sleep 8
adb shell dumpsys activity activities > home-override-evidence/activities-after-first-home.txt
adb exec-out screencap -p > home-override-evidence/reboot-home.png || true
grep -m1 'topResumedActivity=' home-override-evidence/activities-after-first-home.txt || true
if grep -Eq "topResumedActivity=.*$PKG" home-override-evidence/activities-after-first-home.txt; then
  echo "RESULT: PASS; first HOME press after reboot opened SatNova"
else
  echo "RESULT: WARN; first HOME press after reboot did NOT open SatNova"
  # Recovering on retry is NOT a first-press PASS.
  recovered=0
  for retry in 1 2 3; do
    sleep 5
    echo "Retry HOME after reboot: $retry"
    adb shell input keyevent 3
    sleep 7
    adb shell dumpsys activity activities > "home-override-evidence/activities-home-retry-${retry}.txt"
    if grep -Eq "topResumedActivity=.*$PKG" "home-override-evidence/activities-home-retry-${retry}.txt"; then
      echo "RESULT: RECOVERED; HOME worked only on retry $retry after reboot"
      recovered=1
      break
    fi
  done
  if [ "$recovered" -eq 0 ]; then
    echo "RESULT: WARN; HOME still unavailable after reboot retries"
  fi
fi
adb logcat -d -s SatNovaHomeOverride:V AccessibilityManagerService:W ActivityTaskManager:W \
  > home-override-evidence/home-override-events-after-reboot.txt || true
# The stock launcher remains installed and enabled during every test.
adb logcat -d -b crash > home-override-evidence/crash-log.txt || true
if grep -Fq "Process: $PKG" home-override-evidence/crash-log.txt; then
  echo "RESULT: FAIL; SatNova crashed"
  exit 42
fi
echo "RESULT: done; never disabled stock HOME or required production ADB"
