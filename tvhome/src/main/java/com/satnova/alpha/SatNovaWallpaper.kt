package com.satnova.alpha

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

/**
 * Islamic theme retains the original bundled artwork unchanged.
 * Optional real 2025 Moulay Abdellah Stadium photo © Reda Benkhadra,
 * CC BY-SA 4.0; credit appears in the background picker.
 * Sports photo downloaded once on demand into private app storage.
 */
internal class SatNovaWallpaper(
    private val context: Context,
    private val prefs: SharedPreferences
) : Drawable() {
    // The original Islamic background remains untouched and the default.
    private val islamic: Bitmap? = try {
        context.assets.open("satnova_wallpaper.webp").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }
    // The sports photo is bundled at 1920×1080 for instant offline switching.
    private val stadium: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            context.assets.open("satnova_sport_theme.webp").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }
    private val customFile = File(context.filesDir, "satnova_custom_background.jpg")
    private var customBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun reload() {
        customBitmap = null
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(4, 33, 26))
        val mode = prefs.getString("ui_theme", "islamic")
        val bitmap = when(mode) {
            "sport" -> stadium ?: islamic
            "custom" -> {
                if (customBitmap == null && customFile.isFile)
                    customBitmap = BitmapFactory.decodeFile(customFile.absolutePath)
                customBitmap ?: islamic
            }
            else -> islamic
        }
        bitmap?.let {
            val b = bounds
            canvas.drawBitmap(it, null, Rect(b.left, b.top, b.right, b.bottom), paint)
        }
        if (mode == "custom") {
            val overlay = Paint().apply { color = Color.argb(105, 0, 18, 22) }
            canvas.drawRect(bounds, overlay)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Deliberately restrained Islamic arch and lantern, for the left side of the info bar. */
internal class IslamicLanternView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = (w / 120f).coerceAtMost(h / 150f)
        c.save()
        c.translate((w - 120 * scale) / 2f, (h - 150 * scale) / 2f)
        c.scale(scale, scale)
        val arch = Path().apply {
            moveTo(12f, 144f); lineTo(12f, 48f)
            cubicTo(12f, 21f, 27f, 15f, 42f, 13f)
            cubicTo(50f, 6f, 59f, 4f, 60f, 0f)
            cubicTo(66f, 8f, 75f, 11f, 80f, 13f)
            cubicTo(100f, 17f, 108f, 32f, 108f, 48f)
            lineTo(108f, 144f); close()
        }
        p.style = Paint.Style.FILL
        p.color = Color.rgb(6, 29, 25)
        c.drawPath(arch, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        p.color = Color.rgb(184, 133, 53)
        c.drawPath(arch, p)
        p.strokeWidth = 1.5f; p.color = Color.rgb(243, 194, 93)
        c.drawRoundRect(RectF(18f, 35f, 102f, 145f), 6f, 6f, p)
        p.style = Paint.Style.FILL
        val glow = RadialGradient(60f, 93f, 41f,
            intArrayOf(Color.argb(165, 255, 188, 55), Color.argb(20, 255, 137, 32), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        p.shader = glow
        c.drawCircle(60f, 93f, 41f, p)
        p.shader = null
        p.color = Color.rgb(232, 188, 96)
        p.strokeWidth = 2f
        c.drawRect(59f, 37f, 61f, 69f, p)
        val lantern = Path().apply {
            moveTo(49f, 72f); lineTo(71f, 72f); lineTo(76f, 80f)
            lineTo(69f, 113f); lineTo(51f, 113f); lineTo(44f, 80f); close()
        }
        p.color = Color.rgb(252, 202, 92); p.style = Paint.Style.STROKE
        c.drawPath(lantern, p)
        p.color = Color.argb(210, 255, 199, 61); p.style = Paint.Style.FILL
        c.drawOval(RectF(52f, 83f, 68f, 106f), p)
        p.color = Color.rgb(255, 223, 130)
        c.drawRect(52f, 70f, 68f, 74f, p)
        c.restore()
    }
}
