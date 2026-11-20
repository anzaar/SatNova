#!/usr/bin/env python3
"""AltaPlay v0.8: visible channel controls, lazy online logos, next prayer,
Moroccan Hijri under Gregorian, HDMI opt-in TV remote and Shahid green key.
Apply ONLY after v0.7 has rebuilt sources, no removal of Android stock HOME.
"""
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
java=launcher/"app/src/main/java/com/gathin/tvhome"
panel=java/"AltaPlayZenPanel.java"
main=java/"MainActivity.java"
prayer=java/"HabousPrayerSchedule.java"
manifest=launcher/"app/src/main/AndroidManifest.xml"
here=Path(__file__).resolve().parent

def once(s,old,new,label):
    n=s.count(old)
    if n!=1:raise RuntimeError(label+" anchor must occur once, found "+str(n)+": "+repr(old[:100]))
    return s.replace(old,new,1)

s=main.read_text(encoding="utf-8")
s=once(s,
    "if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;",
    "if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return AltaPlayZenPanel.focusTvRow(this);",
    "one-row DPAD DOWN")
s=once(s,
    "dispatchKeyEvent(KeyEvent event) {\n        // A real key",
    '''dispatchKeyEvent(KeyEvent event) {
        // Keys are received by the launcher only while it is foreground.
        // HDMI forwarding is handled separately by an explicitly enabled
        // accessibility service, with exact package matching.
        if (AltaPlayRemoteKeys.colored(event.getKeyCode())) {
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)
                AltaPlayRemoteKeys.handle(this,event.getKeyCode());
            return true;
        }
        // A real key''',
    "colored TV remote keys")
main.write_text(s,encoding="utf-8")

s=prayer.read_text(encoding="utf-8")
s=once(s,
    '    Map<String,LocalTime> today(){',
    '''    Map<String,LocalTime> forDate(LocalDate day){
        if(day==null)return Collections.emptyMap();
        try{
            YearMonth month=YearMonth.from(day);
            JSONObject source=loadedMonth(month);
            return source==null?Collections.emptyMap():times(source,month,day,selected().id);
        }catch(Exception e){return Collections.emptyMap();}
    }
    Map<String,LocalTime> today(){''',
    "tomorrow official timetable")
prayer.write_text(s,encoding="utf-8")

s=panel.read_text(encoding="utf-8")
s=once(s,
    "import android.widget.ImageView;",
    "import android.widget.ImageView;\nimport android.view.KeyEvent;\nimport android.provider.Settings;\nimport android.text.InputType;\nimport android.widget.EditText;",
    "remote imports") if "import android.widget.EditText;" not in s else once(s,
    "import android.widget.ImageView;",
    "import android.widget.ImageView;\nimport android.view.KeyEvent;\nimport android.provider.Settings;\nimport android.text.InputType;",
    "remote imports")
s=once(s,
    "    private TextView arabicDate;",
    '    private TextView arabicDate;\n    private TextView hijriDate;',
    "Hijri field")

s=once(s,
    "        panel.setClipChildren(false);panel.setClipToPadding(false);",
    "        panel.setClipChildren(true);panel.setClipToPadding(true);",
    "clip panel to rounded shelf")
s=once(s,
    "        scroll.setFillViewport(true);scroll.setFocusable(false);",
    '''        scroll.setFillViewport(false);scroll.setFocusable(false);
        scroll.setClipChildren(true);scroll.setClipToPadding(true);''',
    "TV horizontal scroll bounds")
s=once(s,
    "        channelRow.setClipChildren(false);",
    '''        channelRow.setTag("altaplay_tv_row");
        channelRow.setClipChildren(true);''',
    "TV focus tag")
s=once(s,
    "        channelRow.setClipToPadding(false);",
    "        channelRow.setClipToPadding(true);",
    "TV focus clipping")
s=once(s,
    "        radioScroll.setFillViewport(true);radioScroll.setFocusable(false);",
    '''        radioScroll.setFillViewport(false);radioScroll.setFocusable(false);
        radioScroll.setClipChildren(true);radioScroll.setClipToPadding(true);''',
    "radio bounds")
s=once(s,
    "        radioRow=new LinearLayout(a);radioRow.setGravity(Gravity.CENTER_VERTICAL);",
    '''        radioRow=new LinearLayout(a);radioRow.setGravity(Gravity.CENTER_VERTICAL);
        radioRow.setTag("altaplay_radio_row");''',
    "radio focus tag")
s=once(s,
    "        radioRow.setClipChildren(false);",
    "        radioRow.setClipChildren(true);",
    "radio focus clipping")
s=once(s,
    "        connection.setOnClickListener(v->configure());",
    "        connection.setOnClickListener(v->configure());",
    "receiver config preserved") if False else s

