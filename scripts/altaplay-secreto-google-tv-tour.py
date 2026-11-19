#!/usr/bin/env python3
"""Real Android 14 Google TV AVD smoke test for the repackaged Secreto + Zen APK.

This is an evidence-capture test, not a simulation of a Zen 4 receiver.
Screen images are read from the running emulator with adb exec-out screencap.
Secreto's third-party original app may require permissions / initial setup.
"""
from __future__ import annotations
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path("altaplay-google-tv-evidence")
SCREENS = OUT / "screens"
OUT.mkdir(exist_ok=True)
SCREENS.mkdir(exist_ok=True)
APK = Path("build-secreto/AltaPlay-Launcher-Secreto-v1.3.2-Zen-TEST.apk")
PKG = "com.altaplay.launcher"
MAIN = "com.gathin.tvhome.MainActivity"
ZEN = "com.satnova.alpha.AltaPlayZenActivity"
SERVICE = "com.satnova.alpha.Zen4HdmiRemoteService"
results = []
errors = []

def cmd(*args, timeout=45, input=None):
    return subprocess.run(args, input=input, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout)

def adb(*args, timeout=45):
    return cmd("adb", *args, timeout=timeout)

def shell(*args, timeout=45):
    return adb("shell", *args, timeout=timeout)

def log(*args):
    print(*args, flush=True)

def record(name, value, expected=None):
    item = {"check": name, "value": value}
    if expected is not None:
        item["pass"] = expected(value)
    results.append(item)
    log("CHECK", name, ":", str(value)[:240],
        "PASS" if item.get("pass") else ("FAIL" if "pass" in item else ""))
    return item.get("pass", False)

def top():
    p = shell("dumpsys", "activity", "activities", timeout=25)
    text = p.stdout.decode(errors="replace")
    (OUT / "last-activities.txt").write_text(text[-50000:])
    lines = [x.strip() for x in text.splitlines()
             if "topResumedActivity" in x or "mResumedActivity" in x]
    return lines[:3]

def screenshot(name, explanation):
    image = adb("exec-out", "screencap", "-p", timeout=30)
    path = SCREENS / (name + ".png")
    success = image.returncode == 0 and image.stdout.startswith(b"\x89PNG")
    if success:
        path.write_bytes(image.stdout)
    results.append({"screenshot": name, "desc": explanation, "ok": success,
                    "bytes": len(image.stdout) if success else 0,
                    "foreground": top()})
    log("PHOTO", name, "CAPTURED" if success else "FAILED",
        len(image.stdout), "foreground:", top()[:1])
    return success

def xml_snapshot(name):
    try:
        dump = shell("uiautomator", "dump", "/sdcard/altaplay-ui.xml", timeout=32)
        raw = shell("cat", "/sdcard/altaplay-ui.xml", timeout=20)
        text = raw.stdout.decode(errors="replace")
        (OUT / (name + ".xml")).write_text(text)
        return ET.fromstring(text)
    except (ET.ParseError, subprocess.TimeoutExpired, OSError) as exc:
        log("UI dump unavailable:", str(exc)[:150])
        return ET.Element("empty")

def find_and_tap(keywords, stage):
    root = xml_snapshot(stage + "_before_tap")
    matches = []
    for elem in root.iter("node"):
        value = (" ".join(elem.attrib.get(k, "") for k in
                          ("text", "content-desc"))).lower()
        if not any(w.lower() in value for w in keywords):
            continue
        m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                      elem.attrib.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            matches.append((x1, y1, x2, y2, value[:130]))
    if not matches:
        record(stage + "_tap", "SKIP: not found (" + ", ".join(keywords) + ")")
        return False
    x1, y1, x2, y2, text = matches[0]
    r = shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    record(stage + "_tap", text + " rc=" + str(r.returncode))
    time.sleep(2.2)
    return True

def key(code):
    r = shell("input", "keyevent", str(code))
    time.sleep(1.2)
    return r.returncode == 0

def start_component(component, name, pause=5.0):
    r = shell("am", "start", "-W", "-n", PKG + "/" + component, timeout=45)
    output = (r.stdout + r.stderr).decode(errors="replace")
    record("start_" + name, output.strip()[-1200:], lambda x: "Error" not in x
           and "Exception" not in x and "Status: ok" in x)
    time.sleep(pause)
    return r.returncode == 0

