#!/usr/bin/env python3
"""AltaPlay v0.12 UI-only overlay; NEVER mutate Zen networking, tuning, remotes or HDMI.

Apply after all v0.11 patches. The original receiver and system services remain
byte-for-byte unchanged. This patch is idempotence-guarded and fails on changed
upstream anchors instead of making blind, possibly destructive replacements.
"""
from pathlib import Path
import shutil
import sys

root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
panel=java/"AltaPlayZenPanel.java"
dock=java/"DockDecoration.java"
if not panel.exists() or not dock.exists():
    raise RuntimeError("Apply all v0.11 overlays before AltaPlay v0.12")

# Explicit safety fence: these files, and their preferences, are NOT writable
# by the UI overlay. Hashes are checked again in the build workflow.
protected=["ZenClient.java","ZenProtocol.java","AltaPlayHdmiRemoteService.java",
           "AltaPlayRemoteKeys.java","AltaPlayHdmi1.java"]
import hashlib
def fingerprint(f):
    return hashlib.sha256((java/f).read_bytes()).hexdigest() if (java/f).exists() else None
before={f:fingerprint(f) for f in protected}

def once(src,old,new,label):
    count=src.count(old)
    if count!=1:
        raise RuntimeError("v0.12 "+label+": expected one source anchor; found "+str(count)+
                           "; source="+repr(old[:115]))
    return src.replace(old,new,1)

s=panel.read_text(encoding="utf-8")

# Bigger text, same actual Habous/NextPrayer algorithm.
s=once(s,'prayerText=title("",18,0xFFF9FFF8);',
       'prayerText=title("",36,0xFFF9FFF8);',
       "larger next prayer")
s=once(s,"prayerText.setMinHeight(dp(37));",
       "prayerText.setMinHeight(dp(70));",
       "countdown minimum height")

# Weather is a view-only transformation: keep weather.text() cached data and
# condition/icon recognition in Arabic, translate only when painting the text.
s=once(s,'weatherText=title("",15,0xFFE6FAF0);',
       'weatherText=title("",30,0xFFE6FAF0);',
       "larger current weather")
s=once(s,'weatherText.setSingleLine(true);',
       '''weatherText.setSingleLine(false);
        weatherText.setMaxLines(2);
        weatherText.setEllipsize(null);''',
       "two-line weather")
s=once(s,'RelativeLayout.LayoutParams wp=new RelativeLayout.LayoutParams(-2,dp(27));',
       'RelativeLayout.LayoutParams wp=new RelativeLayout.LayoutParams(-2,dp(67));',
       "weather row height")
s=once(s,'wp.rightMargin=dp(8);wp.topMargin=dp(4);',
       'wp.setMarginEnd(dp(8));wp.topMargin=dp(78);',
       "weather row under top controls")
s=once(s,'weatherText.setText(icon+"  "+value);',
       'weatherText.setText(icon+"  "+AltaPlayAmbientText.weather(activity,value));',
       "weather visible localization")
s=once(s,'weatherText.setContentDescription("الطقس: "+value+"، اضغط لتغيير المدينة أو اضغط مطوّلاً لتحديث الطقس");',
       'weatherText.setContentDescription(AltaPlayAmbientText.weatherAccessibility(activity,value));',
       "weather accessibility localization")

# A language change must not change the prayer dates, timetable, city or timer.
s=once(s,'''        prayerText.setText(NextPrayer.display(now,prayers.today(),
            prayers.forDate(now.toLocalDate().plusDays(1))));''',
'''        prayerText.setText(AltaPlayAmbientText.prayer(activity,
            NextPrayer.display(now,prayers.today(),
                prayers.forDate(now.toLocalDate().plusDays(1)))));''',
       "localized next-prayer only")
s=once(s,'hijriDate.setText(MoroccanHijri.display(day,shift)+',
       'hijriDate.setText(AltaPlayAmbientText.hijri(activity,MoroccanHijri.display(day,shift))+',
       "localized approximate Hijri month names")
s=once(s,'DateTimeFormatter.ofPattern("EEEE، d MMMM",uiLocale())',
       'DateTimeFormatter.ofPattern(uiRtl()?"EEEE، d MMMM":"EEEE, d MMMM",uiLocale())',
       "French/English Gregorian punctuation")
# Fix mismatched directional padding around the adjacent date widgets.
s=once(s,'arabicDate.setPadding(0,0,dp(9),0);',
       'arabicDate.setPaddingRelative(0,0,dp(9),0);',
       "date right-to-left symmetric padding")
s=once(s,'hijriDate.setPadding(dp(6),0,0,0);',
       'hijriDate.setPaddingRelative(dp(6),0,0,0);',
       "Hijri right-to-left symmetric padding")

