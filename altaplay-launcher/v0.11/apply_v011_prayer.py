#!/usr/bin/env python3
"""AltaPlay v0.11 prayer legibility, no increased startup cost.

Makes next prayer and time-remaining readable at TV distance. Reuses the
existing published Habous data and NextPrayer calculations verbatim. Does
not force a timezone on the display clock or add any timers.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
panel=launcher/"app/src/main/java/com/gathin/tvhome/AltaPlayZenPanel.java"
s=panel.read_text(encoding="utf-8")

def once(old,new,label):
    global s
    n=s.count(old)
    if n!=1:raise RuntimeError("prayer readability "+label+" anchor="+str(n))
    s=s.replace(old,new,1)

once('''        prayerText=title("",13,0xF5E7F5E8);
        prayerText.setSingleLine(true);''',
'''        prayerText=title("",18,0xFFF9FFF8);
        // Bigger and brighter, without expensive shadows/animations.
        // Preserve exact NextPrayer/Habous timings and countdown wording.
        prayerText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        prayerText.setSingleLine(true);''',"prayer 18sp bold")

once('''            left.addView(prayerText,new LinearLayout.LayoutParams(-1,dp(25)));''',
'''            left.addView(prayerText,new LinearLayout.LayoutParams(-1,dp(37)));''',
"reserve room for larger prayer text")

# At large Android font scales, keep the actual next-prayer countdown
# intelligible by allowing an optional second line (do not ellipsize it).
once('''        prayerText.setEllipsize(TextUtils.TruncateAt.END);''',
'''        prayerText.setEllipsize(null);
        prayerText.setSingleLine(false);
        prayerText.setMaxLines(2);''',
"show the full countdown")
panel.write_text(s,encoding="utf-8")
print("PASS v0.11: next prayer and time remaining 18sp bold, no truncation, Habous untouched")
