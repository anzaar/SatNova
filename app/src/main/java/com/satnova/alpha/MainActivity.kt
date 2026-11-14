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
    @Volatile private var lastRemotePressAt=0L
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
    private var inlinePlayer:InlineVlcPlayer?=null
    @Volatile private var streamedChannel=""
    @Volatile private var activeStreamUrl:String?=null
    @Volatile private var autoStreamRequestId=""
    @Volatile private var liveScreenRequested=false
    @Volatile private var currentChannelId = ""
    @Volatile private var currentTvState = 0
    @Volatile private var streamPending = false
    @Volatile private var streamOpened = false
    @Volatile private var satIpFlag = -1
    @Volatile private var lastSignalUpdate = 0L
    @Volatile private var trackingSignal = false
    @Volatile private var signalTrackingStartedAt = 0L
    @Volatile private var receiverModel = "Récepteur en détection"
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
    private fun setVerified(value: Boolean) {
        verified = value
        if(!value){
            activeStreamUrl=null
            streamedChannel=""
            autoStreamRequestId=""
            streamPending=false
        }
        runOnUiThread {
            if(::screen.isInitialized) {
                screen.setReceiverOnline(value)
                if(!value){
                    inlinePlayer?.stop()
                    screen.resetVideoPlaceholder()
                    screen.showInlineState("Déconnecté")
                }
            }
            if (::connectButton.isInitialized) connectButton.text =
                if (value) "Disconnect" else "Connect"
            showField("État connexion", if (value)
                "● Récepteur connecté • réseau dans Options"
                else "● Hors ligne • toucher pour Options")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        screen = SatNovaScreen(
            activity = this,
            onDiscover = { discover() },
            onAdvancedScan = { prefix -> scanPrefix(prefix) },
            onSelectReceiver = { host ->
                ip.setText(host)
                connect(host)
            },
            onDisconnect = {
                work.execute{disconnect();report("Récepteur déconnecté")}
            },
            onConnect = {
                if (verified) work.execute { disconnect(); report("Déconnecté") }
                else connect(ip.text.toString().trim())
            },
            onWatch = { retryInlineStream() },
            onReadSatellites = { readSatellites() },
            onReadTransponders = { readTransponders() },
            onReadSignal = { toggleSignalTracking() },
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
        inlinePlayer=InlineVlcPlayer(this,screen.inlineVideo){ msg ->
            screen.showInlineState(msg)
            if(msg.startsWith("LIVE • lecture"))screen.setVideoPlaying(true)
        }
        screen.setReceiverOnline(false)
        // Auto-connect only to an IP previously verified by SatNova.
        val rememberedIp=getSharedPreferences("satnova",Context.MODE_PRIVATE)
            .getString("last_receiver_ip",null)
        if(rememberedIp!=null && isPrivate(rememberedIp)){
            showField("État connexion","● Reconnexion à "+rememberedIp+"…")
            connect(rememberedIp)
        }else{
            showField("État connexion","● Non connecté • Options pour saisir l'IP")
            showField("Chaîne actuelle","Connectez Echolink depuis Options")
        }

        monitorWork.scheduleWithFixedDelay({
            if(verified && System.currentTimeMillis()-lastRemotePressAt>4500L) work.execute{
                if(!verified || System.currentTimeMillis()-lastRemotePressAt<=4500L)return@execute
                try{
                    requestState(3)
                    requestState(19)
                    monitorTicks++
                    if(monitorTicks%5==0)requestState(15)
                    if(monitorTicks%7==0)requestState(14)
                    if(monitorTicks%7==0)requestState(26)
                }catch(e:Exception){report("Synchronisation : "+e.message)}
            }
        }, 6, 12, TimeUnit.SECONDS)
        // Dish alignment: do not poll unless the user explicitly starts this mode.
        monitorWork.scheduleWithFixedDelay({
            if(trackingSignal && verified) work.execute{
                if(!trackingSignal || !verified)return@execute
                try{
                    requestState(14)
                    requestState(15)
                }catch(e:Exception){
                    screen.updateSignal("Mesure interrompue : "+(e.message?:"connexion"))
                }
            }
        }, 2, 3, TimeUnit.SECONDS)
        monitorWork.scheduleWithFixedDelay({
            if(trackingSignal && verified && lastSignalUpdate==0L &&
               System.currentTimeMillis()-signalTrackingStartedAt>7500L){
                screen.updateSignalReading(null,null,
                    "Firmware 1.48 : aucune valeur de force/qualité fournie par les réponses 14/15. "+
                    "La position de la parabole doit être confirmée sur l'écran TV.")
            }
        }, 8, 9, TimeUnit.SECONDS)
    }
    private fun showField(key:String,value:String)=runOnUiThread {
        fieldViews[key]?.let { view ->
            view.text=value
            if(key=="Diagnostic vidéo"){
                view.visibility=if(value.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }
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
        screen.updateZapStatus("Demande 1000 vers le récepteur…")
        sendTune(id,tvState,false)
    }
    private fun sendTune(id:String,tvState:Int,flat:Boolean){
        work.execute{
            try{
                if(!verified||socket==null)return@execute
                if(targetTuneId!=id)return@execute
                tuneMethod=if(flat)2 else 1
                val requestedRecord=channelRecords[id]
                val programId=key(requestedRecord,"ProgramId","ProgramID","ServiceID") ?: id
                val payload=if(jsonMode){
                    val req=JSONObject().put("request","1000")
                    if(flat){
                        req.put("TvState",tvState.toString()).put("ProgramId",programId)
                    }else{
                        req.put("array",JSONArray().put(JSONObject()
                            .put("TvState",tvState.toString()).put("ProgramId",programId)))
                    }
                    req.toString()
                }else {
                    if(flat)
                        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1000\"><TvState>"+
                            tvState+"</TvState><ProgramId>"+programId+"</ProgramId></Command>"
                    else
                        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1000\"><parm><TvState>"+
                            tvState+"</TvState><ProgramId>"+programId+"</ProgramId></parm></Command>"
                }
                val sock=socket ?: return@execute
                sendFrame(sock,payload)
                val msg=if(flat)"Mode alternatif 1000 envoyé. Vérification du tuner…"
                    else "Demande 1000 envoyée. Vérification du tuner…"
                report(msg)
                screen.updateZapStatus(msg)
                showField("Dernière commande envoyée","1000 • "+programId+
                    " • mode "+(if(flat) "direct" else "array")+" • état non confirmé")
                monitorWork.schedule({
                    if(!verified||targetTuneId!=id)return@schedule
                    work.execute{try{requestState(3)}catch(_:Exception){}}
                },1300,TimeUnit.MILLISECONDS)
                monitorWork.schedule({
                    if(!verified||targetTuneId!=id)return@schedule
                    if(currentChannelId==id){
                        report("Chaîne confirmée : "+(channelNames[id]?:id))
                        screen.updateZapStatus("Changement confirmé par le récepteur.")
                        targetTuneId=""
                    }else if(!flat){
                        report("Récepteur sans changement confirmé. Test alternatif…")
                        screen.updateZapStatus("Pas de changement confirmé ; test d'une autre variante…")
                        sendTune(id,tvState,true)
                    }else {
                        val detail="Changement non confirmé. Réponse 1000 : "+
                            (if(tuneAckStatus<0)"aucun ACK" else "code "+tuneAckStatus)+
                            ". Fermer les menus TV ; essayer sans SAT Chef."
                        report(detail)
                        screen.updateZapStatus(detail)
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
    private fun toggleSignalTracking(){
        if(!verified){report("Connecter le récepteur pour mesurer le signal");return}
        trackingSignal=!trackingSignal
        screen.updateSignalTracking(trackingSignal)
        if(trackingSignal){
            lastSignalUpdate=0L
            signalTrackingStartedAt=System.currentTimeMillis()
            screen.updateSignalReading(null,null,
                "Mesure du tuner actif en cours • force et qualité réelles uniquement")
            work.execute{
                try{requestState(14);requestState(15)}
                catch(e:Exception){screen.updateSignal("Erreur du tuner : "+(e.message?:"connexion"))}
            }
        }else screen.updateSignal("Mesure arrêtée par l'utilisateur. Dernière donnée conservée si reçue.")
    }
    private fun receiverPercent(record:JSONObject,vararg keys:String):Int?{
        val value=pick(record,*keys)?:return null
        val num=value.trim().removeSuffix("%").trim().toDoubleOrNull()?:return null
        // Percentage is only displayed for fields explicitly meaning a percentage.
        if(num<0.0 || num>100.0 || num.isNaN())return null
        return num.toInt()
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
    private fun key(record:JSONObject?,vararg names:String):String? =
        record?.let{pick(it,*names)}

    private fun receiverChannelNumber(id:String,record:JSONObject?):String {
        val direct=key(record,"ChannelNumber","ChannelNo","ChNum","ProgramNumber")
        if(direct!=null)return "N° "+direct
        val idx=key(record,"ChannelIndex","Index")?.toIntOrNull()
        if(idx!=null)return "N° "+(idx+1)+" (index du récepteur)"
        val inList=channelOrder.indexOf(id)
        return if(inList>=0) "Position "+(inList+1)+" (liste du récepteur)"
            else "Numéro indisponible • ID "+id.take(16)
    }
    private fun currentSatelliteAndTp(rec:JSONObject?):Pair<String,String>{
        if(rec==null)return Pair("Satellite en cours d'identification","TP en attente de lecture")
        var sat=key(rec,"SatelliteName","SatName","Satellite")
        var frequency=key(rec,"Frequency","Freq","TPFreq","FrequencyMHz")
        val directSat=key(rec,"SatIndex","SatelliteIndex","SatelliteId","SatID")
        val tpIndex=key(rec,"TPIndex","TpIndex","TPID","TransponderId")
            ?: key(rec,"ServiceID","ProgramId","ProgramID")?.takeIf{it.length>=9}?.substring(4,9)
        val tp=transponderRecords.firstOrNull{item->
            val index=key(item,"TPIndex","TpIndex","TPId","TransponderId")
            index!=null && tpIndex!=null && index.toIntOrNull()==tpIndex.toIntOrNull()
        }
        if(tp!=null)frequency=frequency ?: key(tp,"Frequency","Freq","TPFreq","FrequencyMHz")
        val satIndex=directSat ?: key(tp,"SatIndex","SatelliteIndex","SatelliteId","SatID")
        if(sat==null && satIndex!=null){
            val satRecord=satelliteRecords.firstOrNull{item->
                val index=key(item,"SatIndex","SatelliteIndex","SatelliteId","SatID")
                index!=null && index.toIntOrNull()==satIndex.toIntOrNull()
            }
            sat=key(satRecord,"SatelliteName","SatName","Name","Satellite")
        }
        val pol=key(tp,"Polarization","Pol") ?: key(rec,"Polarization","Pol")
        val sr=key(tp,"SymbolRate","SR") ?: key(rec,"SymbolRate","SR")
        val tpText=listOfNotNull(frequency?.let{it+" MHz"},pol?.let{"Pol "+it},
            sr?.let{"SR "+it}).joinToString(" • ").ifBlank{"TP non identifié par le firmware"}
        return Pair(sat?:"Satellite non communiqué par le firmware",tpText)
    }
    private fun showCurrent(){
        val id=currentChannelId
        if(id.isBlank())return
        val rec=channelRecords[id]
        showField("Chaîne actuelle",channelNames[id] ?:
            "Chaîne en identification (ID "+id.take(16)+")")
        showField("Numéro de chaîne",receiverChannelNumber(id,rec))
        val (sat,tp)=currentSatelliteAndTp(rec)
        showField("Satellite actuel",sat)
        showField("Transpondeur actuel",tp)
        val radio=key(rec,"Radio","TvState")?.toIntOrNull()
        if(radio!=null)currentTvState=if(radio==1)1 else 0
    }
    private fun handleReply(kind:Int,statusCode:Int,body:ByteArray) {
        val text=body.toString(StandardCharsets.UTF_8).trim()
        val date=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(java.util.Date())
        showField("Dernière réponse",date+" · type "+kind+" · code "+statusCode)
        showField("Données brutes reçues",if(text.isEmpty()) "(no body)" else text.take(900))
        if(kind==1040){
            showField("Retour télécommande",if(statusCode==0)
                "Réponse 1040 : code 0 • effet à vérifier sur TV"
                else "Commande télécommande rejetée • code "+statusCode)
            return
        }
        if(kind==1000){
            tuneAckStatus=statusCode
            val statusText=when(statusCode){
                0 -> "Récepteur : demande acceptée (code 0) ; on vérifie le changement."
                17 -> "Récepteur : menu/overlay ouvert (code 17). Appuyer sur Exit sur TV."
                16 -> "Récepteur : enregistrement en cours (code 16)."
                2 -> "Récepteur : format de demande refusé (code 2)."
                else -> "Récepteur : zapping refusé (code "+statusCode+")."
            }
            screen.updateZapStatus(statusText)
            if(statusCode!=0){
                report(statusText)
                if(statusCode==17 || statusCode==16) targetTuneId=""
            }
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
            streamOpened=false
            activeStreamUrl=link
            videoStatus("Flux "+Uri.parse(link).scheme+" reçu. Lecture intégrée…")
            runOnUiThread {
                val intent=Intent(this,VlcLiveActivity::class.java)
                intent.putExtra("stream_url",link)
                intent.putExtra("channel_title",channelNames[currentChannelId] ?: "Canal "+currentChannelId)
                if(liveScreenRequested){
                    liveScreenRequested=false
                    inlinePlayer?.stop()
                    startActivity(intent)
                }else{
                    activeStreamUrl=link
                    inlinePlayer?.start(link)
                    screen.showInlineState("LIVE • Flux du récepteur en cours")
                }
            }
            return
        }
        if(statusCode!=0){
            runOnUiThread { diagnostics.text="Réponse "+kind+": refus code "+statusCode }
            return
        }
        if(kind in 2000..2999){
            if(kind==2015){
                showField("Retour télécommande","Récepteur : session maître annoncée")
                report("Le récepteur annonce un contrôle maître")
                return
            }
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
                showCurrent()
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
                showCurrent()
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
                    val changed=currentChannelId!=id
                    currentChannelId=id
                    showCurrent()
                    if(changed || (activeStreamUrl==null && !streamPending &&
                        autoStreamRequestId!=id)){
                        autostartInlineStream(id)
                    }
                    if(targetTuneId==id){
                        report("Zapping confirmé : "+(channelNames[id]?:id))
                        screen.updateZapStatus("Zapping confirmé sur la chaîne "+(channelNames[id]?:id))
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
                val level=receiverPercent(info,
                    "SignalStrength","SignalLevel","SignalIntensity","StrengthPercent","SignalPercent")
                val quality=receiverPercent(info,
                    "SignalQuality","QualityLevel","Quality","QualityPercent","SignalQualityPercent")
                val ber=pick(info,"BER","Ber","BitErrorRate")
                if(level!=null||quality!=null||ber!=null){
                    val pieces=listOfNotNull(level?.let{"Force "+it+" %"},
                        quality?.let{"Qualité "+it+" %"},ber?.let{"BER "+it})
                    val signalMessage=pieces.joinToString(" · ")+" • mesuré "+date
                    showField("Signal temps réel",signalMessage)
                    screen.updateSignalReading(level,quality,
                        "Valeurs reçues du récepteur • "+date+
                        if(ber!=null)" • BER "+ber else "")
                    lastSignalUpdate=System.currentTimeMillis()
                }

                pick(info,"SoftwareVersion")?.let{showField("Firmware",it)}
                pick(info,"ProductName","ModelName")?.let{
                    receiverModel=it.trim()
                    showField("Modèle du récepteur",receiverModel.uppercase(java.util.Locale.ROOT))
                }
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
                    monitorWork.schedule({
                        if(verified)work.execute{
                            try{requestNextChannelPage()}catch(_:Exception){}
                        }
                    },420,TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun retryInlineStream(){
        if(!verified){report("Connectez le récepteur.");return}
        if(streamPending){report("Demande vidéo déjà en cours.");return}
        val id=currentChannelId
        if(id.isBlank()){
            work.execute{try{requestState(3)}catch(_:Exception){}}
            return
        }
        autoStreamRequestId=""
        streamedChannel=""
        activeStreamUrl=null
        runOnUiThread{
            inlinePlayer?.stop()
            screen.resetVideoPlaceholder()
            screen.showInlineState("Relance du flux en cours…")
        }
        autostartInlineStream(id)
    }
    private fun autostartInlineStream(id:String){
        if(!verified || id.isBlank())return
        if(streamedChannel==id && activeStreamUrl!=null)return
        if(autoStreamRequestId==id && streamPending)return
        streamedChannel=id
        activeStreamUrl=null
        autoStreamRequestId=id
        runOnUiThread{
            inlinePlayer?.stop()
            screen.resetVideoPlaceholder()
            screen.showInlineState("Initialisation Live TV pour la chaîne actuelle…")
        }
        startLiveTv()
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
        if(verified)activeStreamUrl?.let{url->
            runOnUiThread{inlinePlayer?.start(url)}
        }
    }
    override fun onStop(){
        inlinePlayer?.pause()
        super.onStop()
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
            receiverModel=model
            showField("Modèle du récepteur",receiverModel.uppercase(java.util.Locale.ROOT))
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
                try{
                    requestState(3)
                    requestState(15)
                    requestState(14)
                    requestState(19)
                }catch(e:Exception){report("Initial state: "+e.message)}
                // Satellite and TP metadata are separate; fetch them once after login,
                // without blocking the first frame or recreating large lists repeatedly.
                monitorWork.schedule({
                    if(verified)work.execute{
                        try{requestState(22);requestState(24)}
                        catch(e:Exception){report("Tuner metadata: "+e.message)}
                    }
                },5,TimeUnit.SECONDS)
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
            showField("État connexion","● Connexion échouée • Options réseau")
        }
    }
    // Same 1040/KeyValue payload as the first working SatNova Alpha.
    // Crucially, do not interleave follow-up state probes with rapid remote presses.
    private fun sendKey(value:Int){
        lastRemotePressAt=System.currentTimeMillis()
        work.execute{
            val s=socket
            if(!verified||s==null){
                report("Récepteur non connecté")
                return@execute
            }
            try{
                val payload=if(jsonMode)
                    "{\"request\":\"1040\",\"array\":[{\"KeyValue\":\"$value\"}]}"
                else
                    "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"1040\"><parm><KeyValue>$value</KeyValue></parm></Command>"
                // Same serial command queue as SatNova 0.1–0.3.
                // Do not send from a competing executor while receiver state pages load.
                sendFrame(s,payload)
                showField("Dernière commande envoyée","1040 • touche "+value+" • en attente")
                showField("Retour télécommande","Touche "+value+" transmise • contrôle du résultat sur TV")
            }catch(e:Exception){
                report("Télécommande : "+(e.message?:"échec d'envoi"))
                showField("Retour télécommande","Échec de l'envoi : "+(e.message?:"connexion"))
            }
        }
    }
    private fun wifiSubnet():String? {
        return try {
            val all=java.net.NetworkInterface.getNetworkInterfaces()
            while(all.hasMoreElements()){
                val iface=all.nextElement()
                if(!iface.isUp || iface.isLoopback)continue
                val addrs=iface.inetAddresses
                while(addrs.hasMoreElements()){
                    val addr=addrs.nextElement()
                    val host=addr.hostAddress ?: continue
                    if(addr is java.net.Inet4Address && isPrivate(host) && !host.startsWith("169.254.")){
                        return host.substringBeforeLast('.')
                    }
                }
            }
            null
        }catch(_:Exception){null}
    }
    private fun scanPrefix(prefix:String)=discoveryWork.execute {
        scanPrefixBlocking(prefix)
    }
    /** Bounded LAN-only /24 discovery of TCP port 20000, no auto-login or phantom devices. */
    private fun scanPrefixBlocking(prefix:String) {
        val parts=prefix.split('.')
        if(parts.size!=3 || parts.any{it.toIntOrNull()==null || it.toInt() !in 0..255} ||
            !isPrivate("$prefix.1")){
            screen.discoveryMessage("Préfixe privé invalide. Exemple : 192.168.1")
            return
        }
        val executor=Executors.newFixedThreadPool(16)
        val latch=java.util.concurrent.CountDownLatch(254)
        val found=java.util.concurrent.atomic.AtomicInteger(0)
        screen.discoveryMessage("Recherche en cours dans $prefix.1–254, port 20000…")
        for(suffix in 1..254){
            val host="$prefix.$suffix"
            executor.execute {
                try {
                    Socket().use{probe->
                        probe.connect(InetSocketAddress(host,20000),450)
                        found.incrementAndGet()
                        screen.addDiscovered(host,"Port 20000 joignable")
                    }
                }catch(_:Exception){}finally{latch.countDown()}
            }
        }
        latch.await(18,TimeUnit.SECONDS)
        executor.shutdownNow()
        if(found.get()==0){
            screen.discoveryMessage("Aucun récepteur joignable sur $prefix.0/24. "+
                "Vérifiez le Wi-Fi ou utilisez l'IP manuelle.")
        }
    }
    private fun discover()=discoveryWork.execute {
        screen.discoveryMessage("Recherche automatique des annonces Echolink…")
        try{
            val wifi=applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock=wifi.createMulticastLock("satnova").apply{setReferenceCounted(false)}
            lock.acquire()
            try{
                DatagramSocket(null).use{udp->
                    udp.reuseAddress=true
                    udp.bind(InetSocketAddress(25860))
                    udp.soTimeout=700
                    val until=System.nanoTime()+2_500_000_000L
                    while(System.nanoTime()<until){
                        val data=ByteArray(256)
                        val datagram=DatagramPacket(data,data.size)
                        try{
                            udp.receive(datagram)
                            if(datagram.length==108){
                                val host=datagram.address.hostAddress ?: ""
                                if(isPrivate(host)){
                                    val name=try{
                                        decode(datagram.data.copyOfRange(
                                            datagram.offset,datagram.offset+datagram.length)).first
                                    }catch(_:Exception){"Echolink probable"}
                                    screen.addDiscovered(host,name)
                                }
                            }
                        }catch(_:java.net.SocketTimeoutException){}
                    }
                }
            }finally{if(lock.isHeld)lock.release()}
        }catch(_:Exception){}
        val prefix=wifiSubnet()
        if(prefix!=null)scanPrefixBlocking(prefix)
        else screen.discoveryMessage("Sous-réseau Wi-Fi inconnu. Saisir l'IP ou utiliser Recherche avancée.")
    }
    private fun disconnect() {
        trackingSignal=false
        if(::screen.isInitialized)screen.updateSignalTracking(false)
        verified = false
        val s = socket; socket = null
        try { s?.close() } catch (_: Exception) { }
        setVerified(false)
    }
    override fun onDestroy() {
        inlinePlayer?.stop()
        disconnect()
        work.shutdownNow()
        remoteWork.shutdownNow()
        discoveryWork.shutdownNow()
        monitorWork.shutdownNow()
        super.onDestroy()
    }
}
