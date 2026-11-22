#!/usr/bin/env python3
"""v0.11 Remote: route HDMI Page+/Page- to ZEN volume, no other key changes.

G-MScreen request 1040: KeyValue 35=volume up, 36=volume down as
documented by compatible-receiver implementation (hardware Zen 4 QA pending).
KEYCODE_VOLUME_UP/DOWN and TV volume remain handled by the TV OS.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
file=java/"AltaPlayRemoteKeys.java"
src=file.read_text(encoding="utf-8")
needle="""            case KeyEvent.KEYCODE_CHANNEL_UP:return 69;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:return 70;"""
replacement="""            case KeyEvent.KEYCODE_CHANNEL_UP:return 69;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:return 70;
            // Page + / - affect the receiver's internal volume only on HDMI.
            // TV's physical VOLUME_UP/DOWN are explicitly excluded from the
            // accessibility service and continue to control television audio.
            case KeyEvent.KEYCODE_PAGE_UP:return 35;
            case KeyEvent.KEYCODE_PAGE_DOWN:return 36;"""
assert src.count(needle)==1,"remote channel keys changed: do not disturb existing keys"
src=src.replace(needle,replacement)
file.write_text(src,encoding="utf-8")
service=(java/"AltaPlayHdmiRemoteService.java").read_text(encoding="utf-8")
assert "KEYCODE_VOLUME_UP" in service and "KEYCODE_VOLUME_DOWN" in service
assert "AltaPlayRemoteKeys.zenCode(this,code)<0" in service
assert "prefs.getBoolean" in service
print("PASS v0.11 Page up/down -> receiver 35/36; CH+/CH- and television volume remain unchanged")
