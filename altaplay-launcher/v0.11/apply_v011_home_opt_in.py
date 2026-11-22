#!/usr/bin/env python3
"""Expose Android's genuine HOME picker; never steal or force HOME.

The user's click opens HomeSettingsActivity -> Settings.ACTION_HOME_SETTINGS.
Only Android Settings/role UI and the user's confirmation can change HOME.
Leave Google TV Launcher installed and enabled; don't use shell set-home,
pm disable, RoleManager.set* or WRITE_SECURE_SETTINGS.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
file=launcher/"app/src/main/java/com/gathin/tvhome/AltaPlayZenPanel.java"
s=file.read_text(encoding="utf8")
def once(old,new,label):
    global s
    n=s.count(old)
    if n!=1:raise RuntimeError("HOME opt-in "+label+": "+str(n))
    s=s.replace(old,new,1)
once('''            tr("مصادر لوغوات القنوات","Sources des logos TV","TV logo sources"),
            tr("إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur",''',
'''            tr("مصادر لوغوات القنوات","Sources des logos TV","TV logo sources"),
            tr("إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur",''',
"menu original anchor check")
# No change in anchor preceding receiver visibility: insert only next to the
# true Android settings entry; the last receiver entry is conditional.
once('''            tr("إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur",
                "Show or hide receiver"),
            tr("إعدادات جهاز الاستقبال","Paramètres du récepteur",
                "Receiver settings")''',
'''            tr("إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur",
                "Show or hide receiver"),
            tr("تعيين AltaPlay كواجهة رئيسية","Définir AltaPlay comme écran d'accueil",
                "Set AltaPlay as default Home"),
            tr("إعدادات جهاز الاستقبال","Paramètres du récepteur",
                "Receiver settings")''',
"home entry in general Settings")
once('''                else if(which==4)receiverVisibilityMenu();
                else if(which==5 && showReceiverButton())zenMenu();''',
'''                else if(which==4)receiverVisibilityMenu();
                else if(which==5){
                    // Explicit user action: show OS-owned default Home selector.
                    // Do NOT change HOME via ADB or private Android APIs.
                    activity.startActivity(new Intent(activity,HomeSettingsActivity.class));
                }
                else if(which==6 && showReceiverButton())zenMenu();''',
"launch system approval UI")
file.write_text(s,encoding="utf8")
print("PASS optional HOME handoff: user clicks Settings -> OS Home picker; no automatic default, no Google disable")
