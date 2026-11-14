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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import org.videolan.libvlc.util.VLCVideoLayout
import androidx.media3.ui.PlayerView
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
    onSwitchPlayer: () -> Unit,
    onClassicPlayer: () -> Unit,
    onRefresh: () -> Unit,
    onReadSatellites: () -> Unit,
    onReadTransponders: () -> Unit,
    onReadSignal: () -> Unit,
    onServers: () -> Unit,
    onNetwork: () -> Unit,
    onScan: () -> Unit,
    onChannelTune: (String, Int) -> Unit,
    onChannelRefresh: () -> Unit,
    onEpgRemote: () -> Unit,
    onKey: (Int) -> Unit
) {
    private val navy = Color.rgb(10, 11, 28)
    private val panelColor = Color.rgb(21, 19, 43)
    private val tileColor = Color.rgb(42, 37, 74)
    private val textColor = Color.rgb(248, 250, 255)
    private val secondary = Color.rgb(153, 166, 191)
    private val lavender = Color.rgb(202, 171, 255)
    private val purple = Color.rgb(120, 69, 198)
    private val blue = Color.rgb(65, 128, 232)
    private val green = Color.rgb(80, 230, 170)
    private val hairline = Color.rgb(53, 63, 91)

    val root: LinearLayout = LinearLayout(activity)
    private lateinit var catalog: CatalogPanel
    private var goToChannels: (() -> Unit)? = null
    private var goToRemote: (() -> Unit)? = null
    private var goToSettings: (() -> Unit)? = null
    private var goToHome: (() -> Unit)? = null
    private lateinit var appScroller: ScrollView
    private lateinit var appNav: LinearLayout
    private lateinit var setupScroller: ScrollView
    private lateinit var discoveryResults: LinearLayout
    private val discoveredHosts=linkedSetOf<String>()
    lateinit var inlineVideo: VLCVideoLayout
        private set
    lateinit var inlineExoVideo: PlayerView
        private set
    private lateinit var inlinePoster: TextView
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
        fun pageBox()=stack().apply{setPadding(10.dp,10.dp,10.dp,18.dp)}
        val page=pageBox()
        val channelsPage=pageBox()
        val satellitePage=pageBox()
        val remotePage=pageBox()
        val settingsPage=pageBox()
        val pages=listOf(page, channelsPage, satellitePage, remotePage, settingsPage)
        pages.forEachIndexed { index,view ->
            view.visibility=if(index==0)View.VISIBLE else View.GONE
            content.addView(view)
        }
        catalog = CatalogPanel(activity, onChannelTune, onReadTransponders, onReadSignal, onEpgRemote, {goToChannels?.invoke()})
        channelsPage.addView(catalog.channelsPage,margin())
        satellitePage.addView(catalog.satellitePage,margin())
        satellitePage.addView(action("⟳  Actualiser les satellites", tileColor, lavender, 46,
            onClick=onReadSatellites),margin(top=12))
        val nav=row().apply{
            setPadding(8.dp,7.dp,8.dp,7.dp)
            setBackgroundColor(panelColor)
        }
        val titles=listOf("⌂\nAccueil","▣\nChaînes","◈\nSatellites","▤\nRemote","⚙\nOptions")
        val navButtons=mutableListOf<TextView>()
        fun showTab(tab:Int){
            pages.forEachIndexed{i,p->p.visibility=if(i==tab)View.VISIBLE else View.GONE}
            navButtons.forEachIndexed{i,b->
                b.setTextColor(if(i==tab)lavender else secondary)
                b.background=shape(if(i==tab)tileColor else panelColor,13)
            }
            scroller.scrollTo(0,0)
            if(tab==1){
                catalog.showChannels()
                onChannelRefresh()
            }
            if(tab==2){
                catalog.showSatellites()
                onReadSatellites()
            }
        }
        goToChannels={showTab(1)}
        goToRemote={showTab(3)}
        goToSettings={showTab(4)}
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

        // Brand from the approved SatNova purple-black receiver dashboard design.
        val branding=row().apply{setPadding(6.dp,10.dp,6.dp,12.dp)}
        val mark=text("◈",27f,lavender,true).apply{
            gravity=Gravity.CENTER
            background=shape(Color.rgb(33,24,60),17,Color.rgb(91,51,150))
        }
        branding.addView(mark,margin(width=44,height=44,right=10))
        val brandTexts=stack()
        brandTexts.addView(text("SATNOVA  •  LIVE TV",21f,textColor,true))
        val actualModel=text("Récepteur : identification…",11f,lavender,true).apply{
            maxLines=2
        }
        brandTexts.addView(actualModel,margin(top=4))
        fields["Modèle du récepteur"]=actualModel
        branding.addView(brandTexts,LinearLayout.LayoutParams(0,-2,1f))
        val version=text("α 1.6.1",10f,lavender,true).apply{
            gravity=Gravity.CENTER
            setPadding(9.dp,7.dp,9.dp,7.dp)
            background=shape(tileColor,24,hairline)
        }
        branding.addView(version)
        page.addView(branding,margin(bottom=3))

        // Offline UI follows the approved connection design. No demo/fake receivers.
        val setupHead=row().apply{setPadding(6.dp,11.dp,6.dp,14.dp)}
        val setupLogo=text("◈",29f,lavender,true).apply{
            gravity=Gravity.CENTER
            background=shape(Color.rgb(42,25,73),16,Color.rgb(107,66,168))
        }
        setupHead.addView(setupLogo,margin(width=48,height=48,right=12))
        setupHead.addView(text("SATNOVA  •  LIVE TV",21f,textColor,true),
            LinearLayout.LayoutParams(0,-2,1f))
        setupPage.addView(setupHead,margin(bottom=10))
        setupPage.addView(text("Connecter un récepteur",22f,textColor,true),
            margin(left=7,bottom=13))
        val welcome=card(setupPage,top=0).apply{
            background=shape(Color.rgb(26,20,51),24,Color.rgb(82,53,136),
                colors=intArrayOf(Color.rgb(36,25,68),Color.rgb(17,20,45)))
            setPadding(14.dp,22.dp,14.dp,20.dp)
        }
        val receiverIcon=text("⌁\n▣",45f,lavender,true).apply{
            gravity=Gravity.CENTER
            background=shape(Color.rgb(42,26,75),34,Color.rgb(100,65,167))
        }
        welcome.addView(receiverIcon,margin(width=120,height=120,top=7,bottom=16).apply{
            gravity=Gravity.CENTER_HORIZONTAL
        })
        welcome.addView(text("Aucun récepteur connecté",20f,textColor,true).apply{
            gravity=Gravity.CENTER
        },margin(bottom=10))
        welcome.addView(text(
            "Connectez votre récepteur par son adresse IP ou recherchez-le sur le réseau local.",
            12f,secondary).apply{gravity=Gravity.CENTER},margin(bottom=4))

        val connection = card(setupPage, top = 0)
        val connectionHeading = row()
        connectionHeading.addView(text("▣  Connexion manuelle (IP)", 13f, lavender, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        connectionHeading.addView(text("LOCAL", 11f, green, true))
        connection.addView(connectionHeading)
        status = text("Non connecté • Même réseau Wi-Fi requis", 13f, secondary)
        connection.addView(status, margin(top = 10))
        ip = EditText(activity).apply {
            setText("")
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
        val discover = action("⌕  Chercher", height = 48, onClick = onDiscover)
        addWeighted(connectLine, discover, right = 5)
        connectButton = Button(activity).apply {
            text = "Connecter"
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

        // The connected home exactly follows: channel -> real live preview ->
        // connection/tuner metadata -> real remote wheel, not a static mock image.
        val hero=card(page,top=1).apply{
            setPadding(12.dp,13.dp,12.dp,13.dp)
            background=shape(Color.rgb(24,21,52),24,Color.rgb(68,51,119),
                colors=intArrayOf(Color.rgb(34,25,66),Color.rgb(18,28,57)))
        }
        val header=row()
        val smallTv=text("▣",18f,lavender,true).apply{
            gravity=Gravity.CENTER
            background=shape(tileColor,12)
        }
        header.addView(smallTv,margin(width=36,height=36,right=9))
        val now=text("Lecture de la chaîne actuelle…",21f,Color.WHITE,true).apply{
            maxLines=2
        }
        header.addView(now,LinearLayout.LayoutParams(0,-2,1f))
        hero.addView(header)
        fields["Chaîne actuelle"]=now

        // A real 16:9 VLCVideoLayout is retained under this tappable poster.
        val videoFrame=FrameLayout(activity).apply{
            background=shape(Color.BLACK,17,Color.rgb(76,61,122))
            clipToOutline=false // TextureView in ScrollView must not be clipped
            isClickable=true
            contentDescription="Vidéo du récepteur • toucher pour relancer"
            setOnClickListener{onWatch()}
        }
        inlineExoVideo=PlayerView(activity).apply{
            setBackgroundColor(Color.BLACK)
            useController=false
            keepScreenOn=true
            visibility=View.GONE
        }
        videoFrame.addView(inlineExoVideo,FrameLayout.LayoutParams(-1,-1))
        inlineVideo=VLCVideoLayout(activity).apply{
            setBackgroundColor(Color.BLACK)
            keepScreenOn=true
        }
        videoFrame.addView(inlineVideo,FrameLayout.LayoutParams(-1,-1))
        inlinePoster=text("◈  SATNOVA  •  LIVE\n\nEn attente du flux réel du récepteur\nTouchez pour réessayer",13f,lavender,true).apply{
            gravity=Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        videoFrame.addView(inlinePoster,FrameLayout.LayoutParams(-1,-1))
        val videoHeight=((activity.resources.displayMetrics.widthPixels-53.dp)*9/16)
            .coerceAtLeast(160.dp)
        hero.addView(videoFrame,margin(height=videoHeight,top=10))

        val liveRow=row().apply{setPadding(2.dp,10.dp,2.dp,3.dp)}
        val inlineState=text("● LIVE • En attente du flux…",11f,lavender,true).apply{
            maxLines=2
        }
        liveRow.addView(inlineState,LinearLayout.LayoutParams(0,-2,1f))
        fields["Lecture intégrée"]=inlineState
        val connectionBadge=text("● Connexion…",10f,green,true).apply{
            setPadding(8.dp,7.dp,8.dp,7.dp)
            background=shape(Color.rgb(18,49,43),14,Color.rgb(52,132,84))
            isClickable=true
            setOnClickListener{goToSettings?.invoke()}
        }
        liveRow.addView(connectionBadge,margin(left=6))
        fields["État connexion"]=connectionBadge
        hero.addView(liveRow)

        val info=row().apply{
            setPadding(8.dp,9.dp,8.dp,9.dp)
            background=shape(Color.rgb(26,24,51),14,Color.rgb(75,57,119))
        }
        val channelNumber=text("Canal : lecture en cours…",11f,textColor).apply{
            maxLines=2;gravity=Gravity.CENTER
        }
        info.addView(channelNumber,LinearLayout.LayoutParams(0,-2,1f))
        fields["Numéro de chaîne"]=channelNumber
        val satName=text("Satellite : en attente",11f,lavender).apply{
            maxLines=2;gravity=Gravity.CENTER
        }
        info.addView(satName,LinearLayout.LayoutParams(0,-2,1f))
        fields["Satellite actuel"]=satName
        val tpInfo=text("Fréquence : en attente",11f,textColor).apply{
            maxLines=2;gravity=Gravity.CENTER
        }
        info.addView(tpInfo,LinearLayout.LayoutParams(0,-2,1f))
        fields["Transpondeur actuel"]=tpInfo
        hero.addView(info,margin(top=9))

        val program=text("EPG : non communiqué par le récepteur",11f,secondary)
        hero.addView(program,margin(top=8))
        fields["Programme en cours / EPG"]=program
        val videoDiagnostic=text("",10f,secondary).apply{visibility=View.GONE}
        hero.addView(videoDiagnostic,margin(top=5))
        fields["Diagnostic vidéo"]=videoDiagnostic
        val playbackActions=row()
        val retryButton=action("↻ Relancer la TV",tileColor,lavender,43,onClick=onWatch)
        playbackActions.addView(retryButton,LinearLayout.LayoutParams(0,43.dp,1f).apply{
            rightMargin=4.dp
        })
        val androidButton=action("▶ Lecteur Android",tileColor,lavender,43,
            onClick=onSwitchPlayer)
        playbackActions.addView(androidButton,LinearLayout.LayoutParams(0,43.dp,1f).apply{
            leftMargin=4.dp
        })
        hero.addView(playbackActions,margin(top=8))
        hero.addView(action("♬  VLC classique 0.8 • Test image et son MP2",
            tileColor,lavender,42,onClick=onClassicPlayer),margin(top=7))

        // Purple remote card inspired by the supplied mockup, with a native hit-tested D-pad.
        val mini=card(page,top=12).apply{
            setPadding(11.dp,12.dp,11.dp,13.dp)
            background=shape(Color.rgb(21,19,46),24,Color.rgb(86,55,142),
                colors=intArrayOf(Color.rgb(37,29,68),Color.rgb(19,18,41)))
        }
        // Readable full-width controls replace the formerly clipped blank side capsules.
        // The native circular directional pad remains interactive and keeps original codes.
        val wheel=SatNovaRemoteWheel(activity,remote).apply{
            contentDescription="Navigation : HAUT, BAS, GAUCHE, DROITE, OK pour valider"
        }
        val wheelRow=row()
        wheelRow.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f))
        wheelRow.addView(wheel,LinearLayout.LayoutParams(198.dp,198.dp))
        wheelRow.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f))
        mini.addView(wheelRow,margin(top=2))
        mini.addView(text("Flèches : naviguer  •  OK : valider",11f,lavender).apply{
            gravity=Gravity.CENTER
        },margin(top=1,bottom=6))
        remoteRow(mini,listOf("CH − • Précédente" to 70,"CH + • Suivante" to 69))
        remoteRow(mini,listOf("VOL − • Baisser" to 36,"VOL + • Monter" to 35))
        remoteRow(mini,listOf("MUTE • Couper son" to 23,"MENU • Menu TV" to 6))
        remoteRow(mini,listOf("EXIT • Retour" to 7,"INFO • Détails" to 57))

        val remoteFeedback=text("Télécommande connectée au récepteur",10f,secondary)
        fields["Retour télécommande"]=remoteFeedback
        mini.addView(remoteFeedback,margin(top=5))
        val extraInline=stack().apply{visibility=View.GONE}
        remoteRow(extraInline,listOf("⏻ Allumer" to 42,"EPG • Guide" to 32,"INFO" to 57))
        listOf(listOf(1,2,3),listOf(4,5,6),listOf(7,8,9)).forEach{digits->
            remoteRow(extraInline,digits.map{it.toString() to (it+12)})
        }
        remoteRow(extraInline,listOf("★ Favoris" to 33,"0" to 12,"↶ Dernière" to 29))
        mini.addView(extraInline)
        val expandRemote=action("⌄  Afficher plus de commandes",tileColor,lavender,43,
            Color.rgb(106,78,163),onClick={
                val isOpen=extraInline.visibility!=View.VISIBLE
                extraInline.visibility=if(isOpen)View.VISIBLE else View.GONE
            })
        mini.addView(expandRemote,margin(top=8))

        // "connection" was already attached by card(setupPage). Never add a View twice.
        // Previous build crashed in Activity.onCreate due to an existing View parent.
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
        setupPage.addView(text("RÉCEPTEURS DÉTECTÉS • RÉSULTATS RÉELS",12f,lavender,true),
            margin(top=22,bottom=10))
        discoveryResults=stack()
        discoveryResults.addView(text("Aucun récepteur détecté. Lancez une recherche.",13f,secondary))
        setupPage.addView(discoveryResults)
        val changeReceiver=card(settingsPage,top=10)
        section(changeReceiver,"Connexion","Gérer la session du récepteur connecté")
        changeReceiver.addView(action("Déconnecter / changer de récepteur",tileColor,lavender,47,
            onClick=onDisconnect))

        val metrics = card(page, top = 14)
        section(metrics, "État du récepteur", "Actualisé à partir des réponses réelles")
        metricsRow(metrics,
            Triple("Volume", "Non disponible", 16f),
            Triple("Mute", "En attente", 16f))
        metricsRow(metrics,
            Triple("Signal / fréquence", "Non communiqué", 14f),
            Triple("Nombre de chaînes", "—", 18f))
        val refresh = action("⟳   Actualiser maintenant", tileColor, lavender, 45,
            outline = hairline, onClick = onRefresh)
        metrics.addView(refresh, margin(top = 12))
        page.removeView(metrics)
        settingsPage.addView(metrics, 1, margin(top = 14))

        // Essentials first; all original keycodes preserved.
        val control = card(remotePage, top = 14)
        section(control, "Télécommande", "Les libellés indiquent l'action envoyée au récepteur")
        remoteRow(control, listOf("⏻ Marche" to 42, "Mute • Son" to 23, "EPG • Guide" to 32))
        remoteRow(control, listOf("Vol −" to 36, "▲ Haut" to 1, "Vol +" to 35))
        remoteRow(control, listOf("◀ Gauche" to 3, "OK • Valider" to 5, "▶ Droite" to 4))
        remoteRow(control, listOf("CH −" to 70, "▼ Bas" to 2, "CH +" to 69))
        remoteRow(control, listOf("Menu TV" to 6, "Exit • Retour" to 7, "Info" to 57))

        val extras = stack()
        remoteRow(extras, listOf("★ Favoris" to 33, "TV / RADIO" to 22, "↶ Dernière" to 29))
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { digits ->
            remoteRow(extras, digits.map { it.toString() to (it + 12) })
        }
        remoteRow(extras, listOf("Sous-titres" to 31, "0" to 12, "Télétexte" to 34))
        remoteRow(extras, listOf("🔴 Rouge" to 8, "🟢 Vert" to 9, "🟡 Jaune" to 10, "🔵 Bleu" to 11))
        extras.visibility = View.GONE
        val more = action("＋  Pavé numérique et touches supplémentaires",
            tileColor, lavender, 46, hairline) {
            extras.visibility = if (extras.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        control.addView(more, margin(top = 12))
        control.addView(extras)


        // Capability-aware receiver tools: display confirmed data, never fake server/network state.
        val tools = card(settingsPage, top = 14)
        section(tools, "Récepteur & installation", "Pointage parabole, satellites, transpondeurs, réseau et recherche")
        val toolsToggle = action("⌄   Ouvrir les outils", tileColor, lavender, 48, hairline) {}
        tools.addView(toolsToggle)
        val toolsBody = stack().apply { visibility = View.GONE }
        val satButtons = row()
        addWeighted(satButtons, action("🛰 Satellites", tileColor, lavender, 46, onClick=onReadSatellites), right=5)
        addWeighted(satButtons, action("◈ Transpondeurs", tileColor, lavender, 46, onClick=onReadTransponders), left=5)
        toolsBody.addView(satButtons, margin(top=12))
        toolsBody.addView(metric("Satellites","Pas encore chargés",13f),margin(top=8))
        toolsBody.addView(metric("Transpondeurs","Diagnostic expérimental uniquement",13f),margin(top=8))
        toolsBody.addView(action("◉   Mesurer le signal", tileColor, green, 45,
            onClick=onReadSignal),margin(top=12))
        toolsBody.addView(metric("Signal temps réel","Lecture indisponible avant connexion",14f),
            margin(top=8))
        toolsBody.addView(text("Une valeur est affichée seulement si le récepteur la transmet. Sinon aucun % n'est inventé.",11f,secondary),
            margin(top=7))
        toolsBody.addView(action("☷   Serveurs / abonnements", tileColor, lavender, 45,
            onClick=onServers),margin(top=14))
        toolsBody.addView(metric("Serveurs","Statut non accessible via l'API documentée. Ouverture du Menu TV possible.",12f),
            margin(top=8))
        toolsBody.addView(action("⌁   Réseau / DHCP / IP statique", tileColor, lavender, 45,
            onClick=onNetwork),margin(top=14))
        toolsBody.addView(metric("Réseau","L'IP en haut est l'adresse de connexion SatNova, pas un réglage réseau du récepteur.",12f),
            margin(top=8))
        toolsBody.addView(action("⌕   Recherche de chaînes", tileColor, lavender, 45,
            onClick=onScan),margin(top=14))
        toolsBody.addView(metric("Recherche","Menu Installation sur la TV ; recherche automatique distante non vérifiée.",12f),
            margin(top=8))
        tools.addView(toolsBody)
        toolsToggle.setOnClickListener {
            val open=toolsBody.visibility!=View.VISIBLE
            toolsBody.visibility=if(open) View.VISIBLE else View.GONE
            toolsToggle.text=if(open)"⌃   Fermer les outils" else "⌄   Ouvrir les outils"
        }

        // Diagnostics hidden from the primary UI but still kept live.
        val advanced = card(settingsPage, top = 14)
        val diagTitle = action("⌄   Informations techniques", panelColor, secondary, 44,
            onClick = {})
        advanced.addView(diagTitle)
        val diagBody = stack().apply { visibility = View.GONE }
        diagBody.addView(text("ÉTAT / PROTOCOLE", 11f, lavender, true), margin(top = 12))
        diagnostics = text("Login non vérifié", 13f, secondary)
        diagBody.addView(diagnostics, margin(top = 7))
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
    }
    fun updateChannels(rows:List<JSONObject>,complete:Boolean){
        activity.runOnUiThread{catalog.updateChannels(rows,complete)}
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
    fun updateSignalReading(level:Int?,quality:Int?,source:String){
        activity.runOnUiThread{catalog.updateSignalReading(level,quality,source)}
    }
    fun updateSignalTracking(running:Boolean){
        activity.runOnUiThread{catalog.updateTracking(running)}
    }

    fun updateZapStatus(message:String){
        activity.runOnUiThread{catalog.updateZapStatus(message)}
    }

    /** Sole entry view until a verified TCP login has completed. */
    fun setReceiverOnline(online:Boolean) = activity.runOnUiThread {
        appScroller.visibility=if(online)View.VISIBLE else View.GONE
        appNav.visibility=if(online)View.VISIBLE else View.GONE
        setupScroller.visibility=if(online)View.GONE else View.VISIBLE
        if(online)goToHome?.invoke()
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
    fun addDiscovered(host:String,model:String?=null)=activity.runOnUiThread {
        if(!discoveredHosts.add(host))return@runOnUiThread
        if(discoveredHosts.size==1)discoveryResults.removeAllViews()
        val item=action((model?:"Appareil possible")+" • "+host+"   → Connecter",
            tileColor,lavender,52,onClick={connectDiscovered(host)})
        discoveryResults.addView(item,margin(bottom=8))
    }
    fun discoveryMessage(message:String)=activity.runOnUiThread {
        if(discoveredHosts.isEmpty()){
            discoveryResults.removeAllViews()
            discoveryResults.addView(text(message,13f,secondary))
        }
    }

}
