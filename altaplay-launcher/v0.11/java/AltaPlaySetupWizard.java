package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time, optional, fully remote-operable AltaPlay setup.
 *
 * All Android HOME and Accessibility changes are delegated to system Settings.
 * No shell commands, silent approvals, system app disabling, or hidden TV
 * permissions. Every stage is resumable and can be deferred from Home Settings.
 * Connection discovery is asynchronous; no network work blocks the UI.
 */
public final class AltaPlaySetupWizard extends Activity {
    private static final String PREF="altaplay_setup";
    private static final String UI="altaplay_ui";
    private static final int REQUEST_HOME=100;
    private static final int REQUEST_ACCESSIBILITY=101;
    private static final AtomicBoolean AUTO_OFFERED=new AtomicBoolean(false);

    private SharedPreferences setup;
    private int step;
    private boolean receiverWanted=true;
    private boolean externalOpen=false;
    private boolean showing=false;
    private AlertDialog current;

    static void offerOnce(Activity owner) {
        if(AUTO_OFFERED.get())return;
        View content=owner.findViewById(R.id.root);
        if(content==null)content=owner.getWindow().getDecorView();
        final View target=content;
        // Retry while Home is regaining focus. The old implementation marked
        // the guide as offered BEFORE checking focus, so one missed first
        // frame permanently hid the installation guide.
        target.postDelayed(()->offerWhenReady(owner,target,0),2200);
    }

    private static void offerWhenReady(Activity owner,View target,int attempt) {
        if(owner.isFinishing() || owner.isDestroyed() || AUTO_OFFERED.get())return;
        SharedPreferences p=owner.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        if(p.getBoolean("completed",false) || p.getBoolean("deferred",false)) {
            AUTO_OFFERED.set(true);
            return;
        }
        if(!target.hasWindowFocus()) {
            if(attempt<12)target.postDelayed(
                ()->offerWhenReady(owner,target,attempt+1),1500);
            return;
        }
        if(AUTO_OFFERED.compareAndSet(false,true)) {
            try {
                owner.startActivity(new Intent(owner,AltaPlaySetupWizard.class));
            } catch(Exception failure) {
                AUTO_OFFERED.set(false);
            }
        }
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setup=getSharedPreferences(PREF,MODE_PRIVATE);
        if(getIntent().getBooleanExtra("start_over",false)){
            setup.edit().putBoolean("completed",false).putBoolean("deferred",false)
                .putInt("step",0).apply();
        }
        step=Math.max(0,Math.min(4,setup.getInt("step",0)));
        receiverWanted=setup.getBoolean("receiver_wanted",true);
        showStage();
    }

