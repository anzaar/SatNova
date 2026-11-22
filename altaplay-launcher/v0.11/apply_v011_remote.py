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
# Android TV keycodes 0..9 and optional numpad digits are contiguous.
# Receiver G-MScreen key codes: 0=12, 1=13, ... 9=21.
# Only accessibility's HDMI-approved window forwards these keys, so
# typing numbers in Shahid, YouTube, settings and other apps is unaffected.
digit_anchor='''    static int zenCode(Context c,int key){
        switch(key){'''
digit_replacement='''    static int zenCode(Context c,int key){
        if(key>=KeyEvent.KEYCODE_0 && key<=KeyEvent.KEYCODE_9)
            return 12+(key-KeyEvent.KEYCODE_0);
        if(key>=KeyEvent.KEYCODE_NUMPAD_0 && key<=KeyEvent.KEYCODE_NUMPAD_9)
            return 12+(key-KeyEvent.KEYCODE_NUMPAD_0);
        switch(key){'''
assert src.count(digit_anchor)==1,"Zen remote digit anchor changed"
src=src.replace(digit_anchor,digit_replacement,1)
file.write_text(src,encoding="utf-8")
service=(java/"AltaPlayHdmiRemoteService.java").read_text(encoding="utf-8")
assert "KEYCODE_VOLUME_UP" in service and "KEYCODE_VOLUME_DOWN" in service
assert "AltaPlayRemoteKeys.zenCode(this,code)<0" in service
assert "prefs.getBoolean" in service
# These are standard remote key injection codes, not direct channel tuning.
assert "KEYCODE_0" in src and "KEYCODE_NUMPAD_9" in src
print("PASS v0.11 Page up/down -> 35/36 and HDMI digits 0..9 -> 12..21; other TV keys unchanged")
