package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Receiver metadata and two independent remotes; never starts live video. */
internal class UnifiedRemotePanel(
    private val activity: Activity,
    private val tune: (String, Int) -> Unit,
    private val zenKey: (Int) -> Unit,
    private val tvKey: (Int) -> Unit,
    private val tvApp: (String) -> Unit,
    private val tvPairing: () -> Unit,
    private val reload: () -> Unit
) {
    private val panel = Color.rgb(21, 29, 52)
    private val tile = Color.rgb(33, 42, 70)
    private val violet = Color.rgb(164, 139, 255)
    private val green = Color.rgb(79, 224, 172)
    private val white = Color.rgb(249, 249, 255)
    private val muted = Color.rgb(163, 175, 199)
    private val worker = Executors.newSingleThreadExecutor()
    private val revision = AtomicLong()
    private val favorites = FavoriteProfile(activity)
    private val links = activity.getSharedPreferences("satnova_tv_shortcuts", Context.MODE_PRIVATE)
    private var channels = emptyList<RemoteChannel>()
    private var families = emptyMap<String,List<RemoteChannel>>()
    private var currentId = ""
    private var receiverHost = activity.getSharedPreferences("satnova",0)
        .getString("last_receiver_ip","unassigned") ?: "unassigned"
    private var radioMode = false
    private var activeGroupKey: String? = null
    private var selectedInGroupId: String? = null
    private val groupDeck=card().apply { visibility=View.GONE }
    private val groupTitle=text("مجموعة القنوات",14f,white,true)
    private val groupStatus=text("",12f,muted)
    private val groupButtons=vertical()
    private val favoriteHeading=text("★  مجموعاتي • TV",15f,white,true)
    private var receiverConnected = false
    private var tvConnected = false
    private var modeTv = false
    private var closed = false
    private val Int.dp get() = (this * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun background(color:Int,radius:Int=16) = GradientDrawable().apply {
        setColor(color);cornerRadius=radius.dp.toFloat();setStroke(1.dp,Color.rgb(55,64,105))
    }
    private fun text(value:String,size:Float=13f,color:Int=white,bold:Boolean=false) =
        TextView(activity).apply { text=value;textSize=size;setTextColor(color)
            includeFontPadding=false;if(bold)setTypeface(Typeface.DEFAULT,Typeface.BOLD) }
    private fun vertical()=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    private fun horizontal()=LinearLayout(activity).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
    }
    private fun margins(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{
        setMargins(0,top.dp,0,bottom.dp)
    }
    private fun weight(parent:LinearLayout,v:View){
        parent.addView(v,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(2.dp,0,2.dp,0)})
    }
    private fun card()=vertical().apply{
        background=background(panel,22);setPadding(13.dp,14.dp,13.dp,14.dp)
    }
    private fun tap(label:String,click:()->Unit)=text(label,12f,white,true).apply{
        gravity=Gravity.CENTER;minHeight=48.dp
        setPadding(6.dp,10.dp,6.dp,10.dp);background=background(tile)
        isClickable=true;isFocusable=true;contentDescription=label;setOnClickListener{click()}
    }

    val root=vertical()
    private val zenStatus=text("○ Zen 4",12f,muted,true)
    private val tvStatus=text("○ Lifemaxx",12f,muted,true)
    private val now=text("القناة الحالية: في انتظار Zen 4",20f,white,true)
    private val metadata=text("رقم القناة • القمر • التردد",12f,muted)
    private val epg=text("EPG: غير متوفر حالياً",12f,white)
    private val signal=text("السينيال: في انتظار القراءة",12f,green,true)
    private val previous=tap("‹ السابقة"){adjacent(-1)}
    private val next=tap("التالية ›"){adjacent(1)}
    private val favGrid=vertical()
    private val modeTitle=text("● Télécommande Zen 4",13f,violet,true)
    private val commands=vertical()
    private val modeZen=tap("Zen 4"){selectMode(false)}
    private val modeLifemaxx=tap("Lifemaxx"){selectMode(true)}

    init {
        favorites.useReceiver(receiverHost, radioMode)
        val devices=horizontal()
        weight(devices,zenStatus);weight(devices,tvStatus)
        root.addView(devices,margins(bottom=11))

        val current=card()
        current.addView(text("●  ZEN 4 • EN COURS",11f,green,true))
        current.addView(now,margins(top=9,bottom=5))
        current.addView(metadata,margins(bottom=10))
        current.addView(epg,margins(bottom=8))
        current.addView(signal,margins(bottom=13))
        val neighbors=horizontal()
        weight(neighbors,previous);weight(neighbors,next)
        current.addView(neighbors)
        root.addView(current,margins(bottom=12))

        val shortcuts=card()
        shortcuts.addView(text("APPLICATIONS TV • ACCÈS RAPIDE",11f,violet,true),margins(bottom=9))
        val apps=horizontal()
        for(pair in listOf("youtube" to "▶ YouTube","altaplay" to "▷ AltaPlay","shahid" to "▶ Shahid")){
            val key=pair.first
            val view=tap(pair.second){launchShortcut(key)}
            view.setOnLongClickListener{editShortcut(key);true}
            view.background=background(when(key){"youtube"->Color.rgb(145,29,57)
                "altaplay"->Color.rgb(94,48,160);else->Color.rgb(16,108,106)})
            weight(apps,view)
        }
        shortcuts.addView(apps)
        shortcuts.addView(text("ضغط مطول لتعديل رابط التطبيق",10f,muted),margins(top=8))
        root.addView(shortcuts,margins(bottom=12))

        val favoriteCard=card()
        val heading=horizontal()
        weight(heading,favoriteHeading)
        heading.addView(tap("تعديل"){manage()})
        favoriteCard.addView(heading,margins(bottom=9))
        val filter=horizontal()
        weight(filter,tap("📺  TV"){chooseMediaMode(false)})
        weight(filter,tap("♫  RADIO"){chooseMediaMode(true)})
        favoriteCard.addView(filter,margins(bottom=9))
        favoriteCard.addView(favGrid)
        val search=horizontal()
        weight(search,tap("⌕ بحث"){searchChannels()})
        weight(search,tap("☷ جميع القنوات"){searchChannels()})
        favoriteCard.addView(search,margins(top=9))
        root.addView(favoriteCard,margins(bottom=12))
        groupDeck.addView(groupTitle,margins(bottom=8))
        groupDeck.addView(groupStatus,margins(bottom=8))
        groupDeck.addView(groupButtons)
        root.addView(groupDeck,margins(bottom=12))

        val remote=card()
        val modes=horizontal()
        weight(modes,modeZen);weight(modes,modeLifemaxx)
        remote.addView(modes)
        remote.addView(modeTitle,margins(top=12,bottom=11))
        remote.addView(commands)
        root.addView(remote,margins(bottom=12))
        renderFavorites();selectMode(false)
    }

    fun useReceiver(host:String){
        receiverHost=host
        favorites.useReceiver(receiverHost,radioMode)
        favorites.sync(channels)
        activeGroupKey=null
        groupDeck.visibility=View.GONE
        renderFavorites()
    }

    private fun visibleChannels()=channels.filter{(it.tvState==1)==radioMode}

    private fun groupChannels(slot:FavoriteSlot):List<RemoteChannel>{
        if(slot.key.startsWith("custom:")){
            val ids=favorites.members(slot.key)
            return visibleChannels().filter{it.id in ids}
        }
        return families[slot.key].orEmpty().filter{(it.tvState==1)==radioMode}
    }

    private fun chooseMediaMode(radio:Boolean){
        if(radioMode==radio)return
        radioMode=radio
        favorites.useReceiver(receiverHost,radioMode)
        favorites.sync(channels)
        activeGroupKey=null;selectedInGroupId=null
        groupDeck.visibility=View.GONE
        favoriteHeading.text=if(radioMode)"★  مجموعاتي • RADIO" else "★  مجموعاتي • TV"
        renderFavorites()
        neighbors()
    }
    fun receiverReady(value:Boolean){
        receiverConnected=value
        zenStatus.text=if(value)"● Zen 4 • connecté" else "○ Zen 4 • hors ligne"
        zenStatus.setTextColor(if(value)green else muted)
        if(!value){now.text="Zen 4 hors ligne";signal.text="Signal : indisponible";currentId=""}
    }
    fun tvReady(value:Boolean){
        tvConnected=value
        tvStatus.text=if(value)"● Lifemaxx • connectée" else "○ Lifemaxx • hors ligne"
        tvStatus.setTextColor(if(value)green else muted)
    }
    fun current(id:String,name:String,number:String,satellite:String,tp:String,programme:String?,upNext:String?){
        currentId=id;now.text=name
        metadata.text=listOf(number,satellite,tp).filter{it.isNotBlank()}.joinToString(" • ")
        epg.text=if(programme.isNullOrBlank())"EPG : non communiqué" else "En cours : "+programme
        if(!upNext.isNullOrBlank())epg.text=epg.text.toString()+"\nEnsuite : "+upNext
        if(id==selectedInGroupId)groupStatus.text="● Zen 4 أكد تغيير القناة"
        neighbors()
        renderGroupDeck()
    }
    fun strength(value:ReceiverTelemetry.Signal){
        val parts=listOfNotNull(value.strength?.let{"Niveau ${it}%"},
            value.quality?.let{"Qualité ${it}%"},
            value.ber?.takeIf{it.isNotBlank()}?.let{"BER "+it})
        signal.text=if(parts.isEmpty())"Signal : non communiqué" else parts.joinToString(" • ")
    }
    fun channels(rows:List<JSONObject>,completed:Boolean){
        val ticket=revision.incrementAndGet()
        val snapshot=rows.map{it.toString()}
        worker.execute{
            val result=snapshot.mapIndexedNotNull { idx,raw ->
                val r=try{JSONObject(raw)}catch(_:Exception){return@mapIndexedNotNull null}
                val id=CatalogIndex.value(r,"ServiceID","ProgramId","ProgramID")?:return@mapIndexedNotNull null
                val name=CatalogIndex.value(r,"ServiceName","Name","ChannelName","ProgramName")?:"Service $id"
                val number=CatalogIndex.value(r,"ChannelNumber","ChannelNo","ChNum","ProgramNumber")
                    ?.toIntOrNull() ?: CatalogIndex.value(r,"ChannelIndex","Index")?.toIntOrNull()?.plus(1) ?: idx+1
                val fav=CatalogIndex.value(r,"Fav","Favor","FavFlag","Favorite","Favourite","IsFavorite")
                RemoteChannel(id,name,number,
                    if(CatalogIndex.value(r,"Radio","TvState")=="1")1 else 0,
                    fav=="1"||fav.equals("true",true),
                    CatalogIndex.value(r,"SatelliteName","SatName","Satellite"),
                    CatalogIndex.value(r,"Frequency","Freq","TPFreq"))
            }.sortedWith(compareBy<RemoteChannel>{it.position}.thenBy{it.name})
            val grouped=ChannelGrouping.byGroup(result)
            activity.runOnUiThread{
                if(closed||ticket!=revision.get())return@runOnUiThread
                channels=result;families=grouped
                favorites.sync(result);renderFavorites();neighbors()
            }
        }
    }
    private fun neighbors(){
        val rows=if(channels.any{it.id==currentId}){
            val playing=channels.first{it.id==currentId}
            channels.filter{it.tvState==playing.tvState}
        }else visibleChannels()
        val index=rows.indexOfFirst{it.id==currentId}
        previous.text=rows.getOrNull(index-1).takeIf{index>0}?.let{"‹ "+it.name}?:"‹ السابقة"
        next.text=rows.getOrNull(index+1)?.let{it.name+" ›"}?:"التالية ›"
    }
    private fun adjacent(delta:Int){
        if(!receiverConnected)return
        val playing=channels.firstOrNull{it.id==currentId}
        val rows=channels.filter{it.tvState==(playing?.tvState ?: if(radioMode)1 else 0)}
        val at=rows.indexOfFirst{it.id==currentId}
        val channel=if(at>=0)rows.getOrNull(at+delta) else null
        if(channel!=null)tune(channel.id,channel.tvState)
        else zenKey(if(delta>0)69 else 70)
    }
    private fun renderFavorites(){
        favGrid.removeAllViews()
        val list=favorites.list()
        for(y in 0..1){
            val row=horizontal()
            for(x in 0..4){
                val slot=list.getOrNull(y*5+x)
                val names=if(slot==null)emptyList() else groupChannels(slot)
                val title=slot?.label?.takeIf{it.isNotBlank()}?:
                    if(slot==null)"＋" else ChannelGrouping.display(slot.key,names.firstOrNull()?.name?:slot.key)
                val count=if(names.isNotEmpty())"\n"+ChannelGrouping.variants(names).size+" قنوات" else ""
                val view=tap(title.take(13)+count){
                    if(slot==null)addGroup() else openGroup(slot)
                }
                view.textSize=if(slot==null)19f else 11f
                view.minHeight=67.dp
                if(slot?.key=="family:mbc")view.background=background(Color.rgb(75,41,123))
                if(slot!=null)view.setOnLongClickListener{options(slot);true}
                weight(row,view)
            }
            favGrid.addView(row,margins(bottom=5))
        }
    }
    private fun openGroup(slot:FavoriteSlot){
        val grouped=groupChannels(slot)
        activeGroupKey=slot.key
        selectedInGroupId=if(grouped.any{it.id==currentId})currentId else selectedInGroupId
        renderGroupDeck()
        if(grouped.isEmpty()){message("مجموعة بلا قنوات فالقائمة الحالية");return}
        val channelNames=ChannelGrouping.variants(grouped)
        if(channelNames.size==1)versions(slot,channelNames[0].first,channelNames[0].second)
        else AlertDialog.Builder(activity)
            .setTitle(slot.label.ifBlank{ChannelGrouping.display(slot.key,grouped[0].name)})
            .setItems(channelNames.map{it.first+"  ("+it.second.size+" نسخ)"}.toTypedArray()){_,index->
                versions(slot,channelNames[index].first,channelNames[index].second)
            }.setNegativeButton("إغلاق",null).show()
    }
    private fun versions(slot:FavoriteSlot,name:String,all:List<RemoteChannel>){
        if(all.size==1){changeChannel(all[0]);return}
        val ordered=all.sortedWith(compareByDescending<RemoteChannel>{it.id==slot.preferredId}.thenBy{it.position})
        AlertDialog.Builder(activity).setTitle(name+" • HD / SD / التردد")
            .setItems(ordered.map{it.name+" • "+it.position+" • "+(it.satellite?:"")}.toTypedArray()){_,idx->
                changeChannel(ordered[idx])
            }.setNeutralButton("نسخة افتراضية"){_,_->
                AlertDialog.Builder(activity).setTitle("اختر النسخة الافتراضية")
                    .setItems(ordered.map{it.name+" • "+it.position}.toTypedArray()){_,idx->
                        favorites.choosePreferred(slot.key,ordered[idx].id)
                    }.show()
            }.setNegativeButton("إغلاق",null).show()
    }
    private fun changeChannel(channel:RemoteChannel){
        if(receiverConnected){
            selectedInGroupId=channel.id
            groupStatus.text="◌ طلب تغيير القناة مرسل • في انتظار تأكيد Zen 4"
            tune(channel.id,channel.tvState)
            renderGroupDeck()
        }else message("Zen 4 غير متصل، ما نقدرش نبدل القناة")
    }
    private fun message(value:String){AlertDialog.Builder(activity).setMessage(value)
        .setPositiveButton("OK",null).show()}
    private fun options(slot:FavoriteSlot){
        val actions=arrayOf("فتح المجموعة","تغيير الاسم","تحريك خطوة لليسار","تحريك خطوة لليمين",
            "حذف من المفضلات")
        AlertDialog.Builder(activity).setTitle(slot.label.ifBlank{slot.key})
            .setItems(actions){_,idx->when(idx){
                0->openGroup(slot)
                1->{
                    val input=EditText(activity).apply{setSingleLine(true);setText(slot.label.ifBlank{
                        ChannelGrouping.display(slot.key,families[slot.key]?.firstOrNull()?.name?:slot.key)})}
                    AlertDialog.Builder(activity).setTitle("اسم الزر")
                        .setView(input).setPositiveButton("حفظ"){_,_->
                            favorites.rename(slot.key,input.text.toString());renderFavorites()
                        }.setNegativeButton("إلغاء",null).show()
                }
                2->{favorites.move(slot.key,-1);renderFavorites()}
                3->{favorites.move(slot.key,1);renderFavorites()}
                4->{favorites.remove(slot.key);renderFavorites()}
            }}.show()
    }
    private fun manage(){
        val slots=favorites.list()
        val names=slots.mapIndexed{idx,slot->
            (idx+1).toString()+". "+slot.label.ifBlank{ChannelGrouping.display(slot.key,
                families[slot.key]?.firstOrNull()?.name?:slot.key)}
        }+listOf("＋ إضافة مجموعة")
        AlertDialog.Builder(activity).setTitle("إدارة 10 مفضلات")
            .setItems(names.toTypedArray()){_,idx->
                if(idx==slots.size)addGroup() else options(slots[idx])
            }.show()
    }
    private fun addGroup(){
        val list=families.entries.filter{f->favorites.list().none{it.key==f.key}}
            .sortedBy{it.value.minOfOrNull{c->c.position}?:Int.MAX_VALUE}.take(400)
        AlertDialog.Builder(activity).setTitle("إضافة مجموعة")
            .setItems(list.map{ChannelGrouping.display(it.key,it.value.first().name)+
                " • "+it.value.size}.toTypedArray()){_,idx->
                if(!favorites.add(list[idx].key))message("10 مجموعات كحد أقصى")
                renderFavorites()
            }.setNegativeButton("إلغاء",null).show()
    }
    private fun searchChannels(){
        val body=vertical().apply{setPadding(10.dp,8.dp,10.dp,8.dp)}
        val entry=EditText(activity).apply{hint="ابحث عن قناة";setSingleLine(true)}
        val list=vertical()
        val scroll=ScrollView(activity).apply{addView(list)}
        body.addView(entry,margins(bottom=9))
        body.addView(scroll,LinearLayout.LayoutParams(-1,340.dp))
        val dialog=AlertDialog.Builder(activity).setTitle("جميع قنوات Zen 4")
            .setView(body).setNeutralButton("تحديث"){_,_->reload()}
            .setNegativeButton("إغلاق",null).create()
        fun redraw(query:String){
            list.removeAllViews()
            val matched=channels.filter{it.name.contains(query,true)}.take(45)
            list.addView(text("يعرض "+matched.size+" قناة • تابع البحث لبقية القائمة",11f,muted))
            matched.forEach{ch->
                list.addView(tap(ch.position.toString()+" • "+ch.name){
                    dialog.dismiss()
                    AlertDialog.Builder(activity).setTitle(ch.name)
                        .setItems(arrayOf("تبديل القناة","إضافة المجموعة للمفضلات")){_,index->
                            if(index==0)changeChannel(ch)
                            else{
                                if(!favorites.add(ChannelGrouping.key(ch.name)))message("المجموعة موجودة أو المفضلات كاملة")
                                renderFavorites()
                            }
                        }.show()
                },margins(top=3))
            }
        }
        entry.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){redraw(s?.toString()?:"")}
            override fun afterTextChanged(s:Editable?){}
        })
        dialog.show();redraw("")
    }

    private fun defaultLink(key:String)=when(key){
        "youtube"->"vnd.youtube://"
        "shahid"->"https://play.google.com/apps/launch?id=net.mbc.shahidTV"
        else->""
    }
    private fun editShortcut(key:String){
        val input=EditText(activity).apply{setSingleLine(true)
            hint="Deep-link / Android TV app link"
            setText(links.getString(key,defaultLink(key)))}
        AlertDialog.Builder(activity).setTitle("فتح "+key+" على Lifemaxx")
            .setMessage("البروتوكول يعتمد على رابط تتعرف عليه TV. التطبيق لازم يكون مثبتاً ويقبل الرابط.")
            .setView(input).setPositiveButton("حفظ"){_,_->
                links.edit().putString(key,input.text.toString().trim()).apply()
            }.setNegativeButton("إلغاء",null).show()
    }
    private fun launchShortcut(key:String){
        val link=links.getString(key,defaultLink(key)).orEmpty()
        if(key=="altaplay" && link.isBlank()){
            AlertDialog.Builder(activity).setTitle("AltaPlay")
                .setMessage("رابط 4645817 كيساعد على التثبيت، ماشي أمر فتح التطبيق. باش تخدم النقرة المباشرة خاصنا رابط Deep Link أو مُعرّف إطلاق التطبيق اللي يقبلو Lifemaxx.")
                .setPositiveButton("رابط التثبيت"){_,_->
                    if(tvConnected)tvApp("https://aftv.news/4645817")
                    else tvPairing()
                }
                .setNeutralButton("إعداد الفتح المباشر"){_,_->editShortcut(key)}
                .setNegativeButton("إلغاء",null).show()
            return
        }
        if(link.isBlank()){editShortcut(key);return}
        if(!tvConnected){
            AlertDialog.Builder(activity).setMessage("اربط Lifemaxx قبل فتح التطبيقات")
                .setPositiveButton("ربط التلفزة"){_,_->tvPairing()}
                .setNegativeButton("إلغاء",null).show()
        }else tvApp(link)
    }
    private fun selectMode(tv:Boolean){
        modeTv=tv;modeTitle.text=if(tv)"● Télécommande Lifemaxx Android TV" else "● Télécommande Zen 4"
        modeZen.background=background(if(tv)tile else Color.rgb(104,67,176))
        modeLifemaxx.background=background(if(tv)Color.rgb(104,67,176) else tile)
        commands.removeAllViews()
        fun remote(label:String,tvCode:Int,zenCode:Int)=tap(label){
            if(modeTv)tvKey(tvCode) else zenKey(zenCode)
        }
        fun line(vararg values:Triple<String,Int,Int>){
            val r=horizontal()
            values.forEach{(label,t,z)->weight(r,remote(label,t,z))}
            commands.addView(r,margins(bottom=6))
        }
        line(Triple("TV HOME",KeyEvent.KEYCODE_HOME,7),
             Triple("VOL −",KeyEvent.KEYCODE_VOLUME_DOWN,36),
             Triple("MUTE",KeyEvent.KEYCODE_VOLUME_MUTE,23),
             Triple("VOL +",KeyEvent.KEYCODE_VOLUME_UP,35))
        line(Triple("▲",KeyEvent.KEYCODE_DPAD_UP,1))
        line(Triple("◀",KeyEvent.KEYCODE_DPAD_LEFT,3),
             Triple("OK",KeyEvent.KEYCODE_DPAD_CENTER,5),
             Triple("▶",KeyEvent.KEYCODE_DPAD_RIGHT,4))
        line(Triple("▼",KeyEvent.KEYCODE_DPAD_DOWN,2))
        line(Triple("↶ BACK",KeyEvent.KEYCODE_BACK,7),
             Triple("MENU",KeyEvent.KEYCODE_MENU,6),
             Triple("INPUT",KeyEvent.KEYCODE_TV_INPUT,22))
        line(Triple("CH −",KeyEvent.KEYCODE_CHANNEL_DOWN,70),
             Triple("EPG",KeyEvent.KEYCODE_GUIDE,32),
             Triple("CH +",KeyEvent.KEYCODE_CHANNEL_UP,69))
        commands.addView(tap("⌂  Android Home • Lifemaxx"){tvKey(KeyEvent.KEYCODE_HOME)},margins(top=6))
        commands.addView(tap("123  •  Clavier numérique"){
            val parent=vertical()
            for(numbers in listOf(listOf(1,2,3),listOf(4,5,6),listOf(7,8,9),listOf(0))){
                val r=horizontal()
                numbers.forEach{n->weight(r,remote(n.toString(),KeyEvent.KEYCODE_0+n,
                    if(n==0)12 else n+12))}
                parent.addView(r)
            }
            AlertDialog.Builder(activity).setTitle("Clavier numérique").setView(parent)
                .setPositiveButton("إغلاق",null).show()
        },margins(top=6))
        commands.addView(tap("▣  Associer / configurer Lifemaxx"){tvPairing()},margins(top=7))
    }
    fun close(){closed=true;worker.shutdownNow()}
}
