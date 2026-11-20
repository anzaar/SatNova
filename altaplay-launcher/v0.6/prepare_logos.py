#!/usr/bin/env python3
"""Build-time only. Package a small, audited set of REAL TV channel logo PNGs.

No arbitrary user-supplied URL, on-device web scraping, or channel name displayed
as a counterfeit logo. Official satellite identities stay separate from Zen's
device-local ProgramId. The APK still works with unknown channels without logos.
"""
from pathlib import Path
import urllib.request, urllib.error
import re, time, hashlib

ROOT=Path("upstream/launcher/app/src/main/assets/altaplay_channel_logos")
ROOT.mkdir(parents=True,exist_ok=True)
GITHUB="https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/"
MAROC="https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/"
sources={
    "al-aoula":MAROC+"al-aoula.png",
    "arryadia":MAROC+"arryadia.png",

    "aljazeera":GITHUB+"united-kingdom/aljazeera-uk.png",
    "mbc1":GITHUB+"united-arab-emirates/mbc-1-ae.png",
    "mbc2":GITHUB+"united-arab-emirates/mbc-2-ae.png",
    "mbc3":GITHUB+"united-arab-emirates/mbc-3-ae.png",
    "mbc4":GITHUB+"united-arab-emirates/mbc-4-ae.png",
    "mbc-action":GITHUB+"united-arab-emirates/mbc-action-ae.png",
    "mbc-max":GITHUB+"united-arab-emirates/mbc-max-ae.png",
}
ok=[]
sha_seen={}
for name,url in sources.items():
    try:
        req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (AltaPlay TV logo preload; offline app assets)","Accept":"image/png"})
        with urllib.request.urlopen(req,timeout=16) as response:
            data=response.read(500001)
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data)>500000 or len(data)<100:
            raise ValueError("Expected nonempty PNG below 500 KiB")
        width=int.from_bytes(data[16:20],"big")
        height=int.from_bytes(data[20:24],"big")
        if not 16<=width<=1800 or not 16<=height<=1800:
            raise ValueError("Unexpected image dimensions")
        sha=hashlib.sha256(data).hexdigest()
        if sha in sha_seen:
            raise ValueError("Duplicate image payload for "+name+" and "+sha_seen[sha]+"; reject generic placeholder")
        sha_seen[sha]=name
        (ROOT/(name+".png")).write_bytes(data)
        ok.append(name)
        print(f"LOGO OK {name}: {width}x{height}, {len(data)} bytes, {url}")
    except Exception as e:
        print(f"LOGO MISSING {name}: {type(e).__name__} {str(e)[:100]} — falls back to text")
print("REAL PNG logos bundled:",len(ok),"/",len(sources))
if not {"mbc1","mbc2","aljazeera","al-aoula","arryadia"}.issubset(ok):
    raise SystemExit("Missing minimum validated official-brand-logo sample; do not claim the logo test passed")
