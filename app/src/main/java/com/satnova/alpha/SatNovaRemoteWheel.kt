package com.satnova.alpha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Native, fully interactive remote D-pad. Every press sends a real G-MScreen key,
 * not a synthetic/fake remote or screenshot.
 */
class SatNovaRemoteWheel(
    context: Context,
    private val pressKey: (Int) -> Unit
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE
        strokeWidth=2f*resources.displayMetrics.density
    }
    private val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        textAlign=Paint.Align.CENTER
        typeface=android.graphics.Typeface.create("sans-serif-medium",1)
    }
    private val bounds=RectF()
    private val inner=RectF()
    private val lavender=Color.rgb(201,170,255)
    private val purple=Color.rgb(119,60,205)
    private var active=-1
    private var touchZone=-1
    private val Int.dp get()=(this*resources.displayMetrics.density+0.5f).toInt()

    init {
        contentDescription="Télécommande : flèche haut, flèche bas, flèche gauche, flèche droite, et OK pour valider"
        isClickable=true
        isFocusable=true
        minimumHeight=190.dp
    }
    override fun onMeasure(w:Int,h:Int){
        val preferred=238.dp
        val ww=resolveSize(preferred,w)
        val hh=resolveSize(preferred,h)
        setMeasuredDimension(ww,hh)
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val cx=width/2f;val cy=height/2f
        val radius=min(width,height)*0.46f
        val inside=radius*0.37f
        bounds.set(cx-radius,cy-radius,cx+radius,cy+radius)
        inner.set(cx-inside,cy-inside,cx+inside,cy+inside)
        val starts=floatArrayOf(-133f,-43f,47f,137f) // up right down left
        for(i in 0..3){
            val p=Path().apply{
                arcTo(bounds,starts[i],86f)
                arcTo(inner,starts[i]+86f,-86f)
                close()
            }
            fill.color=when {
                active==i -> Color.rgb(113,74,196)
                else -> Color.rgb(40,38,73)
            }
            canvas.drawPath(p,fill)
            outline.color=Color.rgb(78,63,121)
            canvas.drawPath(p,outline)
        }
        fill.color=if(active==4)Color.rgb(151,85,242) else purple
        canvas.drawCircle(cx,cy,inside-2f,fill)
        outline.color=lavender
        canvas.drawCircle(cx,cy,inside-2f,outline)
        labelPaint.textSize=21f*resources.displayMetrics.scaledDensity
        canvas.drawText("OK",cx,cy-(labelPaint.ascent()+labelPaint.descent())/2f,labelPaint)
        val d=radius*0.70f
        val density=resources.displayMetrics.density
        labelPaint.textSize=27f*resources.displayMetrics.scaledDensity
        for((symbol,x,y) in listOf(
            Triple("⌃",cx,cy-d-7*density),
            Triple("›",cx+d,cy-8*density),
            Triple("⌄",cx,cy+d-8*density),
            Triple("‹",cx-d,cy-8*density))){
            canvas.drawText(symbol,x,y-(labelPaint.ascent()+labelPaint.descent())/2f,labelPaint)
        }
        // Fully named directions: accessible for users unfamiliar with icons.
        labelPaint.textSize=10f*resources.displayMetrics.scaledDensity
        labelPaint.color=Color.WHITE
        for((name,x,y) in listOf(
            Triple("HAUT",cx,cy-d+14*density),
            Triple("DROITE",cx+d,cy+19*density),
            Triple("BAS",cx,cy+d+19*density),
            Triple("GAUCHE",cx-d,cy+19*density))){
            canvas.drawText(name,x,y,labelPaint)
        }
        labelPaint.color=Color.WHITE
    }
    private fun zone(x:Float,y:Float):Int{
        val dx=x-width/2f;val dy=y-height/2f
        val radius=min(width,height)*0.46f
        val dist=sqrt(dx*dx+dy*dy)
        if(dist>radius)return -1
        if(dist<radius*0.37f)return 4
        val degrees=(Math.toDegrees(atan2(dy.toDouble(),dx.toDouble()))+360)%360
        return when{
            degrees>=225&&degrees<315 -> 0 // up
            degrees>=315||degrees<45 -> 1 // right
            degrees>=45&&degrees<135 -> 2 // down
            else -> 3 // left
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                touchZone=zone(event.x,event.y)
                active=touchZone
                parent?.requestDisallowInterceptTouchEvent(touchZone>=0)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE->{
                active=zone(event.x,event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP->{
                val selected=zone(event.x,event.y)
                active=-1
                invalidate()
                if(selected>=0 && selected==touchZone){
                    val code=when(selected){0->1;1->4;2->2;3->3;else->5}
                    pressKey(code)
                    performClick()
                }
                touchZone=-1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL->{
                active=-1;touchZone=-1;invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
    override fun performClick():Boolean {
        super.performClick()
        return true
    }
}
