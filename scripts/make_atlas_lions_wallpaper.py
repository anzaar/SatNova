#!/usr/bin/env python3
"""SatNova Atlas Lions sports identity, full-HD, independent of the Islamic theme.

Real stadium photography by Reda Benkhadra, CC BY-SA 4.0:
https://commons.wikimedia.org/wiki/File:Moulay_Abdellah_Stadium.jpg
This adapted image is distributed CC BY-SA 4.0 with attribution in SatNova.
A source copy outside Android assets makes regeneration deterministic.
No player/team portrait, commercial logo, match claims, or UI text is baked in.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
import math

W,H=1920,1080
out=Path("tvhome/src/main/assets/satnova_sport_theme.webp")
src=Path("scripts/resources/moulay_abdellah_stadium_source.webp")
src.parent.mkdir(parents=True,exist_ok=True)
if not src.exists():
    assert out.is_file(), "Source stadium asset unavailable"
    src.write_bytes(out.read_bytes())
photo=Image.open(src).convert("RGB").resize((W,H),Image.Resampling.LANCZOS)
photo=ImageEnhance.Contrast(photo).enhance(1.14)
photo=ImageEnhance.Color(photo).enhance(1.14)
image=photo.convert("RGBA")
# Left 65% stays almost as calm as the original Islamic mosque background.
shade=Image.new("RGBA",(W,H),(0,0,0,0))
d=ImageDraw.Draw(shade,"RGBA")
for x in range(W):
    r=x/W
    opacity=int(243-143*r**1.32)
    color=(4+int(28*r),23-int(7*r),19-int(2*r),opacity)
    d.line((x,0,x,H),fill=color)
image=Image.alpha_composite(image,shade)
# Subtle crimson floodlight hue near the right, with natural stadium visible.
red=Image.new("RGBA",(W,H),(0,0,0,0));d=ImageDraw.Draw(red,"RGBA")
for y in range(0,610,4):
    intensity=int(75*(1-y/610))
    d.line((990,y,W,y),fill=(147,8,35,max(0,intensity)),width=4)
red=red.filter(ImageFilter.GaussianBlur(35))
image=Image.alpha_composite(image,red)
# Flowing Moroccan red banner high on right.
flag=Image.new("RGBA",(W,H),(0,0,0,0));d=ImageDraw.Draw(flag,"RGBA")
top=lambda i: 70+61*math.sin(i/210)+25*math.sin(i/52)
bottom=lambda i: 405+83*math.sin(i/193+1.2)+31*math.sin(i/43)
coords=[(1015+i,top(i)) for i in range(0,911,10)]
coords += [(1015+i,bottom(i)) for i in range(910,-1,-10)]
d.polygon(coords,fill=(161,8,37,104))
for k in range(20):
    t=k/20
    d.line([(1015+i,(1-t)*top(i)+t*bottom(i)) for i in range(0,911,18)],
           fill=(253,104,113,8 if k%4 else 20),width=3)
image=Image.alpha_composite(image,flag.filter(ImageFilter.GaussianBlur(1.2)))
# Green pentagram is a Moroccan flag star, NOT a federation logo.
star=Image.new("RGBA",(W,H),(0,0,0,0));d=ImageDraw.Draw(star,"RGBA")
cx,cy,rad=1615,282,160
pts=[(cx+rad*math.cos(-math.pi/2+2*math.pi*i/5),
      cy+rad*math.sin(-math.pi/2+2*math.pi*i/5)) for i in range(5)]
line=[pts[i] for i in (0,2,4,1,3,0)]
d.line(line,fill=(0,22,23,164),width=25,joint="curve")
d.line(line,fill=(25,164,88,235),width=14,joint="curve")
d.line(line,fill=(100,216,148,98),width=3,joint="curve")
glow=star.filter(ImageFilter.GaussianBlur(16))
glow.putalpha(glow.getchannel("A").point(lambda a:int(a*.22)))
image=Image.alpha_composite(image,glow)
image=Image.alpha_composite(image,star)
# Abstract original Atlas lion relief, intentionally faint behind foreground TV UI.
lion=Image.new("RGBA",(W,H),(0,0,0,0));d=ImageDraw.Draw(lion,"RGBA")
mx,my=1640,825
mane=[]
for i in range(48):
    a=i*math.tau/48
    radius=193+(20 if i%2==0 else -12)+14*math.cos(3*a)
    mane.append((mx+radius*math.cos(a),my+radius*math.sin(a)))
d.polygon(mane,fill=(92,67,19,18))
d.line(mane+[mane[0]],fill=(230,173,88,50),width=3,joint="curve")
head=[(1565,747),(1582,728),(1613,716),(1640,730),(1661,759),
      (1680,780),(1674,802),(1697,817),(1707,836),(1696,854),
      (1668,865),(1640,863),(1615,885),(1570,885),(1544,863),
      (1536,829),(1552,799),(1548,773),(1565,747)]
d.line(head,fill=(238,179,96,72),width=6,joint="curve")
d.arc((1608,755,1660,802),200,341,fill=(241,201,130,75),width=5)
d.line([(1668,833),(1690,842),(1678,849)],fill=(241,201,130,65),width=5)
image=Image.alpha_composite(image,lion.filter(ImageFilter.GaussianBlur(1)))
# Muted green-red stadium light-line, tying Moroccan identity to sports hardware.
acc=Image.new("RGBA",(W,H),(0,0,0,0));d=ImageDraw.Draw(acc,"RGBA")
d.line([(x,527+22*math.sin(x/217)) for x in range(1090,W+12,12)],
       fill=(213,153,75,61),width=2)
d.line((0,H-5,W,H-5),fill=(6,114,64,66),width=4)
image=Image.alpha_composite(image,acc)
out.parent.mkdir(parents=True,exist_ok=True)
image.convert("RGB").save(out,"WEBP",quality=90,method=6)
assert Image.open(out).size == (W,H)
print("Atlas Lions wallpaper:",out,out.stat().st_size,"bytes")