    @Override protected void onResume(){
        super.onResume();
        if(externalOpen) {
            externalOpen=false;
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                if(isFinishing()||isDestroyed())return;
                if(step==1 && isDefaultHome()) {
                    Toast.makeText(this,tr("تم تعيين AltaPlay كواجهة رئيسية",
                            "AltaPlay est maintenant l'accueil",
                            "AltaPlay is now the default Home"),Toast.LENGTH_SHORT).show();
                    next();
                } else if(step==3 && accessibilityEnabled()){
                    Toast.makeText(this,tr("تم تفعيل خدمة التحكم",
                            "Service de télécommande activé",
                            "Remote-control service enabled"),Toast.LENGTH_SHORT).show();
                    next();
                } else showStage();
            },400);
        }
    }

    private void setStep(int s){
        step=Math.max(0,Math.min(4,s));
        setup.edit().putInt("step",step).putBoolean("deferred",false).apply();
        showStage();
    }
    private void next(){setStep(step+1);}
    private String language(){
        return getSharedPreferences(UI,MODE_PRIVATE).getString("language",
            Locale.getDefault().getLanguage().startsWith("fr")?"fr":
            Locale.getDefault().getLanguage().startsWith("ar")?"ar":"en");
    }
    private String tr(String ar,String fr,String en){
        String lang=language();
        return "fr".equals(lang)?fr:("en".equals(lang)?en:ar);
    }
    private String title(){
        String x=tr("إعداد AltaPlay","Configuration d'AltaPlay","AltaPlay setup");
        return x+" · "+(step+1)+"/5";
    }
    private void defer(){
        setup.edit().putBoolean("deferred",true).putInt("step",step).apply();
        finish();
    }
    private void dialog(AlertDialog d) {
        if(current!=null && current.isShowing()) current.dismiss();
        current=d;
        d.setOnCancelListener(ignore->defer());
        d.show();
        View decor=d.getWindow()==null?null:d.getWindow().getDecorView();
        if(decor!=null)decor.setLayoutDirection("ar".equals(language())?
            View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        showing=true;
    }
    private void showStage(){
        if(isFinishing()||isDestroyed())return;
        showing=false;
        if(step==0)chooseLanguage();
        else if(step==1)chooseHome();
        else if(step==2)chooseReceiver();
        else if(step==3)chooseRemote();
        else finishFlow();
    }

    private void chooseLanguage(){
        String[] items={"العربية","Français","English"};
        String[] codes={"ar","fr","en"};
        AlertDialog d=new AlertDialog.Builder(this)
          .setTitle(title()+" · "+tr("اختار لغة الواجهة",
             "Choisissez votre langue","Choose your language"))
          .setItems(items,(di,i)->{
              getSharedPreferences(UI,MODE_PRIVATE).edit()
                  .putString("language",codes[i]).apply();
              // Language and reading direction change on next Home load too.
              next();
          })
          .setNegativeButton(tr("لاحقاً","Plus tard","Later"),(di,i)->defer())
          .create();
        dialog(d);
    }

    private void chooseHome(){
        AlertDialog d=new AlertDialog.Builder(this)
          .setTitle(title())
          .setMessage(isDefaultHome()?
              tr("AltaPlay محددة بالفعل كواجهة رئيسية. اضغط متابعة.",
                 "AltaPlay est déjà l'écran d'accueil. Continuez.",
                 "AltaPlay is already the default Home. Continue."):
              tr("باش زر HOME يرجعك لـAltaPlay، غادي نفتح إعدادات Android. "+
                 "اختار AltaPlay ووافق. Google TV غادي يبقى مثبت، ويمكنك ترجع ليه فأي وقت.",
                 "Pour utiliser AltaPlay avec la touche Accueil, ouvrez les paramètres Android, "+
                 "choisissez AltaPlay et validez. Google TV reste installé et réversible.",
                 "To use AltaPlay for the Home button, open Android settings, "+
                 "select AltaPlay and confirm. Google TV stays installed and can be restored."))
          .setPositiveButton(isDefaultHome()?
              tr("متابعة","Continuer","Continue"):
              tr("فتح إعدادات الشاشة الرئيسية","Ouvrir les réglages Accueil",
                 "Open Android Home settings"),(di,i)->{
                  if(isDefaultHome()){next();return;}
                  try{
                      externalOpen=true;
                      startActivityForResult(new Intent(Settings.ACTION_HOME_SETTINGS),REQUEST_HOME);
                  }catch(Exception e){
                      externalOpen=false;
                      Toast.makeText(this,tr("ما قدرناش نفتح الإعدادات، يمكن تتجاوز هاد المرحلة.",
                        "Réglages indisponibles. Vous pouvez passer cette étape.",
                        "Settings unavailable. You can skip this step."),Toast.LENGTH_LONG).show();
                      showStage();
                  }
              })
          .setNeutralButton(tr("تجاوز دابا","Ignorer","Skip for now"),(di,i)->next())
          .setNegativeButton(tr("رجوع","Retour","Back"),(di,i)->setStep(0))
          .create();
        dialog(d);
    }

    private boolean isDefaultHome(){
        try {
            RoleManager role=getSystemService(RoleManager.class);
            if(role!=null){
                // Android public API: this app can query only whether IT owns HOME.
                // getRoleHolders() is a privileged API on many Android TV SDKs.
                if(role.isRoleHeld(RoleManager.ROLE_HOME))return true;
            }
        }catch(Exception ignored){}
        try {
            Intent home=new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo target=
                getPackageManager().resolveActivity(home,PackageManager.MATCH_DEFAULT_ONLY);
            return target!=null && target.activityInfo!=null
                && getPackageName().equals(target.activityInfo.packageName);
        }catch(Exception ignored){return false;}
    }

    private void chooseReceiver(){
        AlertDialog d=new AlertDialog.Builder(this)
          .setTitle(title())
          .setMessage(tr(
             "عندك جهاز استقبال متصل بالتلفاز؟ إذا لا، غادي نخبيو القنوات وزر الاستقبال، "+
             "وتبقى التطبيقات خدامة عادي. تقدر ترجعهم من الإعدادات.",
             "Vous avez un décodeur ? Sinon, les chaînes et le bouton du récepteur "+
             "seront masqués, sans toucher à vos applications. Option réversible.",
             "Do you use a satellite receiver? If not, hide its channels and button "+
             "without affecting apps. You can restore them later."))
          .setPositiveButton(tr("نعم، بحث تلقائي","Oui, recherche auto",
              "Yes, find automatically"),(di,i)->{
              receiverWanted=true;
              saveReceiverChoice(true);
              try{
                  ZenClient c=ZenClient.get(this);
                  c.start();
                  c.discoverNow();
              }catch(Exception ignored){}
              next();
          })
          .setNeutralButton(tr("ما عنديش جهاز استقبال","Sans récepteur",
              "I don't use a receiver"),(di,i)->{
              receiverWanted=false;
              saveReceiverChoice(false);
              next();
          })
          .setNegativeButton(tr("إدخال IP يدوياً","Saisir une adresse IP",
              "Enter IP manually"),(di,i)->manualReceiverAddress())
          .create();
        dialog(d);
    }

    private void saveReceiverChoice(boolean yes){
        getSharedPreferences(UI,MODE_PRIVATE).edit()
            .putBoolean("receiver_show_channels",yes)
            .putBoolean("receiver_show_button",yes).apply();
        setup.edit().putBoolean("receiver_wanted",yes).apply();
        // No deletion: cached channels/favorites, logo overrides and remote
        // settings are preserved if the user later changes the choice.
    }

    private void manualReceiverAddress(){
        EditText text=new EditText(this);
        text.setSingleLine(true);
        text.setHint("192.168.1.2");
        text.setText(ZenClient.get(this).host());
        AlertDialog d=new AlertDialog.Builder(this)
            .setTitle(tr("عنوان جهاز الاستقبال","Adresse IP du récepteur",
                "Receiver IP address"))
            .setMessage(tr("إلا ما بانش فالبحث التلقائي، دخل IP من نفس الشبكة.",
              "Si la recherche ne le trouve pas, saisissez son IP sur le même réseau.",
              "If discovery fails, enter its address on the same Wi-Fi/LAN."))
            .setView(text)
            .setPositiveButton(tr("حفظ ومتابعة","Enregistrer et continuer",
                "Save and continue"),(di,i)->{
                try{
                    ZenClient.get(this).setHost(text.getText().toString().trim());
                    receiverWanted=true;
                    saveReceiverChoice(true);
                    next();
                }catch(Exception e){
                    Toast.makeText(this,tr("عنوان غير صالح","Adresse invalide",
                      "Invalid address"),Toast.LENGTH_LONG).show();
                    manualReceiverAddress();
                }
            })
            .setNeutralButton(tr("بحث تلقائي","Recherche auto","Automatic discovery"),
                (di,i)->chooseReceiver())
            .setNegativeButton(tr("رجوع","Retour","Back"),(di,i)->chooseReceiver())
            .create();
        dialog(d);
    }

    private boolean accessibilityEnabled(){
        try{
            AccessibilityManager am=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);
            if(am==null)return false;
            for(AccessibilityServiceInfo si:
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)){
                if(si.getResolveInfo()!=null && si.getResolveInfo().serviceInfo!=null){
                    android.content.pm.ServiceInfo service=si.getResolveInfo().serviceInfo;
                    if(getPackageName().equals(service.packageName) &&
                       service.name.endsWith(".AltaPlayHdmiRemoteService"))
                        return true;
                }
            }
        }catch(Exception ignored){}
        return false;
    }

    private void chooseRemote(){
        if(!receiverWanted){next();return;}
        boolean access=accessibilityEnabled();
        boolean armed=getSharedPreferences("altaplay_zen",MODE_PRIVATE)
            .getBoolean("hdmi_remote_enabled",false);
        AlertDialog d=new AlertDialog.Builder(this)
          .setTitle(title())
          .setMessage(
              tr("باش تتحكم فالمستقبل بتيليكوموند التلفاز وانت فـHDMI، خاصك توافق على "+
                 "خدمة «AltaPlay HDMI Remote» من إمكانية الوصول. هاد الخدمة ما كتخدمش "+
                 "داخل التطبيقات الأخرى. التفعيل اختياري.",
                 "Pour contrôler le décodeur depuis HDMI, activez le service "+
                 "«AltaPlay HDMI Remote» dans les paramètres d'accessibilité. "+
                 "Il ne doit pas intercepter les touches dans les autres applications. Facultatif.",
                 "To control the receiver from HDMI, enable “AltaPlay HDMI Remote” "+
                 "in Android Accessibility settings. Other apps keep their usual keys. Optional.")
              +"\n\n"+(access?
                 tr("الخدمة مفعّلة","Service activé","Service enabled"):
                 tr("الخدمة غير مفعّلة","Service désactivé","Service not enabled"))
              + (armed?"\n"+tr("ربط أزرار HDMI محفوظ","Commande HDMI enregistrée",
                  "HDMI key mapping is saved"):"\n"+
                  tr("كمّل ربط أزرار HDMI من إعدادات جهاز الاستقبال بعد هاد الخطوة.",
                     "Terminez l'association HDMI dans les paramètres du récepteur.",
                     "Finish HDMI key mapping in Receiver settings afterward.")))
          .setPositiveButton(access?
              tr("متابعة","Continuer","Continue"):
              tr("فتح إمكانية الوصول","Ouvrir l'accessibilité",
                 "Open Accessibility settings"),(di,i)->{
              if(access){next();return;}
              try{
                  externalOpen=true;
                  startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                      REQUEST_ACCESSIBILITY);
              }catch(Exception e){
                  externalOpen=false;
                  showStage();
              }
          })
          .setNeutralButton(tr("تجاوز مؤقتاً","Ignorer","Skip for now"),(di,i)->next())
          .setNegativeButton(tr("رجوع","Retour","Back"),(di,i)->setStep(2))
          .create();
        dialog(d);
    }

    private void finishFlow(){
        String msg=tr(
          "الإعداد الأساسي كمل. التطبيقات والخلفية خدامين حتى بلا جهاز استقبال. "+
          "إذا بغيتي تبدّل شي حاجة، دخل لـ«الإعدادات» فالواجهة.",
          "Configuration initiale terminée. Les applications et le fond d'écran "+
          "restent utilisables sans décodeur. Modifiez les options dans Paramètres.",
          "Basic setup complete. Apps and wallpaper work without a receiver. "+
          "You can update these choices from Settings.")
          +"\n\n"+(isDefaultHome()?
             tr("✓ AltaPlay هي الواجهة الرئيسية","✓ AltaPlay est l'accueil",
                "✓ AltaPlay is the default Home"):
             tr("• HOME ما تبدلاتش؛ تقدر تعيّنها لاحقاً","• L'accueil reste inchangé",
                "• HOME is unchanged; you can set it later."));
        AlertDialog d=new AlertDialog.Builder(this)
          .setTitle(tr("جاهز للاستعمال","Prêt à utiliser","Ready to use"))
          .setMessage(msg)
          .setPositiveButton(tr("دخول للواجهة","Ouvrir AltaPlay","Open AltaPlay"),
            (di,i)->{
                setup.edit().putInt("step",4)
                    .putBoolean("completed",true).putBoolean("deferred",false).apply();
                finish();
            })
          .setNegativeButton(tr("رجوع","Retour","Back"),(di,i)->setStep(3))
          .create();
        dialog(d);
    }

    @Override public void onBackPressed(){
        if(step>0)setStep(step-1);
        else defer();
    }
}