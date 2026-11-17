package com.satnova.alpha

import android.app.Activity
import android.app.role.RoleManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextClock
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.SystemClock
import android.provider.Settings
import android.content.ClipboardManager
import android.content.ClipData
import java.util.concurrent.Executors

/** Standalone Android TV launcher. The phone application is not installed on the TV. */
open class MinimalHomeActivity : Activity() {
    protected open val previewMode: Boolean = false // Compact phone layout; real network commands stay enabled
    private val bgColor = Color.rgb(12, 25, 28)
    private val surface = Color.argb(228, 20, 43, 48)
    private val selected = Color.argb(245, 23, 68, 58)
    private val fg = Color.rgb(245, 247, 251)
    private val muted = Color.rgb(165, 175, 191)
    private val border = Color.rgb(118, 255, 181)
    private val prefs by lazy { getSharedPreferences("satnova_minimal_home", Context.MODE_PRIVATE) }
    private val logos by lazy { ChannelLogoStore(this) }
    private val families by lazy { ChannelFamilies(prefs) }
    private val islamicPanel by lazy { MoroccanHomePanel(this, prefs, previewMode) }
    private val fileProbeWorker = Executors.newSingleThreadExecutor()
    private var channels: List<ZenChannel> = emptyList()
    private var radios: List<ZenChannel> = emptyList()
    private var online = false
    private var receiverMessage = "Récepteur non connecté"
    private var activePage = 0
    private var diagnosticToSave: String? = null
    private val diagnosticSaveRequest = 7124
    private val logoPickerRequest = 7350
    private var pendingLogoChannel: ZenChannel? = null
    private var trackedChannel = ""
    private var trackedSince = 0L
    private var historyRecordedFor = ""
    private val receiverListener = object : Zen4Client.Listener {
            override fun state(connected: Boolean, message: String) {
                val changed = online != connected || receiverMessage != message
                online = connected
                receiverMessage = message
                if (changed) scheduleRender()
            }
            override fun catalogue(channels: List<ZenChannel>) {
                this@MinimalHomeActivity.channels = channels.filter { !it.radio }.sortedBy { it.position }
                this@MinimalHomeActivity.radios = channels.filter { it.radio }.sortedBy { it.position }
                scheduleRender()
            }
            override fun playing(channelId: String) {
                observeChannel(channelId)
            }
            override fun tuneFailed(channel: ZenChannel, reason: String) {
                channelError("Commande non envoyée",
                    "Impossible de sélectionner «${channel.name}». Détail : " + reason +
                    ". Vérifiez la connexion Wi-Fi et réessayez.")
            }
            override fun tuneConfirmed(channel: ZenChannel) {
                if (previewMode) {
                    Toast.makeText(this@MinimalHomeActivity,
                        "Changement confirmé sur Zen 4 · ${channel.name}", Toast.LENGTH_LONG).show()
                }
                // HDMI switching is initiated when the tune command is queued,
                // not after Zen 4 acknowledges it.
            }
    }
    private lateinit var client: Zen4Client
    private lateinit var content: LinearLayout
    private var activeScroll: ScrollView? = null
    private val savedRowScroll = mutableMapOf<String, Int>()