# Gregorian header remains in its original date column; Hijri goes just below.
s=once(s,
    '''            dateColumn.addView(arabicDate,dateColumn.indexOfChild(date)+1,
                new LinearLayout.LayoutParams(-1,dp(27)));''',
    '''            dateColumn.addView(arabicDate,dateColumn.indexOfChild(date)+1,
                new LinearLayout.LayoutParams(-1,dp(24)));
            hijriDate=title("",14,0xFFE0E7D5);
            hijriDate.setSingleLine(true);
            hijriDate.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
            hijriDate.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
            hijriDate.setFocusable(true);
            hijriDate.setContentDescription("تاريخ هجري تقديري. اضغط للتعديل حسب رؤية الهلال في المغرب");
            hijriDate.setOnClickListener(v->configureHijri());
            dateColumn.addView(hijriDate,dateColumn.indexOfChild(arabicDate)+1,
                new LinearLayout.LayoutParams(-1,dp(19)));''',
    "Hijri under Gregorian")
s=once(s,
    '''        if(arabicDate!=null){
            arabicDate.setText(LocalDate.now(MOROCCO)
                .format(DateTimeFormatter.ofPattern("EEEE، d MMMM",AR_MA)));
        }''',
    '''        LocalDate day=LocalDate.now(MOROCCO);
        if(arabicDate!=null)
            arabicDate.setText(day.format(DateTimeFormatter.ofPattern("EEEE، d MMMM",AR_MA)));
        if(hijriDate!=null){
            int shift=activity.getSharedPreferences("altaplay_hijri",Context.MODE_PRIVATE).getInt("day_adjust",0);
            hijriDate.setText(MoroccanHijri.display(day,shift)+" · تقديري");
        }''',
    "live Hijri date refresh")

start=s.index("    private void refreshPrayer(){")
end=s.index("    private void refreshAmbient(){",start)
if start<0 or end<0:raise RuntimeError("prayer refresh anchors missing")
s=s[:start]+'''    private void refreshPrayer(){
        java.time.ZonedDateTime now=java.time.ZonedDateTime.now(MOROCCO);
        prayerText.setText(NextPrayer.display(now,prayers.today(),
            prayers.forDate(now.toLocalDate().plusDays(1))));
    }
'''+s[end:]

