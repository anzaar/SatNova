package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Actual connected/offline Zen 4 UI. No invented channels or EPG: titles and
 * logos come from receiver catalogue / device cache. Null data stays blank.
 * Header uses the exact Habous timetable and existing Open-Meteo client.
 */
final class AltaPlayZenPanel {
    private static final int PAGE_SIZE=24;
    private static final int RADIO_PAGE_SIZE=24;
    private static final int GLASS_TEXT=0xFFEBF8EF;
    private static final ZoneId MOROCCO=ZoneId.of("Africa/Casablanca");
    private final Activity activity;
    private final ZenClient client;
    private final ZenWeather weather;
    private final HabousPrayerSchedule prayers;
    private final FrameLayout root;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TextView connection,meta,prayerText,weatherText;
    private final LinearLayout channelRow,radioRow;
    private TextView zenPill;
    private View atmosphere;
    private int page,radioPage;
    private int renderedSignature=Integer.MIN_VALUE;
    private String renderedStatus="";

    static void attach(Activity a,Config cfg){
        FrameLayout root=a.findViewById(R.id.root);
        if(root==null || a.findViewById(R.id.grid)==null)return;
        // Do not replace an existing owner-chosen wallpaper or disable stock HOME.
        if(!cfg.wallpaperEnabled || cfg.wallpapers.isEmpty()){
            ImageView wallpaper=new ImageView(a);
            wallpaper.setImageResource(R.drawable.altaplay_islamic);
            wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
            wallpaper.setFocusable(false);
            wallpaper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(wallpaper,0,new FrameLayout.LayoutParams(-1,-1));
        }
        new AltaPlayZenPanel(a);
    }

