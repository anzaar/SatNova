#!/usr/bin/env bash
# Deliberately small install/setup: direct follow-up is the UI wallpaper flow.
set -Eeuo pipefail
mkdir -p altaplay-emulator-evidence/screens
echo "Real Google TV Android: $(adb shell getprop ro.build.version.sdk)"
APK=build-secreto/AltaPlay-Launcher-Secreto-v1.3.2-Zen-TEST.apk
test -s "$APK"
adb install -r "$APK"
adb shell pm path com.altaplay.launcher
adb shell am start -W -n com.altaplay.launcher/com.gathin.tvhome.MainActivity
sleep 3
adb exec-out screencap -p > altaplay-emulator-evidence/screens/00_fresh_home.png
