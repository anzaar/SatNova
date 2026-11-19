package com.satnova.alpha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original Atlas Lions emblem for the optional Moroccan sports theme.
 * Not an FRMF team crest, trademark, sponsor logo or photograph of a player.
 * Drawn by Android Canvas; remains sharp on 4K TVs and needs no network.
 */
internal class AtlasLionsEmblemView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val green = Color.rgb(22, 173, 102)
    private val gold = Color.rgb(244, 199, 118)
    private val crimson = Color.rgb(151, 21, 44)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width / 120f, height / 148f)
        canvas.save()
        canvas.translate((width - 120 * s) / 2f, (height - 148 * s) / 2f)
        canvas.scale(s, s)
        p.shader = LinearGradient(0f, 0f, 120f, 148f,
            intArrayOf(Color.rgb(24, 14, 27), Color.rgb(102, 21, 40),
                Color.rgb(18, 47, 37)), null, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(2f, 2f, 118f, 146f), 20f, 20f, p)
        p.shader = null
        p.color = gold
        p.alpha = 205
        p.strokeWidth = 2.5f
        p.style = Paint.Style.STROKE
        canvas.drawRoundRect(RectF(4f, 4f, 116f, 144f), 18f, 18f, p)
        p.alpha = 255
        p.style = Paint.Style.FILL

        // Subtle stadium-crest halo.
        p.color = Color.argb(72, 30, 170, 106)
        canvas.drawCircle(60f, 70f, 47f, p)
        p.color = gold
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        canvas.drawCircle(60f, 70f, 44f, p)
        p.style = Paint.Style.FILL

        // Geometric lion mane, entirely original vector paths.
        val mane = Path()
        val cx = 60f; val cy = 78f
        for (i in 0 until 28) {
            val angle = 2.0 * Math.PI * i / 28.0 - Math.PI / 2.0
            val radius = if (i % 2 == 0) 42.0 else 31.0
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) mane.moveTo(x, y) else mane.lineTo(x, y)
        }
        mane.close()
        p.color = Color.rgb(215, 143, 62)
        canvas.drawPath(mane, p)
        p.color = gold
        p.strokeWidth = 1.6f
        p.style = Paint.Style.STROKE
        canvas.drawPath(mane, p)
        p.style = Paint.Style.FILL

        // Lion head profile: ears, broad cheeks and angular muzzle.
        val face = Path().apply {
            moveTo(35f, 65f); lineTo(43f, 50f); lineTo(53f, 58f)
            lineTo(60f, 53f); lineTo(74f, 54f); lineTo(85f, 64f)
            lineTo(82f, 80f); lineTo(89f, 92f); lineTo(80f, 99f)
            lineTo(72f, 99f); lineTo(65f, 113f); lineTo(56f, 113f)
            lineTo(45f, 99f); lineTo(36f, 96f); lineTo(31f, 83f)
            close()
        }
        p.color = Color.rgb(247, 201, 123)
        canvas.drawPath(face, p)
        // Two shaded eyes and nose avoid reliance on font emoji glyphs.
        p.color = Color.rgb(64, 39, 33)
        canvas.drawOval(RectF(42f, 73f, 50f, 78f), p)
        canvas.drawOval(RectF(70f, 73f, 78f, 78f), p)
        val nose = Path().apply {
            moveTo(53f, 93f); lineTo(69f, 93f); lineTo(61f, 102f); close()
        }
        p.color = crimson
        canvas.drawPath(nose, p)
        p.style = Paint.Style.STROKE
        p.color = Color.rgb(112, 61, 43)
        p.strokeWidth = 2f
        val mouth = Path().apply { moveTo(61f, 101f); lineTo(61f, 107f);
            moveTo(51f, 106f); quadTo(61f, 114f, 71f, 106f) }
        canvas.drawPath(mouth, p)
        p.style = Paint.Style.FILL

        // National star at top; an independent Moroccan symbol.
        val star = Path()
        for (i in listOf(0, 2, 4, 1, 3, 0)) {
            val a = (-Math.PI / 2 + i * 2 * Math.PI / 5)
            val x = 60f + 10f * cos(a).toFloat()
            val y = 24f + 10f * sin(a).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.5f
        p.color = green
        canvas.drawPath(star, p)
        p.style = Paint.Style.FILL
        canvas.restore()
    }
}
