#!/usr/bin/env python3
"""Apply the validated AltaPlay v0.5 *real* connected Zen UI after v0.1+v0.3+v0.4."""
from pathlib import Path
import sys, shutil
root=Path(sys.argv[1]).resolve()
here=Path(__file__).resolve().parent
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
main=java/"MainActivity.java"
panel=java/"AltaPlayZenPanel.java"
if not all(p.is_file() for p in (main,panel,java/"ZenClient.java",java/"HabousPrayerSchedule.java",java/"ZenWeather.java")):
    raise SystemExit("Apply all prior overlays before v0.5")
shutil.copy2(here/"AltaPlayZenPanel.java",panel)
s=main.read_text(encoding="utf-8")
needle="        if (lunar) lunarDate.setText(Lunar.today(getResources(), lunarOffset));"
assert s.count(needle)==1,"Upstream greeting method anchor changed"
s=s.replace(needle,needle+'\n        greeting.setText(AltaPlayZenPanel.greetingForHour(hour));',1)
main.write_text(s,encoding="utf-8")
dock=java/"DockDecoration.java"
s=dock.read_text(encoding="utf-8")
for old,new in [
    ("0x8C121014","0x88234740"),
    ("0x141416","0x244940"),
    ("0x24FFFFFF, 0x0DFFFFFF","0x365A987D, 0x184F866C")
]:
    if old not in s:raise RuntimeError(f"DockDecoration default changed: {old}")
    s=s.replace(old,new)
dock.write_text(s,encoding="utf-8")
tile=launcher/"app/src/main/res/drawable/tile_bg.xml"
s=tile.read_text(encoding="utf-8")
if "#FF1C1C1E" not in s:raise RuntimeError("Tile background changed")
tile.write_text(s.replace("#FF1C1C1E","#C5274842"),encoding="utf-8")
pills=launcher/"app/src/main/res/drawable/pill_bg.xml"
s=pills.read_text(encoding="utf-8")
for old,new in [("#2EFFFFFF","#3A729C87"),("#14FFFFFF","#1B4B7063")]:
    if old not in s:raise RuntimeError("Pill look changed")
    s=s.replace(old,new)
pills.write_text(s,encoding="utf-8")
print("AltaPlay v0.5 real Zen channel and radio cards, Arabic prayer/date/greeting, ambient weather and natural tint applied")