# Replace channel card factory only: no change to tune(), remote, or Zen cache.
start=s.index("    private View channel(List<ZenClient.Channel> group,boolean radio){")
end=s.index("    private void tune(ZenClient.Channel item){",start)
if start<0 or end<0:raise RuntimeError("channel renderer anchors")
s=s[:start]+'''    static boolean focusTvRow(Activity a){return focusTaggedRow(a,"altaplay_tv_row");}
    static boolean focusRadioRow(Activity a){return focusTaggedRow(a,"altaplay_radio_row");}
    private static boolean focusTaggedRow(Activity a,String tag){
        FrameLayout root=a.findViewById(R.id.root);
        if(root==null)return false;
        View x=root.findViewWithTag(tag);
        if(!(x instanceof LinearLayout))return false;
        LinearLayout row=(LinearLayout)x;
        for(int i=0;i<row.getChildCount();i++){
            View card=row.getChildAt(i);
            if(card.isFocusable()&&card.getVisibility()==View.VISIBLE){
                return card.requestFocus();
            }
        }
        // Offline state still allows opening the receiver IP configuration.
        View connected=root.findViewById(R.id.grid);
        return connected!=null&&connected.hasFocus()?false:false;
    }
    static boolean focusApps(Activity a){
        RecyclerView grid=a.findViewById(R.id.grid);
        if(grid==null)return false;
        RecyclerView.ViewHolder first=grid.findViewHolderForAdapterPosition(0);
        if(first!=null){
            View focus=first.itemView.findViewById(R.id.card);
            if(focus!=null&&focus.requestFocus())return true;
        }
        return grid.requestFocus();
    }
    private View channel(List<ZenClient.Channel> group,boolean radio){
        ZenClient.Channel item=group.get(0);
        LinearLayout card=new LinearLayout(activity);
        card.setOrientation(radio?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(3),dp(2),dp(3),dp(2));
        card.setBackground(tileBackground(false));
        card.setFocusable(true);card.setClickable(true);
        card.setContentDescription(item.name+(group.size()>1?"، "+group.size()+" نسخ":""));
        if(!radio){
            FrameLayout logoBox=new FrameLayout(activity);
            TextView placeholder=title(item.name,13,0xFFDBF0E9);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setMaxLines(1);
            placeholder.setEllipsize(TextUtils.TruncateAt.END);
            logoBox.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
            ImageView img=new ImageView(activity);
            img.setTag(item.name);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setAdjustViewBounds(false);
            img.setPadding(dp(2),dp(1),dp(2),dp(1));
            logoBox.addView(img,new FrameLayout.LayoutParams(-1,-1));
            File logo=AltaPlayOnlineLogos.cached(activity,item.name);
            if(logo==null)logo=client.logo(item.id);
            if(logo!=null){
                android.graphics.Bitmap bitmap=BitmapFactory.decodeFile(logo.getAbsolutePath());
                if(bitmap!=null){
                    img.setImageBitmap(bitmap);
                    img.setContentDescription("شعار القناة: "+item.name);
                }
            }
            // Real external art updates asynchronously, persisted for offline use.
            AltaPlayOnlineLogos.fetchForVisible(activity,item.name,img);
            card.addView(logoBox,new LinearLayout.LayoutParams(dp(119),dp(46)));
        }
        String shown=item.name+(group.size()>1?" ×"+group.size():"");
        TextView name=title((radio?"♫  ":"")+shown,radio?11:12,GLASS_TEXT);
        name.setGravity(Gravity.CENTER);name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name,new LinearLayout.LayoutParams(radio?dp(143):-1,radio?dp(25):dp(18)));
        if(!radio){
            TextView hint=title(client.isOnline()?"مباشر":"محفوظة",10,0xB4D8ECDA);
            hint.setGravity(Gravity.CENTER);
            card.addView(hint,new LinearLayout.LayoutParams(-1,dp(11)));
        }
        card.setOnClickListener(v->{
            if(!client.isOnline()){
                Toast.makeText(activity,"Zen 4 غير متصل · القناة محفوظة فقط",Toast.LENGTH_SHORT).show();return;
            }
            if(group.size()==1){tune(group.get(0));return;}
            String[] names=new String[group.size()];
            for(int i=0;i<group.size();i++)names[i]=group.get(i).name+" · "+group.get(i).id;
            new AlertDialog.Builder(activity).setTitle("اختر نسخة "+item.name)
                .setItems(names,(dialog,which)->tune(group.get(which)))
                .setNegativeButton("إلغاء",null).show();
        });
        card.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_DOWN&&!radio)return focusRadioRow(activity);
            if(key==KeyEvent.KEYCODE_DPAD_UP)
                return radio?focusTvRow(activity):focusApps(activity);
            return false;
        });
        card.setOnFocusChangeListener((v,focused)->{
            // Never scale beyond the container: neighboring channels and logos
            // must remain INSIDE the glass panel and TV/radio rows.
            v.animate().cancel();
            v.setScaleX(1f);v.setScaleY(1f);
            v.setBackground(tileBackground(focused));
            if(focused && v.getParent() instanceof LinearLayout){
                LinearLayout parent=(LinearLayout)v.getParent();
                if(parent.getParent() instanceof HorizontalScrollView){
                    HorizontalScrollView horizontal=(HorizontalScrollView)parent.getParent();
                    horizontal.smoothScrollTo(Math.max(0,v.getLeft()-dp(13)),0);
                }
            }
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(radio?dp(156):dp(131),radio?dp(31):dp(82));
        lp.setMargins(dp(4),radio?dp(1):dp(2),dp(5),radio?dp(1):dp(2));
        card.setLayoutParams(lp);
        return card;
    }
'''+s[end:]
# Offline placeholder must still allow DPAD_DOWN to enter and configure Zen.
s=once(s,
    'text.setPadding(dp(12),dp(8),dp(12),dp(8));channelRow.addView(text);',
    '''text.setPadding(dp(12),dp(8),dp(12),dp(8));
            text.setFocusable(true);text.setOnClickListener(v->configure());
            text.setOnKeyListener((v,k,e)->e.getAction()==KeyEvent.ACTION_DOWN&&k==KeyEvent.KEYCODE_DPAD_UP&&focusApps(activity));
            channelRow.addView(text);''',
    "offline focus")
s=once(s,
    'none.setPadding(dp(9),0,0,0);radioRow.addView(none);',
    '''none.setPadding(dp(9),0,0,0);none.setFocusable(true);
            none.setOnKeyListener((v,k,e)->e.getAction()==KeyEvent.ACTION_DOWN&&k==KeyEvent.KEYCODE_DPAD_UP&&focusTvRow(activity));
            radioRow.addView(none);''',
    "radio no-data focus")

# Keep in-app long press configuration, permit explicit user mapping for
# receiver's firmware-specific Audio key (not guessed from generic STBs).
s=once(s,
    '.setNeutralButton("تعطيل",(d,w)->activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE)\n                .edit().putBoolean("hdmi_remote_enabled",false).apply())',
    '.setNeutralButton("رمز الصوتيات",(d,w)->configureAudioKey())',
    "audio mapping entrypoint")
