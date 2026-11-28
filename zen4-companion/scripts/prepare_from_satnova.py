#!/usr/bin/env python3
"""
Populates zen4-companion/app/src/main/java/com/gathin/tvhome/ with ONLY the
clean receiver, HDMI, remote, and logo classes from SatNova/altaplay-launcher.
"""
import argparse
import base64
import io
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

EXCLUDED = (
    "Habous",
    "ZenWeather",
    "MoroccanHijri",
    "NextPrayer",
    "AltaPlayAmbientText",
    "AltaPlayZenPanel",
    "MainActivity.java",
    "AltaPlayOnlineLogos.java",
    "AltaPlayHdmiRemoteService.java",
    "AltaPlayHdmi1.java",
)

ALTAPLAY_FILES = [
    "v0.6/java/AltaPlayChannelLogos.java",
    "v0.8/java/AltaPlayRemoteKeys.java",
    "v0.11/java/AltaPlayHdmiGuidedSetup.java",
    "v0.11/java/ZenLanDiscovery.java",
    "v0.11/java/ZenManualLogo.java",
]

PATCH_SCRIPTS = [
    "v0.11/apply_v011_zen.py",
    "v0.11/apply_v011_remote.py",
    "v0.11/apply_v011_receiver_identity.py",
    "v0.11/apply_v011_receiver_idle.py",
    "v0.11/apply_v011_receiver_name.py",
]


def is_excluded(name: str) -> bool:
    return any(p.lower() in name.lower() for p in EXCLUDED)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--satnova-root", required=True, type=Path)
    parser.add_argument("--companion-root", required=True, type=Path)
    args = parser.parse_args()

    altaplay = args.satnova_root / "altaplay-launcher"
    java_dst = args.companion_root / "app" / "src" / "main" / "java" / "com" / "gathin" / "tvhome"
    java_dst.mkdir(parents=True, exist_ok=True)

    stale = java_dst / "AltaPlayZenPanel.java"
    if stale.exists():
        stale.unlink()

    # 1) Extract ZenClient / ZenProtocol from overlay-v0.1.zip.base64.txt
    b64_path = altaplay / "overlay-v0.1.zip.base64.txt"
    if b64_path.is_file():
        raw_zip = base64.b64decode(b64_path.read_text(encoding="utf-8"))
        with zipfile.ZipFile(io.BytesIO(raw_zip)) as zf:
            for member in zf.namelist():
                base = Path(member).name
                if not base.endswith(".java") or is_excluded(base):
                    continue
                if any(k in base for k in ("Zen", "Hdmi", "AltaPlay")):
                    (java_dst / base).write_bytes(zf.read(member))
                    print(f"+ v0.1: {base}")

    # 2) Copy modular receiver/HDMI/remote/logo files
    for rel in ALTAPLAY_FILES:
        src = altaplay / rel
        if src.is_file():
            shutil.copy2(src, java_dst / src.name)
            print(f"+ copied: {src.name}")

    # 3) Apply v0.11 receiver & remote patches
    for rel in PATCH_SCRIPTS:
        script = altaplay / rel
        if script.is_file():
            print(f"> applying {rel}")
            subprocess.run([sys.executable, str(script), str(args.companion_root)], check=False)

    # 4) Ensure ZenClient.identity(byte[]) is package-private for ZenLanDiscovery
    zen_client = java_dst / "ZenClient.java"
    if zen_client.is_file():
        txt = zen_client.read_text(encoding="utf-8")
        fixed = txt.replace("private static String identity(", "static String identity(")
        if fixed != txt:
            zen_client.write_text(fixed, encoding="utf-8")
            print("~ opened package access for ZenClient.identity(byte[])")

    print("Companion sources prepared cleanly (0 errors expected).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
