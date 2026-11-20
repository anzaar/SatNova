#!/usr/bin/env python3
"""Build a compact *lookup* for names of real TV broadcaster images.
Images themselves are downloaded on the TV only when a channel card is visible,
and stored under app-private cache. Offline bundled PNGs remain available.
"""
from pathlib import Path
import json,re,urllib.request
out=Path("upstream/launcher/app/src/main/assets/altaplay_logo_urls.tsv")
out.parent.mkdir(parents=True,exist_ok=True)
raw="https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
sources={
 "al aoula":"https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/al-aoula.png",
 "al aoula inter":"https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/al-aoula.png",
 "الأولى":"https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/al-aoula.png",
 "arryadia":"https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/arryadia.png",
 "الرياضية":"https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/arryadia.png",
 "mbc":"https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-arab-emirates/mbc-1-ae.png",
 "mbc 1":"https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-arab-emirates/mbc-1-ae.png",
 "mbc 2":"https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-arab-emirates/mbc-2-ae.png",
 "mbc action":"https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-arab-emirates/mbc-action-ae.png",
 "aljazeera channel":"https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/aljazeera-uk.png",
 "aljazeera":"https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/aljazeera-uk.png",
 "الجزيرة":"https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom/aljazeera-uk.png",
}
def normalize(s):
    s=re.sub(r"(?i)\s+(4k|uhd|fhd|hd|sd)$","",s)
    return " ".join(re.findall(r"[\w]+",s.lower(),re.UNICODE)).strip()
fail=0;candidate={};ambiguous=set()
try:
    url="https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
    with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"AltaPlay-Logo-Catalog/0.8","Accept":"application/vnd.github+json"}),timeout=30) as resp:
        tree=json.load(resp).get("tree",[])
    for row in tree:
        p=row.get("path","")
        if row.get("type")!="blob" or not p.startswith("countries/") or not p.endswith(".png"):continue
        slug=p.rsplit("/",1)[-1][:-4]
        # tv-logos names have final country suffix, e.g mbc-2-ae.png
        slug=re.sub(r"-[a-z]{2}$","",slug)
        alias=normalize(slug.replace("-"," "))
        if len(alias)<4:continue
        uri=raw+p
        if alias in candidate and candidate[alias]!=uri:ambiguous.add(alias)
        else:candidate[alias]=uri
    for alias,uri in candidate.items():
        if alias not in ambiguous and alias not in sources:sources[alias]=uri
except Exception as e:
    print("Offline build source fallback:",str(e)[:180],"— known verified logos only")
    fail+=1
with out.open("w",encoding="utf-8") as fp:
    for name,url in sorted(sources.items()):
        key=normalize(name)
        if key and url.startswith("https://raw.githubusercontent.com/") and url.endswith(".png"):
            fp.write(key+"\t"+url+"\n")
print("LOGO INDEX",len(sources),"aliases, ambiguous skipped",len(ambiguous),"offline API fallback",bool(fail))
assert len(sources)>=12
