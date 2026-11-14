package com.satnova.alpha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Draws only receiver-reported percentages; a missing/stale reading is shown as a dash. */
class SignalGaugeView(context:Context,private val label:String,private val accent:Int):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var value:Int?=null
    init {setWillNotDraw(false);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;setValue(null)}
    fun setValue(percent:Int?){
        value=percent?.takeIf{it in 0..100}
        contentDescription="$label : "+(value?.let{"$it %"}?:"mesure indisponible")
        invalidate()
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val w=width.toFloat();val h=height.toFloat()
        val cx=w/2f;val cy=h/2f
        val radius=min(w,h)*.38f
        val stroke=(radius*.095f).coerceAtLeast(3f)
        val bounds=RectF(cx-radius,cy-radius,cx+radius,cy+radius)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.strokeCap=Paint.Cap.ROUND
        paint.color=Color.rgb(43,49,73)
        canvas.drawArc(bounds,135f,270f,false,paint)
        value?.let{
            paint.color=accent
            canvas.drawArc(bounds,135f,270f*it/100f,false,paint)
        }
        paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER
        paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
        paint.color=Color.rgb(247,247,255)
        paint.textSize=radius*.64f
        val number=value?.let{"$it%"}?:"—"
        canvas.drawText(number,cx,cy-(paint.ascent()+paint.descent())/2f,paint)
        paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.NORMAL)
        paint.textSize=radius*.22f
        paint.color=accent
        canvas.drawText(label,cx,cy+radius*.48f,paint)
    }
}
