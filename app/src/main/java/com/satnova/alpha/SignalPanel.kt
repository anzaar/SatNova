package com.satnova.alpha

import android.app.Activity
import android.content.res.Configuration
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
import android.widget.TextView

/** Full-height dish-alignment panel with one live receiver reader and optional quality tone. */
class SignalPanel(private val activity:Activity,private val onToggle:()->Unit,private val openFinder:()->Unit) {
    private val Int.dp get()=(this*activity.resources.displayMetrics.density+.5f).toInt()
    val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(4.dp,4.dp,4.dp,8.dp)}
    private val green=Color.rgb(83,226,177)
    private val purple=Color.rgb(170,151,255)
    private fun label(s:String,size:Float=13f)=TextView(activity).apply{
        text=s;textSize=size;setTextColor(Color.rgb(224,224,245));includeFontPadding=false
    }
    private val heading=label("RÉGLAGE DE LA PARABOLE",13f).apply{setTypeface(null,Typeface.BOLD)}
    private val deviceName=label("Récepteur connecté",17f).apply{setTypeface(null,Typeface.BOLD);maxLines=2}
    private val deviceDetails=label("IP : —\nSérie : en attente",12f).apply{maxLines=3}
    private val contextText=label("Chaîne actuelle du récepteur",12f).apply{maxLines=3;setTextColor(purple)}
    private val quality=SignalGaugeView(activity,"Qualité",green)
    private val strength=SignalGaugeView(activity,"Force",purple)
    private val state=label("Lecture du signal…",12f).apply{gravity=Gravity.CENTER}
    private val sound=label("♫  Son",14f)
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
                clearGauges();heading.text="EN ATTENTE DU SIGNAL";heading.setTextColor(purple)
                if(sampledAt>0)state.text="Actualisation de la mesure…"
                state.visibility=View.VISIBLE;tone?.stopTone()
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
        sound.gravity=Gravity.CENTER;sound.setPadding(14.dp,13.dp,14.dp,13.dp)
        sound.background=GradientDrawable().apply{setColor(Color.rgb(43,39,79));cornerRadius=15.dp.toFloat()}
        sound.isClickable=true;sound.isFocusable=true
        sound.contentDescription="Activer le repère sonore de qualité du signal"
        top.addView(sound)
        root.addView(top)
        root.addView(deviceName,LinearLayout.LayoutParams(-1,-2).apply{topMargin=10.dp})
        root.addView(deviceDetails,LinearLayout.LayoutParams(-1,-2).apply{topMargin=4.dp})
        root.addView(contextText,LinearLayout.LayoutParams(-1,-2).apply{topMargin=8.dp;bottomMargin=6.dp})
        val finder=label("◎  Caméra • direction satellite",14f).apply{
            gravity=Gravity.CENTER
            setPadding(12.dp,9.dp,12.dp,9.dp)
            background=GradientDrawable().apply{setColor(Color.rgb(43,39,79));cornerRadius=14.dp.toFloat()}
            isClickable=true;isFocusable=true
            setOnClickListener{openFinder()}
        }
        root.addView(finder,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=6.dp})
        val landscape=activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        val gauges=LinearLayout(activity).apply{orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL}
        fun gauge(view:View){
            val lp=if(landscape)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f)
            lp.setMargins(4.dp,4.dp,4.dp,4.dp)
            view.background=GradientDrawable().apply{setColor(Color.rgb(20,26,44));cornerRadius=24.dp.toFloat()}
            gauges.addView(view,lp)
        }
        gauge(quality);gauge(strength)
        root.addView(gauges,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(state,LinearLayout.LayoutParams(-1,28.dp).apply{topMargin=4.dp})
        sound.setOnClickListener{
            audible=!audible
            sound.text=if(audible)"♫  Son activé" else "♫  Son"
            sound.setTextColor(if(audible)green else Color.rgb(224,224,245))
            sound.contentDescription=if(audible)"Désactiver le repère sonore" else "Activer le repère sonore"
            if(!active && audible)onToggle()
            if(!audible)tone?.stopTone()
        }
        clearGauges()
    }
    private fun clearGauges(){strength.setValue(null);quality.setValue(null)}
    fun setContext(channel:String,satellite:String,tp:String){
        contextText.text=listOf(channel,satellite,tp).filter{
            it.isNotBlank() && !it.contains("non communiqué") && !it.contains("non identifié") && !it.contains("en cours d'identification")
        }.joinToString("\n")
    }
    fun setDevice(name:String,host:String,serial:String){
        deviceName.text=name.ifBlank{"Récepteur connecté"}
        deviceDetails.text="IP : "+host.ifBlank{"—"}+"\nSérie : "+serial.ifBlank{"en attente"}
    }
    fun setActive(value:Boolean){
        if(closed || value==active)return
        active=value;root.keepScreenOn=value;handler.removeCallbacks(pulse)
        sampledAt=0L;sample=null;clearGauges()
        heading.text=if(value)"LECTURE DU SIGNAL…" else "RÉGLAGE DE LA PARABOLE"
        state.text=if(value)"Lecture du tuner…" else "Mesure en pause"
        state.visibility=View.VISIBLE
        if(value)handler.post(pulse) else tone?.stopTone()
    }
    fun update(value:ReceiverTelemetry.Signal,at:Long){
        if(!active || closed)return
        sample=value;sampledAt=at
        heading.text="● SIGNAL EN DIRECT";heading.setTextColor(green)
        strength.setValue(value.strength);quality.setValue(value.quality)
        state.text=if(audible)"Le son se rapproche quand la qualité augmente" else "Mesure actuelle du récepteur"
        state.visibility=View.VISIBLE
    }
    fun message(text:String){if(!closed){state.text=text;state.visibility=View.VISIBLE}}
    fun close(){closed=true;active=false;handler.removeCallbacksAndMessages(null);tone?.release();tone=null;root.keepScreenOn=false}
}
