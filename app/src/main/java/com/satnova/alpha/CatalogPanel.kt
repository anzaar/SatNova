package com.satnova.alpha

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Bounded view trees; all channel/TP association work runs off the Android UI thread. */
class CatalogPanel(private val activity:Activity,private val tune:(String,Int)->Unit,
    private val refreshTp:()->Unit,measureSignal:()->Unit,private val openEpg:()->Unit,
    private val openChannelTab:()->Unit) {
    private val surface=Color.rgb(28,36,58)
    private val light=Color.rgb(247,249,255)
    private val muted=Color.rgb(159,174,199)
    private val accent=Color.rgb(183,171,255)
    private val Int.dp get()=(this*activity.resources.displayMetrics.density+0.5f).toInt()
    private fun txt(s:String,size:Float=14f,color:Int=light,bold:Boolean=false)=TextView(activity).apply{
        text=s;textSize=size;setTextColor(color);if(bold)setTypeface(null,Typeface.BOLD)
    }
    private fun col()=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    private fun lp(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,top.dp,0,bottom.dp)}
    private fun tile(s:String,action:()->Unit)=txt(s,15f,light,true).apply{
        setPadding(14.dp,14.dp,14.dp,14.dp)
        background=GradientDrawable().apply{setColor(surface);cornerRadius=15.dp.toFloat()}
        isClickable=true;isFocusable=true;setOnClickListener{action()}
    }
    val channelsPage=col()
    val satellitePage=col()
    private val channelList=col()
    private val satelliteList=col()
    private val tpList=col()
    private val channelDetail=col()
    private val tpDetail=col()
    private val count=txt("Chargement des chaînes…",12f,muted)
    private val satNotice=txt("En attente des satellites",13f,muted)
    private val tpNotice=txt("Choisissez un satellite",13f,muted)
    private val zapStatus=txt("",12f,accent)
    private val filter=EditText(activity)
    private val signal=SignalPanel(activity,measureSignal)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val version=AtomicLong()
    private val searchVersion=AtomicLong()
    @Volatile private var closed=false
    private var channels=emptyList<JSONObject>()
    private var satellites=emptyList<JSONObject>()
    private var tps=emptyList<JSONObject>()
    private var index=CatalogIndex(emptyList(),emptyList(),emptyList())
    private var activeTab=0
    private var complete=false
    private var filterMode=0
    private var channelPage=0
    private var satellitePageNumber=0
    private var tpPageNumber=0
    private var selectedSatellite:String?=null
    private var selectedTp:Pair<String,String>?=null
    private val pageSize=30
    private val rebuild=Runnable{
        val generation=version.get()
        val c=channels;val s=satellites;val t=tps
        worker.execute{
            if(version.get()!=generation || closed)return@execute
            val built=CatalogIndex(c,s,t)
            main.post{
                if(closed || version.get()!=generation)return@post
                index=built
                if(activeTab==1)renderChannels()
                if(activeTab==2){renderSatellites();renderTps()}
            }
        }
    }
    private fun invalidate(){
        version.incrementAndGet()
        main.removeCallbacks(rebuild)
        main.postDelayed(rebuild,120)
    }
    init {
        channelsPage.addView(txt("Chaînes TV & Radio",23f,light,true),lp(bottom=10))
        val modes=LinearLayout(activity)
        listOf("TV","Radio","Favoris").forEachIndexed{i,name->
            modes.addView(tile(name){filterMode=i;channelPage=0;renderChannels()},LinearLayout.LayoutParams(0,-2,1f))
        }
        channelsPage.addView(modes,lp(bottom=10))
        filter.hint="Rechercher une chaîne…";filter.setSingleLine(true)
        filter.setTextColor(light);filter.setHintTextColor(muted)
        channelsPage.addView(filter,lp(bottom=8));channelsPage.addView(count,lp(bottom=8))
        channelsPage.addView(channelDetail);channelsPage.addView(channelList)
        filter.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){channelPage=0;renderChannels()}
            override fun afterTextChanged(s:Editable?){}
        })
        satellitePage.addView(signal.root,lp(bottom=18))
        satellitePage.addView(txt("Satellites",23f,light,true),lp(bottom=8))
        satellitePage.addView(satNotice,lp(bottom=8));satellitePage.addView(satelliteList)
        satellitePage.addView(tpNotice,lp(top=16,bottom=8));satellitePage.addView(tpList)
        satellitePage.addView(tpDetail,lp(top=12))
    }
    fun showTab(tab:Int){
        activeTab=tab
        if(tab==1)renderChannels()
        if(tab==2){renderSatellites();renderTps()}
    }
    fun updateChannels(rows:List<JSONObject>,fullyLoaded:Boolean){channels=rows;complete=fullyLoaded;invalidate()}
    fun updateSatellites(rows:List<JSONObject>){satellites=rows;invalidate()}
    fun updateTps(rows:List<JSONObject>,received:Boolean=true){tps=rows;invalidate()}
    private fun <T> page(rows:List<T>,number:Int)=rows.drop(number*pageSize).take(pageSize)
    private fun pager(parent:LinearLayout,size:Int,number:Int,change:(Int)->Unit){
        val nav=LinearLayout(activity)
        if(number>0)nav.addView(tile("‹ Précédent"){change(number-1)},LinearLayout.LayoutParams(0,-2,1f))
        if((number+1)*pageSize<size)nav.addView(tile("Suivant ›"){change(number+1)},LinearLayout.LayoutParams(0,-2,1f))
        parent.addView(nav,lp(top=8,bottom=8))
    }
    private fun renderChannels(){
        if(activeTab!=1 || closed)return
        val generation=searchVersion.incrementAndGet()
        val data=index;val mode=filterMode;val term=filter.text.toString().trim().lowercase(Locale.ROOT)
        val pageNumber=channelPage
        worker.execute{
            val found=data.channelRows.filter{c->
                (when(mode){0->!c.radio;1->c.radio;else->c.favorite}) && (term.isBlank() || c.search.contains(term))
            }
            main.post{
                if(closed || activeTab!=1 || generation!=searchVersion.get())return@post
                channelList.removeAllViews()
                val number=pageNumber.coerceAtMost(((found.size-1)/pageSize).coerceAtLeast(0))
                channelPage=number
                count.text="${found.size} chaîne(s) • "+(if(complete)"liste chargée" else "lecture progressive…")
                page(found,number).forEach{c->channelList.addView(tile(c.name+"\n"+listOfNotNull(c.frequency,c.polarization).joinToString(" · ")){
                    showChannel(c)
                },lp(bottom=7))}
                pager(channelList,found.size,number){channelPage=it;renderChannels()}
                if(found.isEmpty())channelList.addView(txt("Aucune chaîne reçue ou correspondante.",13f,muted))
            }
        }
    }
    private fun showChannel(c:CatalogIndex.Channel){
        channelDetail.removeAllViews()
        channelDetail.addView(txt(c.name,22f,light,true),lp(top=10,bottom=8))
        channelDetail.addView(txt(listOfNotNull(c.frequency?.let{"$it MHz"},c.polarization,c.symbolRate?.let{"SR $it"}).joinToString(" · "),13f,muted))
        channelDetail.addView(txt("EPG : "+(c.epg?:"Non communiqué par le récepteur")),lp(top=8))
        c.next?.let{channelDetail.addView(txt("Ensuite : $it",13f,muted),lp(top=6))}
        channelDetail.addView(tile("▶ Zapper sur le récepteur"){tune(c.id,if(c.radio)1 else 0)},lp(top=12))
        channelDetail.addView(tile("EPG sur la TV"){openEpg()},lp(top=7))
        (zapStatus.parent as? ViewGroup)?.removeView(zapStatus)
        channelDetail.addView(zapStatus,lp(top=8,bottom=12))
    }
    private fun renderSatellites(){
        if(activeTab!=2)return
        val rows=index.satelliteRows
        satellitePageNumber=satellitePageNumber.coerceAtMost(((rows.size-1)/pageSize).coerceAtLeast(0))
        satNotice.text="${rows.size} satellites configurés • liste du récepteur"
        satelliteList.removeAllViews()
        page(rows,satellitePageNumber).forEach{s->
            val channelCount=index.channelsBySatellite[s.key]?.size?:0
            satelliteList.addView(tile("🛰 ${s.name}\n$channelCount chaîne(s) associée(s)"){
                selectedSatellite=s.key;selectedTp=null;tpPageNumber=0
                tpDetail.removeAllViews();renderTps()
                if(tps.isEmpty()){tpNotice.text="Lecture des transpondeurs…";refreshTp()}
            },lp(bottom=7))
        }
        pager(satelliteList,rows.size,satellitePageNumber){satellitePageNumber=it;renderSatellites()}
    }
    private fun renderTps(){
        if(activeTab!=2)return
        tpList.removeAllViews()
        val sat=selectedSatellite?:return
        val rows=index.tpsBySatellite[sat]?:emptyList()
        tpPageNumber=tpPageNumber.coerceAtMost(((rows.size-1)/pageSize).coerceAtLeast(0))
        tpNotice.text=if(rows.isEmpty())"Aucun transpondeur associé reçu" else "${rows.size} transpondeur(s)"
        page(rows,tpPageNumber).forEach{tp->
            val key=sat to tp.key
            val count=index.channelsByTp[key]?.size?:0
            tpList.addView(tile(tp.label+"\n$count chaîne(s) associée(s)"){
                selectedTp=key;tpDetail.removeAllViews()
                tpDetail.addView(txt(tp.label,18f,accent,true),lp(bottom=8))
                val matched=index.channelsByTp[key]?:emptyList()
                matched.take(pageSize).forEach{c->tpDetail.addView(tile(c.name){
                    openChannelTab();showChannel(c)
                },lp(bottom=6))}
                if(matched.size>pageSize)tpDetail.addView(txt("Utilisez Chaînes pour la liste complète",12f,muted))
            },lp(bottom=7))
        }
        pager(tpList,rows.size,tpPageNumber){tpPageNumber=it;renderTps()}
    }
    fun updateSignal(message:String)=signal.message(message)
    fun setSignalMonitoring(active:Boolean)=signal.setActive(active)
    fun updateTunerSignal(value:ReceiverTelemetry.Signal,at:Long)=signal.update(value,at)
    fun updateZapStatus(message:String){zapStatus.text=message}
    fun close(){closed=true;version.incrementAndGet();searchVersion.incrementAndGet();main.removeCallbacksAndMessages(null);worker.shutdownNow();signal.close()}
}
