package com.satnova.alpha

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.*

/** Independent physical spirit level, no receiver, video or network connection. */
class InclinometerActivity:Activity(),SensorEventListener {
    private val sensors by lazy{getSystemService(SENSOR_SERVICE) as SensorManager}
    private lateinit var level:LevelView
    private lateinit var heading:TextView
    private lateinit var reading:TextView
    private var gX=0.0
    private var gY=0.0
    private var gZ=9.81
    private var zeroX=0.0
    private var zeroY=0.0
    private var hasSample=false
    private var sensor:Sensor?=null
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val navy=Color.rgb(10,17,34)
        val white=Color.rgb(238,245,255)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(22,30,22,24)
            setBackgroundColor(navy)
        }
        fun label(s:String,size:Float)=TextView(this).apply{
            text=s;textSize=size;setTextColor(white);gravity=Gravity.CENTER
            setPadding(12,10,12,10)
        }
        heading=label("SATNOVA • NIVEAU / INCLINOMÈTRE",19f)
        root.addView(heading)
        root.addView(label("Posez le téléphone sur une surface ou contre le support pour contrôler son inclinaison.",13f))
        level=LevelView()
        root.addView(level,LinearLayout.LayoutParams(-1,0,1f))
        reading=label("En attente du capteur…",23f)
        root.addView(reading)
        val zero=Button(this).apply{
            text="Définir le zéro relatif"
            isAllCaps=false
            setOnClickListener{
                if(hasSample){zeroX=gX;zeroY=gY;render()}
            }
        }
        root.addView(zero)
        root.addView(label("Le zéro relatif sert à comparer deux positions. Pour l'angle absolu du support, utilisez la valeur horizontale sans recalibrage.",12f))
        setContentView(root)
        sensor=sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?:
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if(sensor==null)heading.text="Capteur d'inclinaison non disponible"
    }
    override fun onResume(){super.onResume();sensor?.let{sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)}}
    override fun onPause(){sensors.unregisterListener(this);super.onPause()}
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    override fun onSensorChanged(event:SensorEvent){
        if(event.values.size<3)return
        // Smooth the accelerometer fallback to reduce vibration.
        val a=if(hasSample)0.25 else 1.0
        gX=gX*(1.0-a)+event.values[0]*a
        gY=gY*(1.0-a)+event.values[1]*a
        gZ=gZ*(1.0-a)+event.values[2]*a
        hasSample=true
        render()
    }
    private fun render(){
        val magnitude=sqrt(gX*gX+gY*gY+gZ*gZ)
        if(magnitude<=0.0)return
        val flatAngle=Math.toDegrees(acos((abs(gZ)/magnitude).coerceIn(0.0,1.0)))
        val across=Math.toDegrees(atan2(gX,gZ))
        val along=Math.toDegrees(atan2(gY,gZ))
        reading.text=String.format(Locale.FRANCE,"Plan horizontal : %.1f°\nAxe X : %+.1f°   Axe Y : %+.1f°",
            flatAngle,across,along)
        level.xOffset=((gX-zeroX)/magnitude).toFloat()
        level.yOffset=((gY-zeroY)/magnitude).toFloat()
        level.invalidate()
    }
    private inner class LevelView:View(this@InclinometerActivity){
        var xOffset=0f
        var yOffset=0f
        private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val x=width/2f
            val y=height/2f
            val r=(min(width,height)*0.37f).coerceAtLeast(25f)
            pen.style=Paint.Style.STROKE;pen.strokeWidth=5f;pen.color=Color.rgb(125,160,225)
            canvas.drawCircle(x,y,r,pen)
            canvas.drawCircle(x,y,r*0.5f,pen)
            canvas.drawLine(x-r,y,x+r,y,pen)
            canvas.drawLine(x,y-r,x,y+r,pen)
            val bx=x+(xOffset*3*r).coerceIn(-r,r)
            val by=y+(yOffset*3*r).coerceIn(-r,r)
            pen.style=Paint.Style.FILL
            pen.color=if(abs(xOffset)<0.02 && abs(yOffset)<0.02)
                Color.rgb(82,230,165) else Color.rgb(252,205,82)
            canvas.drawCircle(bx,by,r*0.18f,pen)
        }
    }
}
