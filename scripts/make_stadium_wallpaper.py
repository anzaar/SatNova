#!/usr/bin/env python3
"""SatNova sports theme at 1920×1080 from a licensed real 2025 stadium photograph.

Source: https://commons.wikimedia.org/wiki/File:Moulay_Abdellah_Stadium.jpg
Photo © Reda Benkhadra, CC BY-SA 4.0.
Edits: crop to 16:9, careful dark/green/red atmospheric grading, subtle vignette.
The adapted image is licensed CC BY-SA 4.0 as well. Attribution is in the app.
"""
from io import BytesIO
from pathlib import Path
from urllib.request import Request, urlopen
import math
import time
from PIL import Image, ImageEnhance, ImageFilter, ImageDraw

OUT = Path("tvhome/src/main/assets/satnova_sport_theme.webp")
if OUT.is_file() and OUT.stat().st_size > 50_000:
    print(f"Reusing packaged stadium theme: {OUT}")
    raise SystemExit(0)

URLS = [
    "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/Moulay_Abdellah_Stadium.jpg/1920px-Moulay_Abdellah_Stadium.jpg",
    "https://upload.wikimedia.org/wikipedia/commons/0/09/Moulay_Abdellah_Stadium.jpg",
    "https://wsrv.nl/?url=upload.wikimedia.org/wikipedia/commons/0/09/Moulay_Abdellah_Stadium.jpg&w=1920",
    "https://images.weserv.nl/?url=upload.wikimedia.org/wikipedia/commons/0/09/Moulay_Abdellah_Stadium.jpg&w=1920",
    "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/Moulay_Abdellah_Stadium.jpg/1280px-Moulay_Abdellah_Stadium.jpg",
]
raw = None
for url in URLS:
    try:
        with urlopen(Request(url, headers={
            "User-Agent": "SatNova TV Home/0.5.14 (https://github.com/anzaar/SatNova; Wikimedia credit shown in UI)",
            "Accept": "image/jpeg,image/webp,image/*"
        }), timeout=25) as response:
            raw = response.read(9_000_001)
        if len(raw) > 9_000_000:
            raise ValueError("Photo larger than expected")
        photo = Image.open(BytesIO(raw)).convert("RGB")
        if photo.width < 1200:
            raise ValueError("Insufficient resolution")
        print("Licensed photo loaded at", photo.size)
        break
    except Exception as e:
        print("Source unavailable", url, e)
        time.sleep(3)
else:
    raise RuntimeError("Cannot fetch licensed stadium photo from approved sources.")

# Keep the pitch as visual anchor, with seats and the stadium roof visible.
w, h = photo.size
target = 16 / 9
if w / h < target:
    crop_h = int(w / target)
    top = int(max(0, min(h - crop_h, (h - crop_h) * 0.40)))
    photo = photo.crop((0, top, w, top + crop_h))
else:
    crop_w = int(h * target)
    left = (w - crop_w) // 2
    photo = photo.crop((left, 0, left + crop_w, h))

photo = photo.resize((1920, 1080), Image.Resampling.LANCZOS)
photo = ImageEnhance.Contrast(photo).enhance(1.12)
photo = ImageEnhance.Color(photo).enhance(0.91)
photo = ImageEnhance.Sharpness(photo).enhance(1.08)

# Screen-scale graded vignette: important TV UI elements stay readable.
overlay = Image.new("RGBA", (1920, 1080), (0, 0, 0, 0))
draw = ImageDraw.Draw(overlay)
for y in range(1080):
    ratio = y / 1080
    shade = 104 if ratio < .23 else int(104 + 45 * (ratio - .23) / .77)
    shade = max(0, min(170, shade))
    draw.line((0, y, 1920, y), fill=(1, 17, 18, shade))
# Vignette only near edges; center lawn remains recognizable.
vignette = Image.new("RGBA", (1920, 1080), (0, 0, 0, 0))
draw = ImageDraw.Draw(vignette)
for edge in range(0, 280):
    strength = int(31 * (1 - edge / 280) ** 1.8)
    draw.line((edge, 0, edge, 1079), fill=(0, 12, 16, strength))
    draw.line((1919-edge, 0, 1919-edge, 1079), fill=(0, 12, 16, strength))
# Editorial accent is deliberately restrained, not a foreground obstruction.
photo = Image.alpha_composite(photo.convert("RGBA"), overlay)
photo = Image.alpha_composite(photo, vignette).convert("RGB")
OUT.parent.mkdir(parents=True, exist_ok=True)
photo.save(OUT, "WEBP", quality=88, method=6)
print(f"Sports wallpaper ready: {OUT}: {OUT.stat().st_size} bytes, {photo.size}")
