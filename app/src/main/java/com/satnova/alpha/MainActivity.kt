package com.satnova.alpha

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

// Prototype: compatibility with Echolink Zen 4 is NOT confirmed until real-device testing.
class MainActivity : Activity() {
    private val bg = Color.rgb(10, 16, 32)
    private val surface = Color.rgb(23, 32, 53)
    private val fg = Color.rgb(239, 244, 255)
    private val muted = Color.rgb(155, 172, 197)
    private val accent = Color.rgb(156, 155, 255)
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var ip: EditText
    private lateinit var connectButton: Button
    private val work = Executors.newSingleThreadExecutor()
    private val remoteWork = Executors.newSingleThreadExecutor()
    private val writeLock = Any()
    private val discoveryWork = Executors.newSingleThreadExecutor()
    private val monitorWork = Executors.newSingleThreadScheduledExecutor()
    private val fieldViews = linkedMapOf<String,TextView>()
    private val channelNames = ConcurrentHashMap<String,String>()
    private val channelRecords = ConcurrentHashMap<String,JSONObject>()
    private val channelOrder = CopyOnWriteArrayList<String>()
    @Volatile private var targetTuneId = ""
    @Volatile private var tuneMethod = 0
    @Volatile private var tuneStartMillis = 0L
    @Volatile private var tuneAckStatus = -1
    private fun currentChannelsOrdered()=channelOrder.mapNotNull{channelRecords[it]}
    private val satelliteRecords = CopyOnWriteArrayList<JSONObject>()
    private val transponderRecords = CopyOnWriteArrayList<JSONObject>()
    private lateinit var screen: SatNovaScreen
    @Volatile private var currentChannelId = ""
    @Volatile private var currentTvState = 0
    @Volatile private var streamPending = false
    @Volatile private var streamOpened = false
    @Volatile private var satIpFlag = -1
    @Volatile private var lastSignalUpdate = 0L
    @Volatile private var lastRequest24 = 0L
    @Volatile private var totalChannelCount = 0
    @Volatile private var nextChannelIndex = 0
    private var monitorTicks = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var verified = false
    @Volatile private var jsonMode = true
    private val clientId by lazy {
        getSharedPreferences("satnova", Context.MODE_PRIVATE).let { p ->
            p.getString("uuid", null) ?: UUID.randomUUID().toString().also { p.edit().putString("uuid", it).apply() }
        }
    }

