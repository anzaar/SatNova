package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Native, bounded channel picker embedded in the home controls. All indexing runs on a worker. */
class CatalogPanel(private val activity:Activity,private val tune:(String,Int)->Unit,
    private val refreshTp:()->Unit,measureSignal:()->Unit,private val openEpg:()->Unit,
    private val openChannelTab:()->Unit,private val reloadCatalogue:()->Unit={},private val openFinder:()->Unit={}) {
    private val surface=Color.rgb(28,36,58)
    private val light=Color.rgb(247,249,255)
    private val muted=Color.rgb(159,174,199)
    private val accent=Color.rgb(183,171,255)
    private val green=Color.rgb(83,226,177)
    private val Int.dp get()=(this*activity.resources.displayMetrics.density+.5f).toInt()
    private fun txt(s:String,size:Float=14f,color:Int=light,bold:Boolean=false)=TextView(activity).apply{
        text=s;textSize=size;setTextColor(color);includeFontPadding=false
        if(bold)setTypeface(null,Typeface.BOLD)
    }
    private fun col()=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    private fun lp(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,top.dp,0,bottom.dp)}
    private fun tile(s:String,action:()->Unit)=txt(s,14f,light,true).apply{
        setPadding(12.dp,13.dp,12.dp,13.dp)
        background=GradientDrawable().apply{setColor(surface);cornerRadius=14.dp.toFloat()}
        isClickable=true;isFocusable=true;setOnClickListener{action()}
    }
    val channelsPage=col()
    val satellitePage=col()
    private val channelList=col()
    private val satelliteList=col()
    private val count=txt("Lecture des chaînes…",11f,muted)
    private val satNotice=txt("Lecture des satellites…",12f,muted)
    private val zapStatus=txt("",12f,green)
    private val filter=EditText(activity)
    private val satChip=tile("Tous les satellites ▾"){chooseSatellite()}
    private val tpChip=tile("Toutes les fréquences ▾"){chooseTp()}
    private val signal=SignalPanel(activity,measureSignal,openFinder)
    val signalView:View get()=signal.root
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
    private var listVisible=false
    private var complete=false
    private var filterMode=0
    private var channelPage=0
    private var satellitePageNumber=0
    private var selectedSatellite:String?=null
    private var selectedTp:String?=null
    private var currentId=""
    private var pendingId=""
    private var focusCurrent=false
    private val pageSize=30
    private fun channelsVisible()=activeTab==0 && listVisible
    private val rebuild=Runnable{
        if(!closed){
            val generation=version.get()
            val c=channels;val s=satellites;val t=tps
            worker.execute{
                if(version.get()!=generation || closed)return@execute
                val built=CatalogIndex(c,s,t)
                main.post{
                    if(closed || version.get()!=generation)return@post
                    index=built
                    if(channelsVisible())renderChannels()
                    if(activeTab==2)renderSatellites()
                }
            }
        }
    }
    private fun invalidate(){
        if(closed)return
        version.incrementAndGet();main.removeCallbacks(rebuild);main.postDelayed(rebuild,180)
    }
    init {
        val chips=LinearLayout(activity)
        satChip.maxLines=1;tpChip.maxLines=1
        satChip.textSize=11f;tpChip.textSize=11f
        chips.addView(satChip,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=4.dp})
        chips.addView(tpChip,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=4.dp})
        channelsPage.addView(chips,lp(top=10,bottom=8))
        val search=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        filter.hint="Rechercher…";filter.setSingleLine(true);filter.textSize=14f
        filter.setTextColor(light);filter.setHintTextColor(muted)
        search.addView(filter,LinearLayout.LayoutParams(0,44.dp,1f))
        search.addView(tile("⋯"){
            val labels=arrayOf("Toutes les chaînes","TV","Radio","Favoris du récepteur","Actualiser depuis le récepteur")
            AlertDialog.Builder(activity).setTitle("Afficher").setSingleChoiceItems(labels,filterMode){dialog,which->
                if(which==4)reloadCatalogue()
                else {filterMode=which;channelPage=0;focusCurrent=false;renderChannels()}
                dialog.dismiss()
            }.show()
        })
        channelsPage.addView(search,lp(bottom=8))
        channelsPage.addView(count,lp(bottom=8))
        channelsPage.addView(zapStatus,lp(bottom=8));zapStatus.visibility=View.GONE
        channelsPage.addView(channelList)
        filter.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){channelPage=0;focusCurrent=false;renderChannels()}
            override fun afterTextChanged(s:Editable?){}
        })
        satellitePage.addView(txt("Satellites",23f,light,true),lp(bottom=8))
        satellitePage.addView(txt("Les plus fournis en premier",12f,accent),lp(bottom=8))
        satellitePage.addView(satNotice,lp(bottom=12));satellitePage.addView(satelliteList)
    }
    fun showTab(tab:Int){
        activeTab=tab
        if(channelsVisible())renderChannels()
        if(tab==2)renderSatellites()
    }
    fun setHomeListVisible(visible:Boolean){
        listVisible=visible;channelsPage.visibility=if(visible)View.VISIBLE else View.GONE
        if(visible){focusCurrent=true;renderChannels()}
        else searchVersion.incrementAndGet()
    }
    fun setCurrentChannel(id:String){
        if(currentId==id)return
        currentId=id
        if(pendingId==id){pendingId="";zapStatus.text="● Chaîne confirmée"}
        if(channelsVisible())renderChannels()
    }
    fun updateChannels(rows:List<JSONObject>,fullyLoaded:Boolean){channels=rows;complete=fullyLoaded;invalidate()}
    fun updateSatellites(rows:List<JSONObject>){satellites=rows;invalidate()}
    fun updateTps(rows:List<JSONObject>,received:Boolean=true){tps=rows;invalidate()}
    private fun <T> page(rows:List<T>,number:Int)=rows.drop(number*pageSize).take(pageSize)
    private fun pager(parent:LinearLayout,size:Int,number:Int,change:(Int)->Unit){
        val nav=LinearLayout(activity)
        if(number>0)nav.addView(tile("‹ Précédent"){change(number-1)},LinearLayout.LayoutParams(0,-2,1f))
        if((number+1)*pageSize<size)nav.addView(tile("Suivant ›"){change(number+1)},LinearLayout.LayoutParams(0,-2,1f))
        if(nav.childCount>0)parent.addView(nav,lp(top=8,bottom=8))
    }
    private fun updateChips(){
        satChip.text=(index.satelliteRows.firstOrNull{it.key==selectedSatellite}?.name?:"Tous les satellites")+" ▾"
        tpChip.text=(index.transponders.firstOrNull{it.key==selectedTp && it.satellite==selectedSatellite}?.label?:"Toutes les fréquences")+" ▾"
    }
    private fun chooseSatellite(after:(()->Unit)?=null){
        val rows=CatalogQuery.satellites(index)
        val labels=arrayOf("Tous les satellites")+rows.map{s->"${s.name}  ·  ${index.channelsBySatellite[s.key]?.size?:0}"}
        AlertDialog.Builder(activity).setTitle("Quel satellite ?").setItems(labels){_,which->
            selectedSatellite=if(which==0)null else rows[which-1].key
            selectedTp=null;channelPage=0;focusCurrent=false;updateChips();renderChannels();after?.invoke()
        }.setNegativeButton("Fermer",null).show()
    }
    private fun chooseTp(){
        val sat=selectedSatellite
        if(sat==null){chooseSatellite{if(selectedSatellite!=null)chooseTp()};return}
        val all=index.tpsBySatellite[sat]?:emptyList()
        val rows=all.filter{!index.channelsByTp[sat to it.key].isNullOrEmpty()}
        if(rows.isEmpty() && tps.isEmpty()){refreshTp()}
        val labels=arrayOf("Toutes les fréquences")+rows.map{it.label+"  ·  "+(index.channelsByTp[sat to it.key]?.size?:0)}
        AlertDialog.Builder(activity).setTitle("Quelle fréquence ?").setItems(labels){_,which->
            selectedTp=if(which==0)null else rows[which-1].key
            channelPage=0;focusCurrent=false;updateChips();renderChannels()
        }.setNegativeButton("Fermer",null).show()
    }
    private fun renderChannels(){
        if(!channelsVisible() || closed)return
        val generation=searchVersion.incrementAndGet()
        val data=index;val mode=filterMode;val term=filter.text.toString()
        val sat=selectedSatellite;val tp=selectedTp;val pageNumber=channelPage
        val focus=focusCurrent;val playing=currentId
        updateChips()
        worker.execute{
            val found=CatalogQuery.channels(data,mode,sat,tp,term)
            val playingIndex=if(focus)found.indexOfFirst{it.id==playing} else -1
            val desired=if(playingIndex>=0)playingIndex/pageSize else pageNumber
            val number=desired.coerceAtMost(((found.size-1)/pageSize).coerceAtLeast(0))
            main.post{
                if(closed || !channelsVisible() || generation!=searchVersion.get())return@post
                focusCurrent=false;channelPage=number;channelList.removeAllViews()
                val modeLabel=when(mode){1->"TV";2->"Radio";3->"Favoris";else->"Ordre du récepteur"}
                count.text="$modeLabel  •  ${found.size} chaîne(s)"+(if(!complete)"  ·  chargement…" else "")
                page(found,number).forEach{c->
                    val live=c.id==currentId
                    val label=c.position.toString().padStart(3,'0')+"   "+c.name+(if(live)"   ●" else "")
                    val row=tile(label){
                        if(c.id!=currentId){
                            pendingId=c.id;zapStatus.text="Changement vers ${c.name}…";zapStatus.visibility=View.VISIBLE
                            tune(c.id,if(c.radio)1 else 0)
                        }
                    }
                    if(live){row.setTextColor(green);row.background=GradientDrawable().apply{setColor(Color.rgb(36,48,64));cornerRadius=14.dp.toFloat();setStroke(1.dp,green)}}
                    row.contentDescription="Chaîne ${c.position}, ${c.name}"+(if(live)", en cours" else "")
                    channelList.addView(row,lp(bottom=6))
                }
                pager(channelList,found.size,number){channelPage=it;focusCurrent=false;renderChannels()}
                if(found.isEmpty())channelList.addView(txt(if(complete)"Aucune chaîne pour ce choix." else "Lecture des chaînes du récepteur…",13f,muted))
            }
        }
    }
    private fun renderSatellites(){
        if(activeTab!=2 || closed)return
        val rows=CatalogQuery.satellites(index)
        satellitePageNumber=satellitePageNumber.coerceAtMost(((rows.size-1)/pageSize).coerceAtLeast(0))
        satNotice.text="${rows.size} satellites  •  ${index.channelRows.size} chaînes chargées"+(if(!complete)"…" else "")
        satelliteList.removeAllViews()
        page(rows,satellitePageNumber).forEach{s->
            val n=index.channelsBySatellite[s.key]?.size?:0
            val detail=if(n>0)"$n chaînes  ›" else if(complete)"Aucune chaîne associée" else "Lecture des chaînes…"
            satelliteList.addView(tile("${s.name}\n$detail"){
                selectedSatellite=s.key;selectedTp=null;channelPage=0;focusCurrent=false
                openChannelTab()
            },lp(bottom=8))
        }
        pager(satelliteList,rows.size,satellitePageNumber){satellitePageNumber=it;renderSatellites()}
    }
    fun updateSignalContext(channel:String,sat:String,tp:String)=signal.setContext(channel,sat,tp)
    fun updateSignalDevice(name:String,host:String,serial:String)=signal.setDevice(name,host,serial)
    fun updateSignal(message:String)=signal.message(message)
    fun setSignalMonitoring(active:Boolean)=signal.setActive(active)
    fun updateTunerSignal(value:ReceiverTelemetry.Signal,at:Long)=signal.update(value,at)
    fun updateZapStatus(message:String){zapStatus.text=message;zapStatus.visibility=View.VISIBLE}
    fun close(){closed=true;version.incrementAndGet();searchVersion.incrementAndGet();main.removeCallbacksAndMessages(null);worker.shutdownNow();signal.close()}
}
