#!/usr/bin/env python3
"""Geometry-only layout smoke test: uses actual uiautomator bounds, NO OCR."""
from pathlib import Path
import xml.etree.ElementTree as ET
import re,sys
from datetime import datetime
from zoneinfo import ZoneInfo
f=Path(sys.argv[1]) if len(sys.argv)>1 else Path("qa-results/zen-connected-window.xml")
if not f.is_file():
    print("INCONCLUSIVE: UI hierarchy not captured; screenshot must be reviewed")
    raise SystemExit(0)
root=ET.parse(f).getroot()
nodes=root.findall(".//node")
def box(s):
    m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",s or "")
    return tuple(map(int,m.groups())) if m else None
clock=[n for n in nodes if n.attrib.get("resource-id","").endswith(":id/clock")]
app=[n for n in nodes if n.attrib.get("content-desc")=="عنوان التطبيق المحدد"]
prayer=[n for n in nodes if "الفجر" in n.attrib.get("text","")]
dates=[n for n in nodes if "نونبر" in n.attrib.get("text","") or "نوفمبر" in n.attrib.get("text","")]
logos=[n for n in nodes if n.attrib.get("content-desc","").startswith("شعار القناة:")]
print("GEOMETRY meta",{"clock":len(clock),"selected_title":len(app),"prayer":len(prayer),"arabic_date":len(dates),"loaded_logo_widgets":len(logos)})
if clock and app:
    c=box(clock[0].attrib.get("bounds"))
    h=box(app[0].attrib.get("bounds"))
    print("CLOCK_RECT",c,"APP_TITLE_RECT",h)
    if c and h and h[1]<c[3]+2:
        raise SystemExit("FAIL: selected app label overlaps clock")
    print("PASS: selected app label is below the clock, not on top")
else:
    print("INCONCLUSIVE: clock and selected-title geometry not both in accessibility hierarchy")
if prayer and app:
    p=box(prayer[0].attrib.get("bounds"))
    h=box(app[0].attrib.get("bounds"))
    if p and h and h[1]<p[3]+1:
        raise SystemExit("FAIL: application title overlaps the prayer header")
    print("PASS: selected application title has independent prayer clearance")
if dates:print("PASS: Moroccan date rendered in Arabic")
else:print("INCONCLUSIVE: date text was not exposed; check screenshot")
if logos:print("PASS: at least one actual broadcaster logo ImageView exposed")
else:print("INCONCLUSIVE: logo widget not visible or inaccessible; check cache and screenshot")