# Move existing buttons; do NOT replace click listeners, discovery, or HDMI.
# Physical START/END are resolved automatically from selected RTL/LTR language.
s=once(s,'    private void installSystemChrome(){',
'''    private void placeTopActions(){
        View actions=activity.findViewById(R.id.topButtons);
        if(actions==null || !(actions.getLayoutParams() instanceof RelativeLayout.LayoutParams))
            return;
        RelativeLayout.LayoutParams lp=(RelativeLayout.LayoutParams)actions.getLayoutParams();
        lp.removeRule(RelativeLayout.BELOW);
        lp.removeRule(RelativeLayout.ABOVE);
        lp.removeRule(RelativeLayout.ALIGN_PARENT_START);
        lp.removeRule(RelativeLayout.CENTER_VERTICAL);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.topMargin=dp(4);
        lp.setMarginEnd(dp(8));
        actions.setLayoutDirection(uiRtl()?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        actions.setLayoutParams(lp);
        // The separate Settings entry already uses the opposite logical START.
        // Receiver and Wi-Fi retain their exact original click/long-click handlers.
    }
    private void installSystemChrome(){''',
       "user-requested top buttons")
s=once(s,
'''        refreshWeather();refreshPrayer();updateZenPill();''',
'''        placeTopActions();
        weatherText.setGravity((uiRtl()?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
        weatherText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        refreshWeather();refreshPrayer();updateZenPill();''',
       "place buttons only after Android has populated them")

# English/French receiver placeholder; actual client cache, connection,
# tune commands, favorites, channel IDs, and live remote are unaffected.
offline='''online?"جاري تحميل القنوات من Zen 4…":"القنوات غير متوفرة بعد · اتصل بـ Zen 4 لتحميلها"'''
if offline in s:
    s=s.replace(offline,
      '''online?tr("جاري تحميل القنوات من Zen 4…",
                       "Chargement des chaînes depuis Zen 4…",
                       "Loading channels from Zen 4…"):
                    tr("القنوات غير متوفرة بعد · اتصل بـ Zen 4 لتحميلها",
                       "Aucune chaîne chargée · connectez-vous à Zen 4",
                       "No channels loaded · connect to Zen 4")''',1)

# Update localized status of the remote without changing any receiver operation.
s=once(s,'Toast.makeText(activity,"Zen 4 غير متصل · القناة محفوظة فقط",Toast.LENGTH_SHORT).show();',
'''Toast.makeText(activity,tr("Zen 4 غير متصل · القناة محفوظة فقط",
                    "Zen 4 déconnecté · chaîne disponible hors connexion seulement",
                    "Zen 4 disconnected · cached channel only"),Toast.LENGTH_SHORT).show();''',
       "localized channel disconnected toast")
s=once(s,'new AlertDialog.Builder(activity).setTitle("اختر نسخة "+item.name)',
       'new AlertDialog.Builder(activity).setTitle(tr("اختر نسخة ","Choisir une version de ","Choose a version of ")+item.name)',
       "localized duplicate channel selector")
# Both the receiver settings and channel-variant dialogs may use the old
# cancel label. Localization is presentation-only; translate every such label.
assert '.setNegativeButton("إلغاء",null).show();' in s
s=s.replace('.setNegativeButton("إلغاء",null).show();',
            '.setNegativeButton(tr("إلغاء","Annuler","Cancel"),null).show();')
panel.write_text(s,encoding="utf-8")

# CleanYourTV's original periodic greeting can overwrite the localized
# Settings header after a language switch. Keep the header translated on
# every clock tick, not just when the app first opens.
main=java/"MainActivity.java"
m=main.read_text(encoding="utf-8")
legacy='greeting.setText(AltaPlayZenPanel.greetingForHour(hour));'
if legacy in m:
    m=once(m,legacy,
        '''String headerLang=getSharedPreferences("altaplay_ui",MODE_PRIVATE)
            .getString("language","ar");
        greeting.setText("fr".equals(headerLang)?"Paramètres":
            ("en".equals(headerLang)?"Settings":"الإعدادات"));''',
        "prevent Arabic-only greeting in FR/EN refresh")
    main.write_text(m,encoding="utf-8")

# Keep app tiles and all focus affordances intact, just lighten the visible
# dock BAND rather than making its clickable cards unreadable.
d=dock.read_text(encoding="utf-8")
d=once(d,"0x88234740","0x44234740","translucent dock band")
dock.write_text(d,encoding="utf-8")

shutil.copyfile(Path(__file__).parent/"java/AltaPlayAmbientText.java",
                java/"AltaPlayAmbientText.java")
after={f:fingerprint(f) for f in protected}
assert before==after, "ZEN/HDMI protected source modified!"
print("PASS v0.12 localized UI + RTL/LTR + translucent dock; original Zen, channel tuning and remote unchanged")
