package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** All catalogue records are read from the receiver; never fabricate satellite associations. */
class CatalogPanel(
    private val activity:Activity,
    private val tune:(String,Int)->Unit,
    private val refreshTp:()->Unit,
    private val measureSignal:()->Unit,
    private val openEpg:()->Unit,
    private val openChannelTab:()->Unit
) {
    private val surface=Color.rgb(28,36,58)
    private val light=Color.rgb(247,249,255)
    private val muted=Color.rgb(159,174,199)
    private val accent=Color.rgb(183,171,255)
    private var filterMode=0
    private val modeButtons=mutableListOf<TextView>()
    private val green=Color.rgb(88,220,167)
    private val Int.dp get()=(this*activity.resources.displayMetrics.density+0.5f).toInt()
    private fun bg(color:Int=surface)=GradientDrawable().apply{setColor(color);cornerRadius=15.dp.toFloat()}
    private fun txt(value:String,size:Float=14f,color:Int=light,bold:Boolean=false)=TextView(activity).apply{
        text=value; textSize=size;setTextColor(color);includeFontPadding=false
        if(bold)setTypeface(null,Typeface.BOLD)
    }
    private fun col()=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    private fun lp(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,top.dp,0,bottom.dp)}
    private fun tile(text:String,action:()->Unit)=txt(text,15f,light,true).apply{
        setPadding(14.dp,15.dp,14.dp,15.dp)
        background=bg();isClickable=true;isFocusable=true;setOnClickListener{action()}
    }
    private fun get(r:JSONObject,vararg keys:String):String? {
        for(k in keys) {
            val v=r.optString(k,"").trim()
            if(v.isNotEmpty()&&v!="null")return v
        }
        return null
    }
    private fun id(r:JSONObject)=get(r,"ServiceID","ProgramId","ProgramID")
    private fun cname(r:JSONObject):String =
        get(r,"ServiceName","Name","ChannelName","ProgramName") ?: ("Chaîne "+(id(r)?:"?"))
    private fun isRadio(r:JSONObject)=get(r,"Radio","TvState")=="1"
    private fun favorite(r:JSONObject):Boolean {
        val v=get(r,"Fav","Favor","FavFlag","Favorite","FavoriteFlag","Favourite","IsFavourite","IsFavorite")
            ?: return false
        return v=="1" || v.equals("true",true)
    }
    private fun orderedChannels()=channels
    private fun freq(r:JSONObject)=get(r,"Frequency","Freq","TPFreq","FrequencyMHz","TPFrequency")
    private fun pol(r:JSONObject):String? {
        val v=get(r,"Polarization","Pol","Polar","Polarity","POL")?:return null
        return when(v.uppercase(Locale.ROOT)){"0"->"H";"1"->"V";else->v}
    }
    private fun sr(r:JSONObject)=get(r,"SymbolRate","SR","Symbol","SymRate")
    private fun satId(r:JSONObject)=get(r,"SatelliteId","SatelliteID","SatID","SatId","SatIndex","SatelliteIndex","SatelliteNo","SatelliteNumber")
    private fun satName(r:JSONObject)=get(r,"SatelliteName","SatName","Satellite","Sat","SatNameValue")
    private fun satLabel(r:JSONObject)=get(r,"SatelliteName","SatName","Name","Satellite","SatNameValue")?:"Satellite"
    private fun norm(x:String?)=(x?:"").trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," ")
    private fun tpIndex(r:JSONObject):Int? {
        val direct=get(r,"TPIndex","TpIndex","TPId","TPID","TransponderId")
        if(direct!=null)return direct.toIntOrNull()
        val service=get(r,"ServiceID")?:return null
        return if(service.length>=9)service.substring(4,9).toIntOrNull() else null
    }
    private fun sameSatellite(item:JSONObject,satellite:JSONObject):Boolean{
        val itemId=satId(item);val targetId=satId(satellite)
        if(itemId==null&&get(item,"ServiceID")!=null){
            val index=tpIndex(item)
            val tp=tps.firstOrNull{tpIndex(it)==index && index!=null}
            if(tp!=null)return sameSatellite(tp,satellite)
        }
        if(itemId!=null&&targetId!=null)return itemId==targetId
        val name=satName(item)
        return name!=null&&norm(name)==norm(satLabel(satellite))
    }
    private fun sameTp(ch:JSONObject,tp:JSONObject):Boolean{
        val t1=tpIndex(ch);val t2=tpIndex(tp)
        if(t1!=null&&t2!=null)return t1==t2
        val f1=freq(ch);val f2=freq(tp)
        if(f1==null||f2==null)return false
        if(f1.filter{it.isDigit()}!=f2.filter{it.isDigit()})return false
        val p1=pol(ch);val p2=pol(tp)
        if(p1!=null&&p2!=null&&norm(p1)!=norm(p2))return false
        return true
    }
    val channelsPage=col()
    val satellitePage=col()
    private val channelList=col()
    private val satelliteList=col()
    private val tpList=col()
    private val channelDetail=col()
    private val tpDetail=col()
    private val channelCount=txt("Chaînes non chargées",12f,muted)
    private val zapStatus=txt("Zapping : aucun test effectué",12f,muted)
    private val tpNotice=txt("Choisissez un satellite.",13f,muted)
    private val satNotice=txt("En attente des satellites.",13f,muted)
    private val signalNotice=txt("Signal non fourni.",13f,muted)
    private val filter=EditText(activity)
    private var channels:List<JSONObject> = emptyList()
    private var satellites:List<JSONObject> = emptyList()
    private var tps:List<JSONObject> = emptyList()
    private var selectedSatellite:JSONObject?=null
    private var selectedTp:JSONObject?=null
    private var complete=false
    private var tpRead=false
    private var limit=50
    private var satelliteLimit=45
    private var tpLimit=45
    private var tracking=false
    private val strengthLabel=txt("Force : —",15f,light,true)
    private val qualityLabel=txt("Qualité : —",15f,light,true)
    private val strengthBar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0}
    private val qualityBar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0}
    private val signalSourceLabel=txt("Aucune mesure reçue. Le réglage doit être confirmé sur la TV.",12f,muted)
    private val alignButton=tile("▶  Démarrer mesure continue"){measureSignal()}
    init {
        channelsPage.addView(txt("Chaînes TV & Radio",23f,light,true),lp(bottom=7))
        channelsPage.addView(txt("Touchez une chaîne pour voir ses informations et son EPG, avant de zapper.",13f,muted),lp(bottom=16))
        filter.apply{
            hint="Rechercher une chaîne…";setSingleLine(true);textSize=16f
            setTextColor(light);setHintTextColor(muted);background=bg()
            setPadding(12.dp,0,12.dp,0)
        }
        val modeRow=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("📺 TV","♫ Radio","★ Favoris récepteur").forEachIndexed {ix,name->
            val b=txt(name,14f,if(ix==0)light else accent,true).apply {
                gravity=android.view.Gravity.CENTER
                setPadding(9.dp,12.dp,9.dp,12.dp)
                background=bg(if(ix==0)Color.rgb(97,77,217) else surface)
                isClickable=true
                setOnClickListener{
                    filterMode=ix
                    modeButtons.forEachIndexed{j,b->
                        b.background=bg(if(ix==j)Color.rgb(97,77,217) else surface)
                        b.setTextColor(if(ix==j)light else accent)
                    }
                    limit=50;renderChannels()
                }
            }
            modeButtons.add(b)
            modeRow.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{
                setMargins(if(ix==0)0 else 4.dp,0,if(ix==2)0 else 4.dp,0)
            })
        }
        channelsPage.addView(modeRow,lp(bottom=12))
        channelsPage.addView(filter,LinearLayout.LayoutParams(-1,52.dp))
        channelsPage.addView(channelCount,lp(top=12,bottom=8))
        channelsPage.addView(channelDetail,lp(bottom=11))
        channelsPage.addView(channelList)
        filter.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){limit=50;renderChannels()}
            override fun afterTextChanged(s:Editable?){}
        })
        satellitePage.addView(txt("Satellites et réglage de parabole",22f,light,true),lp(bottom=6))
        val alignment=col().apply{
            setPadding(14.dp,16.dp,14.dp,16.dp)
            background=bg(Color.rgb(36,29,74))
        }
        alignment.addView(txt("◉  POINTAGE DE LA PARABOLE",16f,accent,true),lp(bottom=10))
        alignment.addView(txt("Mesure du tuner actuellement actif sur le récepteur. Choisir un satellite "+
            "dans la liste ne dirige pas automatiquement le tuner sur ce satellite.",12f,muted),lp(bottom=13))
        alignment.addView(strengthLabel,lp(bottom=6))
        alignment.addView(strengthBar,lp(bottom=11))
        alignment.addView(qualityLabel,lp(bottom=6))
        alignment.addView(qualityBar,lp(bottom=12))
        alignment.addView(signalSourceLabel,lp(bottom=9))
        alignment.addView(alignButton,lp(bottom=10))
        alignment.addView(txt("Pour orienter l'antenne : sélectionner le satellite et un TP actif "+
            "sur le récepteur, puis chercher le maximum de QUALITÉ. "+
            "Aucune valeur n'est estimée depuis l'image vidéo.",12f,muted))
        satellitePage.addView(alignment,lp(bottom=16))
        satellitePage.addView(txt("Sélectionnez un satellite pour afficher uniquement ses transpondeurs vérifiés.",13f,muted),lp(bottom=13))
        satellitePage.addView(satNotice,lp(bottom=9))
        satellitePage.addView(satelliteList,lp(bottom=15))
        satellitePage.addView(txt("TRANSPONDEURS DU SATELLITE",12f,accent,true),lp(bottom=9))
        satellitePage.addView(tpNotice,lp(bottom=9))
        satellitePage.addView(tpList)
        satellitePage.addView(tpDetail,lp(top=12))
        satellitePage.addView(txt("La mesure ci-dessus est spécifique au tuner actif.",12f,muted),lp(top=11))
        satellitePage.addView(signalNotice,lp(top=9))
    }
    fun updateChannels(rows:List<JSONObject>,fullyLoaded:Boolean){
        channels=rows.toList();complete=fullyLoaded;renderChannels()
        // Avoid O(satellites × channels) re-render on every 100-channel page.
        // Transponder details are refreshed only on explicit user selection.
    }
    fun updateSatellites(rows:List<JSONObject>){
        satellites=rows.toList()
        renderSatelliteList()
    }
    private fun renderSatelliteList(){
        satNotice.text=satellites.size.toString()+" satellites configurés sur le récepteur "+
            "• la présence dans la liste ne confirme pas le signal"
        satelliteList.removeAllViews()
        val snapshot=satellites
        snapshot.take(satelliteLimit).forEach{sat->
            val name=satLabel(sat)
            satelliteList.addView(tile("🛰  "+name+"\nToucher pour les transpondeurs"){
                selectedSatellite=sat
                selectedTp=null
                tpLimit=45
                tpList.removeAllViews()
                tpDetail.removeAllViews()
                tpNotice.text="Sélection : "+name+" • lecture des fréquences…"
                if(tpRead && tps.isNotEmpty())renderTps()
                else refreshTp()
            },lp(bottom=7))
        }
        if(snapshot.size>satelliteLimit){
            satelliteList.addView(tile("Afficher "+minOf(45,snapshot.size-satelliteLimit)+
                " satellites supplémentaires"){
                satelliteLimit+=45
                renderSatelliteList()
            },lp(bottom=8))
        }
    }
    fun updateTps(rows:List<JSONObject>,received:Boolean=true){
        tps=rows.toList();tpRead=received
        if(selectedSatellite!=null)renderTps()
    }
    fun updateSignal(s:String){
        signalNotice.text=s
        signalSourceLabel.text=s
    }
    fun updateSignalReading(level:Int?,quality:Int?,message:String){
        strengthBar.progress=level?:0
        qualityBar.progress=quality?:0
        strengthLabel.text="Force du signal : "+(level?.let{it.toString()+" %"}?:"—")
        qualityLabel.text="Qualité du signal : "+(quality?.let{it.toString()+" %"}?:"—")
        signalSourceLabel.text=message
        signalNotice.text=message
    }
    fun updateTracking(running:Boolean){
        tracking=running
        alignButton.text=if(running)"■  Arrêter les mesures" else
            "▶  Démarrer mesure continue"
    }
    private fun renderChannels(){
        channelList.removeAllViews()
        val term=norm(filter.text.toString())
        val found=orderedChannels().filter{
            (when(filterMode){0->!isRadio(it);1->isRadio(it);else->favorite(it)}) &&
            (term.isBlank()||norm(cname(it)).contains(term)||norm(satName(it)).contains(term))
        }
        channelCount.text=found.size.toString()+" chaîne(s) • "+(if(complete)"liste du récepteur chargée" else "chargement du récepteur…")
        found.take(limit).forEach{ch->
            val detail=listOfNotNull(satName(ch),freq(ch)?.let{it+" MHz"}).joinToString(" · ")
            channelList.addView(tile(
                (if(favorite(ch))"★  " else "")+cname(ch)+
                    (if(detail.isBlank())"" else "\n"+detail)
            ){showChannel(ch);openChannelTab()},lp(bottom=7))
        }
        if(found.size>limit)channelList.addView(tile("Afficher 50 autres chaînes"){
            limit+=50;renderChannels()
        },lp(bottom=12))
        if(found.isEmpty())channelList.addView(txt("Aucune chaîne disponible ou correspondante.",13f,muted))
    }
    private fun showChannel(ch:JSONObject){
        channelDetail.removeAllViews()
        channelDetail.setPadding(14.dp,14.dp,14.dp,14.dp)
        channelDetail.background=bg(Color.rgb(37,30,83))
        channelDetail.addView(txt("DÉTAILS DE LA CHAÎNE",11f,accent,true))
        channelDetail.addView(txt(cname(ch),22f,light,true),lp(top=9))
        val details=listOfNotNull(satName(ch),freq(ch)?.let{it+" MHz"},pol(ch),sr(ch)?.let{"SR "+it}).joinToString(" · ")
        channelDetail.addView(txt(if(details.isEmpty())"Fréquence non reçue" else details,12f,muted),lp(top=9))
        val epg=get(ch,"EventName","EventTitle","CurrentEvent","NowPlaying","EpgTitle","EPGName")
        val next=get(ch,"NextEvent","NextEventTitle","NextProgramme")
        channelDetail.addView(txt("EPG • PROGRAMME",11f,accent,true),lp(top=17,bottom=7))
        channelDetail.addView(txt(epg?:"EPG non communiqué dans les informations reçues.",14f,light,epg!=null))
        if(next!=null)channelDetail.addView(txt("Ensuite : "+next,12f,muted),lp(top=8))
        channelDetail.addView(tile("EPG du programme en cours sur la TV"){openEpg()},lp(top=13,bottom=8))
        channelDetail.addView(txt(
            "Actions de modification : uniquement sur le récepteur. "+
            "Aucune liste locale ne remplace la configuration du décodeur.",
            12f,muted),lp(top=10,bottom=12))
        id(ch)?.let{programId->
            val radio=if(get(ch,"Radio","TvState")=="1")1 else 0
            channelDetail.addView(tile("▶  Zapper sur le récepteur"){
                zapStatus.text="Demande de zapping en cours…"
                tune(programId,radio)
            })
        } ?: channelDetail.addView(txt("Pas d'identifiant de programme : zapping impossible.",12f,muted))
        channelDetail.addView(zapStatus,lp(top=9))
    }
    private fun renderTps(){
        tpList.removeAllViews()
        val sat=selectedSatellite?:return
        if(!tpRead){tpNotice.text="Lecture des transpondeurs en cours…";return}
        // A capped batch avoids constructing hundreds of nested Android Views on tap.
        val linked=tps.asSequence().filter{sameSatellite(it,sat)}.toList()
        if(linked.isEmpty()){
            tpNotice.text=if(tps.isEmpty())"Aucun transpondeur reçu du récepteur." else
                "Le récepteur n'a pas fourni de lien vérifiable entre ce satellite et ses transpondeurs."
            return
        }
        tpNotice.text=linked.size.toString()+" TP pour "+satLabel(sat)+" • affichage progressif"
        linked.take(tpLimit).forEach{tp->
            val f=freq(tp)?.let{it+" MHz"}?:"Fréquence non transmise"
            val line=listOf(f,pol(tp),sr(tp)?.let{"SR "+it}).filterNotNull().joinToString(" · ")
            tpList.addView(tile("◈  "+line+"\nAppuyer pour consulter les chaînes"){
                selectedTp=tp;showTp(tp)
            },lp(bottom=8))
        }
        if(linked.size>tpLimit){
            tpList.addView(tile("Afficher "+minOf(45,linked.size-tpLimit)+
                " transpondeurs supplémentaires"){
                tpLimit+=45;renderTps()
            },lp(bottom=8))
        }
    }
    private fun showTp(tp:JSONObject){
        val sat=selectedSatellite?:return
        tpDetail.removeAllViews()
        tpDetail.setPadding(15.dp,15.dp,15.dp,15.dp)
        tpDetail.background=bg(Color.rgb(37,30,83))
        tpDetail.addView(txt("DÉTAILS • "+satLabel(sat),12f,accent,true))
        tpDetail.addView(txt(listOfNotNull(freq(tp)?.let{it+" MHz"},pol(tp),sr(tp)?.let{"SR "+it}).joinToString(" · "),19f,light,true),lp(top=9,bottom=12))
        val matches=channels.filter{sameTp(it,tp)&&sameSatellite(it,sat)}
        tpDetail.addView(txt("CHAÎNES DU TRANSPONDEUR",11f,accent,true),lp(bottom=8))
        if(matches.isEmpty()){
            tpDetail.addView(txt("Aucune association vérifiable. Ce transpondeur n'est pas nécessairement vide.",13f,muted))
        }else{
            matches.take(30).forEach{ch->tpDetail.addView(tile(cname(ch)){showChannel(ch);openChannelTab()},lp(bottom=6))}
            if(matches.size>30)tpDetail.addView(txt("+"+(matches.size-30)+" autres",12f,muted))
        }
        tpDetail.addView(txt("Signal du tuner actuel, pas forcément ce transpondeur : "+
            signalNotice.text.toString(),12f,green),lp(top=12))
    }
    fun updateZapStatus(message:String) {
        zapStatus.text=message
    }

}
