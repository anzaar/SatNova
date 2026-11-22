#!/usr/bin/env python3
"""AltaPlay v0.11: distraction-free TV shelf, language selection and Zen settings.

Apply AFTER v0.11 Zen connectivity patch, which follows v0.10.
Does not clear existing app data or modify Android HOME settings.
"""
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
panel=java/"AltaPlayZenPanel.java"
s=panel.read_text(encoding="utf-8")
def once(old,new,label):
    global s
    count=s.count(old)
    if count!=1: raise RuntimeError(f"v0.11 UI: {label} anchor {count}, {old[:115]!r}")
    s=s.replace(old,new,1)
def remove(old,label):
    once(old,"",label)

# Public model: the TV channel cards have their OWN backgrounds; a big dark-green
# overlay underneath apps and channels hides the user's chosen wallpaper.
once("        panel.setBackground(bg);",
     '''        panel.setBackground(null); // transparent wallpaper gap; each TV tile retains its glass border''',"transparent shelf")
remove("        panel.addView(header);","remove Zen text in gap")
remove("        panel.addView(meta,new LinearLayout.LayoutParams(-1,dp(18)));","remove status line in gap")
remove("        panel.addView(tvLabel,new LinearLayout.LayoutParams(-1,dp(16)));","remove redundant TV title")
remove("        panel.addView(radioLabel,new LinearLayout.LayoutParams(-1,dp(16)));","remove radio title")
remove("        panel.addView(radioScroll,new LinearLayout.LayoutParams(-1,dp(34)));","remove radio shelf")
once("panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(87)));",
     "panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(93)));","one TV shelf")
once("        shell.addView(panel,index+1,new LinearLayout.LayoutParams(-1,dp(211)));",
'''        LinearLayout.LayoutParams shelfLayout=new LinearLayout.LayoutParams(-1,dp(100));
        shelfLayout.topMargin=dp(23); // natural wallpaper between apps and TV, no overlay
        shell.addView(panel,index+1,shelfLayout);''',"wallpaper separation")

# No radio rendering and no DPAD jump into a removed radio row.
start=s.index("        List<List<ZenClient.Channel>> radioGroups=grouped(radios);")
end=s.index("    private List<List<ZenClient.Channel>> grouped(",start)
s=s[:start]+'''        // The radio shelf is intentionally removed. Preserve radio entries in
        // ZenClient cache so TV data is never altered.
    }
'''+s[end:]
once("if(key==KeyEvent.KEYCODE_DPAD_DOWN&&!radio)return focusRadioRow(activity);",
     "if(key==KeyEvent.KEYCODE_DPAD_DOWN&&!radio)return true;","no radio focus")

# Restore the system timezone for the wall CLOCK only; Ministry prayer times
# remain attached to the Moroccan city and Casablanca timezone.
once('if(clock!=null)clock.setTimeZone(MOROCCO.getId());',
     'if(clock!=null){clock.setTimeZone(null);clock.setFormat12Hour(null);clock.setFormat24Hour(null);}',"clock follows system timezone and 12/24 preference")
once('if(ampm!=null)ampm.setTimeZone(MOROCCO.getId());',
     'if(ampm!=null){ampm.setTimeZone(null);ampm.setFormat12Hour(null);ampm.setFormat24Hour(null);}',"AM/PM follows system timezone and 12/24 preference")
s=s.replace("LocalDate.now(MOROCCO)", "LocalDate.now()")  # Gregorian + Hijri calendar date follows system
count=s.count('greetingForHour(LocalTime.now(MOROCCO).getHour())')
if count!=2: raise RuntimeError("unexpected greeting call count "+str(count))
s=s.replace('greetingForHour(LocalTime.now(MOROCCO).getHour())',
            'greetingForHour(LocalTime.now().getHour())')
# Keep Hijri and prayer timetable for the selected Morocco city.
# Date language follows user's chosen language if selected.
once('DateTimeFormatter.ofPattern("EEEE، d MMMM",AR_MA)',
     'DateTimeFormatter.ofPattern("EEEE، d MMMM",uiLocale())',"multilingual date")

# Make the Zen status pill exactly the SAME graphic language as the stock
# Source and Settings pills, not an independently colored green pill.
once("            zenPill.setOnClickListener(v->configure());",
     "            zenPill.setOnClickListener(v->zenMenu());","top Zen menu")
