package com.satnova.alpha

import android.app.Activity
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Live tuner readings. A stale/missing sample clears the bars and silences the tone. */
class SignalPanel(private val activity:Activity,onToggle:()->Unit) {
    val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(20,20,20,20)}
    private fun label(s:String,size:Float=15f)=TextView(activity).apply{
        text=s;textSize=size;setTextColor(Color.rgb(235,232,255));setPadding(0,8,0,8)
    }
    private val heading=label("RÉGLAGE DE LA PARABOLE • SIGNAL DU TUNER",18f)
    private val strengthText=label("Force : —",22f)
    private val qualityText=label("Qualité : —",22f)
    private val strengthBar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
    private val qualityBar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
    private val state=label("Ouvrez Satellites pour mesurer la chaîne actuellement réglée.",13f)
    private val toggle=label("▶ Démarrer la mesure",16f).apply{isClickable=true;setOnClickListener{onToggle()}}
    private val sound=label("☐ Repère sonore",16f)
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var audible=false
    private var sample:ReceiverTelemetry.Signal?=null
    private var sampledAt=0L
    private var tone:ToneGenerator?=null
    private val pulse=object:Runnable {
        override fun run(){
            if(!active)return
            val fresh=sampledAt>0 && SystemClock.elapsedRealtime()-sampledAt<=4500
            if(!fresh){
                clearBars()
                if(sampledAt>0)state.text="Lecture périmée • en attente d'une nouvelle mesure du récepteur"
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
        root.setBackgroundColor(Color.rgb(28,30,59))
        root.addView(heading)
        root.addView(label("Mesure de la chaîne en cours sur le récepteur. Choisir un satellite dans la liste ne déplace pas le tuner.",12f))
        root.addView(strengthText);root.addView(strengthBar,LinearLayout.LayoutParams(-1,16))
        root.addView(qualityText);root.addView(qualityBar,LinearLayout.LayoutParams(-1,16))
        root.addView(state);root.addView(toggle);root.addView(sound)
        sound.isClickable=true
        sound.setOnClickListener{
            audible=!audible
            sound.text=if(audible)"☑ Repère sonore • plus rapproché quand la qualité monte" else "☐ Repère sonore"
            if(!audible)tone?.stopTone()
        }
        clearBars()
    }
    private fun clearBars(){
        strengthText.text="Force : —";qualityText.text="Qualité : —"
        strengthBar.visibility=View.INVISIBLE;qualityBar.visibility=View.INVISIBLE
    }
    fun setActive(value:Boolean){
        active=value
        root.keepScreenOn=value
        handler.removeCallbacks(pulse)
        toggle.text=if(value)"■ Arrêter la mesure" else "▶ Démarrer la mesure"
        if(value){sampledAt=0L;sample=null;clearBars();state.text="Lecture du signal du tuner…";handler.post(pulse)}
        else {sampledAt=0L;sample=null;clearBars();state.text="Mesure arrêtée";tone?.stopTone()}
    }
    fun update(value:ReceiverTelemetry.Signal,at:Long){
        if(!active)return
        sample=value;sampledAt=at
        strengthText.text="Force : "+(value.strength?.let{"$it %"}?:"—")
        qualityText.text="Qualité : "+(value.quality?.let{"$it %"}?:"—")
        strengthBar.visibility=if(value.strength==null)View.INVISIBLE else View.VISIBLE
        qualityBar.visibility=if(value.quality==null)View.INVISIBLE else View.VISIBLE
        strengthBar.progress=value.strength?:0;qualityBar.progress=value.quality?:0
        state.text="Lecture reçue du récepteur"+(value.ber?.let{" • BER $it"}?:"")
    }
    fun message(text:String){state.text=text}
    fun close(){active=false;handler.removeCallbacksAndMessages(null);tone?.release();tone=null;root.keepScreenOn=false}
}
