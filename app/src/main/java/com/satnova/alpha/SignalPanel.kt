package com.satnova.alpha

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Compact live tuner gauge shared by the home remote and channel-list modes. */
class SignalPanel(private val activity:Activity,private val onToggle:()->Unit) {
    private val Int.dp get()=(this*activity.resources.displayMetrics.density+.5f).toInt()
    val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(0,12.dp,0,0)}
    private val green=Color.rgb(83,226,177)
    private val purple=Color.rgb(170,151,255)
    private fun label(s:String,size:Float=13f)=TextView(activity).apply{
        text=s;textSize=size;setTextColor(Color.rgb(224,224,245));includeFontPadding=false
    }
    private val heading=label("SIGNAL • EN ATTENTE",10f)
    private val strengthText=label("Force  —",13f)
    private val qualityText=label("Qualité  —",13f)
    private val strengthBar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{
        max=100;progressTintList=ColorStateList.valueOf(purple)
    }
    private val qualityBar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{
        max=100;progressTintList=ColorStateList.valueOf(green)
    }
    private val state=label("Lecture du tuner…",11f)
    private val sound=label("♫  Son",12f)
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var audible=false
    private var closed=false
    private var sample:ReceiverTelemetry.Signal?=null
    private var sampledAt=0L
    private var tone:ToneGenerator?=null
    private val pulse=object:Runnable {
        override fun run(){
            if(!active||closed)return
            val fresh=sampledAt>0 && SystemClock.elapsedRealtime()-sampledAt<=4500
            if(!fresh){
                clearBars()
                heading.text="SIGNAL • EN ATTENTE"
                if(sampledAt>0){state.text="Actualisation de la mesure…";state.visibility=View.VISIBLE}
                tone?.stopTone()
            }
            val q=if(fresh)sample?.quality else null
            if(audible && q!=null && q>0){
                try{
                    if(tone==null)tone=ToneGenerator(AudioManager.STREAM_MUSIC,65)
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP,80)
                }catch(_:RuntimeException){}
            }
            handler.postDelayed(this,if(audible && q!=null && q>0)(1500L-q*12L).coerceAtLeast(300L) else 1000L)
        }
    }
    init {
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.setTextColor(purple)
        top.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        sound.gravity=Gravity.CENTER
        sound.setPadding(12.dp,10.dp,12.dp,10.dp)
        sound.background=GradientDrawable().apply{setColor(Color.rgb(43,39,79));cornerRadius=14.dp.toFloat()}
        sound.isClickable=true;sound.isFocusable=true
        sound.contentDescription="Activer le repère sonore de qualité du signal"
        top.addView(sound)
        root.addView(top)
        val meters=LinearLayout(activity)
        fun meter(text:TextView,bar:ProgressBar):LinearLayout=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            text.setTypeface(null,Typeface.BOLD)
            addView(text)
            addView(bar,LinearLayout.LayoutParams(-1,7.dp).apply{topMargin=7.dp})
        }
        meters.addView(meter(strengthText,strengthBar),LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=9.dp})
        meters.addView(meter(qualityText,qualityBar),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=9.dp})
        root.addView(meters,LinearLayout.LayoutParams(-1,-2).apply{topMargin=8.dp})
        root.addView(state,LinearLayout.LayoutParams(-1,-2).apply{topMargin=6.dp})
        sound.setOnClickListener{
            audible=!audible
            sound.text=if(audible)"♫  Son activé" else "♫  Son"
            sound.setTextColor(if(audible)green else Color.rgb(224,224,245))
            sound.contentDescription=if(audible)"Désactiver le repère sonore" else "Activer le repère sonore"
            if(!active && audible)onToggle()
            if(!audible)tone?.stopTone()
        }
        clearBars()
    }
    private fun clearBars(){
        strengthText.text="Force  —";qualityText.text="Qualité  —"
        strengthBar.visibility=View.INVISIBLE;qualityBar.visibility=View.INVISIBLE
    }
    fun setActive(value:Boolean){
        if(closed || value==active)return
        active=value;root.keepScreenOn=value
        handler.removeCallbacks(pulse)
        sampledAt=0L;sample=null;clearBars()
        heading.text=if(value)"SIGNAL • CONNEXION…" else "SIGNAL • EN PAUSE"
        state.text=if(value)"Lecture du tuner…" else ""
        state.visibility=if(value)View.VISIBLE else View.GONE
        if(value)handler.post(pulse) else tone?.stopTone()
    }
    fun update(value:ReceiverTelemetry.Signal,at:Long){
        if(!active || closed)return
        sample=value;sampledAt=at
        heading.text="● SIGNAL EN DIRECT"
        heading.setTextColor(green)
        strengthText.text="Force  "+(value.strength?.let{"$it %"}?:"—")
        qualityText.text="Qualité  "+(value.quality?.let{"$it %"}?:"—")
        strengthBar.visibility=if(value.strength==null)View.INVISIBLE else View.VISIBLE
        qualityBar.visibility=if(value.quality==null)View.INVISIBLE else View.VISIBLE
        strengthBar.setProgress(value.strength?:0,true);qualityBar.setProgress(value.quality?:0,true)
        state.visibility=View.GONE
    }
    fun message(text:String){if(!closed){state.text=text;state.visibility=View.VISIBLE}}
    fun close(){closed=true;active=false;handler.removeCallbacksAndMessages(null);tone?.release();tone=null;root.keepScreenOn=false}
}
