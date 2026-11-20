#!/usr/bin/env python3
"""Build-time repack ONLY. Original Secreto code/resources remain intact.

Upstream APK is downloaded from its official public GitHub release by the CI
workflow and verified against the official SHA-256 before patching.
No upstream APK, private signing key or decompiled source is committed here.
"""
from __future__ import annotations
import argparse
import re
import sys
import zipfile
from pathlib import Path

SOURCE_PACKAGE = "com.secreto.tvlauncher"
TARGET_PACKAGE = "com.altaplay.launcher"
A = "http://schemas.android.com/apk/res/android"

def patch(decoded: Path, repository: Path) -> None:
    manifest = decoded / "AndroidManifest.xml"
    raw = manifest.read_text(encoding="utf-8")
    if not re.search(r'<manifest\b[^>]*\bpackage="' + SOURCE_PACKAGE + '"', raw, re.S):
        raise RuntimeError("Manifest package unexpected; abort instead of patching arbitrary APK")
    raw, changes = re.subn(
        r'(<manifest\b[^>]*\bpackage=")' + SOURCE_PACKAGE + '"',
        r'\g<1>' + TARGET_PACKAGE + '"', raw, count=1, flags=re.S
    )
    assert changes == 1
    # Provider authorities and manifest-declared actions may embed the old
    # application ID. They must be distinct to install beside official Secreto.
    raw = raw.replace(SOURCE_PACKAGE, TARGET_PACKAGE)
    if 'com.gathin.tvhome.MainActivity' not in raw:
        raise RuntimeError("Missing original Secreto MainActivity; abort")
    # Add ONLY the permissions needed for Zen. Existing Secreto permissions remain.
    permissions = (
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    )
    prefix = ""
    for permission in permissions:
        if f'android:name="{permission}"' not in raw:
            prefix += f'    <uses-permission android:name="{permission}"/>\n'
    if not re.search(r'<application\b', raw):
        raise RuntimeError("No existing application node")
    raw = raw.replace("<application", prefix + "    <application", 1)

    # Rename visible launcher label; keep all Secreto application/component logic.
    begin = raw.index("<application")
    end = raw.index(">", begin)
    head = raw[begin:end]
    if re.search(r'android:label="[^"]*"', head):
        head = re.sub(r'android:label="[^"]*"', 'android:label="AltaPlay Launcher"', head, count=1)
    else:
        head += ' android:label="AltaPlay Launcher"'
    raw = raw[:begin] + head + raw[end:]

    xml = """        <!-- AltaPlay Zen add-on: original Secreto activities/services untouched. -->
        <service android:name="com.satnova.alpha.Zen4PersistentService"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="connectedDevice"/>
        <service android:name="com.satnova.alpha.Zen4HdmiRemoteService"
            android:label="AltaPlay · Zen 4 (HDMI)"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService"/>
            </intent-filter>
            <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/altaplay_zen_accessibility"/>
        </service>
        <activity android:name="com.satnova.alpha.AltaPlayIslamicImagePickerActivity"
            android:label="AltaPlay Islamic wallpaper"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.OPEN_DOCUMENT"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.OPENABLE"/>
                <data android:mimeType="image/*"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.GET_CONTENT"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.OPENABLE"/>
                <data android:mimeType="image/*"/>
            </intent-filter>
        </activity>
        <provider android:name="com.satnova.alpha.AltaPlayIslamicWallpaperProvider"
            android:authorities="com.altaplay.launcher.islamic"
            android:grantUriPermissions="true"
            android:exported="false"/>
        <activity android:name="com.satnova.alpha.AltaPlayZenActivity"
            android:label="AltaPlay · Zen 4"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
            </intent-filter>
        </activity>
"""
    raw = raw.replace("</application>", xml + "    </application>", 1)
    manifest.write_text(raw, encoding="utf-8")

    xmlfile = decoded / "res/xml/altaplay_zen_accessibility.xml"
    xmlfile.parent.mkdir(parents=True, exist_ok=True)
    xmlfile.write_text("""<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:notificationTimeout="100"
    android:canRequestFilterKeyEvents="true"
    android:canRetrieveWindowContent="true"
    android:description="@android:string/ok"/>
""", encoding="utf-8")

    for name in ("satnova_wallpaper.webp", "badr26-picons.tsv"):
        src = repository / "tvhome/src/main/assets" / name
        dst = decoded / "assets" / name
        if not src.is_file():
            raise RuntimeError("Missing bundled Zen asset: " + str(src))
        dst.write_bytes(src.read_bytes())

    # The publisher's app-id appears in original alarm actions, update checks,
    # etc.; replace only that exact package string.  Class names, methods,
    # dialogs and feature implementations are NOT changed or stripped.
    patched_smali = 0
    for smali_root in decoded.glob("smali*"):
        if not smali_root.is_dir():
            continue
        for f in smali_root.rglob("*.smali"):
            s = f.read_text(encoding="utf-8")
            if SOURCE_PACKAGE in s:
                f.write_text(s.replace(SOURCE_PACKAGE, TARGET_PACKAGE), encoding="utf-8")
                patched_smali += 1
    if not patched_smali:
        raise RuntimeError("Didn't find original package constants in Secreto smali")
    print(f"Original Secreto smali patched in {patched_smali} classes; features otherwise preserved")

def inject_dex(rebuilt: Path, zen_apk: Path, output: Path) -> None:
    if not rebuilt.is_file() or not zen_apk.is_file():
        raise RuntimeError("APK missing")
    with zipfile.ZipFile(rebuilt) as original, zipfile.ZipFile(zen_apk) as zen:
        sources = sorted((x for x in zen.namelist()
                          if re.fullmatch(r"classes\d*\.dex", x)),
                         key=lambda x: int(x[7:-4]) if x != "classes.dex" else 1)
        if not sources:
            raise RuntimeError("No Zen dex classes")
        occupied = {x for x in original.namelist()
                    if re.fullmatch(r"classes\d*\.dex", x)}
        index = 2
        with zipfile.ZipFile(output, "w") as mod:
            for info in original.infolist():
                mod.writestr(info, original.read(info.filename))
            for src in sources:
                while f"classes{index}.dex" in occupied:
                    index += 1
                new = f"classes{index}.dex"
                mod.writestr(new, zen.read(src), compress_type=zipfile.ZIP_STORED)
                print(f"Added Zen DEX {src} as {new}")
                index += 1

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["patch", "inject"])
    p.add_argument("paths", nargs="+")
    a = p.parse_args()
    if a.mode == "patch" and len(a.paths) == 2:
        patch(Path(a.paths[0]), Path(a.paths[1]))
    elif a.mode == "inject" and len(a.paths) == 3:
        inject_dex(*(Path(i) for i in a.paths))
    else:
        p.error("patch <decoded> <repo>; inject <rebuilt> <zen_apk> <output>")