once('            zenPill.setOnLongClickListener(v->{configureRemote();return true;});',
     '            zenPill.setOnLongClickListener(v->{zenMenu();return true;});',"top Zen long menu")
once('            zenPill.setBackground(pill(false));',
'''            View sources=activity.findViewById(R.id.btnInputs);
            if(sources!=null && sources.getBackground()!=null &&
               sources.getBackground().getConstantState()!=null){
                zenPill.setBackground(sources.getBackground().getConstantState().newDrawable().mutate());
            }else zenPill.setBackground(pill(false));''',"theme matching")
once('            zenPill.setBackground(pill(online));',
     '            // Keep stock top-pill background and focus theme unchanged.',"keep same top style")
once('if(zenPill!=null)zenPill.setText(client.isOnline()?"●  Zen 4 · متصل":"◇  Zen 4 · غير متصل");',
     'updateZenPill();',"status-only ambient update")
once('String newText=online?"●  Zen 4 · متصل":"◇  Zen 4 · غير متصل";',
     'String newText=online?tr("●  Zen 4 · متصل","● Zen 4 · connecté","● Zen 4 · connected"):tr("◇ Zen 4 · غير متصل","◇ Zen 4 · déconnecté","◇ Zen 4 · disconnected");',"localized Zen")

# The former Sources control becomes the Wi-Fi shortcut; the vendor's device
# sources are still reachable via Android Settings if needed.
once('        TextView settings=activity.findViewById(R.id.btnSettings);',
'''        View inputs=activity.findViewById(R.id.btnInputs);
        if(inputs instanceof TextView){
            TextView button=(TextView)inputs;
            button.setText("Wi-Fi");
            button.setContentDescription("Wi-Fi");
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_wifi,0,0,0);
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(v->openWifi());
            button.setOnLongClickListener(v->{openWifi();return true;});
        }
        TextView settings=activity.findViewById(R.id.btnSettings);''',"Wi-Fi top button")

