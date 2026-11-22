#!/usr/bin/env python3
"""Expose *actual* online logo sources; keep Zen ProgramIds and app smooth.

The receiver catalogue remains authoritative for available channels.
Manual image per device-local ProgramId takes precedence; package/cached
art works offline; visible cards asynchronously request exact indexed logos.
No Google/HTML scraping, no arbitrary search by satellite orbital position.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
source=java/"AltaPlayOnlineLogos.java"
panel=java/"AltaPlayZenPanel.java"

def once(text,old,new,stage):
    count=text.count(old)
    if count!=1:raise RuntimeError("v0.11 Logo Sources "+stage+" anchor="+str(count))
    return text.replace(old,new,1)

s=source.read_text(encoding="utf-8")
s=once(s,
'''            .replace('\\u00a0',' ')
            .replaceAll("(?i)\\\\s+(4k|uhd|fhd|hd|sd)$","")''',
'''            .replace('\\u00a0',' ')
            .replace('í','i')
            // Broadcaster sometimes arrives as Medi1TV, Medi 1 TV or Medi1 TV HD.
            .replaceAll("(?i)medi\\\\s*1\\\\s*tv","medi1 tv")
            .replaceAll("(?i)\\\\s+(4k|uhd|fhd|hd|sd)$","")''',
"Medi1TV logo-name normalization")
s=once(s,
'''    static File cached(Context ctx,String name){''',
'''    /** Returns only an indexed, vetted HTTPS logo source, never guessed URLs. */
    static String sourceFor(Context ctx,String channel){
        return index(ctx).get(normalized(channel));
    }

    static File cached(Context ctx,String name){''',
"source lookup")
source.write_text(s,encoding="utf-8")

s=panel.read_text(encoding="utf-8")
s=once(s,
'''    private void homeSettingsMenu(){''',
'''    private void showLogoSources(){
        String info=tr(
            "مصادر اللوغوات التلقائية:\\n"+
            "• tv-logo/tv-logos (GitHub)\\n"+
            "• t4r1k2-dev/minitv-logos (GitHub)\\n\\n"+
            "الترتيب: لوغو يدوي للقناة، ثم الصور المحلية والمحفوظة، ثم مصدر موثوق من الإنترنت عند ظهور القناة. "+
            "إذا لم نجد شعاراً مطابقاً، يبقى اسم القناة ظاهراً. قائمة القنوات نفسها تأتي من جهاز الاستقبال.",
            "Sources des logos automatiques :\\n"+
            "• tv-logo/tv-logos (GitHub)\\n"+
            "• t4r1k2-dev/minitv-logos (GitHub)\\n\\n"+
            "Priorité au logo personnalisé, puis aux fichiers locaux/en cache, puis à la source HTTPS "+
            "correspondant exactement à la chaîne visible. Les chaînes proviennent toujours du récepteur.",
            "Automatic logo sources:\\n"+
            "• tv-logo/tv-logos (GitHub)\\n"+
            "• t4r1k2-dev/minitv-logos (GitHub)\\n\\n"+
            "Custom per-channel logos take priority, followed by offline bundled/cached images, "+
            "then exact-match HTTPS sources for visible channels. The receiver owns the channel list.");
        new AlertDialog.Builder(activity)
            .setTitle(tr("مصادر لوغوات القنوات","Sources des logos TV","TV logo sources"))
            .setMessage(info)
            .setPositiveButton(tr("حسناً","OK","OK"),null).show();
    }
    private void showChannelLogoSource(ZenClient.Channel item){
        String url=AltaPlayOnlineLogos.sourceFor(activity,item.name);
        String message=url==null?
            tr("ما كاينش مصدر تلقائي مطابق لهذا الاسم حالياً. تقدر تضيف لوغو يدوي من الرابط أو FTP.",
               "Aucune source automatique exacte pour ce nom. Ajoutez une URL/FTP si nécessaire.",
               "No exact automatic source for this channel. You can add a manual URL/FTP logo."):
            tr("المصدر التلقائي المطابق:\\n","Source automatique correspondante :\\n",
               "Exact automatic source:\\n")+url;
        new AlertDialog.Builder(activity)
            .setTitle(displayName(item))
            .setMessage(message)
            .setPositiveButton(tr("حسناً","OK","OK"),null).show();
    }
    private void homeSettingsMenu(){''',
"add source explanations")
s=once(s,
'''            tr("اللغة","Langue","Language"),
            tr("إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur",''',
'''            tr("اللغة","Langue","Language"),
            tr("مصادر لوغوات القنوات","Sources des logos TV","TV logo sources"),
            tr("إظهار وإخفاء جهاز الاستقبال","Afficher ou masquer le récepteur",''',
"settings source item")
s=once(s,
'''                else if(which==3)receiverVisibilityMenu();
                else if(which==4 && showReceiverButton())zenMenu();''',
'''                else if(which==3)showLogoSources();
                else if(which==4)receiverVisibilityMenu();
                else if(which==5 && showReceiverButton())zenMenu();''',
"menu indices")
s=once(s,
'''            tr("لوغو يدوي من رابط أو FTP","Logo via URL ou FTP","Manual logo from URL or FTP"),
            tr("استرجاع اللوغو التلقائي","Restaurer le logo automatique","Restore automatic logo")''',
'''            tr("لوغو يدوي من رابط أو FTP","Logo via URL ou FTP","Manual logo from URL or FTP"),
            tr("معرفة مصدر لوغو القناة","Voir la source du logo","View logo source"),
            tr("استرجاع اللوغو التلقائي","Restaurer le logo automatique","Restore automatic logo")''',
"channel logo source item")
s=once(s,
'''                else if(which==1)manualLogo(item);
                else{
                    File logo=new File(new File(activity.getFilesDir(),"zen-logos"),item.id+".png");''',
'''                else if(which==1)manualLogo(item);
                else if(which==2)showChannelLogoSource(item);
                else{
                    File logo=new File(new File(activity.getFilesDir(),"zen-logos"),item.id+".png");''',
"channel source action")
panel.write_text(s,encoding="utf-8")
print("PASS: logo provenance in Settings and per-channel long-press, no satellite channel injection")
