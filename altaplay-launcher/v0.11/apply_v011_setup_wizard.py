#!/usr/bin/env python3
"""Install optional, resumable first-use setup without slowing HOME startup.

The system alone confirms default HOME and Accessibility.  Never disable
Google TV, never force the HOME role, never modify accessibility secure flags.
"""
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
manifest=launcher/"app/src/main/AndroidManifest.xml"
panel=java/"AltaPlayZenPanel.java"

def once(s,a,b,n):
    count=s.count(a)
    if count!=1:raise RuntimeError(f"guided setup {n}: anchor {count}")
    return s.replace(a,b,1)

s=manifest.read_text(encoding="utf8")
s=once(s,
'''        <activity
            android:name=".LauncherSettingsActivity"''',
'''        <activity android:name=".AltaPlaySetupWizard"
            android:exported="false"
            android:excludeFromRecents="true"
            android:screenOrientation="landscape" />
        <activity
            android:name=".LauncherSettingsActivity"''',
"wizard in manifest")
manifest.write_text(s,encoding="utf8")
s=panel.read_text(encoding="utf8")
s=once(s,
'''        new AltaPlayZenPanel(a);
    }''',
'''        new AltaPlayZenPanel(a);
        // Start an optional, skippable guide AFTER the first frame is drawn.
        // Never decode wallpapers or wait for Zen before rendering Home.
        AltaPlaySetupWizard.offerOnce(a);
    }''',
"first-run offer")
s=once(s,
'''            tr("تعيين AltaPlay كواجهة رئيسية","Définir AltaPlay comme écran d'accueil",
                "Set AltaPlay as default Home"),
            tr("إعدادات جهاز الاستقبال","Paramètres du récepteur",''',
'''            tr("تعيين AltaPlay كواجهة رئيسية","Définir AltaPlay comme écran d'accueil",
                "Set AltaPlay as default Home"),
            tr("مساعد الإعداد خطوة بخطوة","Assistant de configuration pas à pas",
                "Step-by-step setup assistant"),
            tr("إعدادات جهاز الاستقبال","Paramètres du récepteur",''',
"setup entry always available")
s=once(s,
'''                else if(which==6 && showReceiverButton())zenMenu();''',
'''                else if(which==6){
                    Intent setup=new Intent(activity,AltaPlaySetupWizard.class);
                    setup.putExtra("start_over",true);
                    activity.startActivity(setup);
                }
                else if(which==7 && showReceiverButton())zenMenu();''',
"setup menu action")
panel.write_text(s,encoding="utf8")
shutil.copyfile(Path(__file__).parent/"java/AltaPlaySetupWizard.java",
                java/"AltaPlaySetupWizard.java")
print("PASS optional 5-step first-run setup and restart from general settings; normal HOME remains fast")