# Rename channel by receiver ProgramId, NOT by display name (display names
# can be duplicated by channel groups). Original cache remains untouched.
once("    private View channel(List<ZenClient.Channel> group,boolean radio){",
'''    private String displayName(ZenClient.Channel item){
        return activity.getSharedPreferences("altaplay_channel_labels",Context.MODE_PRIVATE)
            .getString(item.id,item.name);
    }
    private java.util.Locale uiLocale(){
        String lang=activity.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
            .getString("language","ar");
        return java.util.Locale.forLanguageTag(lang);
    }
    private String tr(String ar,String fr,String en){
        String lang=activity.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
            .getString("language","ar");
        return "fr".equals(lang)?fr:("en".equals(lang)?en:ar);
    }
    private void openWifi(){
        try{activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}
        catch(Exception e){Toast.makeText(activity,"Wi-Fi settings unavailable",Toast.LENGTH_SHORT).show();}
    }
    private void languageMenu(){
        String[] names={"العربية","Français","English"};
        String[] values={"ar","fr","en"};
        new AlertDialog.Builder(activity)
            .setTitle(tr("اللغة","Langue","Language"))
            .setItems(names,(d,which)->{
                activity.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
                    .edit().putString("language",values[which]).apply();
                activity.recreate();
            }).setNegativeButton(tr("إلغاء","Annuler","Cancel"),null).show();
    }
    private void zenMenu(){
        String[] choices={
            tr("تشخيص الاتصال","Diagnostic de connexion","Connection diagnostics"),
            tr("إعادة الاتصال الآن","Reconnecter maintenant","Reconnect now"),
            tr("البحث تلقائياً في الشبكة","Rechercher Zen sur le réseau","Discover Zen on LAN"),
            tr("تغيير عنوان IP","Modifier l'adresse IP","Change IP address"),
            tr("التحكم بالتيليكوموند","Télécommande Zen","Zen remote"),
            tr("إعدادات إمكانية الوصول","Paramètres d'accessibilité","Accessibility settings"),
            tr("تحديث لائحة القنوات","Actualiser les chaînes","Refresh channel catalogue"),
            tr("اللغة","Langue","Language"),
            tr("الخلفيات وإعدادات اللانشر","Fonds d'écran et paramètres du lanceur","Wallpaper & launcher settings")
        };
        new AlertDialog.Builder(activity)
            .setTitle("Zen 4 · "+(client.isOnline()?
                tr("متصل","connecté","connected"):tr("غير متصل","déconnecté","disconnected")))
            .setMessage(client.diagnostics())
            .setItems(choices,(dialog,which)->{
                if(which==0)new AlertDialog.Builder(activity)
                    .setTitle(tr("حالة اتصال Zen 4","État de connexion Zen 4","Zen 4 connection status"))
                    .setMessage(client.diagnostics())
                    .setPositiveButton(tr("إعادة الاتصال","Reconnecter","Reconnect"),
                        (d,w)->client.reconnectNow())
                    .setNeutralButton(tr("بحث تلقائي","Recherche auto","Auto discover"),
                        (d,w)->client.discoverNow())
                    .setNegativeButton(tr("إغلاق","Fermer","Close"),null).show();
                else if(which==1)client.reconnectNow();
                else if(which==2)client.discoverNow();
                else if(which==3)configure();
                else if(which==4)configureRemote();
                else if(which==5)try{activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
                    catch(Exception e){Toast.makeText(activity,"Accessibility unavailable",Toast.LENGTH_SHORT).show();}
                else if(which==6)client.refreshCatalogue();
                else if(which==7)languageMenu();
                else if(which==8)activity.startActivity(
                    new Intent(activity,LauncherSettingsActivity.class));
            })
            .setNegativeButton(tr("إغلاق","Fermer","Close"),null).show();
    }
    private void renameChannel(ZenClient.Channel item){
        EditText input=new EditText(activity);
        input.setSingleLine(true);
        input.setText(displayName(item));
        new AlertDialog.Builder(activity)
            .setTitle(tr("تغيير اسم القناة","Renommer la chaîne","Rename channel"))
            .setView(input)
            .setPositiveButton(tr("حفظ","Enregistrer","Save"),(d,w)->{
                String name=input.getText().toString().trim();
                if(name.isEmpty()||name.length()>85)return;
                activity.getSharedPreferences("altaplay_channel_labels",Context.MODE_PRIVATE)
                    .edit().putString(item.id,name).apply();
                renderedSignature=Integer.MIN_VALUE;render();
            }).setNeutralButton(tr("الاسم الأصلي","Nom d'origine","Original name"),(d,w)->{
                activity.getSharedPreferences("altaplay_channel_labels",Context.MODE_PRIVATE)
                    .edit().remove(item.id).apply();
                renderedSignature=Integer.MIN_VALUE;render();
            }).setNegativeButton(tr("إلغاء","Annuler","Cancel"),null).show();
    }
    private void manualLogo(ZenClient.Channel item){
        EditText url=new EditText(activity);
        url.setSingleLine(true);
        url.setHint("https://.../logo.png  or ftp://...");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
            android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(activity)
            .setTitle(tr("لوغو القناة من رابط أو FTP","Logo via URL ou FTP","Logo via URL or FTP"))
            .setView(url)
            .setPositiveButton(tr("تحميل","Télécharger","Download"),(d,w)->
                ZenManualLogo.importUrl(activity,item.id,url.getText().toString(),saved->{
                    if(saved){renderedSignature=Integer.MIN_VALUE;render();}
                }))
            .setNegativeButton(tr("إلغاء","Annuler","Cancel"),null).show();
    }
    private void channelMenu(ZenClient.Channel item){
        String[] actions={
            tr("تغيير اسم القناة","Renommer","Rename"),
            tr("لوغو يدوي من رابط أو FTP","Logo via URL ou FTP","Manual logo from URL or FTP"),
            tr("استرجاع اللوغو التلقائي","Restaurer le logo automatique","Restore automatic logo")
        };
        new AlertDialog.Builder(activity).setTitle(displayName(item))
            .setItems(actions,(d,which)->{
                if(which==0)renameChannel(item);
                else if(which==1)manualLogo(item);
                else{
                    File logo=new File(new File(activity.getFilesDir(),"zen-logos"),item.id+".png");
                    if(logo.isFile())logo.delete();
                    renderedSignature=Integer.MIN_VALUE;render();
                }
            }).setNegativeButton(tr("إلغاء","Annuler","Cancel"),null).show();
    }

    private View channel(List<ZenClient.Channel> group,boolean radio){''',"settings and customization")

