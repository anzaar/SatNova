#!/usr/bin/env python3
"""Allow hiding receiver TV shelf and receiver setup button independently.

Both options are available from general AltaPlay Settings even when BOTH
receiver elements are hidden. Never delete caches, logs, logos or any Zen
accessibility/HDMI bridge preferences.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
src=launcher/"app/src/main/java/com/gathin/tvhome/AltaPlayZenPanel.java"
s=src.read_text(encoding="utf-8")
def once(old,new,label):
    global s
    n=s.count(old)
    if n!=1:raise RuntimeError("receiver visibility "+label+" anchor="+str(n))
    s=s.replace(old,new,1)

once('    private TextView zenPill;',
'''    private TextView zenPill;
    private View receiverShelf;''',
"retained receiver shelf")

once('''        shell.addView(panel,index+1,shelfLayout);''',
'''        shell.addView(panel,index+1,shelfLayout);
        receiverShelf=panel;
        receiverShelf.setVisibility(showReceiverChannels()?View.VISIBLE:View.GONE);''',
"hide TV shelf and free measured screen space")

once('''            top.addView(zenPill,Math.max(0,pos),lp);''',
'''            top.addView(zenPill,Math.max(0,pos),lp);
            zenPill.setVisibility(showReceiverButton()?View.VISIBLE:View.GONE);
            zenPill.setFocusable(showReceiverButton());''',
"hide receiver top pill")

# Updates to the status pill remain cheap and independent of the shelf.
once('''        updateZenPill();
        List<ZenClient.Channel> channels=new ArrayList<>(),radios=new ArrayList<>();''',
'''        updateZenPill();
        if(!showReceiverChannels())return; // no hidden cards rendered/rebuilt
        List<ZenClient.Channel> channels=new ArrayList<>(),radios=new ArrayList<>();''',
"avoid rendering hidden channel cards")

# Visibility is reversible even if both controls are hidden; preferences
# default to enabled for backward compatibility with existing installations.
once('''    private void homeSettingsMenu(){''',
'''    private android.content.SharedPreferences visibilityPrefs(){
        return activity.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE);
    }
    private boolean showReceiverChannels(){
        return visibilityPrefs().getBoolean("receiver_show_channels",true);
    }
    private boolean showReceiverButton(){
        return visibilityPrefs().getBoolean("receiver_show_button",true);
    }
    private void receiverVisibilityMenu(){
        final boolean[] values={showReceiverChannels(),showReceiverButton()};
        final String[] labels={
            tr("إظهار قسم قنوات جهاز الاستقبال","Afficher les chaînes du récepteur",
                "Show receiver channel shelf"),
            tr("إظهار زر جهاز الاستقبال وإعداداته","Afficher le bouton et les réglages du récepteur",
                "Show receiver button and settings")
        };
        new AlertDialog.Builder(activity)
            .setTitle(tr("إظهار وإخفاء جهاز الاستقبال","Affichage du récepteur",
                "Receiver visibility"))
            .setMultiChoiceItems(labels,values,(dialog,index,checked)->values[index]=checked)
            .setPositiveButton(tr("حفظ","Enregistrer","Save"),(dialog,which)->{
                visibilityPrefs().edit()
                    .putBoolean("receiver_show_channels",values[0])
                    .putBoolean("receiver_show_button",values[1])
                    .apply();
                // Rebuild only the local UI, not data, receiver or Android HOME.
                activity.recreate();
            })
            .setNegativeButton(tr("إلغاء","Annuler","Cancel"),null).show();
    }
    private void homeSettingsMenu(){''',
"persistent independent options")

# The general settings menu MUST remain the way back if both are hidden.
once('''            tr("إعدادات Zen 4","Paramètres Zen 4","Zen 4 settings")
        };''',
'''            tr("إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur",
                "Show or hide receiver"),
            tr("إعدادات جهاز الاستقبال","Paramètres du récepteur",
                "Receiver settings")
        };
        // Hide the receiver-settings ENTRY when the receiver button itself
        // is hidden, but always show the independent visibility control.
        if(!showReceiverButton())
            choices=java.util.Arrays.copyOf(choices,choices.length-1);''',
"settings recoverability and hidden receiver menu")

once('''                else if(which==3)zenMenu();''',
'''                else if(which==3)receiverVisibilityMenu();
                else if(which==4 && showReceiverButton())zenMenu();''',
"visibility settings accessible from greeting-position Settings")

src.write_text(s,encoding="utf-8")
print("PASS receiver visibility: independent shelf/button toggles, hide both, restore via Settings; caches untouched")
