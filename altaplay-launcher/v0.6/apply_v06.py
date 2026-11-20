#!/usr/bin/env python3
"""Apply v0.6 — fix true layout overlap, Morocco clocks & real channel logos.

Requires v0.5 already applied. Safe: no installed TV/system changes.
"""
from pathlib import Path
import shutil, sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
panel=java/"AltaPlayZenPanel.java"
main=java/"MainActivity.java"
zen=java/"ZenClient.java"
config=launcher/"app/src/main/res/values/config.xml"
here=Path(__file__).resolve().parent

def once(src,needle,repl,label):
    count=src.count(needle)
    if count!=1:
        raise RuntimeError(f"{label}: wanted exactly one source anchor {needle[:85]!r}, got {count}")
    return src.replace(needle,repl,1)

s=panel.read_text(encoding="utf-8")
s=once(s,
    "import android.widget.LinearLayout;",
    "import android.widget.LinearLayout;\nimport android.widget.RelativeLayout;\nimport java.time.LocalDate;\nimport java.time.format.DateTimeFormatter;",
    "imports")
s=once(s,
    "    private TextView zenPill;",
    '    private TextView zenPill;\n    private TextView arabicDate;\n    private static final Locale AR_MA=new Locale("ar","MA");',
    "fields")
s=once(s,
    "        LinearLayout shell=(LinearLayout)wrapper.getParent();",
    """        LinearLayout shell=(LinearLayout)wrapper.getParent();
        // Reserve actual measured space for selected app text ABOVE the app dock.
        // This replaces the original floating giant hero which overlapped the clock.
        TextView selectedApp=title("التطبيقات",17,GLASS_TEXT);
        selectedApp.setTag("altaplay_selected_app");
        selectedApp.setContentDescription("عنوان التطبيق المحدد");
        selectedApp.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        selectedApp.setSingleLine(true);
        selectedApp.setEllipsize(TextUtils.TruncateAt.END);
        selectedApp.setPadding(dp(14),0,0,0);
        shell.addView(selectedApp,shell.indexOfChild(wrapper),
            new LinearLayout.LayoutParams(-1,dp(25)));""",
    "selected app")
s=once(s,
    "shell.addView(panel,index+1,new LinearLayout.LayoutParams(-1,dp(219)));",
    "shell.addView(panel,index+1,new LinearLayout.LayoutParams(-1,dp(211)));",
    "panel height")
# Compact internals of lower panel so BOTH TV and radio shelves fit vertically.
# Previously their child heights exceeded panel height and clipped radio cards.
for old,new in [
    ('header.addView(connection,new LinearLayout.LayoutParams(0,dp(27),1));',
     'header.addView(connection,new LinearLayout.LayoutParams(0,dp(25),1));'),
    ('panel.addView(meta,new LinearLayout.LayoutParams(-1,dp(20)));',
     'panel.addView(meta,new LinearLayout.LayoutParams(-1,dp(18)));'),
    ('panel.addView(tvLabel,new LinearLayout.LayoutParams(-1,dp(20)));',
     'panel.addView(tvLabel,new LinearLayout.LayoutParams(-1,dp(16)));'),
    ('panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(106)));',
     'panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(87)));'),
    ('panel.addView(radioLabel,new LinearLayout.LayoutParams(-1,dp(17)));',
     'panel.addView(radioLabel,new LinearLayout.LayoutParams(-1,dp(16)));'),
    ('panel.addView(radioScroll,new LinearLayout.LayoutParams(-1,dp(36)));',
     'panel.addView(radioScroll,new LinearLayout.LayoutParams(-1,dp(34)));'),
    ('new LinearLayout.LayoutParams(dp(119),dp(53))',
     'new LinearLayout.LayoutParams(dp(119),dp(40))'),
    ('new LinearLayout.LayoutParams(radio?dp(143):-1,radio?dp(25):dp(24))',
     'new LinearLayout.LayoutParams(radio?dp(143):-1,radio?dp(25):dp(19))'),
    ('new LinearLayout.LayoutParams(-1,dp(15))',
     'new LinearLayout.LayoutParams(-1,dp(13))'),
    ('radio?dp(31):dp(103)',
     'radio?dp(31):dp(82)'),
]:
    if old not in s:raise RuntimeError("Missing compact panel anchor "+old)
    s=s.replace(old,new)