    // Zen 4 catalogue batches must not rebuild the entire TV interface
    // on every response. Coalesce redraws, especially while dialogs are open.
    private val redrawHandler = Handler(Looper.getMainLooper())
    private var redrawScheduled = false
    private var redrawDirty = false
    private var lastInputAt = 0L
    private var lastCatalogueRenderAt = 0L
    private val redrawTask = Runnable {
        redrawScheduled = false
        if (!isFinishing && !isDestroyed && hasWindowFocus()) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastInputAt < 2500L || now - lastCatalogueRenderAt < 9000L) {
                redrawHandler.postDelayed({ scheduleRender() }, 3000L)
            } else {
                redrawDirty = false
                lastCatalogueRenderAt = now
                render()
            }
        }
    }

    private fun scheduleRender() {
        if (!::content.isInitialized || isFinishing || isDestroyed) return
        redrawDirty = true
        if (redrawScheduled) return
        redrawScheduled = true
        redrawHandler.postDelayed(redrawTask, 1800L)
    }

    private fun restoreLauncherFocus(force: Boolean = false) {
        if (!::content.isInitialized || isFinishing || isDestroyed || !hasWindowFocus()) return
        val current = currentFocus
        if (force || current == null || !current.isShown || !current.isFocusable) {
            findFocusable(window.decorView, "Accueil")?.requestFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::content.isInitialized) {
            window.decorView.post {
                if (isFinishing || isDestroyed || !hasWindowFocus()) return@post
                restoreLauncherFocus()
                if (redrawDirty && !redrawScheduled) scheduleRender()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) lastInputAt = SystemClock.elapsedRealtime()
        if (event.action == KeyEvent.ACTION_DOWN && hasWindowFocus() &&
            currentFocus == null && event.keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
            )) {
            restoreLauncherFocus(force = true)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun snapshotRows(view: View?) {
        if (view is HorizontalScrollView && view.tag is String) {
            savedRowScroll[view.tag as String] = view.scrollX
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) snapshotRows(view.getChildAt(i))
        }
    }

    private fun rememberRow(id: String, view: HorizontalScrollView): HorizontalScrollView {
        view.tag = id
        val oldX = savedRowScroll[id] ?: 0
        if (oldX > 0) view.post { view.scrollTo(oldX, 0) }
        return view
    }

    private fun findFocusable(root: View, description: String): View? {
        if (root.isFocusable && root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            findFocusable(root.getChildAt(i), description)?.let { return it }
        }
        return null
    }

    private data class AppItem(val label: String, val component: ComponentName, val info: ResolveInfo)
    private data class LocalPage(val title: String, val ids: MutableList<String>)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        client = Zen4SessionHub.attach(this, this, receiverListener)
        if (!previewMode) {
            try {
                val service = Intent(this, Zen4PersistentService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service)
                else startService(service)
            } catch (_: Exception) {
                // The launcher retains its own reconnection loop if the
                // device firmware refuses foreground services.
            }
        }
        // A damaged cached UI asset must not leave the television without a launcher.
        // Show a minimal recovery screen rather than crashing the HOME activity.
        try {
            render()
        } catch (error: Exception) {
            android.util.Log.e("SatNovaHome", "Initial HOME render failed", error)
            showStartupRecovery(error)
        }
        // Explain both optional setup steps only on the first TV launch.
        // Dismissing either choice never changes the Android launcher or
        // grants Accessibility permission without the user's consent.
        if (!previewMode && !prefs.getBoolean("first_start_guide_v1_seen", false)) {
            prefs.edit().putBoolean("first_start_guide_v1_seen", true).apply()
            window.decorView.post {
                if (!isFinishing && !isDestroyed) firstStartGuide()
            }
        }
    }

    private fun showStartupRecovery(error: Exception) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(bgColor)
        }
        root.addView(TextView(this).apply {
            text = "SatNova · Mode récupération"
            textSize = 26f
            setTextColor(fg)
        })
        root.addView(TextView(this).apply {
            text = "L'accueil n'a pas pu charger toutes ses données. Les paramètres enregistrés sont conservés."
            textSize = 17f
            setTextColor(fg)
        })
        root.addView(android.widget.Button(this).apply {
            text = "Réessayer"
            setOnClickListener {
                try { render() } catch (e: Exception) {
                    android.util.Log.e("SatNovaHome", "HOME retry failed", e)
                    Toast.makeText(this@MinimalHomeActivity, "Échec du chargement", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(android.widget.Button(this).apply {
            text = "Paramètres Android"
            setOnClickListener { startSystemSettings(listOf(Intent(Settings.ACTION_SETTINGS)), "Paramètres indisponibles") }
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!previewMode) {
            getSharedPreferences("zen4_hdmi_bridge", Context.MODE_PRIVATE)
                .edit().putBoolean("armed", false).apply()
        }
    }

    override fun onDestroy() {
        if (::client.isInitialized) Zen4SessionHub.detach(this)
        fileProbeWorker.shutdownNow()
        redrawHandler.removeCallbacks(redrawTask)
        logos.close()
        islamicPanel.stop()
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
            setPadding(dp(26), 0, dp(26), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(row)
        return scroll to row
    }

    /**
     * Render 20 items immediately; append 20 more near the right edge,
     * preserving focus and horizontal scroll instead of rebuilding the screen.
     */
    private fun <T> pagedHorizontal(items: List<T>, rowId: String,
        renderer: (LinearLayout, T) -> Unit): HorizontalScrollView {
        val (scroll, row) = horizontalRow()
        var loaded = 0
        fun appendBatch() {
            val next = (loaded + 20).coerceAtMost(items.size)
            for (index in loaded until next) renderer(row, items[index])
            loaded = next
        }
        appendBatch()
        // Rebuild enough previous cards to restore horizontal position after
        // receiver catalogue refresh, without rendering the entire list.
        val required = ((savedRowScroll[rowId] ?: 0) /
            (cardWidth() + dp(14)) + 8).coerceAtMost(items.size)
        while (loaded < required) appendBatch()
        scroll.setOnScrollChangeListener { _, x, _, _, _ ->
            if (loaded < items.size && x + scroll.width >= row.width - dp(140)) appendBatch()
        }
        return rememberRow(rowId, scroll)
    }

    private fun withArrows(scroll: HorizontalScrollView): View {
        val frame = FrameLayout(this)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        fun arrow(label: String, direction: Int, gravity: Int) {
            val view = text(label, 24f, true, fg).apply {
                this.gravity = Gravity.CENTER
                contentDescription = if (direction < 0) "Défiler vers la gauche"
                    else "Défiler vers la droite"
                setOnClickListener { scroll.smoothScrollBy(direction * dp(cardWidth() + 14) * 2, 0) }
                isFocusable = true
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) selected else surface, focused)
                }
                background = rounded(surface)
            }
            frame.addView(view, FrameLayout.LayoutParams(dp(27), dp(46), gravity))
        }
        arrow("‹", -1, Gravity.START or Gravity.CENTER_VERTICAL)
        arrow("›", 1, Gravity.END or Gravity.CENTER_VERTICAL)
        return frame
    }

    private fun cardWidth(kind: String = "tv"): Int {
        // Convert pixels to logical dp BEFORE returning; old code double-scaled
        // the width on high-density Android TV displays.
        val availableDp = resources.displayMetrics.widthPixels /
            resources.displayMetrics.density
        val n = when (kind) {
            "apps" -> 5
            "radio" -> 4
            else -> 6
        }
        val paddingDp = if (previewMode) 36 else 96
        return ((availableDp - paddingDp - n * 14 - 48) / n)
            .toInt().coerceAtLeast(if (previewMode) 84 else 102)
    }

    private fun addCard(row: LinearLayout, label: String, subtitle: String?,
        icon: android.graphics.drawable.Drawable?, click: () -> Unit,
        longClick: (() -> Unit)? = null, logoChannel: ZenChannel? = null,
        kind: String = "tv") {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rounded(surface)
            contentDescription = listOfNotNull(label, subtitle).joinToString(", ")
        }
        if (icon != null || logoChannel != null) {
            val logoImage = ImageView(this).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (logoChannel != null) {
                    // Contrast plate for both white and black transparent picons.
                    background = rounded(Color.rgb(90, 98, 111))
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }
                contentDescription = if (logoChannel != null) "Logo de $label" else label
            }
            card.addView(logoImage, LinearLayout.LayoutParams(
                dp(if (logoChannel != null) (if (kind == "radio") 76 else 82) else if (kind == "radio") 42 else 48),
                dp(if (logoChannel != null) (if (kind == "radio") 48 else 56) else if (kind == "radio") 35 else 43)
            ).apply { bottomMargin = dp(5) })
            if (logoChannel != null) logos.bind(logoImage, logoChannel)
        }
        card.addView(text(label, if (kind == "radio") 13f else 14f, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!subtitle.isNullOrBlank()) {
            card.addView(text(subtitle, 11f, color = muted).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        focusable(card, click, longClick)
        val h = when (kind) {
            "apps" -> 99
            "radio" -> if (logoChannel != null) 104 else 78
            else -> if (logoChannel != null) 120 else 112
        }
        row.addView(card, LinearLayout.LayoutParams(dp(cardWidth(kind)), dp(h)).apply {
            rightMargin = dp(14)
        })
    }

    private fun section(title: String) {
        content.addView(text(title, 22f, true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(11); bottomMargin = dp(7)
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
        val oldFocus = currentFocus?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val oldY = activeScroll?.scrollY ?: 0
        snapshotRows(activeScroll)
        val firstDraw = activeScroll == null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = islamicPanel.backdrop()
            setPadding(dp(if (previewMode) 18 else 48), dp(if (previewMode) 12 else 24), dp(if (previewMode) 18 else 48), dp(if (previewMode) 12 else 24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(iconButton(R.drawable.satnova_home_icon, "Accueil") { render() },
            LinearLayout.LayoutParams(dp(46), dp(46)))
        header.addView(text("SatNova TV Home", 23f, true,
            Color.rgb(215, 236, 187)).apply { gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(-2, dp(46)).apply { leftMargin = dp(16) })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (!previewMode) {
            header.addView(TextClock(this).apply {
                format24Hour = "HH:mm"
                format12Hour = "h:mm a"
                textSize = 21f
                setTextColor(fg)
                gravity = Gravity.CENTER
                contentDescription = "Heure locale"
                isFocusable = false
            }, LinearLayout.LayoutParams(-2, dp(52)).apply { rightMargin = dp(24) })
        }
        header.addView(iconButton(android.R.drawable.ic_menu_search, "Recherche") { search() },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) })
        header.addView(iconButton(android.R.drawable.ic_menu_preferences, "Paramètres") {
            settings()
        }.apply {
            // Long OK / long click opens the real Android TV Settings even
            // when the launcher is configured as default.
            setOnLongClickListener { openAndroidSystemSettings(); true }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
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
        activeScroll = scroll
        setContentView(root)
        content.addView(islamicPanel.build(), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12); bottomMargin = dp(5)
        })
        showApps()
        showTv()
        showRadios()
        if (prefs.getBoolean("show_history", false)) showHistory()
        showFavorites()
        scroll.post {
            if (isFinishing || isDestroyed || activeScroll !== scroll) return@post
            scroll.scrollTo(0, oldY)
            val focus = oldFocus?.let { findFocusable(root, it) }
            if (focus?.requestFocus() != true) {
                val firstApp = selectedApps(installedApps()).firstOrNull()?.label
                (firstApp?.let { findFocusable(root, it) }
                    ?: header.getChildAt(0))?.requestFocus()
            }
        }
    }

    private fun installedApps(): List<AppItem> {
        val pm = packageManager
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        return intents.flatMap { pm.queryIntentActivities(it, 0) }
            .filter { it.activityInfo != null && it.activityInfo.packageName != packageName }
            .map { AppItem(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name), it) }
            .distinctBy { it.component.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun selectedApps(all: List<AppItem>): List<AppItem> {
        if (!prefs.contains("apps")) {
            fun weight(label: String): Int = when {
                label.contains("altaplay", true) -> 0
                label.contains("shahid", true) -> 1
                label.contains("youtube", true) -> 2
                label.contains("downloader", true) -> 3
                else -> 10
            }
            return all.sortedWith(compareBy<AppItem> { weight(it.label) }
                .thenBy { it.label.lowercase() }).take(8)
        }
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
                { launchApp(app) }, { chooseApps() }, kind = "apps")
        }
        addCard(row, "Ajouter", null, getDrawable(android.R.drawable.ic_input_add),
            { chooseApps() }, kind = "apps")
        content.addView(withArrows(rememberRow("apps", scroll)))
    }

    private fun showTv() {
        section("TV")
        // Export and receiver-file shortcuts were retired from the TV home.
        content.addView(text(receiverMessage, 14f, color = if (online)
            Color.rgb(167, 225, 180) else muted),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (channels.isEmpty()) {
            val (scroll, row) = horizontalRow()
            addCard(row, if (online) "Chargement…" else "Récepteur non connecté",
                if (online) "Lecture des chaînes" else "Connexion Wi-Fi",
                getDrawable(android.R.drawable.ic_menu_info_details), { settings() })
            content.addView(withArrows(rememberRow("tv-empty", scroll)))
            return
        }
        val tiles = families.tiles(channels)
        content.addView(withArrows(pagedHorizontal(tiles, "tv") { row, tile ->
            addCard(row, tile.label,
                if (tile.isGroup) "${tile.members.size} chaînes" else ChannelDescription.summary(tile.first),
                getDrawable(android.R.drawable.ic_media_play),
                { if (tile.isGroup) showFamily(tile) else openChannel(tile.first) },
                { if (tile.isGroup) editFamily(tile) else channelActions(tile.first) }, tile.first)
        }))
    }

    private fun showRadios() {
        section("Radios")
        if (radios.isEmpty()) {
            val (scroll, row) = horizontalRow()
            addCard(row, "Aucune radio", "En attente du catalogue",
                getDrawable(android.R.drawable.ic_media_play), { settings() })
            content.addView(withArrows(rememberRow("radios-empty", scroll)))
        } else {
            content.addView(withArrows(pagedHorizontal(radios, "radios") { row, radio ->
                addCard(row, radio.name, ChannelDescription.summary(radio),
                    getDrawable(android.R.drawable.ic_media_play),
                    { openChannel(radio) }, { channelActions(radio) }, radio, kind = "radio")
            }))
        }
    }

    private fun showHistory() {
        section("Récemment regardées")
        val (scroll, row) = horizontalRow()
        val ids = history().take(20)
        ids.mapNotNull { id -> (channels + radios).firstOrNull { it.id == id } }.forEach { channel ->
            addCard(row, channel.name, ChannelDescription.summary(channel),
                getDrawable(android.R.drawable.ic_media_play), { openChannel(channel) },
                logoChannel = channel)
        }
        if (row.childCount == 0) {
            addCard(row, "Aucun historique", "Après 60 secondes de visionnage",
                getDrawable(android.R.drawable.ic_menu_recent_history), { settings() })
        }
        content.addView(withArrows(rememberRow("history", scroll)))
    }

    private fun history(): List<String> {
        return try {
            val a = JSONArray(prefs.getString("watch_history", "[]") ?: "[]")
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        } catch (_: Exception) { emptyList() }
    }

    private fun observeChannel(id: String) {
        val now = SystemClock.elapsedRealtime()
        if (trackedChannel != id) {
            trackedChannel = id
            trackedSince = now
            historyRecordedFor = ""
        } else if (historyRecordedFor != id && now - trackedSince >= 60_000L) {
            historyRecordedFor = id
            val ids = (listOf(id) + history().filterNot { it == id }).take(20)
            prefs.edit().putString("watch_history", JSONArray(ids).toString()).apply()
            if (prefs.getBoolean("show_history", false)) render()
        }
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
        content.addView(withArrows(rememberRow("favorite-tabs", tabs)))
        val (scroll, favorites) = horizontalRow()
        val channelMap = (channels + radios).associateBy { it.id }
        val ids = list[activePage].ids
        if (ids.isEmpty()) {
            addCard(favorites, "Aucune chaîne", "Appui long sur une chaîne TV",
                getDrawable(android.R.drawable.ic_menu_info_details), { settings() })
        } else {
            ids.mapNotNull(channelMap::get).forEach { channel ->
                addCard(favorites, channel.name, ChannelDescription.summary(channel),
                    getDrawable(android.R.drawable.ic_media_play),
                    { openChannel(channel) }, { removeFavorite(channel.id) }, channel)
            }
        }
        content.addView(withArrows(rememberRow("favorites:$activePage", scroll)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    /** A family tile opens a chooser, never tunes an arbitrary variant on first click. */
    private fun showFamily(tile: ChannelFamilies.Tile) {
        val rows = tile.members.sortedBy { it.position }
        AlertDialog.Builder(this)
            .setTitle(tile.label)
            .setItems(rows.map { c -> "${c.name}   ·   ${ChannelDescription.full(c)}" }.toTypedArray()) { _, index ->
                openChannel(rows[index])
            }
            .setNeutralButton("Modifier le groupe") { _, _ -> editFamily(tile) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun editFamily(tile: ChannelFamilies.Tile) {
        val groupKey = tile.key ?: run { channelActions(tile.first); return }
        val options = arrayOf("Ajouter une chaîne", "Retirer une chaîne",
            "Dissocier le groupe", "Choisir une version")
        AlertDialog.Builder(this).setTitle("Groupe · ${tile.label}")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> addToGroup(groupKey)
                    1 -> removeFromGroup(tile)
                    2 -> AlertDialog.Builder(this).setTitle("Dissocier ce groupe ?")
                        .setMessage("Les chaînes resteront dans la liste, avec leur ordre original.")
                        .setPositiveButton("Dissocier") { _, _ -> families.ungroup(tile); render() }
                        .setNegativeButton("Annuler", null).show()
                    3 -> showFamily(tile)
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun addToGroup(key: String) {
        val input = EditText(this).apply {
            hint = "Chercher une chaîne par nom"
            isSingleLine = true
        }
        AlertDialog.Builder(this).setTitle("Ajouter à ${families.nameOf(key)}")
            .setView(input)
            .setPositiveButton("Rechercher") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isBlank()) return@setPositiveButton
                val matches = channels.filter { it.name.contains(query, ignoreCase = true) }
                    .take(100)
                if (matches.isEmpty()) {
                    Toast.makeText(this, "Aucune chaîne trouvée", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(this).setTitle("Choisir une chaîne")
                    .setItems(matches.map { "${it.name} · ${ChannelDescription.full(it)}" }.toTypedArray()) { _, index ->
                        families.assign(matches[index].id, key)
                        render()
                    }.setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun removeFromGroup(tile: ChannelFamilies.Tile) {
        val members = tile.members
        AlertDialog.Builder(this).setTitle("Retirer du groupe · ${tile.label}")
            .setItems(members.map { "${it.name} · ${ChannelDescription.full(it)}" }.toTypedArray()) { _, index ->
                families.removeFromGroup(members[index].id)
                render()
            }.setNegativeButton("Annuler", null).show()
    }

    /** Pick an image from TV/USB/file providers; the result is copied to app-private
     * storage. No broad storage or Accessibility permission is requested. */
    private fun chooseLogoFile(channel: ZenChannel) {
        pendingLogoChannel = channel
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(pick, logoPickerRequest)
        } catch (_: Exception) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }, logoPickerRequest)
            } catch (_: Exception) {
                pendingLogoChannel = null
                Toast.makeText(this, "Sélecteur de fichiers absent sur ce téléviseur",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun channelActions(channel: ZenChannel) {
        val labels = arrayOf("Regarder / écouter", "Ajouter aux Favoris",
            "Ajouter à un groupe", "Afficher séparément", "Regroupement automatique",
            "Choisir un logo local (TV / USB)", "Supprimer mon logo local")
        AlertDialog.Builder(this).setTitle(channel.name)
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> openChannel(channel)
                    1 -> addChannelToPage(channel)
                    2 -> chooseGroup(channel)
                    3 -> { families.removeFromGroup(channel.id); render() }
                    4 -> { families.assign(channel.id, null); render() }
                    5 -> chooseLogoFile(channel)
                    6 -> {
                        logos.removeCustomLogo(channel)
                        logos.retryFailed()
                        render()
                    }
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun chooseGroup(channel: ZenChannel) {
        if (channel.radio) {
            Toast.makeText(this, "Les groupes TV sont réservés aux chaînes TV", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = families.keys(channels)
        val labels = keys.map { it.second } + "Créer un groupe…"
        AlertDialog.Builder(this).setTitle("Ajouter à un groupe · ${channel.name}")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == keys.size) {
                    val input = EditText(this).apply {
                        hint = "Nom du groupe"
                        isSingleLine = true
                    }
                    AlertDialog.Builder(this).setTitle("Nouveau groupe")
                        .setView(input)
                        .setPositiveButton("Créer") { _, _ ->
                            families.create(input.text.toString())?.let { key ->
                                families.assign(channel.id, key)
                                render()
                            }
                        }.setNegativeButton("Annuler", null).show()
                } else {
                    families.assign(channel.id, keys[index].first)
                    render()
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun channelError(title: String, detail: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(detail)
            .setPositiveButton("Compris", null)
            .show()
    }

    private fun openChannel(channel: ZenChannel) {
        when (client.tuneDetailed(channel)) {
            Zen4Client.TuneResult.SENT -> {
                // tuneDetailed queues the receiver command on its network executor.
                // Start HDMI immediately on the UI thread, without awaiting the reply.
                if (previewMode) {
                    Toast.makeText(this, "Commande en cours · ${channel.name}", Toast.LENGTH_SHORT).show()
                } else {
                    openHdmi1IfPossible()
                }
            }
            Zen4Client.TuneResult.DISCONNECTED ->
                channelError("Récepteur déconnecté",
                    "Impossible de sélectionner «${channel.name}». Zen 4 n'est pas connecté. Vérifiez le Wi-Fi du téléviseur et du récepteur, puis réessayez.")
            Zen4Client.TuneResult.CHANNEL_NOT_FOUND ->
                channelError("Chaîne introuvable",
                    "«${channel.name}» n'existe plus dans le catalogue actuel de Zen 4. Actualisez les chaînes du récepteur, puis réessayez.")
            Zen4Client.TuneResult.SEND_FAILED ->
                channelError("Commande non envoyée",
                    "Impossible d'envoyer la commande pour «${channel.name}». Vérifiez que Zen 4 est allumé et accessible sur le réseau.")
        }
    }

    /**
     * Detect the uniquely connected HDMI input on Lifemaxx.
     * TvInputManager reports input connectivity, not guaranteed active video.
     * If state is missing/ambiguous, use HDMI 1 only when its input is visible.
     * Never guess an input if the TV does not expose it.
     */
    private fun openHdmi1IfPossible() {
        if (!previewMode && hdmiBridgeEnabled()) {
            val bridgePrefs = hdmiBridgePrefs()
            if (bridgePrefs.getBoolean("auto_arm", false)) {
                bridgePrefs.edit().putBoolean("armed", true)
                    .putBoolean("hdmi_seen", false)
                    .putLong("armed_since", System.currentTimeMillis()).apply()
            }
        }
        var suggested = "HDMI 1"
        val handled = try {
            val manager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val inputs = manager?.tvInputList?.filter { it.type == TvInputInfo.TYPE_HDMI } ?: emptyList()
            val connected = inputs.filter { input ->
                try { manager?.getInputState(input.id) == TvInputManager.INPUT_STATE_CONNECTED }
                catch (_: Exception) { false }
            }
            val knownHdmi1 = inputs.firstOrNull { input ->
                val label = input.loadLabel(this).toString().replace(Regex("\\s+"), "")
                label.equals("HDMI1", ignoreCase = true) ||
                    input.id.contains("HDMI1", ignoreCase = true) ||
                    input.id.contains("HDMI_1", ignoreCase = true)
            }
            val target = if (connected.size == 1) connected.first() else knownHdmi1
            if (connected.size == 1 && target != null) {
                suggested = target.loadLabel(this).toString()
            }
            if (target != null) {
                val uri = TvContract.buildChannelUriForPassthroughInput(target.id)
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    true
                } else false
            } else false
        } catch (_: Exception) { false }
        if (!handled) {
            channelError("Passage HDMI indisponible",
                "La commande de chaîne a été confirmée par Zen 4, mais le téléviseur n'a pas pu basculer automatiquement vers " + suggested + ". Appuyez sur SOURCE / INPUT de la télécommande et choisissez " + suggested + ".")
        }
    }

    private fun addChannelToPage(channel: ZenChannel) {
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

    private fun changeIp() {
        val input = EditText(this).apply {
            hint = "192.168.1.100"
            setText(client.lastIp)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this).setTitle("Adresse IP du récepteur")
            .setView(input)
            .setPositiveButton("Connecter") { _, _ ->
                if (client.setHost(input.text.toString().trim()))
                    Toast.makeText(this, "Nouvelle connexion demandée", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "Adresse IP locale invalide", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Annuler", null).show()
    }

    /** Only Android/system UI can assign or remove the default HOME app. */
    private fun startSystemSettings(intents: List<Intent>, fallbackMessage: String) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Some Android TV firmwares omit stock Settings activities.
            }
        }
        Toast.makeText(this, fallbackMessage, Toast.LENGTH_LONG).show()
    }

    private fun openHomeChoiceSettings() {
        if (previewMode) return
        startSystemSettings(
            listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
            "Choix HOME indisponible : utilisez Paramètres système du téléviseur"
        )
    }

    private fun showHomeDiagnostic() {
        if (previewMode) return
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) { null }
        val component = resolved?.activityInfo?.let { "${it.packageName}/${it.name}" }
            ?: "Non résolu par Android"
        val role = if (Build.VERSION.SDK_INT >= 29) {
            try {
                val manager = getSystemService(RoleManager::class.java)
                when {
                    manager == null || !manager.isRoleAvailable(RoleManager.ROLE_HOME) -> "ROLE_HOME indisponible"
                    manager.isRoleHeld(RoleManager.ROLE_HOME) -> "SatNova détient ROLE_HOME"
                    else -> "SatNova ne détient pas ROLE_HOME"
                }
            } catch (_: Exception) { "ROLE_HOME non vérifiable" }
        } else "ROLE_HOME non disponible sur cet Android"
        val report = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" +
            "\\nFabricant: ${Build.MANUFACTURER}" +
            "\\nMarque: ${Build.BRAND}" +
            "\\nModèle: ${Build.MODEL}" +
            "\\nHOME résolu: $component" +
            "\\nRôle: $role" +
            "\\nSatNova: $packageName" +
            "\\nCes informations ne prouvent pas que la touche HOME physique suit le choix Android."
        AlertDialog.Builder(this)
            .setTitle("Diagnostic accueil HOME")
            .setMessage(report)
            .setPositiveButton("Copier") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SatNova HOME", report))
                Toast.makeText(this, "Diagnostic copié", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Choisir HOME") { _, _ -> requestSatNovaHome() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun requestSatNovaHome() {
        if (previewMode) return
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val roles = getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                        showHomeDiagnostic()
                        return
                    }
                    startActivityForResult(
                        roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 7420)
                    return
                }
            } catch (_: Exception) {
                // Fall back to the device's own launcher selector.
            }
        }
        openHomeChoiceSettings()
    }

    private fun restoreOriginalHome() {
        if (previewMode) return
        AlertDialog.Builder(this)
            .setTitle("Revenir à l'accueil d'origine")
            .setMessage("L'accueil d'origine n'a pas été supprimé. SatNova ouvrira les réglages Android TV : sélectionnez le lanceur LifeMax / d'origine et confirmez. Sur certains téléviseurs, le fabricant bloque ce choix ; SatNova ne peut pas le contourner.")
            .setPositiveButton("Choisir l'accueil d'origine") { _, _ ->
                openHomeChoiceSettings()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openAndroidSystemSettings() {
        if (previewMode) return
        startSystemSettings(listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent("android.settings.TV_SETTINGS")
        ), "Réglages système indisponibles sur ce firmware LifeMax")
    }

    private fun openDeveloperOptions() {
        if (previewMode) return
        AlertDialog.Builder(this)
            .setTitle("Options pour les développeurs · ADB")
            .setMessage("SatNova ouvre uniquement les réglages Android TV. Si les options développeur ne sont pas visibles, allez dans Paramètres système → À propos → Numéro de build et appuyez généralement 7 fois sur OK, si LifeMax le permet. Activez ensuite le débogage ADB/USB manuellement. SatNova n'active jamais ADB à votre place.")
            .setPositiveButton("Ouvrir les options") { _, _ ->
                startSystemSettings(listOf(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS)
                ), "Options de développement indisponibles sur LifeMax")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Escape hatch stays available after choosing SatNova as default HOME. */
    private fun androidSystemMenu() {
        if (previewMode) return
        val labels = arrayOf(
            "Tous les paramètres Android TV",
            "Wi-Fi / Réseau",
            "Options développeur / Débogage ADB",
            "Informations du téléviseur",
            "Bluetooth et accessoires",
            "Applications installées",
            "Accessibilité"
        )
        AlertDialog.Builder(this).setTitle("Paramètres du système LifeMax")
            .setItems(labels) { _, selected ->
                when (selected) {
                    0 -> openAndroidSystemSettings()
                    1 -> openWifiSettings()
                    2 -> openDeveloperOptions()
                    3 -> startSystemSettings(listOf(
                        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS)
                    ), "Informations de l'appareil indisponibles")
                    4 -> startSystemSettings(listOf(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS)
                    ), "Réglages Bluetooth indisponibles")
                    5 -> startSystemSettings(listOf(
                        Intent(Settings.ACTION_APPLICATION_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS)
                    ), "Réglages des applications indisponibles")
                    6 -> openAccessibilitySettings()
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun openWifiSettings() {
        if (previewMode) return
        startSystemSettings(listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        ), "Ouvrez Réseau et Internet dans les paramètres du téléviseur")
    }

    private fun openAccessibilitySettings() {
        if (previewMode) return
        startSystemSettings(listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        ), "Ouvrez Accessibilité dans les paramètres du téléviseur")
    }

    private fun firstStartGuide() {
        val bridgeAvailable = hdmiBridgeAvailable()
        AlertDialog.Builder(this)
            .setTitle("Bienvenue dans SatNova TV Home")
            .setMessage(if (bridgeAvailable)
                "SatNova peut devenir votre accueil par défaut. Le module facultatif de contrôle HDMI demande une autorisation Accessibilité distincte. Le choix HOME reste réversible."
                else "SatNova peut devenir votre accueil par défaut, sans autorisation Accessibilité. Le retour à l'accueil original et les paramètres système restent accessibles.")
            .setItems(if (bridgeAvailable) arrayOf(
                "1 · Choisir SatNova comme accueil par défaut",
                "2 · Autoriser la télécommande LifeMax → Zen 4",
                "3 · Réglages Wi-Fi du téléviseur"
            ) else arrayOf(
                "1 · Choisir SatNova comme accueil par défaut",
                "2 · Réglages Wi-Fi du téléviseur"
            )) { _, which ->
                when (which) {
                    0 -> requestSatNovaHome()
                    1 -> if (bridgeAvailable) explainAccessibility() else openWifiSettings()
                    2 -> openWifiSettings()
                }
            }
            .setPositiveButton("Plus tard") { _, _ ->
                window.decorView.postDelayed({ restoreLauncherFocus(force = true) }, 100L)
            }
            .show()
    }

    private fun hdmiBridgeAvailable(): Boolean = try {
        packageManager.getServiceInfo(ComponentName(this,
            Zen4HdmiRemoteService::class.java), 0)
        true
    } catch (_: Exception) { false }

    private fun explainAccessibility() {
        if (previewMode) return
        if (!hdmiBridgeAvailable()) {
            AlertDialog.Builder(this).setTitle("Télécommande HDMI")
                .setMessage("Cette édition standard ne demande pas Accessibilité et ne peut pas capter les touches TV dans l'écran HDMI. Le changement de chaîne via Zen 4 et l'ouverture HDMI restent disponibles. Le pont HDMI est réservé à l'édition optionnelle et doit être testé sur LifeMax.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (hdmiBridgeEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Télécommande autorisée")
                .setMessage("L'accès Accessibilité est activé. Le contrôle sur HDMI doit encore être lancé dans SatNova → Paramètres → Télécommande Lifemaxx → Zen 4. L'efficacité des touches MENU, OK et flèches dépend du firmware LifeMax.")
                .setPositiveButton("Options de télécommande") { _, _ -> hdmiRemoteMenu() }
                .setNegativeButton("Fermer", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Activer la télécommande sur HDMI")
                .setMessage("La fonctionnalité est facultative. Dans les paramètres Android TV, recherchez « SatNova · Télécommande Zen 4 sur HDMI » dans Accessibilité, puis autorisez-la vous-même. Cela permet de transmettre certaines touches uniquement lorsque l'écran HDMI autorisé est actif. HOME, SOURCE, volume et alimentation restent contrôlés par LifeMax. Vous pouvez désactiver le service à tout moment.")
                .setPositiveButton("Ouvrir Accessibilité") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }


    /** HDMI input is not the HOME foreground app. The Accessibility permission
     * remains entirely under user's control in Android TV Settings. */
    private fun hdmiBridgePrefs() =
        getSharedPreferences("zen4_hdmi_bridge", Context.MODE_PRIVATE)

    private fun hdmiBridgeEnabled(): Boolean {
        if (!hdmiBridgeAvailable()) return false
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val id = ComponentName(this, Zen4HdmiRemoteService::class.java)
            .flattenToString()
        return enabled.split(':').any { it.equals(id, ignoreCase = true) }
    }

    private fun hdmiRemoteMenu() {
        if (previewMode) return
        if (!hdmiBridgeAvailable()) { explainAccessibility(); return }
        val prefs = hdmiBridgePrefs()
        val enabled = hdmiBridgeEnabled()
        val lastPackage = prefs.getString("observed_viewer_package", "").orEmpty()
        val packageApproved = prefs.getString("confirmed_hdmi_package", "").orEmpty()
        val status = buildString {
            append("Service Accessibilité : ")
            append(if (enabled) "activé" else "désactivé")
            append("\nActivation au changement de chaîne : ")
            append(if (prefs.getBoolean("auto_arm", false)) "oui" else "non")
            append("\nMode Zen 4 sur HDMI : ")
            append(if (prefs.getBoolean("armed", false)) "armé" else "arrêté")
            append("\nDernière touche TV : ")
            append(prefs.getInt("last_tv_key", -1).takeIf { it >= 0 } ?: "aucune")
            append("\nTraduction Zen 4 : ")
            append(prefs.getInt("last_zen_key", -1).takeIf { it >= 0 } ?: "aucune")
            append("\nDernier envoi : ")
            append(prefs.getString("last_forward_result", "non testé"))
            append("\nApplication HDMI observée : ")
            append(lastPackage.ifBlank { "inconnue" })
            append("\nApplication HDMI autorisée : ")
            append(packageApproved.ifBlank { "détection connue uniquement" })
        }
        val options = arrayOf(
            "Activer le contrôle Zen 4 sur HDMI",
            "Autoriser le service dans Accessibilité",
            "Autoriser l'application HDMI détectée",
            "Activer / désactiver le contrôle automatique après zapping",
            "Arrêter le contrôle HDMI"
        )
        AlertDialog.Builder(this).setTitle("Télécommande Lifemaxx → Zen 4")
            .setMessage(status)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> {
                        if (!hdmiBridgeEnabled()) {
                            AlertDialog.Builder(this).setTitle("Autorisation nécessaire")
                                .setMessage("Autorisez volontairement le service SatNova · Télécommande Zen 4 sur HDMI dans les paramètres Accessibilité. Puis revenez ici.")
                                .setPositiveButton("Accessibilité") { _, _ ->
                                    openAccessibilitySettings()
                                }.setNegativeButton("Annuler", null).show()
                        } else {
                            prefs.edit()
                                .putBoolean("armed", true)
                                .putBoolean("hdmi_seen", false)
                                .putLong("armed_since", System.currentTimeMillis())
                                .apply()
                            AlertDialog.Builder(this)
                                .setTitle("Mode HDMI activé")
                                .setMessage("Les flèches, OK, BACK, MENU, CH+/CH−, EPG et les chiffres seront convertis en commandes Zen 4 uniquement si l'application HDMI est reconnue. HOME, SOURCE, le volume et l'alimentation restent à la TV. Vous pouvez interrompre le mode en revenant à HOME. Expiration automatique après deux heures.")
                                .setPositiveButton("Essayer HDMI") { _, _ -> openHdmi1IfPossible() }
                                .setNeutralButton("Je passe par SOURCE", null)
                                .show()
                        }
                    }
                    1 -> explainAccessibility()
                    2 -> {
                        val forbidden = listOf("", packageName,
                            "com.android.systemui", "com.android.settings",
                            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx")
                        if (lastPackage in forbidden) {
                            Toast.makeText(this,
                                "Aucune application HDMI fiable détectée. Tester d'abord la source HDMI.",
                                Toast.LENGTH_LONG).show()
                        } else {
                            AlertDialog.Builder(this).setTitle("Confirmer l'application HDMI")
                                .setMessage("Autoriser la transmission des touches Zen 4 uniquement lorsque l'application « $lastPackage » est au premier plan ? Vérifiez que cette application affiche bien votre HDMI.")
                                .setPositiveButton("Autoriser") { _, _ ->
                                    prefs.edit().putString("confirmed_hdmi_package", lastPackage).apply()
                                }.setNegativeButton("Annuler", null).show()
                        }
                    }
                    3 -> {
                        val enable = !prefs.getBoolean("auto_arm", false)
                        prefs.edit().putBoolean("auto_arm", enable).apply()
                        Toast.makeText(this, if (enable)
                            "Contrôle HDMI automatique activé après sélection d'une chaîne"
                            else "Contrôle HDMI automatique désactivé", Toast.LENGTH_LONG).show()
                    }
                    4 -> {
                        prefs.edit().putBoolean("armed", false)
                            .putBoolean("hdmi_seen", false).apply()
                        Toast.makeText(this, "Contrôle HDMI arrêté", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun settings() {
        val options = arrayOf(
            "Applications favorites", "Ajouter une page Favoris",
            "Renommer la page Favoris", "Adresse IP de Zen 4",
            "Afficher / masquer l'historique", "مدينة الصلاة والطقس",
            "إدخال أوقات الوزارة لليوم", "إدخال أوقات الوزارة للغد",
            "موقع وزارة الأوقاف الرسمي",
            "Entrée HDMI : détection automatique", "Actualiser les logos",
            "Réinitialiser les groupes TV", "Tester ouverture HDMI du récepteur",
            "Choisir SatNova comme accueil par défaut",
            "Revenir à l'accueil LifeMax d'origine",
            "Configurer Accessibilité · télécommande HDMI",
            "Télécommande Lifemaxx → Zen 4 (HDMI)",
            "Paramètres Wi-Fi du téléviseur",
            "Paramètres Android TV (système, développeur, ADB)",
            "Diagnostic HOME · fabricant, rôle et accueil résolu"
        )
        AlertDialog.Builder(this).setTitle("Paramètres")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> chooseApps()
                    1 -> askPageName(null)
                    2 -> askPageName(activePage)
                    3 -> changeIp()
                    4 -> {
                        val next = !prefs.getBoolean("show_history", false)
                        prefs.edit().putBoolean("show_history", next).apply()
                        render()
                    }
                    5 -> islamicPanel.chooseCity { render() }
                    6 -> islamicPanel.editOfficialDay(false) { render() }
                    7 -> islamicPanel.editOfficialDay(true) { render() }
                    8 -> try {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.habous.gov.ma/")))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Navigateur indisponible", Toast.LENGTH_SHORT).show()
                    }
                    9 -> Toast.makeText(this, "Détection HDMI automatique, HDMI 1 en secours", Toast.LENGTH_SHORT).show()
                    10 -> { logos.retryFailed(); render() }
                    11 -> AlertDialog.Builder(this).setTitle("Réinitialiser les groupes ?")
                        .setMessage("Restaure les regroupements automatiques de l'application, sans modifier le récepteur.")
                        .setPositiveButton("Réinitialiser") { _, _ -> families.resetAll(); render() }
                        .setNegativeButton("Annuler", null).show()
                    12 -> if (!previewMode) openHdmi1IfPossible()
                    13 -> requestSatNovaHome()
                    14 -> restoreOriginalHome()
                    15 -> explainAccessibility()
                    16 -> hdmiRemoteMenu()
                    17 -> openWifiSettings()
                    18 -> androidSystemMenu()
                    19 -> showHomeDiagnostic()
                }
            }.show()
    }

    /**
     * Save a compact, privacy-filtered snapshot from *actual* read-only
     * receiver responses. No extra protocol command, settings change,
     * image scraping or receiver login data export.
     */
    private fun saveReceiverDiagnostic() {
        diagnosticToSave = try { client.exportDiagnostic() } catch (_: Exception) {
            Toast.makeText(this, "Impossible de préparer le diagnostic", Toast.LENGTH_LONG).show()
            null
        }
        if (diagnosticToSave == null) return
        val filename = "Zen4_CHMAX_Diagnostic_" + System.currentTimeMillis() + ".json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        try {
            startActivityForResult(intent, diagnosticSaveRequest)
        } catch (_: Exception) {
            diagnosticToSave = null
            Toast.makeText(this, "Aucune application pour enregistrer ce fichier", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Used for compatibility with Android 8+ and the system document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == logoPickerRequest) {
            val channel = pendingLogoChannel
            pendingLogoChannel = null
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null || channel == null) return
            fileProbeWorker.execute {
                val success = try {
                    contentResolver.openInputStream(uri)?.use {
                        logos.importCustomLogo(channel, it)
                    } ?: false
                } catch (_: Exception) { false }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (success) { logos.retryFailed(); render() }
                    Toast.makeText(this, if (success)
                        "Logo conservé sur ce téléviseur" else
                        "Image non prise en charge (max 700 Ko)",
                        Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (requestCode != diagnosticSaveRequest) return
        val json = diagnosticToSave
        diagnosticToSave = null
        if (resultCode != Activity.RESULT_OK || data?.data == null || json == null) return
        try {
            contentResolver.openOutputStream(data.data!!, "w")?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Écriture indisponible")
            Toast.makeText(this, "Diagnostic Zen 4 enregistré", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Échec de l'enregistrement du diagnostic", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Safe service discovery on the *authenticated* Zen4 IP only.
     * Tests common file sharing ports; never enumerates files or uses passwords.
     */
    private fun checkReceiverFileAccess() {
        val host = client.verifiedHost
        if (host == null) {
            Toast.makeText(this, "Connectez Zen 4 avant le test Wi-Fi", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Vérification des services réseau…", Toast.LENGTH_SHORT).show()
        fileProbeWorker.execute {
            val result = try { Zen4FileAccessProbe.inspect(host) }
                catch (_: Exception) { "Impossible de vérifier les services réseau." }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("Fichiers Zen 4 · Wi-Fi")
                    .setMessage(result)
                    .setPositiveButton("Copier le résultat") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Zen 4 · Diagnostic réseau", result))
                        Toast.makeText(this, "Résultat copié", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Fermer", null)
                    .show()
            }
        }
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

}
