#!/usr/bin/env python3
"""AltaPlay v0.11 generic receiver name until authenticated receiver identifies itself.
Preserve last successfully verified device label after DHCP reconnect/offline.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
client=java/"ZenClient.java";panel=java/"AltaPlayZenPanel.java"
def once(text,old,new,name):
    n=text.count(old)
    if n!=1:raise RuntimeError("receiver label "+name+" anchor="+str(n))
    return text.replace(old,new,1)
c=client.read_text(encoding="utf-8")
c=once(c,'jsonMode=info.json;model=info.model;',
'''jsonMode=info.json;model=info.model;
            // Persist only an actual authenticated model string from the
            // receiver. ZenProtocol.decodeLogin uses "ZEN" as fallback;
            // that fallback is NOT proof of hardware model.
            if(model!=null && !model.trim().isEmpty() &&
               !"ZEN".equalsIgnoreCase(model.trim()) &&
               model.length()<=48 && model.indexOf('\\n')<0)
                prefs.edit().putString("receiver_model",model.trim()).apply();''',
"persist verified model")
# Diagnostics should not claim Zen 4 on a virgin app.
c=once(c,
'''return (online?"Connected":"Disconnected")+" · Zen 4"''',
'''return (online?"Connected":"Disconnected")+" · "+
            prefs.getString("receiver_model","Receiver")''',
"generic diagnostics")
client.write_text(c,encoding="utf-8")
s=panel.read_text(encoding="utf-8")
s=once(s,
'''    private void zenMenu(){''',
'''    private String receiverDisplayName(){
        String name=activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE)
            .getString("receiver_model","").trim();
        if(name.isEmpty()||name.length()>48)
            return tr("جهاز الاستقبال","Récepteur","Receiver");
        return name;
    }
    private void zenMenu(){''',
"name resolver")
s=once(s,
'''String newText=online?tr("●  Zen 4 · متصل","● Zen 4 · connecté","● Zen 4 · connected"):tr("◇ Zen 4 · غير متصل","◇ Zen 4 · déconnecté","◇ Zen 4 · disconnected");''',
'''String newText=(online?"●  ":"◇  ")+receiverDisplayName()+" · "+
            (online?tr("متصل","connecté","connected"):
                tr("غير متصل","déconnecté","disconnected"));''',
"top receiver label")
s=once(s,
'''            .setTitle("Zen 4 · "+(client.isOnline()?
                tr("متصل","connecté","connected"):tr("غير متصل","déconnecté","disconnected")))''',
'''            .setTitle(receiverDisplayName()+" · "+(client.isOnline()?
                tr("متصل","connecté","connected"):tr("غير متصل","déconnecté","disconnected")))''',
"receiver settings title")
s=once(s,
'''                    .setTitle(tr("حالة اتصال Zen 4","État de connexion Zen 4","Zen 4 connection status"))''',
'''                    .setTitle(receiverDisplayName()+" · "+
                        tr("حالة الاتصال","État de connexion","Connection status"))''',
"receiver diagnostics title")
s=s.replace('tr("إعدادات Zen 4","Paramètres Zen 4","Zen 4 settings")',
            'tr("إعدادات جهاز الاستقبال","Paramètres du récepteur","Receiver settings")')
panel.write_text(s,encoding="utf-8")
print("PASS generic AR/FR/EN receiver name until authenticated model, remembered when offline")