def capture_logs():
    for name, args in (
        ("crash-logcat", ("logcat", "-d", "-b", "crash")),
        ("altaplay-logcat", ("logcat", "-d", "-t", "3500")),
        ("accessibility", ("dumpsys", "accessibility")),
        ("package", ("dumpsys", "package", PKG)),
        ("window", ("dumpsys", "window", "windows")),
    ):
        try:
            r = shell(*args, timeout=35)
            text = (r.stdout + r.stderr).decode(errors="replace")
            (OUT / (name + ".txt")).write_text(text[-250000:])
        except Exception as exc:
            record(name + "_dump", repr(exc))

def test():
    log("== AltaPlay direct Secreto mod: GOOGLE TV 14 actual emulator test ==")
    for prop in ("ro.build.version.sdk", "ro.product.name", "ro.build.type"):
        value = shell("getprop", prop).stdout.decode(errors="replace").strip()
        record(prop, value)
    record("screen", shell("wm", "size").stdout.decode(errors="replace").strip())
    screenshot("00_google_tv_baseline", "Stock Google TV before installing AltaPlay")
    if not APK.is_file():
        raise RuntimeError("APK missing: " + str(APK))
    r = adb("install", "-r", str(APK), timeout=145)
    text = (r.stdout + r.stderr).decode(errors="replace")
    if not record("install", text.strip(), lambda x: "Success" in x):
        raise RuntimeError("Android rejected installation: " + text[-800:])
    record("package_path", shell("pm", "path", PKG).stdout.decode(errors="replace").strip(),
           lambda x: "base.apk" in x)

    start_component(MAIN, "secreto_main", pause=7)
    screenshot("01_first_altaplay_secreto_home",
               "Original Secreto main activity under the AltaPlay package, initial start")
    record("home_foreground_initial", top(), lambda value: PKG in str(value))
    xml_snapshot("01_home")
    key(22)
    key(20)
    screenshot("02_secreto_remote_navigation",
               "Original Secreto interface after real TV D-pad inputs")
    if find_and_tap(["settings", "paramètres", "parametros", "إعدادات"],
                    "secreto_settings"):
        screenshot("03_secreto_settings",
                   "Secreto settings actually opened via the running TV interface")
        key(4)
    else:
        record("secreto_settings", "SKIP: not exposed in current AVD UI")

    # ADB enables Accessibility ONLY on a disposable test emulator.
    # This does not grant any permission on the owner's Lifemaxx TV.
    line = PKG + "/" + SERVICE
    shell("settings", "put", "secure", "enabled_accessibility_services", line)
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(5)
    enabled = shell("settings", "get", "secure",
                    "enabled_accessibility_services").stdout.decode(errors="replace").strip()
    record("zen_accessibility_configured", enabled, lambda x: SERVICE in x)
    start_component(MAIN, "main_after_accessibility", pause=4)
    screenshot("04_original_home_with_zen_accessibility",
               "Original Secreto UI with the extra Zen Accessibility service enabled")
    # A newly granted Accessibility service might not receive a state-change
    # event for an Activity already in focus. Force a real normal page change,
    # then capture whether the Zen bar appears back over the original HOME.
    start_component(ZEN, "zen_overlay_prime", pause=2)
    key(4)
    time.sleep(3)
    screenshot("04b_secreto_after_page_return",
               "Actual Secreto HOME after a real Activity transition with Zen service bound")
    record("secreto_zen_overlay_foreground", top(), lambda x: PKG in str(x)
           and "MainActivity" in str(x))
    key(172)  # GUIDE: opt-in channel navigation; no actual receiver is connected.
    screenshot("05_zen_guide_test",
               "Zen GUIDE test on Secreto HOME, showing true on-device overlay outcome")
    key(22)
    screenshot("06_zen_focus_navigation",
               "Real D-pad RIGHT event after GUIDE; no receiver simulated")
    key(4)
    screenshot("07_guide_cancel",
               "After BACK, Zen selection must stop and Secreto control resumes")

    start_component(ZEN, "zen_settings", pause=4)
    screenshot("08_zen_settings_and_offline_state",
               "AltaPlay Zen connection/settings page, offline receiver status")
    find_and_tap(["exporter", "export"], "islamic_export")
    screenshot("09_islamic_wallpaper_export_result",
               "Islamic wallpaper export button result (if present)")
    path = shell("ls", "-l", "/sdcard/Download/AltaPlay/",
                 timeout=20).stdout.decode(errors="replace")
    record("islamic_wallpaper_export", path.strip(),
           lambda x: "AltaPlay-Islamic.webp" in x)

    # On this ephemeral emulator we can opt into AltaPlay HOME, unlike production.
    r = shell("cmd", "package", "set-home-activity",
              PKG + "/" + MAIN, timeout=35)
    record("set_home_activity", (r.stdout+r.stderr).decode(errors="replace")[:800])
    key(3)
    time.sleep(5)
    screenshot("10_home_key_return",
               "HOME pressed from Zen settings; actual default HOME resolution")
    record("home_foreground_after_home_key", top(),
           lambda x: PKG in str(x))
    record("home_role",
           shell("cmd", "role", "get-role-holders",
                 "android.app.role.HOME").stdout.decode(errors="replace").strip())

    start_component(MAIN, "before_reboot", pause=2)
    screenshot("11_home_stability", "Secreto home after repeated navigation")
    log("Rebooting disposable Google TV emulator for startup regression")
    adb("reboot")
    adb("wait-for-device", timeout=150)
    ready = False
    for _ in range(80):
        val = shell("getprop", "sys.boot_completed", timeout=20).stdout.decode(errors="replace").strip()
        if val == "1":
            ready = True
            break
        time.sleep(2)
    record("reboot_complete", ready, lambda x: x)
    if ready:
        time.sleep(7)
        key(3)
        screenshot("12_home_after_reboot",
                   "HOME first press after reboot, no extra system bloat disabled")
        record("home_foreground_after_reboot", top(), lambda x: PKG in str(x))
    else:
        record("reboot_test", "SKIP: Google TV AVD did not finish reboot")
    crash = shell("logcat", "-d", "-b", "crash").stdout.decode(errors="replace")
    # Follow-up: on *disposable* Google TV emulator only, exclude the
    # unfinished Google TV setup stock launcher from the HOME competition.
    # This must NEVER be performed on the user's actual Lifemaxx TV.
    result = shell("pm", "disable-user", "--user", "0",
                   "com.google.android.apps.tv.launcherx", timeout=30)
    record("TEMP_google_launcher_disabled_emulator_only",
           (result.stdout + result.stderr).decode(errors="replace").strip())
    shell("cmd", "package", "set-home-activity", PKG + "/" + MAIN)
    time.sleep(3)
    key(3)
    time.sleep(4)
    screenshot("13_home_without_google_setup_emulator_only",
               "HOME after temporarily disabling unfinished Google TV setup on disposable AVD")
    record("home_foreground_stock_off", top(), lambda x: PKG in str(x))
    log("Reboot with Google setup temporarily disabled on this disposable AVD")
    adb("reboot")
    adb("wait-for-device", timeout=150)
    for _ in range(80):
        if shell("getprop", "sys.boot_completed").stdout.decode(errors="replace").strip() == "1":
            break
        time.sleep(2)
    time.sleep(6)
    key(3)
    screenshot("14_home_after_reboot_stock_off_emulator_only",
               "First HOME after reboot with Google TV setup disabled on disposable AVD")
    record("home_foreground_stock_off_after_reboot", top(), lambda x: PKG in str(x))
    # Restore Google stock package before the AVD disappears. A real user's
    # Google TV launcher is not touched by this QA at any point.
    restore = shell("pm", "enable", "--user", "0",
                    "com.google.android.apps.tv.launcherx", timeout=30)
    record("stock_google_launcher_restored",
           (restore.stdout + restore.stderr).decode(errors="replace").strip())
    crash = shell("logcat", "-d", "-b", "crash").stdout.decode(errors="replace")
    record("app_crash_log", "AltaPlay crash" if "Process: "+PKG in crash else "No AltaPlay crash in crash buffer",
           lambda x: x == "No AltaPlay crash in crash buffer")

try:
    test()
except Exception as exc:
    log("QA ERROR", repr(exc))
    errors.append(repr(exc))
finally:
    capture_logs()
    (OUT/"index.json").write_text(json.dumps(results,ensure_ascii=False,indent=2))
    summary = {
        "platform": "Google TV Android 14 AVD; TV 1080p",
        "app": "AltaPlay Launcher from unmodified Secreto v1.3.2 features plus Zen module",
        "screenshot_count": sum(1 for r in results if r.get("screenshot") and r.get("ok")),
        "errors": errors,
        "checks": [r for r in results if "check" in r],
        "limitations": [
            "No physical Lifemaxx hardware",
            "No Zen 4 receiver or real HDMI passthrough; cannot prove remote forwarding",
            "Screenshots are real emulator captures, not AI mockups",
            "No live catalogue unless a real Zen is paired",
        ],
    }
    (OUT/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    log("RESULT: screenshots",summary["screenshot_count"],"errors",errors)
    if errors:
        sys.exit(1)
