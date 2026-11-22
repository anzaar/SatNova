#!/usr/bin/env python3
"""AltaPlay v0.11: explicit AR RTL / FR EN LTR, no decorative label in app-TV gap.

This is applied after v0.11 UI. Language affects both content and the
direction of the actual Home hierarchy, not just localized strings.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
panel=java/"AltaPlayZenPanel.java"
main=java/"MainActivity.java"
manifest=launcher/"app/src/main/AndroidManifest.xml"

def once(src,old,new,tag):
    n=src.count(old)
    if n!=1:raise RuntimeError("RTL "+tag+" anchor count "+str(n)+" for "+repr(old[:90]))
    return src.replace(old,new,1)

s=panel.read_text(encoding="utf-8")
# Remove a v0.6 auxiliary app row that is exactly the text between
# applications and channels user explicitly asked to remove.
start=s.index('        TextView selectedApp=title("التطبيقات",17,GLASS_TEXT);')
end=s.index("        LinearLayout panel=new LinearLayout(a);",start)
assert "shell.addView(selectedApp" in s[start:end]
s=s[:start]+s[end:]
s=once(s,
'        activity=a;root=a.findViewById(R.id.root);',
'''        activity=a;root=a.findViewById(R.id.root);
        root.setLayoutDirection(uiRtl()?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        root.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);''',
"root direction")
s=once(s,
'    private java.util.Locale uiLocale(){',
'''    private boolean uiRtl(){
        return "ar".equals(activity.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
            .getString("language","ar"));
    }
    private java.util.Locale uiLocale(){''',
"user language direction")
s=once(s,
'panel.setBackground(null); // transparent wallpaper gap; each TV tile retains its glass border',
'''panel.setBackground(null); // unobstructed background behind channels
        panel.setLayoutDirection(uiRtl()?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);''',
"transparent shelf")
s=once(s,
'        channelRow.setGravity(Gravity.CENTER_VERTICAL);',
'''        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setLayoutDirection(uiRtl()?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);''',
"TV channel row")
s=once(s,
'            arabicDate.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);',
'''            arabicDate.setTextDirection(uiRtl()?View.TEXT_DIRECTION_FIRST_STRONG_RTL:View.TEXT_DIRECTION_LTR);
            arabicDate.setGravity((uiRtl()?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);''',
"date RTL")
s=once(s,
'            prayerText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);',
'''            prayerText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            prayerText.setGravity((uiRtl()?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);''',
"prayer direction")
s=once(s,
'            zenPill.setGravity(Gravity.CENTER);',
'''            zenPill.setGravity(Gravity.CENTER);
            zenPill.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);''',
"Zen pill direction")
s=once(s,
'        v.setText(text);v.setTextColor(color);v.setTextSize(size);',
'''        v.setText(text);v.setTextColor(color);v.setTextSize(size);
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        v.setLayoutDirection(uiRtl()?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);''',
"labels direction")
panel.write_text(s,encoding="utf-8")

s=main.read_text(encoding="utf-8")
s=once(s,
'        setContentView(R.layout.activity_main);',
'''        setContentView(R.layout.activity_main);
        boolean altaplayRtl="ar".equals(getSharedPreferences("altaplay_ui",MODE_PRIVATE)
            .getString("language","ar"));
        View baseRoot=findViewById(R.id.root);
        baseRoot.setLayoutDirection(altaplayRtl?
            View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);''',
"root created direction")
s=once(s,
'rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);',
'''rtlAppGrid = "ar".equals(getSharedPreferences("altaplay_ui",MODE_PRIVATE)
            .getString("language","ar"));''',
"main app grid direction")
main.write_text(s,encoding="utf-8")
s=manifest.read_text(encoding="utf-8")
s=once(s,
'    <application\n        android:label=',
'''    <application
        android:supportsRtl="true"
        android:label=''',
"manifest RTL")
manifest.write_text(s,encoding="utf-8")
print("PASS v0.11 directional language switch: Arabic RTL, French/English LTR, no extra app-TV text")
