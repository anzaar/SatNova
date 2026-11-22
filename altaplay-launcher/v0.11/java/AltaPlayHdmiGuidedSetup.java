package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvContract;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HDMI key forwarding setup by selecting an Android TV HDMI input, not ADB.
 * The OS describes HDMI input components; Android resolves which viewer app
 * opens that input. Only an explicitly confirmed viewer package is stored.
 * On unsupported vendors the operation remains opt-in and safely disabled.
 */
final class AltaPlayHdmiGuidedSetup {
    private AltaPlayHdmiGuidedSetup(){}

    private static final class Candidate {
        final String label,packageName;
        Candidate(String label,String pkg){this.label=label;this.packageName=pkg;}
    }

    private static String tr(Activity a,String ar,String fr,String en){
        String language=a.getSharedPreferences("altaplay_ui",Context.MODE_PRIVATE)
            .getString("language","ar");
        return "fr".equals(language)?fr:("en".equals(language)?en:ar);
    }
    private static boolean eligible(Activity a,String pkg){
        return pkg!=null && pkg.length()>3 &&
            !pkg.equals(a.getPackageName()) &&
            !pkg.equals("com.google.android.tvlauncher") &&
            !pkg.startsWith("com.android.settings") &&
            !pkg.equals("com.android.systemui");
    }
    private static boolean isInstalled(Activity a,String pkg){
        try{
            ApplicationInfo info=a.getPackageManager().getApplicationInfo(pkg,0);
            return info!=null && info.enabled;
        }catch(Exception e){return false;}
    }

    static void show(Activity activity,Runnable onComplete){
        Map<String,Candidate> found=new LinkedHashMap<>();
        try{
            TvInputManager manager=(TvInputManager)activity.getSystemService(Context.TV_INPUT_SERVICE);
            if(manager!=null){
                for(TvInputInfo input:manager.getTvInputList()){
                    if(input.getType()!=TvInputInfo.TYPE_HDMI)continue;
                    Uri uri=TvContract.buildChannelUriForPassthroughInput(input.getId());
                    Intent intent=new Intent(Intent.ACTION_VIEW,uri);
                    List<ResolveInfo> viewers=activity.getPackageManager()
                        .queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY);
                    String title=String.valueOf(input.loadLabel(activity));
                    for(ResolveInfo viewer:viewers){
                        if(viewer.activityInfo==null)continue;
                        String pkg=viewer.activityInfo.packageName;
                        if(eligible(activity,pkg)){
                            String key=pkg+" | "+title;
                            found.put(key,new Candidate(title+" · "+
                                tr(activity,"تطبيق التلفاز","Application TV","TV app"),pkg));
                        }
                    }
                }
            }
        }catch(Exception ignored){}

        // Some MediaTek TV apps show HDMI via TV-input sessions that do not
        // publish a standard VIEW intent. Only offer this known viewer when
        // the physical TV has the actual package installed, never on emulator.
        final String mediatek="com.mediatek.wwtv.tvcenter";
        if(found.isEmpty() && isInstalled(activity,mediatek))
            found.put(mediatek,new Candidate(
                tr(activity,"واجهة HDMI الخاصة بالتلفاز",
                    "Interface HDMI du téléviseur","TV HDMI viewer"),mediatek));

        if(found.isEmpty()){
            new AlertDialog.Builder(activity)
                .setTitle(tr(activity,"إعداد تيليكوموند HDMI",
                    "Télécommande HDMI","HDMI remote setup"))
                .setMessage(tr(activity,
                    "ما قدرناش نتعرفو بثقة على تطبيق HDMI فهاد التلفاز. "+
                    "ما غاديش نحوّلو الأزرار بالخطأ. تقدر تستعمل HDMI عادي وتعاود تجرب من الإعدادات.",
                    "L'application HDMI n'a pas été identifiée avec certitude. "+
                    "Les touches ne seront pas redirigées vers le mauvais logiciel. Réessayez plus tard.",
                    "Could not reliably identify this TV's HDMI viewer. "+
                    "No keys will be redirected to the wrong app. Try again from Settings."))
                .setPositiveButton(tr(activity,"مفهوم","Compris","OK"),(d,i)->{
                    if(onComplete!=null)onComplete.run();
                })
                .show();
            return;
        }

        List<Candidate> candidates=new ArrayList<>(found.values());
        String[] labels=new String[candidates.size()];
        for(int i=0;i<candidates.size();i++)labels[i]=candidates.get(i).label;
        new AlertDialog.Builder(activity)
            .setTitle(tr(activity,"اختار HDMI ديال جهاز الاستقبال",
                "Sélectionnez l'entrée HDMI du récepteur",
                "Select the receiver's HDMI input"))
            .setItems(labels,(dialog,which)->confirm(activity,candidates.get(which),onComplete))
            .setNegativeButton(tr(activity,"لاحقاً","Plus tard","Later"),(dialog,i)->{
                if(onComplete!=null)onComplete.run();
            })
            .show();
    }

    private static void confirm(Activity a,Candidate selected,Runnable after){
        new AlertDialog.Builder(a)
            .setTitle(tr(a,"تفعيل التحكم عبر HDMI",
                "Activer la télécommande HDMI","Enable HDMI remote"))
            .setMessage(tr(a,
                "واش هادي هي الواجهة اللي كتفرج منها فـHDMI على جهاز الاستقبال؟ "+
                "إلا وافقت، أزرار القنوات والأرقام وPage غادي تتبع للمستقبل "+
                "غير فهاد الواجهة. داخل Shahid وYouTube تبقى عادية.",
                "Est-ce bien l'application qui affiche votre décodeur sur HDMI ? "+
                "Si vous confirmez, les touches sélectionnées sont transmises "+
                "seulement ici. Shahid et YouTube restent inchangés.",
                "Does this TV app display your receiver on HDMI? "+
                "Only if you confirm will supported remote buttons be sent "+
                "to the receiver in this app. Other apps remain unaffected.")
                +"\\n\\n"+selected.label)
            .setPositiveButton(tr(a,"نعم، تفعيل","Oui, activer","Yes, enable"),
                (d,i)->{
                    SharedPreferences pref=a.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE);
                    pref.edit().putString("hdmi_package",selected.packageName)
                        .putBoolean("hdmi_remote_enabled",true).apply();
                    Toast.makeText(a,tr(a,"تم حفظ ربط HDMI",
                        "Association HDMI enregistrée","HDMI mapping saved"),
                        Toast.LENGTH_SHORT).show();
                    if(after!=null)after.run();
                })
            .setNegativeButton(tr(a,"رجوع","Retour","Back"),(d,i)->show(a,after))
            .show();
    }
}