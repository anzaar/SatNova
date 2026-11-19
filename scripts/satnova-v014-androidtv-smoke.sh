#!/usr/bin/env bash
# Android TV virtual smoke test for the EXACT user-hosted, permanently signed SatNova v0.5.14.
# GitHub Actions may use ADB as a testing instrument, which does NOT prove an
# end-user can install or assign HOME without ADB on a physical Lifemaxx TV.
set -Eeuo pipefail
mkdir -p test-results
LOG="test-results/smoke.log"
exec > >(tee -a "$LOG") 2>&1
PACKAGE="com.satnova.tvhome.tvbridge"
ACTIVITY="com.satnova.alpha.MinimalHomeActivity"
APK_SHA="4d63206a49bfc807f0c001d5770dcc2479fd38040d02fbd5df71e047bdfc9a34"
CERT_SHA="4c4373d5917ec908c6f12310531dee83ac4bf613680e59aef59e0935ddcf3118"
FILE="test-results/SatNova-v0.5.14-SIGNED.apk"
echo "===== SATNOVA EXACT SIGNED APK SMOKE TEST ====="
date -u
echo "System API: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "System image: $(adb shell getprop ro.build.description | tr -d '\r')"
echo "Screen: $(adb shell wm size)"
echo "Downloading identical signed APK from user-supplied URL..."
curl -fLsS --retry 3 --retry-delay 3 --connect-timeout 20 --max-time 150 \
    "https://iptvroyal.fr/nova.apk" -o "$FILE"
ACTUAL_SHA="$(sha256sum "$FILE" | awk '{print $1}')"
echo "APK SHA256 = $ACTUAL_SHA"
if [ "$ACTUAL_SHA" != "$APK_SHA" ]; then
    echo "FATAL: remote nova.apk differs from previously verified permanent-signature v0.5.14"
    exit 30
fi
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
test -x "$BUILD_TOOLS/apksigner"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$FILE" > test-results/signature.txt
grep -Fqi "$CERT_SHA" test-results/signature.txt
"$BUILD_TOOLS/aapt" dump badging "$FILE" > test-results/badging.txt
grep -Fq "package: name='$PACKAGE' versionCode='20'" test-results/badging.txt
echo "Verified exact signed v0.5.14 APK, cert and Android manifest"

record() { printf '%s\n' "$1" >> test-results/results.tsv; }
record $'test\tresult\tdetail'
adb logcat -c || true
# Genuine clean-install cycles. Automated installation via ADB is a test
# instrument; these cycles are NOT evidence of ordinary Downloader success.
for cycle in 1 2 3; do
    echo ""
    echo "=== CLEAN INSTALL + LAUNCH CYCLE $cycle / 3 ==="
    adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
    if adb install "$FILE"; then record "install_cycle_$cycle"$'\tPASS\t'"adb install"; else
        record "install_cycle_$cycle"$'\tFAIL\t'"install rejected"
        exit 31
    fi
    adb shell pm path "$PACKAGE" | tee "test-results/installed-$cycle.txt"
    adb shell am force-stop "$PACKAGE" || true
    adb shell am start -W -n "$PACKAGE/$ACTIVITY" | tee "test-results/start-$cycle.txt"
    sleep 5
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/window.xml > "test-results/ui-$cycle.xml" || true
    adb exec-out screencap -p > "test-results/start-$cycle.png" || true
    if ! adb shell pidof "$PACKAGE" | grep -Eq '[0-9]'; then
        adb logcat -d -b crash > "test-results/crash-cycle-$cycle.txt" || true
        adb logcat -d -t 500 > "test-results/logcat-cycle-$cycle.txt" || true
        adb shell dumpsys activity activities > "test-results/activities-crash-cycle-$cycle.txt" || true
        adb shell dumpsys meminfo "$PACKAGE" > "test-results/meminfo-cycle-$cycle.txt" || true
        record "launch_cycle_$cycle"$'\tFAIL\t'"process not running; diagnostics saved"
        exit 32
    fi
    if adb logcat -d -b crash | grep -Fq "Process: $PACKAGE"; then
        adb logcat -d -b crash > "test-results/crash-$cycle.txt"
        record "launch_cycle_$cycle"$'\tFAIL\t'"AndroidRuntime crash"
        exit 33
    fi
    record "launch_cycle_$cycle"$'\tPASS\t'"process alive; screenshot saved"
    # Dpad/navigation: dispatch real Android remote KeyEvents, detect crashes.
    for code in 22 20 21 19 23 4; do
        adb shell input keyevent "$code" || true
        sleep 0.4
    done
    if adb logcat -d -b crash | grep -Fq "Process: $PACKAGE"; then
        adb logcat -d -b crash > "test-results/crash-nav-$cycle.txt"
        record "dpad_cycle_$cycle"$'\tFAIL\t'"crash"
        exit 34
    fi
    record "dpad_cycle_$cycle"$'\tPASS\t'"DPAD right/down/left/up/OK/BACK do not crash"
