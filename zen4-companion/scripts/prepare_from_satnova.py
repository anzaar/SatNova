#!/usr/bin/env python3
"""
Populates zen4-companion/app/src/main/java/com/gathin/tvhome/ with ONLY the
receiver, channels, HDMI, and remote classes from SatNova/altaplay-launcher,
strictly excluding Adhan/Habous/Hijri/Weather and preserving our upgraded
MainActivity.java, AltaPlayHdmiRemoteService.java, and AltaPlayOnlineLogos.java.
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
    "MainActivity.java",
    "AltaPlayOnlineLogos.java",
    "AltaPlayHdmiRemoteService.java",
)

ALTAPLAY_FILES = [
    "v0.5/AltaPlayZenPanel.java",
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

    # 1) Extract ZenClient / ZenProtocol / AltaPlayHdmi1 from overlay-v0.1.zip.base64.txt
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

    # 3) Apply v0.11 receiver & remote patches (numpad 0-9, PAGE_UP/DOWN, LAN rediscovery)
    for rel in PATCH_SCRIPTS:
        script = altaplay / rel
        if script.is_file():
            print(f"> applying {rel}")
            subprocess.run([sys.executable, str(script), str(args.companion_root)], check=False)

    print("Companion sources prepared successfully (zero Adhan/Weather files).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