    private fun label(value: String, size: Float = 16f, color: Int = fg): TextView {
        return TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            setPadding(12, 10, 12, 10)
        }
    }
    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12, 12, 12, 12)
    }
    private fun row(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(textValue: String, parent: LinearLayout, weight: Float = 1f, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            setTextColor(fg)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(surface))
            val params = LinearLayout.LayoutParams(0, 57.dp, weight)
            params.setMargins(3.dp, 3.dp, 3.dp, 3.dp)
            parent.addView(this, params)
            setOnClickListener { action() }
        }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun report(value: String) = runOnUiThread {
        if (::status.isInitialized) status.text = value
    }
    private fun setVerified(value: Boolean) = runOnUiThread {
        verified = value
        if (::connectButton.isInitialized) connectButton.text = if (value) "Disconnect" else "Connect"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        screen = SatNovaScreen(
            activity = this,
            onDiscover = { discover() },
            onConnect = {
                if (verified) work.execute { disconnect(); report("Déconnecté") }
                else connect(ip.text.toString().trim())
            },
            onWatch = { startLiveTv() },
            onReadSatellites = { readSatellites() },
            onReadTransponders = { readTransponders() },
            onReadSignal = { readSignal() },
            onServers = { navigateSetting("servers") },
            onNetwork = { navigateSetting("network") },
            onScan = { navigateSetting("scan") },
            onChannelTune = { id, tvState -> tuneChannel(id,tvState) },
            onChannelRefresh = { refreshChannels() },
            onEpgRemote = { sendKey(32) },
            onRefresh = {
                if (!verified) report("Connecter le récepteur d'abord")
                else work.execute {
                    try {
                        requestState(15)
                        requestState(3)
                        requestState(19)
                    } catch (e: Exception) { report("Actualisation : " + e.message) }
                }
            },
            onKey = { code -> sendKey(code) }
        )
        status = screen.status
        ip = screen.ip
        ip.setText(getSharedPreferences("satnova",Context.MODE_PRIVATE)
            .getString("last_receiver_ip","192.168.1.2"))
        connectButton = screen.connectButton
        diagnostics = screen.diagnostics
        fieldViews.putAll(screen.fields)
        setContentView(screen.root)

        monitorWork.scheduleWithFixedDelay({
            if (verified) work.execute {
                try {
                    requestState(3)
                    requestState(19)
                    monitorTicks++
                    if (monitorTicks % 6 == 0) requestState(15)
                    if (monitorTicks % 3 == 0) requestState(14)
                    if (monitorTicks % 3 == 0) requestState(26)
                } catch (e: Exception) { report("Monitor : " + e.message) }
            }
        }, 3, 4, TimeUnit.SECONDS)
    }
    private fun showField(key:String,value:String)=runOnUiThread { fieldViews[key]?.text=value }
    private fun refreshChannels(){
        if(!verified){report("Connecter le récepteur pour les chaînes");return}
        screen.updateChannels(currentChannelsOrdered(), totalChannelCount>0 &&
            channelRecords.size>=totalChannelCount)
        work.execute{
            try{
                requestState(15)
                if(totalChannelCount>0 && channelRecords.isEmpty())requestNextChannelPage()
            }catch(e:Exception){report("Chargement chaînes : "+e.message)}
        }
    }
    private fun tuneChannel(id:String,tvState:Int){
        if(!verified){report("Connectez le récepteur avant le zapping");return}
        if(!channelRecords.containsKey(id)){report("Chaîne inconnue");return}
        targetTuneId=id
        tuneMethod=0
        tuneStartMillis=System.currentTimeMillis()
        tuneAckStatus=-1
        sendTune(id,tvState,false)
    }
    private fun sendTune(id:String,tvState:Int,flat:Boolean){
        work.execute{
            try{
                if(!verified||socket==null)return@execute
                if(targetTuneId!=id)return@execute
                tuneMethod=if(flat)2 else 1
                val payload=if(jsonMode){
                    val req=JSONObject().put("request","1000")
                    if(flat){
                        req.put("TvState",tvState.toString()).put("ProgramId",id)
                    }else{
                        req.put("array",JSONArray().put(JSONObject()
                            .put("TvState",tvState.toString()).put("ProgramId",id)))
                    }
                    req.toString()
                }else {
                    if(flat)
                        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1000\"><TvState>"+
                            tvState+"</TvState><ProgramId>"+id+"</ProgramId></Command>"
                    else
                        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1000\"><parm><TvState>"+
                            tvState+"</TvState><ProgramId>"+id+"</ProgramId></parm></Command>"
                }
                val sock=socket ?: return@execute
                sendFrame(sock,payload)
                report(if(flat)"Test de zapping alternatif…"
                    else "Zapping demandé au récepteur, vérification…")
                showField("Dernière commande envoyée","1000 • "+id+
                    " • mode "+(if(flat) "direct" else "array")+" • état non confirmé")
                monitorWork.schedule({
                    if(!verified||targetTuneId!=id)return@schedule
                    work.execute{try{requestState(3)}catch(_:Exception){}}
                },1300,TimeUnit.MILLISECONDS)
                monitorWork.schedule({
                    if(!verified||targetTuneId!=id)return@schedule
                    if(currentChannelId==id){
                        report("Chaîne confirmée : "+(channelNames[id]?:id))
                        targetTuneId=""
                    }else if(!flat){
                        report("Récepteur sans changement confirmé, essai compatibilité…")
                        sendTune(id,tvState,true)
                    }else {
                        report("Zapping non confirmé. Réponse 1000 = "+
                            (if(tuneAckStatus<0)"aucun ACK" else "code "+tuneAckStatus)+
                            ". Vérifier que MENU/EPG est fermé sur TV.")
                        targetTuneId=""
                    }
                },2600,TimeUnit.MILLISECONDS)
            }catch(e:Exception){
                report("Échec du zapping : "+(e.message?:"erreur"))
                targetTuneId=""
            }
        }
    }
    private fun videoStatus(text:String) { showField("Diagnostic vidéo","VIDÉO : "+text); report(text) }
    private fun readSatellites(){
        if(!verified){report("Connecter le récepteur d'abord");return}
        showField("Satellites","Lecture de la liste des satellites…")
        work.execute{try{requestState(22)}catch(e:Exception){showField("Satellites","Échec : "+e.message)}}
    }
    private fun readTransponders(){
        if(!verified){report("Connecter le récepteur d'abord");return}
        showField("Transpondeurs","Test de lecture 24 en cours (non garanti sur Zen 4)…")
        work.execute{try{
            lastRequest24=System.currentTimeMillis()
            requestState(24)
        }catch(e:Exception){showField("Transpondeurs","Échec : "+e.message)}}
    }
    private fun readSignal(){
        if(!verified){report("Connecter le récepteur d'abord");return}
        lastSignalUpdate=0L
        val waiting="Mesure demandée • lecture de la télémétrie du tuner…"
        showField("Signal temps réel",waiting)
        screen.updateSignal(waiting)
        work.execute{
            try{requestState(14);requestState(15)}catch(e:Exception){
                showField("Signal temps réel","Échec : "+e.message)
            }
        }
        monitorWork.schedule({
            if(lastSignalUpdate==0L && verified){
                val unavailable="Firmware 1.48 : aucune valeur SNR/qualité/BER reçue " +
                    "dans les réponses disponibles. Mesure réelle non accessible pour l'instant."
                showField("Signal temps réel",unavailable)
                screen.updateSignal(unavailable)
            }
        },6,TimeUnit.SECONDS)
    }
    private fun navigateSetting(section:String) {
        when(section){
            "servers"->showField("Serveurs",
                "Forever / Apollo / autres: état et activation non exposés par le protocole connu. Ouverture du MENU sur TV. Utiliser Configuration de réseau → Configuration du Serveur. Ne pas saisir de mots de passe dans SatNova.")
            "network"->showField("Réseau",
                "L'IP de connexion SatNova est modifiable en haut. Pour DHCP / Statique: MENU sur TV → Configuration de réseau. Noter IP, masque, passerelle, DNS avant tout changement. Une nouvelle IP coupera la session SatNova.")
            "scan"->showField("Recherche",
                "MENU sur TV → Installation → sélection satellite / transpondeur → Recherche. Ne pas lancer de scan sans vérifier le tuner et la liste existante.")
        }
        if(!verified){report("Connecter pour ouvrir le MENU sur TV");return}
        sendKey(6)
    }



    private fun requestState(type:Int,from:Int=0,to:Int=0) {
        val active=socket ?: return
        if(!verified) return
        val payload=if(jsonMode){
            val obj=JSONObject().put("request",type.toString())
            if(type==0){
                obj.put("FromIndex",from.toString())
                obj.put("ToIndex",to.toString())
            }
            obj.toString()
        }else{
            if(type==0)
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"0\"><FromIndex>"+from+"</FromIndex><ToIndex>"+to+"</ToIndex></Command>"
            else
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\""+type+"\" />"
        }
        sendFrame(active,payload)
    }
    private fun requestNextChannelPage() {
        if(!verified||totalChannelCount<=0||nextChannelIndex>=totalChannelCount||nextChannelIndex>=3000)return
        val from=nextChannelIndex
        val to=minOf(from+99,totalChannelCount-1,2999)
        requestState(0,from,to)
        nextChannelIndex=to+1
    }
    private fun rows(text:String):List<JSONObject> = try {
        when(val value=JSONTokener(text).nextValue()){
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            is JSONObject -> {
                val a=value.optJSONArray("array") ?: value.optJSONArray("Array")
                if(a==null) listOf(value) else (0 until a.length()).mapNotNull{a.optJSONObject(it)}
            }
            else -> emptyList()
        }
    } catch (_:Exception){ emptyList() }
    private fun pick(record:JSONObject,vararg names:String):String?{
        for(k in names){
            val value=record.optString(k,"").trim()
            if(value.isNotEmpty()&&value!="null")return value
        }
        return null
    }
    private fun showCurrent(){
        if(currentChannelId.isNotEmpty())
            showField("Chaîne actuelle",channelNames[currentChannelId] ?: "ID "+currentChannelId+" · nom en chargement")
    }
    private fun handleReply(kind:Int,statusCode:Int,body:ByteArray) {
        val text=body.toString(StandardCharsets.UTF_8).trim()
        val date=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(java.util.Date())
        showField("Dernière réponse",date+" · type "+kind+" · code "+statusCode)
        showField("Données brutes reçues",if(text.isEmpty()) "(no body)" else text.take(900))
        if(kind==1000){
            tuneAckStatus=statusCode
            if(statusCode!=0)report("Récepteur refuse le zapping : code "+statusCode+
                if(statusCode==17)" (menu ouvert)" else "")
            return
        }
        if(kind==1009){
            if(!streamPending) return
            streamPending=false
            if(statusCode!=0){
                videoStatus("Refus du récepteur • réponse 1009 • code "+statusCode+
                    ". Vérifier DVB To IPTV + abonnement SAT>IP / Apollo.")
                return
            }
            val normalized=text.replace("\\/","/")
            val row=rows(normalized).firstOrNull()
            val success=row?.optString("success","1") ?: "1"
            if(success=="0"){
                videoStatus("Le récepteur a indiqué success=0 pour le flux HTTP.")
                return
            }
            val structured=row?.optString("url","") ?: ""
            val link=if(structured.startsWith("http://",true) ||
                structured.startsWith("https://",true) || structured.startsWith("rtsp://",true)){
                structured.trim()
            }else{
                Regex("""(?:rtsp|https?)://[^\s"'<>\\]+""",RegexOption.IGNORE_CASE)
                    .find(normalized)?.value?.trimEnd(',', '}', ']')
            }
            if(link==null){
                videoStatus("1009 sans URL exploitable. Réponse : "+text.take(160))
                return
            }
            val streamHost=Uri.parse(link).host ?: ""
            if(!isPrivate(streamHost)){
                videoStatus("Adresse du flux refusée (hors réseau local).")
                return
            }
            streamOpened=true
            videoStatus("Flux "+Uri.parse(link).scheme+" reçu. Ouverture du lecteur…")
            runOnUiThread {
                val intent=Intent(this,VlcLiveActivity::class.java)
                intent.putExtra("stream_url",link)
                intent.putExtra("channel_title",channelNames[currentChannelId] ?: "Canal "+currentChannelId)
                startActivity(intent)
            }
            return
        }
        if(statusCode!=0){
            runOnUiThread { diagnostics.text="Réponse "+kind+": refus code "+statusCode }
            return
        }
        if(kind in 2000..2999){
            if(kind in listOf(2001,2002,2003,2004)) work.execute {
                try{
                    requestState(3);requestState(19)
                    if(kind==2002){
                        channelNames.clear();channelRecords.clear();channelOrder.clear();nextChannelIndex=0
                        screen.updateChannels(emptyList(),false)
                        requestNextChannelPage()
                    }
                }catch(_:Exception){}
            }
            return
        }
        val recs=rows(text)
        when(kind){
            22,23 -> {
                if(kind==23 && recs.isEmpty())return
                satelliteRecords.clear()
                satelliteRecords.addAll(recs.map{JSONObject(it.toString())})
                screen.updateSatellites(satelliteRecords.toList())
                val output=recs.take(35).mapNotNull{ rec->
                    val name=pick(rec,"SatelliteName","SatName","Name","Satellite","SatNameValue")
                    val pos=pick(rec,"OrbitalPosition","Position","Angle")
                    if(name!=null) "• "+name+(if(pos!=null)" · "+pos else "") else null
                }
                showField("Satellites", if(output.isNotEmpty()) output.joinToString("\n") else
                    "Réponse reçue mais format inconnu (type "+kind+", "+body.size+" octets).")
            }
            24 -> {
                transponderRecords.clear()
                transponderRecords.addAll(recs.map{JSONObject(it.toString())})
                screen.updateTransponders(transponderRecords.toList())
                val output=recs.take(35).mapNotNull{ rec->
                    val freq=pick(rec,"Frequency","Freq","TPFreq","FrequencyMHz")
                    val sym=pick(rec,"SymbolRate","Symbol","SR","SymRate")
                    val pol=pick(rec,"Polarization","Pol","Polar")
                    if(freq!=null) "• "+freq+" MHz"+(if(pol!=null)" / "+pol else "")+
                        (if(sym!=null)" / "+sym else "") else null
                }
                showField("Transpondeurs",if(output.isNotEmpty())output.joinToString("\n")
                    else "Le récepteur a répondu, mais aucune liste TP reconnue. "+
                        "Statut "+statusCode+" · "+body.size+" octets. Opération lecture seule.")
            }

            3 -> {
                val id=recs.firstOrNull()?.let { pick(it,"Data","ProgramId","ServiceID") }
                if(id!=null){
                    currentChannelId=id
                    showCurrent()
                    if(targetTuneId==id){
                        report("Zapping confirmé : "+(channelNames[id]?:id))
                        targetTuneId=""
                    }
                }
            }
            19 -> {
                val value=recs.firstOrNull()?.let { pick(it,"Data","MuteState") }
                if(value!=null)
                    showField("Mute",if(value=="1")"ON (confirmé)" else if(value=="0")"OFF (confirmé)" else value)
            }
            14,15 -> {
                val info=recs.firstOrNull() ?: return
                val lvl=pick(info,"SignalStrength","SignalLevel","Signal","Strength")
                val qual=pick(info,"SignalQuality","Quality","QualityLevel","SNR")
                val ber=pick(info,"BER","Ber")
                if(lvl!=null||qual!=null||ber!=null) {
                    val pieces=listOfNotNull(lvl?.let{"Niveau: "+it},qual?.let{"Qualité: "+it},ber?.let{"BER: "+it})
                    val signalMessage=pieces.joinToString(" · ")+" (récepteur, "+date+")"
                    showField("Signal temps réel",signalMessage)
                    screen.updateSignal(signalMessage)
                    lastSignalUpdate=System.currentTimeMillis()
                }

                pick(info,"SoftwareVersion")?.let{showField("Firmware",it)}
                pick(info,"ProductName","ModelName")?.let{runOnUiThread {diagnostics.text="Récepteur confirmé: "+it}}
                pick(info,"ChannelNum")?.let{ n->
                    showField("Nombre de chaînes",n+" (confirmé)")
                    val count=n.toIntOrNull()
                    if(count!=null&&count>0&&count!=totalChannelCount){
                        totalChannelCount=count;nextChannelIndex=0;channelNames.clear()
                        channelRecords.clear();channelOrder.clear()
                        screen.updateChannels(emptyList(),false)
                        work.execute{try{requestNextChannelPage()}catch(_:Exception){}}
                    }
                }
                pick(info,"MuteState")?.let{showField("Mute",if(it=="1")"ON (rapporté)" else "OFF (rapporté)")}
                pick(info,"Volume","VolumeValue","VolumeState")?.let{showField("Volume",it+" (rapporté)")}
                pick(info,"Signal","SignalLevel","SignalQuality")?.let{showField("Signal / fréquence",it+" (rapporté)")}
            }
            0 -> {
                for(item in recs){
                    val id=pick(item,"ServiceID","ProgramId") ?: continue
                    val name=pick(item,"ServiceName","Name","ChannelName","ProgramName") ?: "Service "+id
                    channelNames[id]=name
                    if(!channelRecords.containsKey(id))channelOrder.add(id)
                    channelRecords[id]=JSONObject(item.toString())
                    if(id==currentChannelId){
                        pick(item,"Radio")?.toIntOrNull()?.let {currentTvState=if(it==1) 1 else 0}
                        pick(item,"EventName","EventTitle","CurrentEvent","NowPlaying")?.let{
                            showField("Programme en cours / EPG",it+" (rapporté)")
                        }
                        val freq=listOfNotNull(pick(item,"SatelliteName"),pick(item,"Frequency"),pick(item,"Polarization"))
                        if(freq.isNotEmpty())showField("Signal / fréquence",freq.joinToString(" • "))
                    }
                }
                showCurrent()
                screen.updateChannels(currentChannelsOrdered(),totalChannelCount>0&&channelRecords.size>=totalChannelCount)
                if(recs.isNotEmpty()&&nextChannelIndex<totalChannelCount&&nextChannelIndex<3000)
                    work.execute{try{requestNextChannelPage()}catch(_:Exception){}}
            }
        }
    }

    private fun startLiveTv(){
        if(!verified){videoStatus("Connecter Echolink avant le streaming.");return}
        if(streamPending){report("Demande de vidéo déjà en cours…");return}
        val id=currentChannelId
        if(id.isBlank()){
            videoStatus("Chaîne inconnue. Attendre la synchro, puis réessayer.")
            work.execute{try{requestState(3)}catch(_:Exception){}}
            return
        }
        streamPending=true
        videoStatus("Demande 1009 envoyée pour "+(channelNames[id] ?: id)+"…")
        work.execute {
            try {
                val s=socket ?: throw IllegalStateException("Récepteur déconnecté")
                val payload=if(jsonMode){
                    JSONObject()
                        .put("request","1009")
                        .put("array",JSONArray().put(
                            JSONObject().put("TvState",currentTvState.toString())
                                .put("ProgramId",id)
                        )).toString()
                }else{
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1009\"><parm><TvState>"+currentTvState+"</TvState><ProgramId>"+id+"</ProgramId></parm></Command>"
                }
                sendFrame(s,payload)
                monitorWork.schedule({
                    if(streamPending){
                        streamPending=false
                        videoStatus("Pas de réponse 1009 en 12 s. Activer DVB To IPTV dans Système.")
                    }
                },12,TimeUnit.SECONDS)
            }catch(e:Exception){
                streamPending=false
                videoStatus("Impossible d'interroger le flux : "+(e.message ?: "erreur"))
            }
        }
    }
    override fun onResume(){
        super.onResume()
        if(streamOpened){
            streamOpened=false
            if(verified)work.execute{try{requestState(1012)}catch(_:Exception){}}
        }
    }
    private fun isPrivate(host: String): Boolean {
        val p = host.split('.')
        if (p.size != 4) return false
        val n = p.map { t ->
            if (t.length !in 1..3 || (t.length > 1 && t.startsWith("0"))) return false
            val v = t.toIntOrNull() ?: return false
            if (v !in 0..255) return false
            v
        }
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
    private fun sendFrame(active: Socket, payload: String) {
        synchronized(writeLock) {
            active.getOutputStream().write(frame(payload))
            active.getOutputStream().flush()
        }
    }
    private fun frame(payload: String): ByteArray {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        return ("Start" + body.size.toString().padStart(7, '0') + "End").toByteArray(StandardCharsets.US_ASCII) + body
    }
    private fun read(input: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var index = 0
        while (index < count) {
            val n = input.read(data, index, count-index)
            if (n < 0) throw IllegalStateException("Receiver closed the connection")
            index += n
        }
        return data
    }
    private fun decode(raw: ByteArray): Triple<String,Boolean,Boolean> {
        if (raw.size != 108) throw IllegalStateException("Unexpected login size")
        val bytes = raw.reversedArray().map { (it.toInt() xor 0x5B).toByte() }.toByteArray()
        val signature = String(bytes,0,12,StandardCharsets.US_ASCII)
        if (signature != "39WwijOog54a") throw IllegalStateException("Not a recognized G-MScreen device")
        val modelBytes = bytes.copyOfRange(20,52)
        val end = modelBytes.indexOf(0.toByte()).let { if (it < 0) modelBytes.size else it }
        val model = String(modelBytes,0,end,StandardCharsets.UTF_8).ifBlank { "Receiver" }
        val flags = bytes[84].toInt() and 255
        return Triple(model,flags and 0x40 != 0,flags and 0x01 != 0)
    }
    private fun connect(host: String) = work.execute {
        if (!isPrivate(host)) { report("Use a private LAN IPv4 address, e.g. 192.168.1.2"); return@execute }
        disconnect()
        report("Connecting to $host:20000 …")
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, 20000), 3500)
            s.soTimeout = 4500
            report("TCP is open. Verifying receiver login …")
            val xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"998\"><data>SatNova Alpha</data><uuid>$clientId</uuid></Command>"
            sendFrame(s,xml)
            val loginBlock=read(s.getInputStream(),108)
            val (model,useJson,full) = decode(loginBlock)
            satIpFlag=((loginBlock.reversedArray()[84].toInt() xor 0x5B) and 0x18) shr 3
            showField("Diagnostic vidéo","SAT>IP indicateur brut = "+satIpFlag+
                " (firmware variable). Vérifier DVB To IPTV dans le menu Système.")
            if (full) throw IllegalStateException("Client slots full. Close SAT Chef first.")
            jsonMode = useJson
            getSharedPreferences("satnova",Context.MODE_PRIVATE).edit()
                .putString("last_receiver_ip",host).apply()
            socket = s
            setVerified(true)
            report("LOGIN VERIFIED: $model · " + if(useJson) "JSON" else "XML")
            runOnUiThread { diagnostics.text = "Protocol login verified; test Volume+ while watching the TV. $model" }
            s.soTimeout = 0
            showField("Chaîne actuelle","Lecture en cours…")
            work.execute {
                try {
                    requestState(15)
                    requestState(14)
                    requestState(3)
                    requestState(19)
                }catch(e:Exception){report("Initial state: "+e.message)}
            }
            Thread {
                try {
                    val input = s.getInputStream()
                    while (socket === s) {
                        val header = read(input,16)
                        if (String(header,0,4,StandardCharsets.US_ASCII) != "GCDH") throw IllegalStateException("Unknown reply")
                        val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.position(4)
                        val length = bb.int
                        val kind = bb.int
                        val statusCode = bb.int
                        if(length !in 0..2000000)throw IllegalStateException("Response too large")
                        val raw=if(length>0)read(input,length) else ByteArray(0)
                        val decoded=if(raw.isNotEmpty())try{
                            InflaterInputStream(ByteArrayInputStream(raw)).use{it.readBytes()}
                        }catch(_:Exception){raw}else raw
                        handleReply(kind,statusCode,decoded)
                    }
                } catch (e: Exception) {
                    if (socket === s) { socket = null; setVerified(false); report("Connection lost: ${e.message}") }
                } finally { try { s.close() } catch (_: Exception) { } }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            disconnect()
            report("Connection/login failed: ${e.message}")
        }
    }
    private fun sendKey(value: Int) = remoteWork.execute {
        val s = socket
        if (!verified || s == null) { report("Connect and verify receiver first"); return@execute }
        try {
            val payload = if (jsonMode) "{\"request\":\"1040\",\"array\":[{\"KeyValue\":\"$value\"}]}"
                else "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1040\"><parm><KeyValue>$value</KeyValue></parm></Command>"
            sendFrame(s,payload)
            showField("Dernière commande envoyée","Key "+value+" envoyé · effet non confirmé")
            if(value in listOf(69,70,35,36,23)) {
                requestState(3)
                requestState(19)
            }
            report("Commande "+value+" envoyée. État récepteur en cours de vérification.")
        } catch (e: Exception) { disconnect(); report("Failed to send key: ${e.message}") }
    }
    private fun discover() = discoveryWork.execute {
        report("Listening for receiver announcements (6 seconds) …")
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifi.createMulticastLock("satnova").apply { setReferenceCounted(false) }
            lock.acquire()
            try {
                DatagramSocket(null).use { udp ->
                    udp.reuseAddress = true
                    udp.bind(InetSocketAddress(25860))
                    udp.soTimeout = 900
                    val until = System.nanoTime() + 6_000_000_000L
                    var found = false
                    while (!found && System.nanoTime() < until) {
                        val bytes = ByteArray(256)
                        val p = DatagramPacket(bytes,bytes.size)
                        try {
                            udp.receive(p)
                            if (p.length == 108) {
                                val decoded = decode(p.data.copyOfRange(p.offset,p.offset+p.length))
                                val host = p.address.hostAddress ?: ""
                                if (isPrivate(host)) {
                                    found = true
                                    runOnUiThread { ip.setText(host) }
                                    report("Found ${decoded.first} at $host")
                                }
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                        catch (_: IllegalStateException) { }
                    }
                    if (!found) report("No announcements found. Enter IP manually.")
                }
            } finally { if(lock.isHeld) lock.release() }
        } catch (e: Exception) { report("Discovery unavailable: ${e.message}") }
    }
    private fun disconnect() {
        verified = false
        val s = socket; socket = null
        try { s?.close() } catch (_: Exception) { }
        setVerified(false)
    }
    override fun onDestroy() {
        disconnect()
        work.shutdownNow()
        remoteWork.shutdownNow()
        discoveryWork.shutdownNow()
        monitorWork.shutdownNow()
        super.onDestroy()
    }
}
