#!/usr/bin/env python3
"""Replace ADB package-name input with a guided, OS-discovered HDMI mapping.

Only enable receiver key forwarding with explicit user confirmation. Never
enable Accessibility silently: that step belongs to Android Settings.
"""
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
wizard=java/"AltaPlaySetupWizard.java"
panel=java/"AltaPlayZenPanel.java"
manifest=launcher/"app/src/main/AndroidManifest.xml"

def once(s,a,b,label):
    n=s.count(a)
    if n!=1:raise RuntimeError(f"HDMI setup {label} missing / repeated: {n}")
    return s.replace(a,b,1)

s=wizard.read_text(encoding="utf8")
s=once(s,
'''                } else if(step==3 && accessibilityEnabled()){
                    Toast.makeText(this,tr("تم تفعيل خدمة التحكم",
                            "Service de télécommande activé",
                            "Remote-control service enabled"),Toast.LENGTH_SHORT).show();
                    next();
                } else showStage();''',
'''                } else if(step==3 && accessibilityEnabled()){
                    Toast.makeText(this,tr("تم تفعيل خدمة التحكم",
                            "Service de télécommande activé",
                            "Remote-control service enabled"),Toast.LENGTH_SHORT).show();
                    // Show a separate, explicit HDMI pairing choice. Enabling
                    // Accessibility alone must NEVER remap TV remote buttons.
                    showStage();
                } else showStage();''',
"Accessibility return")
s=once(s,
'''          .setPositiveButton(access?
              tr("متابعة","Continuer","Continue"):
              tr("فتح إمكانية الوصول","Ouvrir l'accessibilité",
                 "Open Accessibility settings"),(di,i)->{
              if(access){next();return;}''',
'''          .setPositiveButton(access?
              (armed?tr("متابعة","Continuer","Continue"):
                 tr("ربط تيليكوموند HDMI","Associer la télécommande HDMI",
                    "Pair HDMI remote")):
              tr("فتح إمكانية الوصول","Ouvrir l'accessibilité",
                 "Open Accessibility settings"),(di,i)->{
              if(access){
                  if(armed)next();
                  else AltaPlayHdmiGuidedSetup.show(this,this::next);
                  return;
              }''',
"HDMI after Accessibility explicit user confirmation")
wizard.write_text(s,encoding="utf8")

s=panel.read_text(encoding="utf8")
start=s.index("    private void configureRemote(){")
end=s.index("    private TextView title(",start)
if start<0 or end<=start:raise RuntimeError("Cannot isolate legacy ADB configuration")
s=s[:start]+'''    private void configureRemote(){
        // No ADB, no Android package names typed by the customer.
        // Show only OS-discovered TV HDMI viewer candidates and require
        // explicit confirmation before any accessibility key forwarding.
        AltaPlayHdmiGuidedSetup.show(activity,()->{});
    }
'''+s[end:]
panel.write_text(s,encoding="utf8")

s=manifest.read_text(encoding="utf8")
s=once(s,
'''        <package android:name="com.xiaomi.mitv.settings" />''',
'''        <package android:name="com.xiaomi.mitv.settings" />
        <!-- Only for confirming an actual installed TV HDMI viewer. -->
        <package android:name="com.mediatek.wwtv.tvcenter" />
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="content" />
        </intent>''',
"HDMI viewer package visibility")
manifest.write_text(s,encoding="utf8")
shutil.copyfile(Path(__file__).parent/"java/AltaPlayHdmiGuidedSetup.java",
                java/"AltaPlayHdmiGuidedSetup.java")
print("PASS HDMI setup: actual Android HDMI inputs + viewer packages, user consent, no ADB package names")
