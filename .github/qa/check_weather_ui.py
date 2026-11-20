#!/usr/bin/env python3
"""Fail the real Google TV QA if low-level weather exceptions leak into Arabic UI."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

paths=[Path(p) for p in sys.argv[1:]]
if not paths:
    paths=[Path("qa-results/first-window.xml"),Path("qa-results/zen-connected-window.xml")]
bad=("Read timed out","connect timed out","Failed to connect","UnknownHostException",
     "SocketTimeoutException","SSLHandshakeException","NetworkOnMainThreadException")
seen=0
for path in paths:
    if not path.is_file():
        print("INCONCLUSIVE: missing UI hierarchy",path)
        continue
    root=ET.parse(path).getroot()
    data="\n".join([n.attrib.get("text","")+" "+n.attrib.get("content-desc","")
                    for n in root.findall(".//node")])
    for word in bad:
        if word.lower() in data.lower():
            raise SystemExit("FAIL: unlocalized weather exception visible in "+str(path)+": "+word)
    if "الطقس" in data or "الخميسات" in data:
        seen+=1
        print("PASS: Arabic weather UI present without technical English exceptions:",path)
    else:
        print("INCONCLUSIVE: weather UI not exposed in hierarchy:",path)
if seen==0:print("INCONCLUSIVE: no readable weather label to inspect; check screenshot")
