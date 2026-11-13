package com.satnova.alpha

import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

/**
 * SatNova 0.4 visual refresh. UI only: network/protocol logic stays in MainActivity.
 * Uses platform widgets (no extra UI libraries or phone-specific assets).
 */
class SatNovaScreen(
    private val activity: Activity,
    onDiscover: () -> Unit,
    onConnect: () -> Unit,
    onWatch: () -> Unit,
    onRefresh: () -> Unit,
    onKey: (Int) -> Unit
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

    val root: ScrollView = ScrollView(activity)
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
            maxLines = if (key == "Données brutes reçues") 8 else 4
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

    init {
        root.apply {
            setBackgroundColor(navy)
            isFillViewport = true
            fitsSystemWindows = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val page = stack().apply {
            setPadding(16.dp, 20.dp, 16.dp, 36.dp)
        }
        root.addView(page, ViewGroup.LayoutParams(-1, -2))

        // Identity
        val branding = row()
        val mark = text("✦", 29f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = shape(purple, 17, colors = intArrayOf(purple, blue))
        }
        branding.addView(mark, margin(width = 56, height = 56, right = 12))
        val brandTexts = stack()
        brandTexts.addView(text("SATNOVA", 25f, Color.WHITE, true))
        brandTexts.addView(text("CONTROL YOUR UNIVERSE", 10f, lavender, true), margin(top = 5))
        branding.addView(brandTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val version = text("α 0.4", 12f, lavender, true).apply {
            gravity = Gravity.CENTER
            setPadding(11.dp, 9.dp, 11.dp, 9.dp)
            background = shape(tileColor, 50, hairline)
        }
        branding.addView(version)
        page.addView(branding, margin(bottom = 15))

        // Receiver and LAN connection
        val connection = card(page, top = 0)
        val connectionHeading = row()
        connectionHeading.addView(text("◉  ECHOLINK ZEN 4", 13f, lavender, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        connectionHeading.addView(text("● LOCAL", 11f, green, true))
        connection.addView(connectionHeading)
        status = text("Non connecté • Même réseau Wi-Fi requis", 13f, secondary)
        connection.addView(status, margin(top = 10))
        ip = EditText(activity).apply {
            setText("192.168.1.2")
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
        val discover = action("⌁  Rechercher", height = 48, onClick = onDiscover)
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

        // Live TV hero: layout intentionally does not invent a video thumbnail.
        val hero = card(page, top = 14)
        hero.background = shape(panelColor, 24, colors = intArrayOf(
            Color.rgb(33, 30, 78), Color.rgb(22, 38, 69)
        ))
        val top = row()
        top.addView(text("●  À L’ANTENNE", 11f, green, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("LIVE • SAT>IP", 11f, lavender, true))
        hero.addView(top)
        hero.addView(text("CHAÎNE ACTUELLE", 11f, lavender, true), margin(top = 24))
        val now = text("En attente du récepteur…", 25f, Color.WHITE, true)
        hero.addView(now, margin(top = 9))
        fields["Chaîne actuelle"] = now
        hero.addView(text("PROGRAMME / EPG", 11f, lavender, true), margin(top = 18))
        val program = text("Pas encore communiqué", 14f, textColor)
        hero.addView(program, margin(top = 6))
        fields["Programme en cours / EPG"] = program
        hero.addView(Space(activity), margin(height = 19))
        hero.addView(action("▶   REGARDER EN DIRECT", purple, Color.WHITE, 58,
            onClick = onWatch), margin())
        hero.addView(text(
            "La vidéo s'ouvre seulement si le récepteur fournit un flux RTSP.",
            11f, secondary), margin(top = 10))

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

        // Essentials first; all original keycodes preserved.
        val control = card(page, top = 14)
        section(control, "Télécommande", "Contrôle direct • Echolink")
        remoteRow(control, listOf("⏻ Power" to 42, "Mute" to 23, "EPG" to 32))
        remoteRow(control, listOf("VOL −" to 36, "▲" to 1, "VOL +" to 35))
        remoteRow(control, listOf("◀" to 3, "OK" to 5, "▶" to 4))
        remoteRow(control, listOf("CH −" to 70, "▼" to 2, "CH +" to 69))
        remoteRow(control, listOf("MENU" to 6, "EXIT" to 7, "INFO" to 57))

        val extras = stack()
        remoteRow(extras, listOf("FAV" to 33, "TV / RADIO" to 22, "RECALL" to 29))
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { digits ->
            remoteRow(extras, digits.map { it.toString() to (it + 12) })
        }
        remoteRow(extras, listOf("SUB" to 31, "0" to 12, "TXT" to 34))
        remoteRow(extras, listOf("🔴" to 8, "🟢" to 9, "🟡" to 10, "🔵" to 11))
        extras.visibility = View.GONE
        val more = action("＋  Pavé numérique et touches supplémentaires",
            tileColor, lavender, 46, hairline) {
            extras.visibility = if (extras.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        control.addView(more, margin(top = 12))
        control.addView(extras)

        // Diagnostics hidden from the primary UI but still kept live.
        val advanced = card(page, top = 14)
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
        page.addView(text("SATNOVA  •  ALPHA BUILD  •  LOCAL ONLY", 10f, secondary, true),
            margin(top = 24).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
}
