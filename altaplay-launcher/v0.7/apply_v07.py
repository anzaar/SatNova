#!/usr/bin/env python3
"""Apply weather-only v0.7 over the previously verified AltaPlay v0.6 source."""
from pathlib import Path
import sys, shutil
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
here=Path(__file__).resolve().parent
panel=java/"AltaPlayZenPanel.java"
if not panel.is_file() or not (java/"ZenWeather.java").is_file():
    raise SystemExit("First apply the v0.1→v0.6 AltaPlay overlays")
shutil.copy2(here/"java/ZenWeather.java",java/"ZenWeather.java")
shutil.copy2(here/"java/ZenWeatherText.java",java/"ZenWeatherText.java")
s=panel.read_text(encoding="utf-8")
def once(a,b):
    global s
    if s.count(a)!=1:raise RuntimeError(f"weather overlay anchor changed ({s.count(a)}): {a[:85]!r}")
    s=s.replace(a,b,1)

once(
    "weatherText.setFocusable(true);weatherText.setOnClickListener(v->configureCity());",
    "weatherText.setFocusable(true);weatherText.setOnClickListener(v->configureCity());\n"
    "        weatherText.setLongClickable(true);\n"
    "        weatherText.setOnLongClickListener(v->{weather.refresh(true,this::refreshWeather);return true;});"
)

start=s.index('        String icon=value.contains("ضباب")?')
end=s.index('        weatherText.setText(icon+"  "+value);',start)
if start<0 or end<start:raise RuntimeError("weather ambience icons anchor changed")
s=s[:start]+'''        String icon=value.contains("ضباب")?"☰":
            value.contains("رذاذ")||value.contains("مطر")||value.contains("أمطار")?"☂":
            value.contains("ثلج")||value.contains("ثلوج")?"❄":
            value.contains("عواصف")?"⚡":
            value.contains("سماء صافية ليلاً")?"☾":
            value.contains("صحو")||value.contains("صافية")?"☀":"☁";
'''+s[end:]
once('        weatherText.setText(icon+"  "+value);',
     '        weatherText.setText(icon+"  "+value);\n'
     '        weatherText.setContentDescription("الطقس: "+value+"، اضغط لتغيير المدينة أو اضغط مطوّلاً لتحديث الطقس");')
once('value.contains("أمطار")?0x193C7890:', '(value.contains("أمطار")||value.contains("مطر")||value.contains("رذاذ"))?0x193C7890:')
panel.write_text(s,encoding="utf-8")
print("PASS AltaPlay v0.7 — weather status exclusively in Arabic, bounded retries, 12-hour cache, refresh by long-press and WMO-based ambient icons")