s=once(s,
    '.putString("hdmi_package",s).putBoolean("hdmi_remote_enabled",true).apply();',
    '''.putString("hdmi_package",s).putBoolean("hdmi_remote_enabled",true).apply();
                new AlertDialog.Builder(activity)
                    .setTitle("السماح لتحكم Zen داخل HDMI")
                    .setMessage("لازم تفعّل AltaPlay • Zen 4 HDMI Remote من إعدادات إمكانية الوصول يدوياً. التحكم يخدم غير مع تطبيق HDMI اللي حددتي، وما كيتدخلش فباقي التطبيقات.")
                    .setPositiveButton("فتح إمكانية الوصول",(dialog,which)->{
                        try{activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
                        catch(Exception e){Toast.makeText(activity,"افتح إعدادات النظام ثم إمكانية الوصول",Toast.LENGTH_LONG).show();}
                    }).setNegativeButton("لاحقاً",null).show();''',
    "explicit system consent for HDMI accessibility")
s=once(s,
    '    private TextView title(String text,int size,int color){',
    '''    private void configureHijri(){
        final android.content.SharedPreferences p=activity.getSharedPreferences("altaplay_hijri",Context.MODE_PRIVATE);
        String[] labels={"ناقص يومين","ناقص يوم","بلا تعديل","زائد يوم","زائد يومين"};
        int current=Math.max(0,Math.min(4,p.getInt("day_adjust",0)+2));
        new AlertDialog.Builder(activity).setTitle("تصحيح التاريخ الهجري حسب رؤية الهلال في المغرب")
            .setSingleChoiceItems(labels,current,(dialog,which)->{
                p.edit().putInt("day_adjust",which-2).apply();
                dialog.dismiss();refreshMoroccanHeader();
            }).setNegativeButton("إلغاء",null).show();
    }
    private void configureAudioKey(){
        EditText edit=new EditText(activity);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);
        int old=activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE)
            .getInt("zen_audio_key",54);
        edit.setText(old<0?"":String.valueOf(old));
        edit.setHint("رمز Audio الحقيقي من مستقبل Zen");
        new AlertDialog.Builder(activity).setTitle("الزر الأزرق · المسارات الصوتية")
            .setMessage("رمز Audio الافتراضي 54 مستعمل في أجهزة G-MScreen أخرى، ومازال خاصو تجربة على Zen 4. تقدر تبدلو أو تعطلو.")
            .setView(edit).setPositiveButton("حفظ",(d,w)->{
                try{
                    int code=Integer.parseInt(edit.getText().toString().trim());
                    if(code<0 || code>255)throw new IllegalArgumentException();
                    activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE)
                        .edit().putInt("zen_audio_key",code).apply();
                }catch(Exception e){Toast.makeText(activity,"رمز غير صحيح",Toast.LENGTH_LONG).show();}
            }).setNeutralButton("تعطيل الصوتيات",(d,w)->
                activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE)
                    .edit().putInt("zen_audio_key",-1).apply())
            .setNegativeButton("إلغاء",null).show();
    }
    private TextView title(String text,int size,int color){''',
    "Hijri and audio settings")
panel.write_text(s,encoding="utf-8")

# Android system must offer accessibility opt-in in Settings.
s=manifest.read_text(encoding="utf-8")
s=once(s,
    '        <!-- Home button for remotes that send KEYCODE_ALL_APPS',
    '''        <!-- Explicitly opt-in HDMI forwarding. Android Settings must enable it;
             keys are routed only in a user-configured HDMI activity. -->
        <service
            android:name=".AltaPlayHdmiRemoteService"
            android:label="AltaPlay • Zen 4 HDMI Remote"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/altaplay_hdmi_accessibility" />
        </service>
        <!-- Home button for remotes that send KEYCODE_ALL_APPS''',
    "accessibility manifest")
s=once(s,
    '        <!-- The standard settings handlers,',
    '''        <package android:name="net.mbc.shahidTV" />
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="shahid" />
        </intent>
        <!-- The standard settings handlers,''',
    "Shahid package visibility")
manifest.write_text(s,encoding="utf-8")
xml=launcher/"app/src/main/res/xml/altaplay_hdmi_accessibility.xml"
xml.parent.mkdir(parents=True,exist_ok=True)
xml.write_text('''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="120"
    android:canRequestFilterKeyEvents="true"
    android:canRetrieveWindowContent="false"
    android:description="@string/app_name" />
''',encoding="utf-8")
for name in ["NextPrayer","MoroccanHijri","AltaPlayOnlineLogos","AltaPlayRemoteKeys","AltaPlayHdmiRemoteService"]:
    shutil.copy2(here/"java"/(name+".java"),java/(name+".java"))
print("PASS AltaPlay v0.8: channel DPAD focus, constrained logo cards/remote PNG cache, next published prayer, Hijri after Gregorian, Shahid green and opt-in HDMI/Zen controls")