done
echo ""
echo "=== HOME ROLE AND HOME BUTTON TEST ==="
adb shell cmd role get-role-holders android.app.role.HOME > test-results/home-before.txt 2>&1 || true
echo "Original HOME: $(cat test-results/home-before.txt)"
# Granting this role through ADB is ONLY a proxy for Android recognition.
# Does not assert the normal TV Settings choice is available to end users.
if adb shell cmd role add-role-holder android.app.role.HOME "$PACKAGE" > test-results/home-role-command.txt 2>&1; then
    record "home_role_recognition"$'\tPASS\t'"Android accepted launcher role through automated test"
else
    record "home_role_recognition"$'\tWARN\t'"role command unavailable or declined"
fi
# Read back HOME role: accepting an adb command is not proof it changed.
adb shell cmd role get-role-holders android.app.role.HOME > test-results/home-after-role.txt 2>&1 || true
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > test-results/home-resolve.txt 2>&1 || true
echo "Holders after request: $(cat test-results/home-after-role.txt)"
echo "HOME resolve: $(cat test-results/home-resolve.txt)"
if grep -Fxq "$PACKAGE" test-results/home-after-role.txt; then
    record "home_role_holder"$'\tPASS\t'"SatNova listed as Android HOME holder"
else
    record "home_role_holder"$'\tWARN\t'"SatNova not listed as Android HOME holder"
fi
adb shell input keyevent 3
sleep 5
adb exec-out screencap -p > test-results/home-button.png || true
adb shell dumpsys activity activities > test-results/activities-home.txt || true
if grep -Eq "topResumedActivity=.*$PACKAGE" test-results/activities-home.txt; then
    record "home_after_keyevent"$'\tPASS\t'"SatNova appears in activity state (inspect screenshot)"
else
    record "home_after_keyevent"$'\tWARN\t'"SatNova not found in activity state; inspect screenshot and system HOME behavior"
fi
echo ""
echo "=== GOOGLE TV SETUP / HOME DIAGNOSTIC (ADB-ONLY, NOT END USER) ==="
adb shell settings get global device_provisioned > test-results/provisioned.txt || true
adb shell settings get secure user_setup_complete > test-results/user-setup-complete.txt || true
adb shell input keyevent 20 || true
adb shell input keyevent 23 || true
sleep 3
adb exec-out screencap -p > test-results/after-setup-cancel.png || true
adb shell cmd package set-home-activity --user 0 "$PACKAGE/$ACTIVITY" > test-results/home-set-command.txt 2>&1 || true
adb shell cmd role get-role-holders android.app.role.HOME > test-results/home-after-set.txt 2>&1 || true
adb shell input keyevent 3 || true
sleep 5
adb shell dumpsys activity activities > test-results/activities-after-set.txt || true
adb exec-out screencap -p > test-results/home-after-set.png || true
if grep -Eq "topResumedActivity=.*$PACKAGE" test-results/activities-after-set.txt; then
    record "home_forced_diagnostic"$'\tPASS\t'"SatNova resumed after explicit package-manager HOME (ADB-only)"
else
    record "home_forced_diagnostic"$'\tWARN\t'"TV startup/setup or HOME selection still overrides SatNova"
fi
echo ""
echo "=== VIRTUAL TV REBOOT TEST ==="
adb reboot || true
adb wait-for-device
BOOTED=0
for _ in $(seq 1 90); do
    if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then
        BOOTED=1
        break
    fi
    sleep 2
done
if [ "$BOOTED" -ne 1 ]; then
    record "reboot"$'\tFAIL\t'"system did not complete boot"
    exit 35
fi
sleep 8
adb shell input keyevent 3
sleep 5
adb shell dumpsys activity activities > test-results/activities-after-reboot.txt || true
adb exec-out screencap -p > test-results/reboot-home.png || true
if grep -Eq "topResumedActivity=.*$PACKAGE" test-results/activities-after-reboot.txt; then
    record "home_after_reboot"$'\tPASS\t'"SatNova appears in activity state after reboot"
else
    record "home_after_reboot"$'\tWARN\t'"SatNova not foreground after reboot (inspect UI)"
fi
adb logcat -d -b crash > test-results/crashes-all.txt || true
if grep -Fq "Process: $PACKAGE" test-results/crashes-all.txt; then
    record "no_fatal_crashes"$'\tFAIL\t'"AndroidRuntime crash appears"
    exit 36
fi
record "no_fatal_crashes"$'\tPASS\t'"no SatNova process crash observed"
echo ""
echo "=== VIRTUAL TEST SUMMARY ==="
cat test-results/results.tsv
echo "LIMITATIONS: no real Lifemaxx HDMI input, no real ZEN PRO 4, no OEM TV remote; HOME role is ADB-proxy only"
echo "COMPLETE"