    private AltaPlayZenPanel(Activity a){
        activity=a;root=a.findViewById(R.id.root);
        client=ZenClient.get(a);weather=new ZenWeather(a);prayers=new HabousPrayerSchedule(a);
        RecyclerView grid=a.findViewById(R.id.grid);
        ViewGroup wrapper=(ViewGroup)grid.getParent();
        if(!(wrapper.getParent() instanceof LinearLayout))throw new IllegalStateException("Launcher wrapper changed");
        LinearLayout shell=(LinearLayout)wrapper.getParent();
        LinearLayout panel=new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12),dp(7),dp(12),dp(6));
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xB8264940,0xB31C3936,0xB020493E});
        bg.setCornerRadius(dp(19));bg.setStroke(dp(1),0x77CAE9CC);
        panel.setBackground(bg);
        panel.setClipChildren(false);panel.setClipToPadding(false);

        LinearLayout header=new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        connection=title("قنوات Zen 4",15,GLASS_TEXT);
        connection.setFocusable(true);connection.setClickable(true);
        connection.setPadding(dp(6),dp(2),dp(12),dp(2));
        connection.setOnClickListener(v->configure());
        connection.setOnLongClickListener(v->{configureRemote();return true;});
        header.addView(connection,new LinearLayout.LayoutParams(0,dp(27),1));
        panel.addView(header);

        meta=title("قم بإعداد Zen 4 لتحميل القنوات",11,0xD9EAF6EF);
        meta.setSingleLine(true);meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(dp(7),0,dp(7),dp(2));
        panel.addView(meta,new LinearLayout.LayoutParams(-1,dp(20)));

        TextView tvLabel=title("القنوات التلفزية",12,0xFFE7F5EB);
        tvLabel.setPadding(dp(7),0,0,0);
        panel.addView(tvLabel,new LinearLayout.LayoutParams(-1,dp(20)));

        HorizontalScrollView scroll=new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);scroll.setFocusable(false);
        channelRow=new LinearLayout(a);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setClipChildren(false);
        channelRow.setClipToPadding(false);
        scroll.addView(channelRow,new HorizontalScrollView.LayoutParams(-2,-1));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(106)));

        TextView radioLabel=title("الإذاعات",11,0xE2F1F6EF);
        radioLabel.setPadding(dp(7),0,0,0);
        panel.addView(radioLabel,new LinearLayout.LayoutParams(-1,dp(17)));
        HorizontalScrollView radioScroll=new HorizontalScrollView(a);
        radioScroll.setHorizontalScrollBarEnabled(false);
        radioScroll.setFillViewport(true);radioScroll.setFocusable(false);
        radioRow=new LinearLayout(a);radioRow.setGravity(Gravity.CENTER_VERTICAL);
        radioRow.setClipChildren(false);
        radioScroll.addView(radioRow,new HorizontalScrollView.LayoutParams(-2,-1));
        panel.addView(radioScroll,new LinearLayout.LayoutParams(-1,dp(36)));

        int index=shell.indexOfChild(wrapper);
        shell.addView(panel,index+1,new LinearLayout.LayoutParams(-1,dp(219)));

        // These labels belong to the system header, NEVER inside the lower Zen panel.
        prayerText=title("",13,0xF5E7F5E8);
        prayerText.setSingleLine(true);
        prayerText.setEllipsize(TextUtils.TruncateAt.END);
        prayerText.setFocusable(true);prayerText.setOnClickListener(v->configureCity());
        weatherText=title("",15,0xFFE6FAF0);
        weatherText.setSingleLine(true);
        weatherText.setFocusable(true);weatherText.setOnClickListener(v->configureCity());

        // MainActivity initializes its stock network and Settings pills later
        // in onCreate, so replace the network pill only after that is finished.
        root.post(this::installSystemChrome);
        client.setListener(this::render);
        client.start();
        prayers.refresh(false,this::refreshPrayer);
        weather.refresh(false,this::refreshWeather);
        render();
        handler.postDelayed(this::refreshAmbient,30_000L);
    }

    static String greetingForHour(int hour){
        if(hour<5)return "ليلة سعيدة";
        if(hour<12)return "صباح الخير";
        if(hour<17)return "نهارك سعيد";
        if(hour<21)return "مساء الخير";
        return "ليلة سعيدة";
    }

    private void installSystemChrome(){
        if(activity.isFinishing()||activity.isDestroyed())return;
        TextView greeting=activity.findViewById(R.id.greeting);
        if(greeting!=null){
            greeting.setAllCaps(false);
            greeting.setTextSize(14);
            greeting.setLetterSpacing(0.04f);
            greeting.setText(greetingForHour(LocalTime.now(MOROCCO).getHour()));
        }
        TextClock date=activity.findViewById(R.id.date);
        if(date!=null){
            date.setTextLocale(new Locale("ar","MA"));
            date.setFormat12Hour("EEEE، d MMMM");
            date.setFormat24Hour("EEEE، d MMMM");
            date.setAllCaps(false);
            date.setLetterSpacing(0f);
            date.setTextSize(15);
            ViewGroup box=(ViewGroup)date.getParent();
            if(prayerText.getParent()==null){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(22));
                lp.topMargin=dp(3);
                box.addView(prayerText,box.indexOfChild(date)+1,lp);
            }
        }

        TextView originalWifi=activity.findViewById(R.id.btnWifi);
        if(originalWifi!=null&&originalWifi.getParent() instanceof LinearLayout){
            LinearLayout top=(LinearLayout)originalWifi.getParent();
            int pos=top.indexOfChild(originalWifi);
            // Remove from the visual hierarchy, but MainActivity retains the
            // original reference for its background network refresh callbacks.
            top.removeView(originalWifi);
            zenPill=title("◇ Zen 4 · غير متصل",15,Color.WHITE);
            zenPill.setGravity(Gravity.CENTER);
            zenPill.setPadding(dp(15),0,dp(15),0);
            zenPill.setFocusable(true);
            zenPill.setClickable(true);
            zenPill.setOnClickListener(v->configure());
            zenPill.setOnLongClickListener(v->{configureRemote();return true;});
            zenPill.setBackground(pill(false));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(43));
            lp.setMargins(dp(7),0,dp(7),0);
            top.addView(zenPill,Math.max(0,pos),lp);
        }
        TextView settings=activity.findViewById(R.id.btnSettings);
        if(settings!=null)settings.setText("الإعدادات");

        // Ambient weather sits at the top right, under the header pills.
        FrameLayout.LayoutParams wp=new FrameLayout.LayoutParams(-2,dp(35),Gravity.TOP|Gravity.RIGHT);
        wp.rightMargin=dp(61);wp.topMargin=dp(107);
        root.addView(weatherText,wp);

        // Static low-cost atmospheric tint follows actual Open-Meteo condition.
        atmosphere=new View(activity);
        atmosphere.setFocusable(false);atmosphere.setClickable(false);
        atmosphere.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(atmosphere,Math.min(1,root.getChildCount()),
            new FrameLayout.LayoutParams(-1,-1));

        refreshWeather();refreshPrayer();updateZenPill();
    }

    private GradientDrawable pill(boolean online){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            online?new int[]{0xA5217052,0xB6195449}:new int[]{0x9E2A4542,0xAD223F3F});
        g.setCornerRadius(dp(24));
        g.setStroke(dp(1),online?0xFF9CF3B8:0x8BD9E3D8);
        return g;
    }

    private void updateZenPill(){
        if(zenPill==null)return;
        boolean online=client.isOnline();
        String newText=online?"●  Zen 4 · متصل":"◇  Zen 4 · غير متصل";
        if(!newText.equals(zenPill.getText().toString())){
            zenPill.setText(newText);
            zenPill.setBackground(pill(online));
        }
    }
    private void refreshWeather(){
        String value=weather.text().replaceFirst("^الطقس\\s*·\\s*","");
        String icon=value.contains("ضباب")?"☰":value.contains("أمطار")?"☂":
            value.contains("ثلوج")?"❄":value.contains("عواصف")?"⚡":
            value.contains("صحو")?"☀":"☁";
        weatherText.setText(icon+"  "+value);
        if(atmosphere!=null){
            int tint=value.contains("ضباب")?0x174FC3BC:
                value.contains("أمطار")?0x193C7890:
                value.contains("صحو")?0x155DA47B:0x12409F99;
            GradientDrawable overlay=new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{tint,0x00000000,0x00000000});
            atmosphere.setBackground(overlay);
        }
    }

    private void refreshPrayer(){
        Map<String,LocalTime> today=prayers.today();
        LocalTime fajr=today.get("fajr");
        if(fajr==null){
            prayerText.setText(prayers.label());
            return;
        }
        LocalTime now=LocalTime.now(MOROCCO);
        String label="الفجر "+fajr+"  •  ";
        if(now.isBefore(fajr)){
            long minutes=Math.max(1,Duration.between(now,fajr).toMinutes());
            long h=minutes/60,m=minutes%60;
            label+="متبقٍ "+(h>0?h+" س ":"")+(m>0?m+" د":"");
        }else label+="موعد اليوم";
        prayerText.setText(label);
    }
    private void refreshAmbient(){
        if(activity.isFinishing()||activity.isDestroyed())return;
        refreshPrayer();
        weather.refresh(false,this::refreshWeather);
        prayers.refresh(false,this::refreshPrayer);
        if(zenPill!=null)zenPill.setText(client.isOnline()?"●  Zen 4 · متصل":"◇  Zen 4 · غير متصل");
        handler.postDelayed(this::refreshAmbient,60_000L);
    }

    private int signature(List<ZenClient.Channel> src){
        int hash=1;
        for(ZenClient.Channel item:src){
            hash=31*hash+item.id.hashCode();
            hash=31*hash+item.name.hashCode();
            hash=31*hash+item.tvState;
        }
        return hash;
    }
    private void render(){
        if(activity.isFinishing()||activity.isDestroyed())return;
        List<ZenClient.Channel> items=client.channels();
        String status=client.getStatus();
        boolean online=client.isOnline();
        updateZenPill();
        List<ZenClient.Channel> channels=new ArrayList<>(),radios=new ArrayList<>();
        for(ZenClient.Channel item:items){
            if(item.tvState==1)radios.add(item);
            else channels.add(item);
        }
        int groupCount=grouped(channels).size();
        connection.setText(online?"●  تم الاتصال بـ Zen 4 • القنوات جاهزة":"قنوات Zen 4 • "+status);
        String count=channels.size()+" قناة · "+radios.size()+" إذاعة";
        if(client.total()>items.size())count+=" • جاري تحميل القائمة ("+items.size()+"/"+client.total()+")";
        meta.setText(count+(online?" • اضغط على قناة للتحويل إلى HDMI":" • القنوات المحفوظة متاحة للتصفح"));
        int sign=signature(items);
        if(sign==renderedSignature&&status.equals(renderedStatus))return;
        renderedSignature=sign;renderedStatus=status;
        channelRow.removeAllViews();radioRow.removeAllViews();

        List<List<ZenClient.Channel>> channelGroups=grouped(channels);
        if(channelGroups.isEmpty()){
            TextView text=title(online?"جاري تحميل القنوات من Zen 4…":"القنوات غير متوفرة بعد · اتصل بـ Zen 4 لتحميلها",13,GLASS_TEXT);
            text.setPadding(dp(12),dp(8),dp(12),dp(8));channelRow.addView(text);
        }else{
            int maximum=(channelGroups.size()-1)/PAGE_SIZE;
            page=Math.min(page,maximum);
            if(page>0)channelRow.addView(navigation("◀",()->{page--;renderedSignature=Integer.MIN_VALUE;render();}));
            int start=page*PAGE_SIZE,end=Math.min(channelGroups.size(),start+PAGE_SIZE);
            for(int i=start;i<end;i++)channelRow.addView(channel(channelGroups.get(i),false));
            if(end<channelGroups.size())channelRow.addView(navigation("▶",()->{page++;renderedSignature=Integer.MIN_VALUE;render();}));
        }
        List<List<ZenClient.Channel>> radioGroups=grouped(radios);
        if(radioGroups.isEmpty()){
            TextView none=title("ما كايناش إذاعات محمّلة",11,0xBFE8F5EA);
            none.setPadding(dp(9),0,0,0);radioRow.addView(none);
        }else{
            int max=(radioGroups.size()-1)/RADIO_PAGE_SIZE;
            radioPage=Math.min(radioPage,max);
            if(radioPage>0)radioRow.addView(navigation("◀",()->{radioPage--;renderedSignature=Integer.MIN_VALUE;render();}));
            int start=radioPage*RADIO_PAGE_SIZE,end=Math.min(radioGroups.size(),start+RADIO_PAGE_SIZE);
            for(int i=start;i<end;i++)radioRow.addView(channel(radioGroups.get(i),true));
            if(end<radioGroups.size())radioRow.addView(navigation("▶",()->{radioPage++;renderedSignature=Integer.MIN_VALUE;render();}));
        }
    }

    private List<List<ZenClient.Channel>> grouped(List<ZenClient.Channel> list){
        Map<String,List<ZenClient.Channel>> map=new LinkedHashMap<>();
        for(ZenClient.Channel item:list){
            // Group only exact names with the common quality suffix; do not mix distinct stations.
            String key=item.name.trim().replaceAll("(?i)\\s+(HD|SD|FHD|UHD|4K)$","")
                .replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
            if(key.isEmpty())key=item.id;
            List<ZenClient.Channel> group=map.get(key);
            if(group==null){group=new ArrayList<>();map.put(key,group);}
            group.add(item);
        }
        return new ArrayList<>(map.values());
    }

    private View navigation(String label,Runnable action){
        TextView view=title(label,20,GLASS_TEXT);
        view.setGravity(Gravity.CENTER);view.setFocusable(true);
        view.setBackground(tileBackground(false));view.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(39),dp(72));
        lp.setMargins(dp(3),dp(3),dp(6),dp(3));view.setLayoutParams(lp);
        return view;
    }

    private View channel(List<ZenClient.Channel> group,boolean radio){
        ZenClient.Channel item=group.get(0);
        LinearLayout card=new LinearLayout(activity);
        card.setOrientation(radio?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(5),dp(4),dp(5),dp(3));
        card.setBackground(tileBackground(false));
        card.setFocusable(true);card.setClickable(true);
        card.setContentDescription(item.name+(group.size()>1?"، "+group.size()+" نسخ":""));
        if(!radio){
            File logo=client.logo(item.id);
            if(logo!=null){
                ImageView img=new ImageView(activity);
                img.setImageBitmap(BitmapFactory.decodeFile(logo.getAbsolutePath()));
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                card.addView(img,new LinearLayout.LayoutParams(dp(119),dp(53)));
            }else{
                TextView letter=title(item.name,16,0xFFF0F6ED);
                letter.setGravity(Gravity.CENTER);letter.setSingleLine(true);
                letter.setEllipsize(TextUtils.TruncateAt.END);
                letter.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x77265B55,0x78546B51,0x78265157}));
                card.addView(letter,new LinearLayout.LayoutParams(dp(119),dp(53)));
            }
        }
        String shown=item.name+(group.size()>1?" ×"+group.size():"");
        TextView name=title((radio?"♫  ":"")+shown,radio?11:12,GLASS_TEXT);
        name.setGravity(Gravity.CENTER);name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name,new LinearLayout.LayoutParams(radio?dp(143):-1,radio?dp(25):dp(24)));
        if(!radio){
            TextView hint=title(client.isOnline()?"مباشر · اضغط للمشاهدة":"قناة محفوظة",10,0xB4D8ECDA);
            hint.setGravity(Gravity.CENTER);
            card.addView(hint,new LinearLayout.LayoutParams(-1,dp(15)));
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
        card.setOnFocusChangeListener((v,focused)->{
            v.animate().cancel();
            v.animate().scaleX(focused?1.055f:1f).scaleY(focused?1.055f:1f)
                .setDuration(110).start();
            v.setBackground(tileBackground(focused));
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(radio?dp(156):dp(131),radio?dp(31):dp(103));
        lp.setMargins(dp(4),radio?dp(1):dp(2),dp(5),radio?dp(1):dp(2));
        card.setLayoutParams(lp);
        return card;
    }
    private void tune(ZenClient.Channel item){
        client.tune(item);
        // The native input picker is safer than assuming a vendor HDMI Activity.
        try{activity.startActivity(new Intent("com.android.tv.action.VIEW_INPUTS"));}
        catch(Exception e){Toast.makeText(activity,"اختر HDMI من زر INPUT في التلفاز",Toast.LENGTH_LONG).show();}
    }
    private GradientDrawable tileBackground(boolean focused){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            focused?new int[]{0xB0347667,0xC1225551}:new int[]{0xA3214C46,0xB4203F3D});
        g.setCornerRadius(dp(12));g.setStroke(dp(focused?2:1),focused?0xFFB5FFE0:0x77A7C8BE);
        return g;
    }
    private void configureCity(){
        List<HabousPrayerSchedule.City> choices=prayers.cities();
        if(choices.isEmpty()){
            Toast.makeText(activity,"لائحة المدن غير متوفرة",Toast.LENGTH_SHORT).show();return;
        }
        String[] names=new String[choices.size()];
        int current=0;
        for(int i=0;i<choices.size();i++){
            HabousPrayerSchedule.City c=choices.get(i);
            names[i]=c.ar+(c.fr.isEmpty()?"":" · "+c.fr);
            if(c.id==prayers.selected().id)current=i;
        }
        new AlertDialog.Builder(activity).setTitle("المدينة · مواقيت الأوقاف والطقس")
            .setSingleChoiceItems(names,current,(dialog,which)->{
                dialog.dismiss();
                prayers.select(choices.get(which));
                refreshPrayer();refreshWeather();
                prayers.refresh(true,this::refreshPrayer);
                weather.refresh(true,this::refreshWeather);
            }).setNegativeButton("إلغاء",null).show();
    }

    private void configure(){
        EditText ip=new EditText(activity);ip.setSingleLine(true);
        ip.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        ip.setText(client.host());ip.setHint("192.168.1.100");
        ip.setPadding(dp(18),dp(10),dp(18),dp(10));
        new AlertDialog.Builder(activity).setTitle("ZEN PRO 4 · عنوان IP المحلي")
            .setMessage("أدخل عنوان جهاز الاستقبال في نفس شبكة التلفاز. الضغط المطوّل يتيح تهيئة تحكم HDMI.")
            .setView(ip).setNegativeButton("إلغاء",null)
            .setPositiveButton("حفظ",(d,w)->{
                try{client.setHost(ip.getText().toString().trim());}
                catch(Exception e){Toast.makeText(activity,"عنوان IP محلي غير صالح",Toast.LENGTH_LONG).show();}
            }).show();
    }
    private void configureRemote(){
        EditText input=new EditText(activity);
        input.setSingleLine(true);
        input.setHint("com.vendor.hdmi.app");
        input.setText(activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE).getString("hdmi_package",""));
        input.setPadding(dp(18),dp(10),dp(18),dp(10));
        new AlertDialog.Builder(activity).setTitle("HDMI remote · Lifemaxx")
            .setMessage("أدخل اسم الحزمة الحقيقية لواجهة HDMI بعد تحديدها عبر ADB. بلا اسم صحيح، لا تُحوّل الأسهم.")
            .setView(input).setNegativeButton("إلغاء",null)
            .setNeutralButton("تعطيل",(d,w)->activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE)
                .edit().putBoolean("hdmi_remote_enabled",false).apply())
            .setPositiveButton("تفعيل",(d,w)->{
                String s=input.getText().toString().trim();
                if(!s.matches("[a-zA-Z][a-zA-Z0-9_.]{2,200}")){
                    Toast.makeText(activity,"اسم حزمة HDMI غير صالح",Toast.LENGTH_LONG).show();return;
                }
                activity.getSharedPreferences("altaplay_zen",Context.MODE_PRIVATE).edit()
                    .putString("hdmi_package",s).putBoolean("hdmi_remote_enabled",true).apply();
            }).show();
    }
    private TextView title(String text,int size,int color){
        TextView v=new TextView(activity);
        v.setText(text);v.setTextColor(color);v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);
        return v;
    }
    private int dp(float value){return (int)(value*activity.getResources().getDisplayMetrics().density+0.5f);}
}