once('String key=item.name.trim().replaceAll(',
     'String key=displayName(item).trim().replaceAll(',"groups reflect custom name")
once('card.setContentDescription(item.name+(group.size()>1?',
     'card.setContentDescription(displayName(item)+(group.size()>1?',"accessible label")
once('TextView placeholder=title(item.name,13,0xFFDBF0E9);',
     'TextView placeholder=title(displayName(item),13,0xFFDBF0E9);',"fallback uses alias")
once('File logo=AltaPlayOnlineLogos.cached(activity,item.name);\n            if(logo==null)logo=client.logo(item.id);',
'''File manual=client.logo(item.id);
            File logo=manual!=null?manual:AltaPlayOnlineLogos.cached(activity,item.name);''',"manual logo priority")
once('AltaPlayOnlineLogos.fetchForVisible(activity,item.name,img);',
     'if(manual==null)AltaPlayOnlineLogos.fetchForVisible(activity,item.name,img);',"never overwrite manual image")
once('String shown=item.name+(group.size()>1?',
     'String shown=displayName(item)+(group.size()>1?',"show custom label")
once('        card.setOnClickListener(v->{',
'''        card.setOnLongClickListener(v->{channelMenu(item);return true;});
        card.setOnClickListener(v->{''',"long press channel menu")

# Localize the default hint, title and disconnected fallback as well.
s=s.replace('title(client.isOnline()?"مباشر":"محفوظة",10,0xB4D8ECDA)',
            'title(client.isOnline()?tr("مباشر","Direct","Live"):tr("محفوظة","En cache","Cached"),10,0xB4D8ECDA)')
# Keep source receiver IDs unchanged. The menu uses these IDs for correct tune.

# Android AlertDialog list and message are mutually exclusive. Keep the
# diagnostics in the dedicated first item; otherwise the language/Zen menu
# is silently hidden on Google TV.
once('            .setMessage(client.diagnostics())\n            .setItems(choices,',
     '            .setItems(choices,',
     "Zen settings must display selectable menu items")
panel.write_text(s,encoding="utf-8")
locale=java/"LauncherLocale.java"
t=locale.read_text(encoding="utf-8")
old='        Locale locale = parse(Config.load(base).locale);'
assert t.count(old)==1,"Locale anchor"
t=t.replace(old,'''        String selected=base.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
            .getString("language","");
        Locale locale = parse(selected.isEmpty()?Config.load(base).locale:selected);''',1)
locale.write_text(t,encoding="utf-8")

# Android owns the launcher clock: no private timezone or 12/24-hour override.
def patch_file(path,old,new,label):
    source=path.read_text(encoding="utf-8")
    count=source.count(old)
    if count!=1: raise RuntimeError("System clock "+label+" anchor="+str(count))
    path.write_text(source.replace(old,new,1),encoding="utf-8")

main=java/"MainActivity.java"
patch_file(main,'clockFormat = cfg.clockFormat;','clockFormat = "system";',"MainActivity config")
patch_file(main,'ClockFormat.apply(getWindow().getDecorView(), clockFormat);',
    'ClockFormat.apply(getWindow().getDecorView(), "system");',"MainActivity formatter")
clock_activity=java/"ClockActivity.java"
patch_file(clock_activity,
    'ClockFormat.apply(getWindow().getDecorView(), Config.load(this).clockFormat);',
    'ClockFormat.apply(getWindow().getDecorView(), "system");',"ClockActivity formatter")
settings=java/"LauncherSettingsActivity.java"
patch_file(settings,'        clockFormat = config.clockFormat;','        clockFormat = "system";',"settings selection")
patch_file(settings,
    '        // Mirror text alignment without globally opting the launcher into RTL.',
    '        // Only Android System clock is available; hide launcher-owned 12/24 overrides.\n        clockChoices[1].setVisibility(View.GONE);\n        clockChoices[2].setVisibility(View.GONE);\n        // Mirror text alignment without globally opting the launcher into RTL.',
    "hide independent clock choices")
shutil.copyfile(Path(__file__).parent/"java/ZenManualLogo.java",java/"ZenManualLogo.java")
print("PASS v0.11 UI: wallpaper gap/no radios, system clock, Wi-Fi+Zen menu, AR/FR/EN, per-ID manual logos and rename")