start=s.index("        TextClock date=activity.findViewById(R.id.date);")
end=s.index("\n        TextView originalWifi=",start)
if start<0 or end<0: raise RuntimeError("header placement anchors changed")
s=s[:start]+"""        // Date and time MUST share the Ministry's Africa/Casablanca zone.
        // A TV emulator may otherwise show UTC while prayer times are Moroccan.
        TextClock clock=activity.findViewById(R.id.clock);
        TextClock ampm=activity.findViewById(R.id.ampm);
        if(clock!=null)clock.setTimeZone(MOROCCO.getId());
        if(ampm!=null)ampm.setTimeZone(MOROCCO.getId());
        TextClock date=activity.findViewById(R.id.date);
        if(date!=null){
            date.setVisibility(View.GONE);  // TextClock formats in device language despite textLocale.
            LinearLayout dateColumn=(LinearLayout)date.getParent();
            arabicDate=title("",16,0xFFE2EDE2);
            arabicDate.setSingleLine(true);
            arabicDate.setEllipsize(TextUtils.TruncateAt.END);
            arabicDate.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
            arabicDate.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
            dateColumn.addView(arabicDate,dateColumn.indexOfChild(date)+1,
                new LinearLayout.LayoutParams(-1,dp(27)));
        }
        // Independent real header row, no overlay on clock or app title.
        LinearLayout left=activity.findViewById(R.id.leftBlock);
        if(left!=null&&prayerText.getParent()==null){
            prayerText.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
            prayerText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
            prayerText.setPadding(dp(7),0,0,0);
            left.addView(prayerText,new LinearLayout.LayoutParams(-1,dp(25)));
        }
        refreshMoroccanHeader();
""" +s[end:]
start=s.index("        // Ambient weather sits at the top right, under the header pills.")
end=s.index("\n        // Static low-cost atmospheric tint",start)
if start<0 or end<0:raise RuntimeError("weather anchors")
s=s[:start]+"""        // Weather is in a measured row BELOW the top-bar controls, rather
        // than floating over the app dock at a hard-coded pixel coordinate.
        View buttons=activity.findViewById(R.id.topButtons);
        if(buttons!=null && buttons.getParent() instanceof RelativeLayout){
            RelativeLayout top=(RelativeLayout)buttons.getParent();
            RelativeLayout.LayoutParams wp=new RelativeLayout.LayoutParams(-2,dp(27));
            wp.addRule(RelativeLayout.BELOW,R.id.topButtons);
            wp.addRule(RelativeLayout.ALIGN_PARENT_END);
            wp.rightMargin=dp(8);wp.topMargin=dp(4);
            top.addView(weatherText,wp);
        }
""" +s[end:]
needle="    private void refreshPrayer(){"
if s.count(needle)!=1:raise RuntimeError("prayer anchor")
start=s.index(needle)
end=s.index("    private void refreshAmbient(){",start)
s=s[:start]+"""    private void refreshMoroccanHeader(){
        if(arabicDate!=null){
            arabicDate.setText(LocalDate.now(MOROCCO)
                .format(DateTimeFormatter.ofPattern("EEEE، d MMMM",AR_MA)));
        }
        TextView greeting=activity.findViewById(R.id.greeting);
        if(greeting!=null)greeting.setText(greetingForHour(LocalTime.now(MOROCCO).getHour()));
    }

    /** Always show Fajr as requested, and count down to the *next* actual adhan. */
    private void refreshPrayer(){
        Map<String,LocalTime> today=prayers.today();
        LocalTime fajr=today.get("fajr");
        if(fajr==null){prayerText.setText(prayers.label());return;}
        String fajrLabel="الفجر "+fajr;
        LocalTime now=LocalTime.now(MOROCCO);
        String[] keys={"fajr","dhuhr","asr","maghrib","isha"};
        String[] names={"الفجر","الظهر","العصر","المغرب","العشاء"};
        for(int i=0;i<keys.length;i++){
            LocalTime target=today.get(keys[i]);
            if(target!=null&&now.isBefore(target)){
                long minutes=Math.max(1,Duration.between(now,target).toMinutes());
                String remaining=(minutes/60>0?(minutes/60)+"س ":"")+(minutes%60)+"د";
                prayerText.setText(fajrLabel+"  •  "+names[i]+" بعد "+remaining);
                return;
            }
        }
        prayerText.setText(fajrLabel+"  •  انتهت صلوات اليوم");
    }

""" +s[end:]
s=once(s,
    "        refreshPrayer();\n        weather.refresh(false,this::refreshWeather);",
    "        refreshMoroccanHeader();\n        refreshPrayer();\n        weather.refresh(false,this::refreshWeather);",
    "refresh clock/date")
