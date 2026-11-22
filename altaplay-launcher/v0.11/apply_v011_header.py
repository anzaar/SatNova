#!/usr/bin/env python3
"""v0.11 header: move Settings to greeting position; larger Hijri & Gregorian one row.

Apply after apply_v011_wallpaper_colors.py. Keep clock timezone system, prayer
calculation Casablanca/Habous, all home apps, Zen and HDMI control unchanged.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
main=java/"MainActivity.java"
panel=java/"AltaPlayZenPanel.java"

def once(s,old,new,tag):
    count=s.count(old)
    if count!=1:raise RuntimeError(f"v0.11 header {tag} anchor {count}: {old[:110]!r}")
    return s.replace(old,new,1)

s=main.read_text(encoding="utf-8")
old='''        String festiveGreeting = festive ? festival.greetingText(getResources()) : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }'''
new='''        // User moved Settings to the former greeting position. Never restore
        // morning/good-night text on the once-per-minute clock refresh.
        String altLanguage=getSharedPreferences("altaplay_ui",MODE_PRIVATE)
            .getString("language","ar");
        greeting.setText("fr".equals(altLanguage)?"Paramètres":
            ("en".equals(altLanguage)?"Settings":"الإعدادات"));
        greeting.setTextColor(Color.WHITE);'''
s=once(s,old,new,"stop greeting from reappearing every minute")
main.write_text(s,encoding="utf-8")

s=panel.read_text(encoding="utf-8")
s=once(s,
'''        if(greeting!=null)greeting.setVisibility(View.GONE);''',
'''        if(greeting!=null)greeting.setText(tr("الإعدادات","Paramètres","Settings"));''',
"refresh header Settings, no good night")
s=once(s,
'''            greeting.setVisibility(View.GONE); // keep wallpaper uncluttered''',
'''            // Reuse greeting's exact former position, turning it into the
            // Settings entry. Unlike stock top-bar Settings, it is always
            // accessible when Zen is disconnected or Android offline.
            greeting.setText(tr("الإعدادات","Paramètres","Settings"));
            greeting.setTextColor(Color.WHITE);
            greeting.setTextSize(16f);
            greeting.setLetterSpacing(0.01f);
            greeting.setAllCaps(false);
            greeting.setSingleLine(true);
            greeting.setGravity((uiRtl()?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
            greeting.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            greeting.setVisibility(View.VISIBLE);
            greeting.setFocusable(true);
            greeting.setClickable(true);
            greeting.setContentDescription(tr("الإعدادات","Paramètres","Settings"));
            greeting.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings,0,0,0);
            greeting.setCompoundDrawablePadding(dp(7));
            greeting.setOnClickListener(v->homeSettingsMenu());
            greeting.setOnLongClickListener(v->{homeSettingsMenu();return true;});''',
"Settings replaces greeting")
s=once(s,
'''        if(settings!=null)settings.setText("الإعدادات");''',
'''        if(settings!=null){
            // User explicitly removed the duplicate top settings button.
            // Settings, developer settings and wallpaper remain in the
            // greeting-position Settings menu.
            settings.setVisibility(View.GONE);
            settings.setFocusable(false);
        }''',
"hide redundant top settings pill")
s=once(s,
'''    private void zenMenu(){''',
'''    private void homeSettingsMenu(){
        String[] choices={
            tr("خلفيات وإعدادات AltaPlay","Fonds d'écran et paramètres AltaPlay",
                "AltaPlay wallpaper & settings"),
            tr("إعدادات التلفاز والنظام","Paramètres du téléviseur","TV & system settings"),
            tr("اللغة","Langue","Language"),
            tr("إعدادات Zen 4","Paramètres Zen 4","Zen 4 settings")
        };
        new AlertDialog.Builder(activity)
            .setTitle(tr("الإعدادات","Paramètres","Settings"))
            .setItems(choices,(d,which)->{
                if(which==0)activity.startActivity(
                    new Intent(activity,LauncherSettingsActivity.class));
                else if(which==1)try{
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                }catch(Exception e){
                    Toast.makeText(activity,
                        tr("تعذر فتح إعدادات النظام","Paramètres système indisponibles",
                            "System settings unavailable"),Toast.LENGTH_SHORT).show();
                }
                else if(which==2)languageMenu();
                else if(which==3)zenMenu();
            })
            .setNegativeButton(tr("إغلاق","Fermer","Close"),null).show();
    }
    private void zenMenu(){''',
"add robust Settings menu")
# dateRow after original dateColumn contains Gregorian & estimated Hijri, both
# already initialized; never reformat time or reinitialize expensive wallpaper.
s=once(s,
'''        // Weather is in a measured row BELOW the top-bar controls, rather''',
'''        // Hijri and Gregorian are peers on ONE BASELINE; enough room now that
        // the redundant top settings pill is gone. Keep size moderate so the
        // text remains readable on the physical 1080p Google TV.
        if(arabicDate!=null && hijriDate!=null &&
            arabicDate.getParent() instanceof LinearLayout &&
            hijriDate.getParent()==arabicDate.getParent()){
            LinearLayout dateColumn=(LinearLayout)arabicDate.getParent();
            int position=dateColumn.indexOfChild(arabicDate);
            dateColumn.removeView(arabicDate);
            dateColumn.removeView(hijriDate);
            LinearLayout row=new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutDirection(uiRtl()?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
            arabicDate.setTextSize(17f);
            hijriDate.setTextSize(17f);
            arabicDate.setGravity((uiRtl()?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
            hijriDate.setGravity((uiRtl()?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
            arabicDate.setPadding(0,0,dp(9),0);
            hijriDate.setPadding(dp(6),0,0,0);
            row.addView(arabicDate,new LinearLayout.LayoutParams(0,dp(36),1.05f));
            row.addView(hijriDate,new LinearLayout.LayoutParams(0,dp(36),1f));
            dateColumn.addView(row,Math.min(position,dateColumn.getChildCount()),
                new LinearLayout.LayoutParams(-1,dp(36)));
        }

        // Weather is in a measured row BELOW the top-bar controls, rather''',
"single larger Hijri+Gregorian date line")
s=once(s,
'''            hijriDate.setText(MoroccanHijri.display(day,shift)+" · تقديري");''',
'''            hijriDate.setText(MoroccanHijri.display(day,shift)+
                tr(" · تقديري"," · estimé"," · estimated"));''',
"estimated Hijri note localized")
panel.write_text(s,encoding="utf-8")
print("PASS v0.11: Settings in former greeting spot, original upper Settings removed, Hijri and Gregorian inline at 17sp")
