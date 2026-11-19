#!/usr/bin/env python3
"""Build a contact sheet from actual emulator screenshots for human review.
Never generate synthetic UI frames or turn missing screenshots into PASS.
"""
from pathlib import Path
import json, math, textwrap
from PIL import Image, ImageOps, ImageDraw, ImageFont
root=Path("home-override-evidence/screens")
pics=sorted(root.glob("*.png")) if root.exists() else []
if not pics:
    print("No screenshots; contact sheet not generated")
    raise SystemExit(0)
try:
    entries=json.loads((root/"index.json").read_text())
except Exception:
    entries=[]
captions={x.get("name"):x.get("caption","") for x in entries if isinstance(x,dict)}
W,H=480,270
P=22
LABEL=62
cols=3
rows=math.ceil(len(pics)/cols)
sheet=Image.new("RGB",(cols*(W+P)+P,rows*(H+LABEL+P)+P),(14,18,22))
d=ImageDraw.Draw(sheet)
fontfile="/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
font=ImageFont.truetype(fontfile,16) if Path(fontfile).exists() else ImageFont.load_default()
for index,p in enumerate(pics):
    x=P+(index%cols)*(W+P)
    y=P+(index//cols)*(H+LABEL+P)
    image=Image.open(p).convert("RGB")
    thumb=ImageOps.contain(image,(W,H),Image.Resampling.LANCZOS)
    sheet.paste(thumb,(x+(W-thumb.width)//2,y+(H-thumb.height)//2))
    d.rectangle((x,y,x+W,y+H),outline=(117,147,145),width=2)
    raw=p.stem+": "+captions.get(p.stem,"")
    lines=textwrap.wrap(raw,width=51)[:2]
    for k,line in enumerate(lines):
        d.text((x+3,y+H+5+22*k),line,font=font,fill=(230,237,238))
out=Path("home-override-evidence/SatNova_v1_Real_Screenshot_Contact_Sheet.jpg")
sheet.save(out,quality=88,optimize=True)
print(out,len(pics),"genuine screenshots",sheet.size)
