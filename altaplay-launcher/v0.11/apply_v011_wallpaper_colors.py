#!/usr/bin/env python3
"""AltaPlay v0.11: preserve genuine Islamic wallpaper colors by default.

Do not let focused app branded ambient glows (red/green) or weather tints
recolor the user's selected wallpaper. Remove redundant hour greetings while
preserving clock, Hijri, prayers, apps, Zen and the actual image.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
main=java/"MainActivity.java"
panel=java/"AltaPlayZenPanel.java"
s=main.read_text(encoding="utf-8")
def once(src,old,new,label):
    count=src.count(old)
    if count!=1:raise RuntimeError(f"wallpaper color patch {label} anchor={count}")
    return src.replace(old,new,1)
s=once(s,"        ambientGlow = cfg.ambientGlow;",
'''        // Disable moving app-color wash: it tinted the Islamic wallpaper red
        // on some cards, green on others. Focus outline remains functional.
        ambientGlow = false;''',"ambient glow")
s=once(s,"        appGlow = cfg.appGlow;",
'''        appGlow = false; // app card focus no longer changes wallpaper tint''',"app glow")
main.write_text(s,encoding="utf-8")
s=panel.read_text(encoding="utf-8")
s=once(s,
'''        if(greeting!=null)greeting.setText(greetingForHour(LocalTime.now().getHour()));''',
'''        if(greeting!=null)greeting.setVisibility(View.GONE);''',"hide greeting refresh")
# Another greeting expression is used by installSystemChrome.
s=once(s,
'''            greeting.setText(greetingForHour(LocalTime.now().getHour()));''',
'''            greeting.setVisibility(View.GONE); // keep wallpaper uncluttered''',"hide greeting install")
# Weather text stays available; remove the fullscreen visual tint.
s=once(s,
'''        atmosphere=new View(activity);
        atmosphere.setFocusable(false);atmosphere.setClickable(false);
        atmosphere.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(atmosphere,Math.min(1,root.getChildCount()),
            new FrameLayout.LayoutParams(-1,-1));''',
'''        atmosphere=null; // no colored fullscreen layer over owner wallpaper''',
"remove atmospheric overlay")
panel.write_text(s,encoding="utf-8")
print("PASS v0.11: fixed wallpaper image colors; no app/forecast colored glow or redundant greeting")
