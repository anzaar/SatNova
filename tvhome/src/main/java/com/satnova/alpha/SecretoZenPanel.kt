package com.satnova.alpha

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Zen channels as a separate, non-focusable Accessibility overlay.
 * It NEVER edits Secreto's APK, assets, app preferences or HOME role.
 *
 * Read-only bar is shown only when the Secreto HOME package is foreground.
 * GUIDE opts into D-pad channel selection; BACK exits selection and returns
 * the original D-pad behavior to Secreto. No overlays in HDMI/other apps.
 */
internal class SecretoZenPanel(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val logos by lazy { ChannelLogoStore(context) }
    private var root: LinearLayout? = null
    private var channels: List<ZenChannel> = emptyList()
    private var connected = false
    private var onSecreto = false
    private var selecting = false
    private var selected = 0

    private fun dp(n: Int): Int = (n * context.resources.displayMetrics.density + .5f).toInt()
    private fun rounded(color: Int, outline: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
            if (outline != Color.TRANSPARENT) setStroke(dp(2), outline)
        }

    fun setChannels(value: List<ZenChannel>) {
        // A receiver disconnect sends state=false, never an empty catalogue.
        if (value.isNotEmpty() || channels.isEmpty()) {
            channels = value.filter { !it.radio }.sortedBy { it.position }
            selected = selected.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
            render()
        }
    }

    fun setConnected(value: Boolean) {
        if (connected != value) {
            connected = value
            render()
        }
    }

    fun setSecretoForeground(value: Boolean) {
        if (onSecreto == value) return
        onSecreto = value
        if (value) show() else hide()
    }

    fun isSecretoForeground() = onSecreto

    private fun show() {
        if (root != null || !onSecreto) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.argb(216, 5, 19, 29))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(190)
            title = "AltaPlay Zen 4 channels"
        }
        try {
            wm.addView(panel, params)
            root = panel
            render()
        } catch (_: Exception) {
            // Some TV firmwares disallow Accessibility overlays.
            root = null
        }
    }

    private fun hide() {
        selecting = false
        root?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        root = null
    }

    private fun text(label: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(context).apply {
            setText(label)
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun render() {
        val panel = root ?: return
        panel.removeAllViews()
        val state = when {
            connected -> "Zen 4 connecté"
            channels.isNotEmpty() -> "Zen 4 hors ligne · liste mémorisée"
            else -> "Zen 4 non connecté · recherche en cours"
        }
        panel.addView(text(
            "AltaPlay Launcher · $state · ${channels.size} chaînes" +
                if (selecting) "  |  ◀ ▶ sélectionner · OK regarder · BACK fermer" else
                    "  |  GUIDE pour sélectionner",
            14f, if (connected) Color.rgb(188, 255, 204) else Color.rgb(255, 208, 150)
        ))
        if (channels.isEmpty()) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val visible = 5
        val start = (selected - visible / 2).coerceIn(0, (channels.size - visible).coerceAtLeast(0))
        channels.drop(start).take(visible).forEachIndexed { j, channel ->
            val i = start + j
            val focused = selecting && i == selected
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(6), dp(7), dp(6))
                background = rounded(
                    if (focused) Color.rgb(44, 83, 78) else Color.argb(145, 21, 37, 48),
                    if (focused) Color.rgb(126, 242, 190) else Color.TRANSPARENT)
            }
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(108), dp(59)))
            logos.bind(icon, channel)
            tile.addView(text(channel.name, 12f), LinearLayout.LayoutParams(dp(115), dp(23)))
            row.addView(tile, LinearLayout.LayoutParams(dp(128), dp(106)).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        panel.addView(row)
    }

    /** Called only for verified Secreto foreground events. */
    fun handleKey(event: KeyEvent, zen: Zen4Client?, openHdmi: () -> Boolean): Boolean {
        if (!onSecreto) return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER ||
            code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_TV_INPUT) return false
        if (code == KeyEvent.KEYCODE_GUIDE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                selecting = !selecting
                render()
            }
            return true
        }
        if (!selecting) return false
        val managed = code in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK
        )
        if (!managed) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        when (code) {
            KeyEvent.KEYCODE_BACK -> { selecting = false; render() }
            KeyEvent.KEYCODE_DPAD_LEFT -> { selected = (selected - 1).coerceAtLeast(0); render() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                selected = (selected + 1).coerceAtMost((channels.size - 1).coerceAtLeast(0)); render()
            }
            KeyEvent.KEYCODE_DPAD_UP -> { selected = (selected - 5).coerceAtLeast(0); render() }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                selected = (selected + 5).coerceAtMost((channels.size - 1).coerceAtLeast(0)); render()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val channel = channels.getOrNull(selected)
                if (channel == null || zen?.connected != true) {
                    Toast.makeText(context, "Zen 4 hors ligne : chaînes conservées", Toast.LENGTH_SHORT).show()
                } else if (zen.tuneDetailed(channel) == Zen4Client.TuneResult.SENT) {
                    // Same order as existing SatNova: queue tune first, then request HDMI.
                    if (!openHdmi()) {
                        Toast.makeText(context, "Sélectionnez HDMI avec SOURCE", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "Commande Zen 4 non envoyée", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    fun close() {
        hide()
        logos.close()
    }
}