s=once(s,
    "                img.setImageBitmap(BitmapFactory.decodeFile(logo.getAbsolutePath()));",
    """                img.setImageBitmap(BitmapFactory.decodeFile(logo.getAbsolutePath()));
                img.setContentDescription("شعار القناة: "+item.name);""",
    "channel logo accessibility")

panel.write_text(s,encoding="utf-8")

s=main.read_text(encoding="utf-8")
s=once(s,
    "greeting.setText(AltaPlayZenPanel.greetingForHour(hour));",
    'greeting.setText(AltaPlayZenPanel.greetingForHour(java.time.LocalTime.now(java.time.ZoneId.of("Africa/Casablanca")).getHour()));',
    "Moroccan greeting")
s=once(s,
    "        lastFocusedKey = adapter.keyAt(position);",
    """        lastFocusedKey = adapter.keyAt(position);
        View altaplaySelected=root.findViewWithTag("altaplay_selected_app");
        if(altaplaySelected instanceof TextView){
            ((TextView)altaplaySelected).setText(adapter.labelAt(position));
        }""",
    "selected app dynamic title")
# CleanYourTV pads a single row by 29dp to accommodate its floating hero.
# AltaPlay uses a separate measured 25dp selected-app label instead.
s=main.read_text(encoding="utf-8")
s=once(s,
    'if (singleRow && !cfg.alwaysShowNames) want += (int) (29 * density);',
    'if (singleRow && !cfg.alwaysShowNames) want += (int) (4 * density);',
    "remove former floating-hero padding")
main.write_text(s,encoding="utf-8")

# A single row never needs the invisible 22dp label under each tile.
# Make its cells fit the measured grid height, including at TV 720p.
adapter=java/"AppAdapter.java"
s=adapter.read_text(encoding="utf-8")
s=once(s,'static final float ITEM_EXTRA_DP = 45;',
    'static final float ITEM_EXTRA_DP = 15;', 'tile extras')
s=once(s,
    'h.name.setVisibility(dockKind && restNameAlpha == 0f ? View.INVISIBLE : View.VISIBLE);',
    'h.name.setVisibility(singleRow && restNameAlpha == 0f ? View.GONE : (dockKind && restNameAlpha == 0f ? View.INVISIBLE : View.VISIBLE));',
    "hide single row phantom labels")
key='int w = tileWidthPx(innerWidthPx, columns, density);'
replace='''int w = tileWidthPx(innerWidthPx, columns, density);
        if(gridHeightPx>0 && rowsBelowDock==0 && !dockTitle && dockRow){
            // Actual screen-space cap instead of clipping the app cards.
            float freeHeight=gridHeightPx/density-ITEM_EXTRA_DP-DOCK_EXTRA_DP-7f;
            int byHeight=Math.round(freeHeight * DESIGN_W_DP / DESIGN_H_DP * density);
            w=Math.max(Math.round(80*density),Math.min(w,byHeight));
        }'''
s=once(s,key,replace,"responsive dock sizing")
adapter.write_text(s,encoding="utf-8")

s=config.read_text(encoding="utf-8")
for a,b in [
    ('<bool name="single_row">false</bool>','<bool name="single_row">true</bool>'),
    ('<bool name="dock_band_all">false</bool>','<bool name="dock_band_all">true</bool>'),
    ('<bool name="dock_title">true</bool>','<bool name="dock_title">false</bool>')
]:
    s=once(s,a,b,"layout config")
config.write_text(s,encoding="utf-8")

s=zen.read_text(encoding="utf-8")
s=once(s,
    "        return f.isFile() && f.length()<=512_000?f:null;",
    """        if(f.isFile() && f.length()<=512_000)return f;
        // Zen's ProgramId varies by receiver/channel list; match packaged
        // broadcaster logos by verified channel names instead of assuming
        // that DVB satellite SID uniquely identifies a channel.
        Channel entry;
        synchronized(catalogueLock){entry=catalogue.get(id);}
        return entry==null?null:AltaPlayChannelLogos.fromName(context,entry.name);""",
    "logo auto-match")
zen.write_text(s,encoding="utf-8")
shutil.copy2(here/"java/AltaPlayChannelLogos.java",java/"AltaPlayChannelLogos.java")
print("PASS AltaPlay v0.6: reserved non-overlapping header, single app row, full Zen panel, Casablanca clock and date, next-adhaan countdown, name-based real-logo resolver")
