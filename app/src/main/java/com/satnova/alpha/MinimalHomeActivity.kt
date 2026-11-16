package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Optional Google TV HOME entry. It intentionally does not replace SatNova's
 * receiver session or issue undocumented tuner / HDMI commands.
 *
 * Alpha: installed apps, local favorite pages and real cached Zen channel data.
 * Live list selection, receiver-side favorite writes and HDMI routing still need
 * protocol/device validation. No EPG or synthetic channels.
 */
class MinimalHomeActivity : Activity() {
    private val bgColor = Color.rgb(15, 18, 23)
    private val surface = Color.rgb(36, 41, 50)
    private val selected = Color.rgb(56, 70, 90)
    private val fg = Color.rgb(245, 247, 251)
    private val muted = Color.rgb(165, 175, 191)
    private val border = Color.rgb(126, 159, 208)
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("satnova_minimal_home", Context.MODE_PRIVATE) }
    private var channels: List<CatalogIndex.Channel> = emptyList()
    private var activePage = 0
    private lateinit var content: LinearLayout

    private data class AppItem(val label: String, val component: ComponentName, val info: ResolveInfo)
    private data class LocalPage(val title: String, val ids: MutableList<String>)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        render()
        reloadChannels()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) reloadChannels()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun rounded(color: Int, focused: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(13).toFloat()
        if (focused) setStroke(dp(2), border)
    }

    private fun text(value: String, size: Float, strong: Boolean = false, color: Int = fg) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (strong) setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun focusable(view: View, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
        view.isFocusable = true
        view.isClickable = true
        view.setOnFocusChangeListener { v, focused ->
            v.background = rounded(if (focused) selected else surface, focused)
            v.elevation = if (focused) dp(5).toFloat() else 0f
        }
        view.setOnClickListener { onClick() }
        if (onLong != null) view.setOnLongClickListener { onLong(); true }
    }

    private fun horizontalRow(): Pair<HorizontalScrollView, LinearLayout> {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(row)
        return scroll to row
    }

    private fun cardWidth(): Int {
        val w = resources.displayMetrics.widthPixels
        return ((w - dp(96) - dp(4 * 14)) / 5).coerceAtLeast(dp(108))
    }

    private fun addCard(row: LinearLayout, label: String, subtitle: String?,
        icon: android.graphics.drawable.Drawable?, click: () -> Unit, longClick: (() -> Unit)? = null) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(surface)
            contentDescription = listOfNotNull(label, subtitle).joinToString(", ")
        }
        if (icon != null) {
            card.addView(ImageView(this).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(5) })
        }
        card.addView(text(label, 14f, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!subtitle.isNullOrBlank()) {
            card.addView(text(subtitle, 11f, color = muted).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        focusable(card, click, longClick)
        row.addView(card, LinearLayout.LayoutParams(cardWidth(), dp(116)).apply { rightMargin = dp(14) })
    }

    private fun section(title: String) {
        content.addView(text(title, 22f, true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(24); bottomMargin = dp(12)
        })
    }

    private fun iconButton(resource: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(resource)
            setColorFilter(fg)
            contentDescription = description
            background = rounded(surface)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            focusable(this, action)
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(48), dp(24), dp(48), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(iconButton(R.drawable.satnova_home_icon, "Accueil") { render() },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(iconButton(android.R.drawable.ic_menu_search, "Recherche") { search() },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) })
        header.addView(iconButton(android.R.drawable.ic_menu_preferences, "Paramètres") { settings() },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(header)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(18))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showApps()
        showTv()
        showFavorites()
    }

    private fun installedApps(): List<AppItem> {
        val pm = packageManager
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        return intents.flatMap { pm.queryIntentActivities(it, 0) }
            .filter { it.activityInfo != null && it.activityInfo.name != MinimalHomeActivity::class.java.name }
            .map { AppItem(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name), it) }
            .distinctBy { it.component.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun selectedApps(all: List<AppItem>): List<AppItem> {
        if (!prefs.contains("apps")) return all.take(8)
        val ids = prefs.getString("apps", "")!!.split("|").filter(String::isNotBlank)
        return ids.mapNotNull { key -> all.firstOrNull { it.component.flattenToString() == key } }
    }

    private fun launchApp(app: AppItem) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { Toast.makeText(this, "Application indisponible", Toast.LENGTH_SHORT).show() }
    }

    private fun showApps() {
        section("Applications favorites")
        val (scroll, row) = horizontalRow()
        val all = installedApps()
        selectedApps(all).forEach { app ->
            addCard(row, app.label, null, app.info.loadIcon(packageManager),
                { launchApp(app) }, { chooseApps() })
        }
        addCard(row, "Ajouter", null, getDrawable(android.R.drawable.ic_input_add), { chooseApps() })
        content.addView(scroll)
    }

    private fun showTv() {
        section("TV")
        val (scroll, row) = horizontalRow()
        if (channels.isEmpty()) {
            addCard(row, "Aucune chaîne mémorisée", "Ouvrir SatNova",
                getDrawable(android.R.drawable.ic_menu_info_details), { openSatNova() })
        } else {
            channels.take(20).forEach { channel ->
                addCard(row, channel.name, "N° ${channel.position}",
                    getDrawable(android.R.drawable.ic_media_play),
                    { openChannel(channel) }, { addChannelToPage(channel) })
            }
        }
        content.addView(scroll)
    }

    private fun pages(): MutableList<LocalPage> {
        val raw = prefs.getString("pages", null)
        if (raw.isNullOrBlank()) return mutableListOf(LocalPage("Mes favoris", mutableListOf()))
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val p = array.optJSONObject(i) ?: return@mapNotNull null
                val title = p.optString("name", "").take(32)
                if (title.isBlank()) return@mapNotNull null
                val idsArray = p.optJSONArray("ids") ?: JSONArray()
                LocalPage(title, (0 until idsArray.length()).mapNotNull { x ->
                    idsArray.optString(x).takeIf(String::isNotBlank)
                }.distinct().toMutableList())
            }.toMutableList().ifEmpty { mutableListOf(LocalPage("Mes favoris", mutableListOf())) }
        } catch (_: Exception) { mutableListOf(LocalPage("Mes favoris", mutableListOf())) }
    }

    private fun savePages(list: List<LocalPage>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("name", p.title).put("ids", JSONArray(p.ids)))
        }
        prefs.edit().putString("pages", arr.toString()).apply()
    }

    private fun showFavorites() {
        section("Favoris")
        val list = pages()
        activePage = activePage.coerceIn(0, list.lastIndex)
        val (tabs, row) = horizontalRow()
        list.forEachIndexed { index, page ->
            val chip = text(page.title, 14f, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = rounded(if (index == activePage) selected else surface)
            }
            focusable(chip, { activePage = index; render() }, { editPage(index) })
            row.addView(chip, LinearLayout.LayoutParams(-2, dp(46)).apply { rightMargin = dp(10) })
        }
        content.addView(tabs)
        val (scroll, favorites) = horizontalRow()
        val channelMap = channels.associateBy { it.id }
        val ids = list[activePage].ids
        if (ids.isEmpty()) {
            addCard(favorites, "Aucune chaîne", "Appui long sur une chaîne TV",
                getDrawable(android.R.drawable.ic_menu_info_details), { settings() })
        } else {
            ids.mapNotNull(channelMap::get).forEach { channel ->
                addCard(favorites, channel.name, "N° ${channel.position}",
                    getDrawable(android.R.drawable.ic_media_play),
                    { openChannel(channel) }, { removeFavorite(channel.id) })
            }
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    private fun openSatNova() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openChannel(channel: CatalogIndex.Channel) {
        // A cached channel is not evidence of a live receiver session.
        Toast.makeText(this, "Ouverture de SatNova pour sélectionner : ${channel.name}", Toast.LENGTH_SHORT).show()
        openSatNova()
    }

    private fun addChannelToPage(channel: CatalogIndex.Channel) {
        val list = pages()
        AlertDialog.Builder(this).setTitle("Ajouter aux favoris")
            .setItems(list.map(LocalPage::title).toTypedArray()) { _, index ->
                val ids = list[index].ids
                if (!ids.contains(channel.id)) ids.add(channel.id)
                savePages(list)
                activePage = index
                render()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun removeFavorite(id: String) {
        AlertDialog.Builder(this).setTitle("Retirer des favoris ?")
            .setPositiveButton("Retirer") { _, _ ->
                val list = pages()
                list[activePage].ids.remove(id)
                savePages(list)
                render()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun editPage(index: Int) {
        val options = arrayOf("Renommer", "Supprimer", "Ajouter une page")
        AlertDialog.Builder(this).setTitle("Favoris").setItems(options) { _, which ->
            when (which) {
                0 -> askPageName(index)
                1 -> {
                    val list = pages()
                    if (list.size > 1) {
                        list.removeAt(index); savePages(list); activePage = 0; render()
                    } else Toast.makeText(this, "Conservez au moins une page", Toast.LENGTH_SHORT).show()
                }
                2 -> askPageName(null)
            }
        }.show()
    }

    private fun askPageName(index: Int?) {
        val input = EditText(this).apply {
            isSingleLine = true
            hint = "Nom de la page"
            if (index != null) setText(pages()[index].title)
        }
        AlertDialog.Builder(this).setTitle(if (index == null) "Nouvelle page" else "Renommer la page")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val title = input.text.toString().trim().take(32)
                if (title.isNotBlank()) {
                    val list = pages()
                    if (index == null) { list.add(LocalPage(title, mutableListOf())); activePage = list.lastIndex }
                    else list[index] = list[index].copy(title = title)
                    savePages(list); render()
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun chooseApps() {
        val all = installedApps()
        val selectedIds = selectedApps(all).map { it.component.flattenToString() }.toMutableSet()
        val checked = BooleanArray(all.size) { selectedIds.contains(all[it].component.flattenToString()) }
        AlertDialog.Builder(this).setTitle("Applications favorites")
            .setMultiChoiceItems(all.map(AppItem::label).toTypedArray(), checked) { _, index, enabled ->
                if (enabled) selectedIds.add(all[index].component.flattenToString())
                else selectedIds.remove(all[index].component.flattenToString())
            }.setPositiveButton("Enregistrer") { _, _ ->
                prefs.edit().putString("apps", all.filter { selectedIds.contains(it.component.flattenToString()) }
                    .joinToString("|") { it.component.flattenToString() }).apply()
                render()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun settings() {
        val options = arrayOf("Applications favorites", "Ajouter une page Favoris",
            "Renommer la page Favoris", "Actualiser TV", "Ouvrir SatNova")
        AlertDialog.Builder(this).setTitle("Paramètres")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> chooseApps()
                    1 -> askPageName(null)
                    2 -> askPageName(activePage)
                    3 -> reloadChannels()
                    4 -> openSatNova()
                }
            }.show()
    }

    private fun search() {
        val input = EditText(this).apply { hint = "Application ou chaîne"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Recherche").setView(input)
            .setPositiveButton("Rechercher") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isBlank()) return@setPositiveButton
                val matchesApps = installedApps().filter { it.label.contains(q, true) }
                val matchesChannels = channels.filter { it.name.contains(q, true) }
                val labels = matchesApps.map { "Application · ${it.label}" } +
                    matchesChannels.map { "TV · ${it.name}" }
                if (labels.isEmpty()) Toast.makeText(this, "Aucun résultat", Toast.LENGTH_SHORT).show()
                else AlertDialog.Builder(this).setTitle("Résultats")
                    .setItems(labels.toTypedArray()) { _, index ->
                        if (index < matchesApps.size) launchApp(matchesApps[index])
                        else openChannel(matchesChannels[index - matchesApps.size])
                    }.show()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun reloadChannels() {
        worker.execute {
            val directory = File(filesDir, "receiver-catalogues")
            val last = directory.listFiles()?.filter {
                it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.json"))
            }?.maxByOrNull { it.lastModified() }
            val snapshot = last?.nameWithoutExtension?.let { key ->
                ReceiverCatalogueCache(directory).load(key)
            }
            val loaded = if (snapshot != null) {
                CatalogIndex(snapshot.channels, snapshot.satellites, snapshot.transponders)
                    .channelRows.filter { !it.radio }.sortedBy { it.position }
            } else emptyList()
            runOnUiThread { if (!isFinishing && !isDestroyed) { channels = loaded; render() } }
        }
    }
}
