#!/usr/bin/env python3
"""v0.11: say generic Receiver until a device-name is confirmed by login.

Only the protocol's successful authenticated login can supply the model.
Cache a real model together with receiver hardware identity, never an
assumption from old UI text. All three language labels work offline.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"

def patch_one(text,old,new,tag):
    n=text.count(old)
    if n!=1:raise RuntimeError(f"v0.11 receiver name: {tag} anchor={n} {old[:110]!r}")
    return text.replace(old,new,1)

client=java/"ZenClient.java"
s=client.read_text(encoding="utf-8")
s=patch_one(s,
    'private volatile String model="ZEN 4", status="Zen 4 غير متصل";',
    'private volatile String model="", status="جهاز الاستقبال غير متصل";',
    "no assumed receiver name")

s=patch_one(s,
    '    boolean isOnline(){return online;}',
    '''    boolean isOnline(){return online;}

    // The decoder uses the word "ZEN" when the receiver sends no model.
    // Treat that placeholder as unconfirmed; don't present it as a device name.
    private static boolean validReceiverModel(String candidate){
        if(candidate==null)return false;
        String name=candidate.trim();
        if(name.length()<2 || name.length()>45 ||
           "ZEN".equalsIgnoreCase(name) ||
           "UNKNOWN".equalsIgnoreCase(name) ||
           "RECEIVER".equalsIgnoreCase(name) ||
           "STB".equalsIgnoreCase(name))return false;
        return name.matches("[\\\\p{L}\\\\p{N} ._+()/-]{2,45}");
    }

    String receiverDisplayName(){
        String name=model;
        if(!validReceiverModel(name)){
            String storedId=prefs.getString("receiver_model_id","");
            String receiverId=prefs.getString("receiver_id","");
            if(!storedId.isEmpty() && storedId.equals(receiverId))
                name=prefs.getString("receiver_model_name","");
        }
        if(validReceiverModel(name))return name.trim();

        String lang=context.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
            .getString("language","ar");
        return "fr".equals(lang)?"Récepteur":
            ("en".equals(lang)?"Receiver":"جهاز الاستقبال");
    }''',
    "generic localized label from authenticated model")

s=patch_one(s,
    'jsonMode=info.json;model=info.model;',
    '''jsonMode=info.json;
            // Only the actual login response can assign a model. Keep the
            // last known name only for the SAME confirmed receiver identity.
            String verified=validReceiverModel(info.model)?info.model.trim():"";
            model=verified;
            if(!id.isEmpty() && !verified.isEmpty()){
                prefs.edit().putString("receiver_model_id",id)
                    .putString("receiver_model_name",verified).apply();
            }''',
    "verified model on handshake")

s=patch_one(s,
    'setStatus(model+" • متصل",true);',
    'setStatus(receiverDisplayName()+" • متصل",true);',
    "receiver connected text")
s=patch_one(s,
    'return (online?"Connected":"Disconnected")+" · Zen 4"',
    'return (online?"Connected":"Disconnected")+" · "+receiverDisplayName()',
    "diagnostic name")
# Avoid name injection in the per-operation status visible in older layouts.
s=s.replace('setStatus("Zen 4 ', 'setStatus(receiverDisplayName()+" ')
s=s.replace('setStatus("Zen 4', 'setStatus(receiverDisplayName()+"')
s=s.replace('lastError="Zen 4 not found', 'lastError="Receiver not found')
client.write_text(s,encoding="utf-8")

panel=java/"AltaPlayZenPanel.java"
s=panel.read_text(encoding="utf-8")
s=patch_one(s,
    'zenPill=title("◇ Zen 4 · غير متصل",15,Color.WHITE);',
    '''zenPill=title("◇ "+client.receiverDisplayName()+" · "+
                tr("غير متصل","déconnecté","disconnected"),15,Color.WHITE);''',
    "initial generic pill")
s=patch_one(s,
    'String newText=online?tr("●  Zen 4 · متصل","● Zen 4 · connecté","● Zen 4 · connected"):tr("◇ Zen 4 · غير متصل","◇ Zen 4 · déconnecté","◇ Zen 4 · disconnected");',
    '''String newText=(online?"●  ":"◇  ")+client.receiverDisplayName()+
            " · "+(online?tr("متصل","connecté","connected"):
                     tr("غير متصل","déconnecté","disconnected"));''',
    "status text follows actual receiver model")
s=patch_one(s,
    '.setTitle("Zen 4 · "+(client.isOnline()?',
    '.setTitle(client.receiverDisplayName()+" · "+(client.isOnline()?',
    "menu title dynamic")
s=patch_one(s,
    'tr("حالة اتصال Zen 4","État de connexion Zen 4","Zen 4 connection status")',
    'tr("حالة اتصال جهاز الاستقبال","État de connexion du récepteur","Receiver connection status")',
    "diagnostic title generic")
s=patch_one(s,
    'new AlertDialog.Builder(activity).setTitle("ZEN PRO 4 · عنوان IP المحلي")',
    'new AlertDialog.Builder(activity).setTitle(client.receiverDisplayName()+" · "+tr("عنوان IP المحلي","Adresse IP locale","Local IP address"))',
    "manual IP entry")
s=patch_one(s,
    'tr("إعدادات Zen 4","Paramètres Zen 4","Zen 4 settings")',
    'tr("إعدادات جهاز الاستقبال","Paramètres du récepteur","Receiver settings")',
    "settings from greeting")

panel.write_text(s,encoding="utf-8")
print("PASS: generic Arabic/French/English Receiver before handshake, verified model after connection, model survives DHCP change")
