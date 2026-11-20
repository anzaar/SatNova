#!/usr/bin/env python3
"""Apply v0.3 Habous city/prayer and shared Open-Meteo weather changes after v0.1."""
from pathlib import Path
import sys,shutil
root=Path(sys.argv[1]).resolve()
panel=root/"launcher/app/src/main/java/com/gathin/tvhome/AltaPlayZenPanel.java"
java=panel.parent
here=Path(__file__).resolve().parent
if not panel.is_file():raise SystemExit("Apply v0.1 source overlay before v0.3")

def once(s,a,b):
    if s.count(a)!=1:raise RuntimeError(f"Missing or duplicate panel anchor: {a[:80]!r} ({s.count(a)})")
    return s.replace(a,b,1)

s=panel.read_text()
s=once(s,
    '        header.addView(weatherText,new LinearLayout.LayoutParams(-2,-2));',
    '        weatherText.setFocusable(true);weatherText.setClickable(true);\n'
    '        weatherText.setOnClickListener(v->configureCity());\n'
    '        header.addView(weatherText,new LinearLayout.LayoutParams(-2,-2));')
s=once(s,
    '        detail.addView(prayerText);',
    '        prayerText.setFocusable(true);prayerText.setClickable(true);\n'
    '        prayerText.setOnClickListener(v->configureCity());\n'
    '        detail.addView(prayerText);')
s=once(s,
    '        weather.refresh(()->weatherText.setText(weather.text()));\n        render();',
    '        weather.refresh(false,()->weatherText.setText(weather.text()));\n'
    '        prayers.refresh(false,()->prayerText.setText(prayers.label()));\n'
    '        render();\n'
    '        refreshLocationEveryTenMinutes();')
anchor='    private void configureRemote(){'
snippet='''    /** All 191 offline city choices come from the Habous list; refreshed from the official page. */
    private void configureCity(){
        List<HabousPrayerSchedule.City> choices=prayers.cities();
        if(choices.isEmpty()){
            Toast.makeText(activity,"لائحة المدن غير متوفرة",Toast.LENGTH_SHORT).show();return;
        }
        String[] names=new String[choices.size()];
        int current=0;
        for(int i=0;i<choices.size();i++){
            HabousPrayerSchedule.City c=choices.get(i);
            names[i]=c.ar+(c.fr.isEmpty()?"":" · "+c.fr);
            if(c.id==prayers.selected().id)current=i;
        }
        new AlertDialog.Builder(activity)
            .setTitle("اختر المدينة · مواقيت وزارة الأوقاف والطقس")
            .setSingleChoiceItems(names,current,(dialog,which)->{
                dialog.dismiss();
                HabousPrayerSchedule.City choice=choices.get(which);
                prayers.select(choice);
                prayerText.setText(prayers.label());
                weatherText.setText(weather.text());
                prayers.refresh(true,()->prayerText.setText(prayers.label()));
                weather.refresh(true,()->weatherText.setText(weather.text()));
            })
            .setNegativeButton("إلغاء",null).show();
    }
    /** Cheap periodic refresh: date rollover/next prayer and current weather without polling ZEN. */
    private void refreshLocationEveryTenMinutes(){
        if(activity.isFinishing()||activity.isDestroyed())return;
        prayerText.setText(prayers.label());
        weatherText.setText(weather.text());
        prayers.refresh(false,()->prayerText.setText(prayers.label()));
        weather.refresh(false,()->weatherText.setText(weather.text()));
        prayerText.postDelayed(this::refreshLocationEveryTenMinutes,10*60_000L);
    }
'''
s=once(s,anchor,snippet+anchor)
panel.write_text(s)

for filename in ["HabousHtmlParser.java","HabousPrayerSchedule.java","ZenWeather.java"]:
    shutil.copy2(here/"java"/filename,java/filename)
assets=root/"launcher/app/src/main/assets"
assets.mkdir(parents=True,exist_ok=True)
shutil.copy2(here/"assets/altaplay_habous_cities.json",assets/"altaplay_habous_cities.json")
print("AltaPlay v0.3 ministry-based prayer times, 191 city options and shared Open-Meteo weather applied")
