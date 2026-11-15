package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import org.videolan.libvlc.util.VLCVideoLayout
import org.json.JSONObject

/**
 * SatNova 0.4 visual refresh. UI only: network/protocol logic stays in MainActivity.
 * Uses platform widgets (no extra UI libraries or phone-specific assets).
 */
class SatNovaScreen(
    private val activity: Activity,
    onDiscover: () -> Unit,
    onAdvancedScan: (String) -> Unit,
    onSelectReceiver: (String) -> Unit,
    onDisconnect: () -> Unit,
    onConnect: () -> Unit,
    onWatch: () -> Unit,
    onRefresh: () -> Unit,
    onReadSatellites: () -> Unit,
    onReadTransponders: () -> Unit,
    onReadSignal: () -> Unit,
    onWatchMode: () -> Unit,
    onSignalMode: () -> Unit,
    onRestart: (String) -> Unit,
    onServers: () -> Unit,
    onNetwork: () -> Unit,
    onScan: () -> Unit,
    onChannelTune: (String, Int) -> Unit,
    onChannelRefresh: () -> Unit,
    onEpgRemote: () -> Unit,
    onTabChanged: (Int) -> Unit,
    onKey: (Int) -> Unit,
    onDishFinder: () -> Unit = {},
    onQibla: () -> Unit = {},
    onTvRemote: () -> Unit = {},
    onTvKey: (Int) -> Unit = {},
    onTvApp: (String) -> Unit = {},
    onBackgroundToggle: () -> Unit = {},
    onCatalogueReload: () -> Unit = {}
) {
    private val navy = Color.rgb(8, 12, 25)
    private val panelColor = Color.rgb(21, 27, 45)
    private val tileColor = Color.rgb(29, 36, 57)
    private val textColor = Color.rgb(248, 250, 255)
    private val secondary = Color.rgb(153, 166, 191)
    private val lavender = Color.rgb(184, 172, 255)
    private val purple = Color.rgb(113, 89, 247)
    private val blue = Color.rgb(65, 128, 232)
    private val green = Color.rgb(80, 230, 170)
    private val hairline = Color.rgb(53, 63, 91)

    val root: LinearLayout = LinearLayout(activity)
    private lateinit var catalog: CatalogPanel
    private lateinit var dashboard: UnifiedRemotePanel
    private lateinit var offlineDashboard: UnifiedRemotePanel
    private var goToChannels: (() -> Unit)? = null
    private var goToRemote: (() -> Unit)? = null
    private var goToSettings: (() -> Unit)? = null
    private var goToHome: (() -> Unit)? = null
    private lateinit var appScroller: ScrollView
    private lateinit var appNav: LinearLayout
    private lateinit var setupScroller: ScrollView
    private lateinit var discoveryResults: LinearLayout
    private val discoveredHosts=linkedSetOf<String>()
    private val discoveredRows=linkedMapOf<String,TextView>()
    private lateinit var receiverTitle: TextView
    private lateinit var connectionState: TextView
    lateinit var inlineVideo: VLCVideoLayout
        private set
    private lateinit var inlinePoster: TextView
    private lateinit var pinnedPlayer: LinearLayout
    private lateinit var previewFrame:FrameLayout
    private lateinit var modeHint:TextView
    private var chosenMode="none"
    private var section=0
    private var receiverOnline=false
    private var signalFullScreen=false
    private var signalReceiverName="Récepteur connecté"
    private var signalReceiverIp=""
    private var signalReceiverSerial=""
    private var showHomeList:(()->Unit)?=null
    private var homeListSelected=false
    private var streamHasPlayed = false
    val status: TextView
    val ip: EditText
    val connectButton: Button
    val diagnostics: TextView
    val fields = linkedMapOf<String, TextView>()

    private val Int.dp get() = (this * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(
        color: Int,
        radius: Int = 18,
        outline: Int? = null,
        colors: IntArray? = null
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, colors ?: intArrayOf(color, color)
    ).apply {
        cornerRadius = radius.dp.toFloat()
        if (outline != null) setStroke(1.dp, outline)
    }

    private fun touch(color: Int, radius: Int = 16, outline: Int? = null): RippleDrawable {
        return RippleDrawable(ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
            shape(color, radius, outline), shape(Color.WHITE, radius))
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = textColor,
        bold: Boolean = false
    ) = TextView(activity).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun stack(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun row(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun margin(
        width: Int = -1,
        height: Int = -2,
        top: Int = 0,
        bottom: Int = 0,
        left: Int = 0,
        right: Int = 0
    ) = LinearLayout.LayoutParams(width, height).apply {
        setMargins(left.dp, top.dp, right.dp, bottom.dp)
    }

    private fun card(parent: LinearLayout, top: Int = 12): LinearLayout {
        val box = stack().apply {
            setBackground(shape(panelColor, 24, hairline))
            setPadding(18.dp, 18.dp, 18.dp, 18.dp)
        }
        parent.addView(box, margin(top = top))
        return box
    }

    private fun section(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 17f, textColor, true), margin(top = 2))
        if (subtitle != null) {
            parent.addView(text(subtitle, 12f, secondary), margin(top = 6, bottom = 14))
        } else {
            val spacer = Space(activity)
            parent.addView(spacer, margin(height = 12))
        }
    }

    private fun action(
        label: String,
        color: Int = tileColor,
        foreground: Int = textColor,
        height: Int = 50,
        outline: Int? = null,
        onClick: () -> Unit
    ): TextView = text(label, 14f, foreground, true).apply {
        gravity = Gravity.CENTER
        minHeight = height.dp
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        isClickable = true
        isFocusable = true
        background = touch(color, 15, outline)
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun addWeighted(parent: LinearLayout, child: View, left: Int = 0, right: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(left.dp, 0, right.dp, 0)
        })
    }

    private fun metric(
        key: String,
        initial: String,
        size: Float = 16f
    ): LinearLayout = stack().apply {
        setBackground(shape(tileColor, 16))
        setPadding(13.dp, 13.dp, 12.dp, 13.dp)
        addView(text(key.uppercase(), 10f, secondary, true))
        val value = text(initial, size, textColor, true).apply {
            maxLines = if (key in listOf("Satellites", "Transpondeurs", "Signal temps réel")) 14
                else if (key == "Données brutes reçues") 8 else 5
            setLineSpacing(2.dp.toFloat(), 1.04f)
        }
        addView(value, margin(top = 9))
        fields[key] = value
    }

    private fun metricsRow(
        parent: LinearLayout,
        left: Triple<String, String, Float>,
        right: Triple<String, String, Float>
    ) {
        val rr = row()
        addWeighted(rr, metric(left.first, left.second, left.third), right = 5)
        addWeighted(rr, metric(right.first, right.second, right.third), left = 5)
        parent.addView(rr, margin(top = 10))
    }

    private fun remoteRow(parent: LinearLayout, buttons: List<Pair<String, Int>>, top: Int = 7) {
        val rr = row()
        buttons.forEachIndexed { i, (name, code) ->
            addWeighted(rr, action(name, if (name == "OK") purple else tileColor,
                height = if (name == "OK") 60 else 52, onClick = { remote(code) }),
                left = if (i == 0) 0 else 4, right = if (i == buttons.lastIndex) 0 else 4)
        }
        parent.addView(rr, margin(top = top))
    }

    private val remote: (Int) -> Unit = onKey
    private val connectDiscovered: (String) -> Unit = onSelectReceiver

    init {
        root.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            fitsSystemWindows = true
        }
        val scroller = ScrollView(activity).apply {
            setBackgroundColor(navy)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = true
        }
        appScroller=scroller
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val content=stack()
        scroller.addView(content, ViewGroup.LayoutParams(-1,-2))
        fun pageBox()=stack().apply{setPadding(16.dp,20.dp,16.dp,36.dp)}
        val page=pageBox()
        val satellitePage=pageBox()
        val settingsPage=pageBox()
        val homePage=pageBox()
        val pages=listOf(homePage, satellitePage, page, settingsPage)
        pages.forEachIndexed { index,view ->
            view.visibility=if(index==0)View.VISIBLE else View.GONE
            content.addView(view)
        }
        catalog = CatalogPanel(activity, onChannelTune, onReadTransponders, onReadSignal, onEpgRemote, {goToChannels?.invoke()},onCatalogueReload,onDishFinder)
        dashboard = UnifiedRemotePanel(activity,onChannelTune,onKey,onTvKey,onTvApp,onTvRemote,onCatalogueReload)
        offlineDashboard = UnifiedRemotePanel(activity,onChannelTune,onKey,onTvKey,onTvApp,onTvRemote,onCatalogueReload)
        homePage.addView(dashboard.root,margin())
        satellitePage.addView(catalog.satellitePage,margin())
        satellitePage.addView(action("⟳  Actualiser les satellites", tileColor, lavender, 46,
            onClick=onReadSatellites),margin(top=12))
        satellitePage.addView(action("☪  القبلة • Direction Qibla",tileColor,lavender,46,
            onClick=onQibla),margin(top=8))
        val nav=row().apply{
            setPadding(8.dp,7.dp,8.dp,7.dp)
            setBackgroundColor(panelColor)
        }
        val titles=listOf("⌂\nAccueil","◈\nSatellites","▣\nTélécommande","⚙\nOptions")
        val navButtons=mutableListOf<TextView>()
        fun showTab(tab:Int){
            section=tab
            catalog.showTab(if(tab==1)2 else 0)
            pages.forEachIndexed{i,p->p.visibility=if(i==tab || (tab==2 && i==0))View.VISIBLE else View.GONE}
            if(tab==2)page.visibility=View.GONE
            navButtons.forEachIndexed{i,b->
                b.setTextColor(if(i==tab)lavender else secondary)
                b.background=shape(if(i==tab)tileColor else panelColor,13)
            }
            scroller.scrollTo(0,0)
            if(tab==2 && homeListSelected)onChannelRefresh()
            if(tab==1){onChannelRefresh();onReadSatellites()}
            onTabChanged(tab)
        }
        goToChannels={showTab(2);showHomeList?.invoke()}
        goToRemote={showTab(2)}
        goToSettings={showTab(3)}
        goToHome={showTab(0)}
        titles.forEachIndexed {i,t->
            val item=text(t,11f,secondary,i==0).apply{
                gravity=Gravity.CENTER
                setPadding(3.dp,7.dp,3.dp,7.dp)
                minHeight=48.dp
                isClickable=true
                setOnClickListener{showTab(i)}
            }
            navButtons.add(item)
            nav.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        appNav=nav
        root.addView(nav,LinearLayout.LayoutParams(-1,-2))
        // An independent, full-height connection screen replaces all navigation while offline.
        val setupPage=pageBox()
        setupPage.addView(offlineDashboard.root,margin(bottom=12))
        setupScroller=ScrollView(activity).apply {
            setBackgroundColor(navy)
            isFillViewport=true
            overScrollMode=View.OVER_SCROLL_NEVER
        }
        setupScroller.addView(setupPage,ViewGroup.LayoutParams(-1,-2))
        root.addView(setupScroller,LinearLayout.LayoutParams(-1,0,1f))
        appScroller.visibility=View.GONE
        appNav.visibility=View.GONE
        setupScroller.visibility=View.VISIBLE
        showTab(0)

        // Identity
        val branding = row()
        val mark=ImageView(activity).apply{
            setImageResource(R.drawable.satnova_icon)
            contentDescription="Logo SatNova"
            scaleType=ImageView.ScaleType.FIT_CENTER
        }
        branding.addView(mark,margin(width=40,height=40,right=9))
        val brandTexts = stack()
        brandTexts.addView(text("SATNOVA", 18f, Color.WHITE, true))
        val receiverModel=text("Récepteur non identifié",10f,lavender,true)
        brandTexts.addView(receiverModel,margin(top=5))
        fields["Modèle du récepteur"]=receiverModel
        branding.addView(brandTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val version = text("V2 • bêta", 10f, lavender, true).apply {
            gravity = Gravity.CENTER
            setPadding(9.dp, 7.dp, 9.dp, 7.dp)
            background = shape(tileColor, 50, hairline)
        }
        branding.addView(version)
        page.addView(branding, margin(bottom = 15))
        page.addView(action("▣  Télécommande Lifemaxx • indépendante du décodeur",tileColor,lavender,54,
            onClick=onTvRemote),margin(bottom=13))

        // Receiver and LAN connection
        setupPage.addView(text("SATNOVA • CONNEXION",25f,textColor,true),
            margin(top=16,bottom=6))
        setupPage.addView(text("OUTILS D'INSTALLATION • SANS RÉCEPTEUR",15f,green,true),
            margin(top=12,bottom=10))
        setupPage.addView(text("Orientation des satellites, LNB et Qibla. Utilisables sans décodeur.",
            13f,secondary),margin(bottom=8))
        setupPage.addView(action("◎  Satellite • caméra / GPS",tileColor,lavender,58,
            onClick=onDishFinder),margin(top=8))
        setupPage.addView(action("☪  القبلة • Direction Qibla",tileColor,lavender,53,
            onClick=onQibla),margin(top=8))
        setupPage.addView(action("▣  Télécommande Lifemaxx • Android TV",tileColor,lavender,58,
            onClick=onTvRemote),margin(top=8))
        setupPage.addView(text("CONNEXION AU RÉCEPTEUR • FACULTATIVE",12f,lavender,true),
            margin(top=24,bottom=3))
        setupPage.addView(text("Connecter un récepteur",23f,lavender,true),
            margin(top=18,bottom=10))
        setupPage.addView(text("Si aucun décodeur n'est connecté, la vidéo et les commandes sont masquées. "+
            "Connectez par IP ou recherchez un récepteur réel sur votre Wi-Fi.",
            13f,secondary),margin(bottom=18))
        val connection = card(page, top = 0)
        val connectionHeading = row()
        receiverTitle=text("Récepteur non identifié",13f,lavender,true)
        connectionHeading.addView(receiverTitle,
            LinearLayout.LayoutParams(0, -2, 1f))
        connectionState=text("● Non connecté",11f,secondary,true)
        connectionHeading.addView(connectionState)
        connection.addView(connectionHeading)
        status = text("Non connecté • Même réseau Wi-Fi requis", 13f, secondary)
        connection.addView(status, margin(top = 10))
        ip = EditText(activity).apply {
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(secondary)
            textSize = 15f
            hint = "IP du récepteur"
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(14.dp, 0, 12.dp, 0)
            background = shape(tileColor, 13, hairline)
            setSelectAllOnFocus(true)
        }
        connection.addView(ip, margin(height = 48, top = 14))
        val connectLine = row()
        val discover = action("⌕  Recherche auto", height = 48, onClick = onDiscover)
        addWeighted(connectLine, discover, right = 5)
        connectButton = Button(activity).apply {
            text = "Connect"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = 0
            minimumHeight = 0
            background = touch(purple, 15)
            setOnClickListener { onConnect() }
        }
        addWeighted(connectLine, connectButton, left = 5)
        connection.addView(connectLine, margin(top = 10))

        // Accueil starts with CURRENT receiver programme, not its connection settings.
        val hero = card(page, top = 2)
        hero.background = shape(panelColor, 24, colors = intArrayOf(
            Color.rgb(33, 30, 78), Color.rgb(22, 38, 69)
        ))
        val top = row()
        top.addView(text("●  EN COURS SUR LE RÉCEPTEUR", 11f, green, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("LIVE", 11f, lavender, true))
        hero.addView(top)
        val connectionBadge = text("● Connexion au récepteur…", 12f, lavender).apply {
            setPadding(0, 12.dp, 0, 8.dp)
            isClickable = true
            setOnClickListener { goToSettings?.invoke() }
        }
        connectionBadge.visibility=View.GONE
        hero.addView(connectionBadge)
        fields["État connexion"] = connectionBadge


        val now = text("Lecture de la chaîne en cours…", 24f, Color.WHITE, true).apply {
            maxLines = 2
        }
        hero.addView(now, margin(top = 7))
        fields["Chaîne actuelle"] = now

        // The parent frame is always 16:9: VLCVideoLayout cannot collapse the preview.
        val videoFrame=FrameLayout(activity).apply {
            background=shape(Color.BLACK,15)
            clipToOutline=true
            isClickable=true
            isFocusable=true
            contentDescription="Vidéo de la chaîne, toucher pour réessayer"
            setOnClickListener { onWatch() }
        }
        previewFrame=videoFrame
        inlineVideo=VLCVideoLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn=true
        }
        videoFrame.addView(inlineVideo,FrameLayout.LayoutParams(-1,-1))
        inlinePoster=text("SATNOVA • LIVE\n\nAperçu du récepteur en attente\nTouchez pour relancer la vidéo",14f,lavender,true).apply {
            gravity=Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(8.dp,12.dp,8.dp,12.dp)
        }
        videoFrame.addView(inlinePoster,FrameLayout.LayoutParams(-1,-1))
        val videoHeight=((activity.resources.displayMetrics.widthPixels-68.dp)*9/16)
            .coerceAtLeast(170.dp)
        // Keep the native video surface attached and visible when switching tabs.
        // Hiding its page can synchronously wait for the decoder to release a surface.
        pinnedPlayer=stack().apply{setPadding(16.dp,8.dp,16.dp,8.dp)}
        // Mode selection lives in the bottom navigation; no duplicate restart shortcuts.
        modeHint=text("",12f,lavender).apply{visibility=View.GONE}
        pinnedPlayer.addView(videoFrame,margin(height=videoHeight.coerceAtMost(190.dp)))
        pinnedPlayer.visibility=View.GONE
        root.addView(pinnedPlayer,0,margin())
        page.removeView(branding)
        root.addView(branding,0,margin(height=52,left=16,right=16))
        val inlineState=text("En attente du flux vidéo du récepteur…",12f,secondary)
        pinnedPlayer.addView(inlineState,margin(top=6))
        fields["Lecture intégrée"]=inlineState

        val channelNumber=text("Numéro en cours de lecture",13f,lavender,true)
        hero.addView(channelNumber,margin(top=12))
        fields["Numéro de chaîne"]=channelNumber
        val satName=text("Satellite en cours de lecture…",13f,textColor)
        hero.addView(satName,margin(top=6))
        fields["Satellite actuel"]=satName
        val tpInfo=text("Fréquence en attente…",12f,secondary)
        hero.addView(tpInfo,margin(top=4))
        hero.addView(action("◎  Pointer le satellite avec la caméra",tileColor,lavender,43,
            onClick=onDishFinder),margin(top=9))
        hero.addView(action("☪  القبلة • Qibla",tileColor,lavender,43,
            onClick=onQibla),margin(top=6))
        fields["Transpondeur actuel"]=tpInfo

        pinnedPlayer.addView(catalog.signalView,margin(top=2,bottom=8))
        catalog.signalView.visibility=View.GONE

        val videoDiagnostic=text("",11f,secondary).apply { visibility=View.GONE }

        fields["Diagnostic vidéo"]=videoDiagnostic

        // Mini remote on Accueil. Key codes 1/2 verified for navigation / channel selection
        // on G-MScreen firmware; previous 69/70 were not consistently supported.
        hero.visibility=View.GONE // V2: metadata is on the unified remote, not in the old video page.
        val mini = card(page, top = 14)
        mini.visibility=View.GONE
        lateinit var applyMode:(Boolean,Boolean)->Unit
        val switcher=row()
        val remoteMode=action("Télécommande",height=44,onClick={applyMode(false,true)})
        val listMode=action("Liste",height=44,onClick={applyMode(true,true)})
        addWeighted(switcher,remoteMode,right=4)
        addWeighted(switcher,listMode,left=4)
        mini.addView(switcher,margin(bottom=10))
        val remoteContent=stack()
        mini.addView(remoteContent)
        mini.addView(catalog.channelsPage)

        remoteRow(remoteContent,listOf("CH −" to 70, "MUTE" to 23, "CH +" to 69))
        remoteRow(remoteContent,listOf("VOL −" to 36,"▲" to 1,"VOL +" to 35))
        remoteRow(remoteContent,listOf("◀" to 3,"OK" to 5,"▶" to 4))
        remoteRow(remoteContent,listOf("MENU" to 6,"▼" to 2,"EXIT" to 7))
        val remoteFeedback = text("Dernière commande : aucune",12f,secondary)
        remoteContent.addView(remoteFeedback,margin(top=10))
        fields["Retour télécommande"]=remoteFeedback
        val extraInline=stack().apply {visibility=View.GONE}
        remoteRow(extraInline,listOf("POWER" to 42,"EPG" to 32,"INFO" to 57))
        listOf(listOf(1,2,3),listOf(4,5,6),listOf(7,8,9)).forEach { digits ->
            remoteRow(extraInline,digits.map { it.toString() to (it+12) })
        }
        remoteRow(extraInline,listOf("FAV" to 33,"0" to 12,"RECALL" to 29))
        remoteRow(extraInline,listOf("TV / RADIO" to 22,"SUB" to 31,"TXT" to 34))
        remoteRow(extraInline,listOf("ROUGE" to 8,"VERT" to 9,"JAUNE" to 10,"BLEU" to 11))
        remoteContent.addView(extraInline)
        val expandRemote=action("⌄  Afficher plus de commandes",tileColor,lavender,46,
            onClick={
                val opening=extraInline.visibility!=View.VISIBLE
                extraInline.visibility=if(opening)View.VISIBLE else View.GONE
            })
        remoteContent.addView(expandRemote,margin(top=12))

        applyMode={list,notify->
            homeListSelected=list
            remoteContent.visibility=if(list)View.GONE else View.VISIBLE
            catalog.setHomeListVisible(list)
            remoteMode.background=touch(if(list)tileColor else purple,14)
            listMode.background=touch(if(list)purple else tileColor,14)
            activity.getSharedPreferences("satnova",0).edit().putBoolean("home_list",list).apply()
            if(list && notify)onChannelRefresh()
        }
        showHomeList={applyMode(true,true)}
        applyMode(activity.getSharedPreferences("satnova",0).getBoolean("home_list",false),false)

        // Move advanced connection settings away from the first screen.
        page.removeView(connection)
        setupPage.addView(connection,margin(top=8))
        setupPage.addView(action("⌕  Recherche automatique",tileColor,lavender,50,
            onClick=onDiscover),margin(top=12))
        setupPage.addView(action("⚙  Recherche avancée • plage IP",tileColor,lavender,50,
            onClick={
                val defaultPrefix=ip.text.toString().trim().substringBeforeLast('.', "192.168.1")
                val field=EditText(activity).apply {
                    setText(defaultPrefix)
                    setSingleLine(true)
                    inputType=InputType.TYPE_CLASS_PHONE or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setSelectAllOnFocus(true)
                }
                AlertDialog.Builder(activity).setTitle("Recherche avancée")
                    .setMessage("Préfixe d'un sous-réseau local, par exemple 192.168.1. "+
                        "Scan des adresses .1 à .254 sur le port 20000.")
                    .setView(field)
                    .setPositiveButton("Démarrer"){_,_->
                        onAdvancedScan(field.text.toString().trim().trimEnd('.'))
                    }
                    .setNegativeButton("Annuler",null).show()
            }),margin(top=10))
        setupPage.addView(text("RÉCEPTEURS DÉTECTÉS",12f,lavender,true),
            margin(top=22,bottom=10))
        discoveryResults=stack()
        discoveryResults.addView(text("Aucun récepteur détecté. Lancez une recherche.",13f,secondary))
        setupPage.addView(discoveryResults)
        val changeReceiver=card(settingsPage,top=10)
        section(changeReceiver,"Connexion","Gérer la session du récepteur connecté")
        changeReceiver.addView(action("Déconnecter / changer de récepteur",tileColor,lavender,47,
            onClick=onDisconnect))

        val networkCard=card(settingsPage,top=14)
        section(networkCard,"Connexion automatique","Le Wi-Fi relance la connexion au dernier récepteur validé.")
        val keepBackground=action(
            if(activity.getSharedPreferences("satnova",0).getBoolean("background_link",false))
                "✓  Maintien en arrière-plan : activé" else "Maintien en arrière-plan : désactivé",
            tileColor,lavender,48,onClick={})
        keepBackground.setOnClickListener{
            onBackgroundToggle()
            keepBackground.text=if(activity.getSharedPreferences("satnova",0)
                .getBoolean("background_link",false))"✓  Maintien en arrière-plan : activé"
                else "Maintien en arrière-plan : désactivé"
        }
        networkCard.addView(keepBackground)
        networkCard.addView(text("Optionnel. La vidéo s'arrête en arrière-plan ; la connexion locale peut être restaurée au retour du Wi-Fi.",11f,secondary),margin(top=8))
        val restartCard=card(settingsPage,top=14)
        restartCard.addView(action("⟳  Redémarrer",tileColor,lavender,50,onClick={
            AlertDialog.Builder(activity).setTitle("Redémarrer")
                .setItems(arrayOf("Appareil", "Application", "Tout")){_,which->
                    onRestart(listOf("device","application","all")[which])
                }.setNegativeButton("Annuler",null).show()
        }))

        // Diagnostics hidden from the primary UI but still kept live.
        val advanced = card(settingsPage, top = 14)
        val diagTitle = action("⌄   Informations techniques", panelColor, secondary, 44,
            onClick = {})
        advanced.addView(diagTitle)
        val diagBody = stack().apply { visibility = View.GONE }
        diagBody.addView(text("ÉTAT / PROTOCOLE", 11f, lavender, true), margin(top = 12))
        diagnostics = text("Login non vérifié", 13f, secondary)
        diagBody.addView(diagnostics, margin(top = 7))
        diagBody.addView(videoDiagnostic,margin(top=6))
        val transportDiagnostic=text("Transport vidéo : en attente",12f,secondary)
        val retainedDiagnostic=text("",12f,secondary)
        fields["Dernier diagnostic vidéo"]=retainedDiagnostic
        diagBody.addView(retainedDiagnostic,margin(top=8))
        fields["Diagnostic transport"]=transportDiagnostic
        diagBody.addView(transportDiagnostic,margin(top=8))
        diagBody.addView(action("Copier le diagnostic vidéo",tileColor,lavender,44,onClick={
            val value=retainedDiagnostic.text.toString().takeIf{it.isNotBlank()}?:
                ("SatNova 1.25\n"+fields["Chaîne actuelle"]?.text+"\n"+
                fields["Lecture intégrée"]?.text+"\n"+videoDiagnostic.text+"\n"+transportDiagnostic.text)
            val clipboard=activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic SatNova",value))
            android.widget.Toast.makeText(activity,"Diagnostic copié",android.widget.Toast.LENGTH_SHORT).show()
        }),margin(top=10))
        listOf(
            "Firmware" to "En attente",
            "Dernière réponse" to "—",
            "Dernière commande envoyée" to "Aucune",
            "Données brutes reçues" to "Aucune"
        ).forEach { (key, initial) ->
            diagBody.addView(metric(key, initial, if (key == "Données brutes reçues") 11f else 14f),
                margin(top = 9))
        }
        diagTitle.setOnClickListener {
            val opening = diagBody.visibility != View.VISIBLE
            diagBody.visibility = if (opening) View.VISIBLE else View.GONE
            diagTitle.text = if (opening) "⌃   Masquer les informations" else
                "⌄   Informations techniques"
        }
        advanced.addView(diagBody)
        settingsPage.addView(text("SATNOVA  •  ALPHA BUILD  •  LOCAL ONLY", 10f, secondary, true),
            margin(top = 24).apply { gravity = Gravity.CENTER_HORIZONTAL })
        setUseMode("none")
    }
    fun updateChannels(rows:List<JSONObject>,complete:Boolean){
        activity.runOnUiThread{
            catalog.updateChannels(rows,complete)
            dashboard.channels(rows,complete)
            offlineDashboard.channels(rows,complete)
        }
    }
    fun updateSatellites(rows:List<JSONObject>){
        activity.runOnUiThread{catalog.updateSatellites(rows)}
    }
    fun updateTransponders(rows:List<JSONObject>){
        activity.runOnUiThread{catalog.updateTps(rows)}
    }
    fun updateSignal(message:String){
        activity.runOnUiThread{catalog.updateSignal(message)}
    }

    /** V2 is control-only: the receiver tuner may be measured without opening a stream. */
    fun setUseMode(mode:String)=activity.runOnUiThread {
        chosenMode=mode
        pinnedPlayer.visibility=View.GONE
        appScroller.visibility=if(receiverOnline)View.VISIBLE else View.GONE
        appNav.visibility=if(receiverOnline)View.VISIBLE else View.GONE
    }
    fun showBrowseReady()=activity.runOnUiThread {
        pinnedPlayer.visibility=View.GONE
        appScroller.visibility=if(receiverOnline)View.VISIBLE else View.GONE
    }
    fun showSignalReady()=activity.runOnUiThread {
        pinnedPlayer.visibility=View.GONE
        catalog.signalView.visibility=View.GONE
        appScroller.visibility=if(receiverOnline)View.VISIBLE else View.GONE
        appNav.visibility=if(receiverOnline)View.VISIBLE else View.GONE
    }
    fun updateSignalContext(channel:String,sat:String,tp:String)=activity.runOnUiThread{catalog.updateSignalContext(channel,sat,tp)}
    fun setReceiverDetails(host:String,serial:String?=null,resetSerial:Boolean=false)=activity.runOnUiThread{
        signalReceiverIp=host
        dashboard.useReceiver(host)
        offlineDashboard.useReceiver(host)
        if(resetSerial)signalReceiverSerial=""
        if(!serial.isNullOrBlank())signalReceiverSerial=serial
        catalog.updateSignalDevice(signalReceiverName,signalReceiverIp,signalReceiverSerial)
    }
    fun setSignalMonitoring(active:Boolean)=activity.runOnUiThread{catalog.setSignalMonitoring(active)}
    fun updateTunerSignal(value:ReceiverTelemetry.Signal,at:Long)=activity.runOnUiThread{
        catalog.updateTunerSignal(value,at)
        dashboard.strength(value)
        offlineDashboard.strength(value)
    }
    fun close(){catalog.close();dashboard.close();offlineDashboard.close()}
    fun setTvRemoteOnline(online:Boolean)=activity.runOnUiThread{
        dashboard.tvReady(online);offlineDashboard.tvReady(online)
    }
    fun updateRemoteNow(id:String,name:String,number:String,satellite:String,tp:String,programme:String?,upNext:String?)=activity.runOnUiThread{
        dashboard.current(id,name,number,satellite,tp,programme,upNext)
        offlineDashboard.current(id,name,number,satellite,tp,programme,upNext)
    }
    fun setCurrentChannel(id:String)=activity.runOnUiThread{catalog.setCurrentChannel(id)}

    fun updateZapStatus(message:String){
        activity.runOnUiThread{
            catalog.updateZapStatus(message)
            dashboard.zapFeedback(message)
            offlineDashboard.zapFeedback(message)
        }
    }

    /** Sole entry view until a verified TCP login has completed. */
    fun setReceiverOnline(online:Boolean) = activity.runOnUiThread {
        receiverOnline=online
        dashboard.receiverReady(online)
        offlineDashboard.receiverReady(online)
        if(!online){signalReceiverIp="";signalReceiverSerial=""}
        connectionState.text=if(online)"● Connecté" else "● Non connecté"
        connectionState.setTextColor(if(online)green else secondary)
        if(!online)setReceiverIdentity(null)
        pinnedPlayer.visibility=View.GONE
        appScroller.visibility=if(online)View.VISIBLE else View.GONE
        appNav.visibility=if(online)View.VISIBLE else View.GONE
        setupScroller.visibility=if(online)View.GONE else View.VISIBLE
        if(online)goToHome?.invoke()
    }
    fun setReceiverIdentity(model:String?)=activity.runOnUiThread {
        val identity=model?.trim()?.takeIf{it.isNotEmpty()} ?: "Récepteur non identifié"
        receiverTitle.text=identity
        fields["Modèle du récepteur"]?.text=identity
        signalReceiverName=identity
        catalog.updateSignalDevice(signalReceiverName,signalReceiverIp,signalReceiverSerial)
    }
    fun showConnectionAttempt()=activity.runOnUiThread {
        connectionState.text="● Connexion…"
        connectionState.setTextColor(lavender)
    }
    fun setVideoPlaying(playing:Boolean)=activity.runOnUiThread {
        if(playing)streamHasPlayed=true
        inlinePoster.visibility=if(streamHasPlayed)View.GONE else View.VISIBLE
    }
    fun resetVideoPlaceholder()=activity.runOnUiThread {
        streamHasPlayed=false
        inlinePoster.visibility=View.VISIBLE
    }
    fun showInlineState(message:String)=activity.runOnUiThread {
        fields["Lecture intégrée"]?.text=message
    }
    fun beginDiscovery()=activity.runOnUiThread {
        discoveredHosts.clear()
        discoveredRows.clear()
        discoveryResults.removeAllViews()
        discoveryResults.addView(text("Recherche en cours…",13f,secondary))
    }
    fun markDiscoveryFailed(host:String)=activity.runOnUiThread {
        discoveredRows[host]?.apply{
            text="Connexion échouée • "+host+"\nToucher pour réessayer"
            contentDescription=text.toString()
            setTextColor(secondary)
        }
    }
    fun addDiscovered(host:String,model:String?=null)=activity.runOnUiThread {
        if(!discoveredHosts.add(host))return@runOnUiThread
        if(discoveredHosts.size==1)discoveryResults.removeAllViews()
        val seen=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.FRANCE).format(java.util.Date())
        val item=action((model?:"Appareil réseau à vérifier")+" • "+host+
            "\nVu à "+seen+" • connexion non vérifiée",
            tileColor,lavender,72,onClick={connectDiscovered(host)})
        discoveredRows[host]=item
        discoveryResults.addView(item,margin(bottom=8))
    }
    fun discoveryMessage(message:String)=activity.runOnUiThread {
        if(discoveredHosts.isEmpty()){
            discoveryResults.removeAllViews()
            discoveryResults.addView(text(message,13f,secondary))
        }
    }

}



