package com.satnova.alpha

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.*

/** Offline Qibla direction; no receiver protocol, camera or network access. */
class QiblaActivity : Activity(), SensorEventListener {
    private val sensors by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val locations by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var position:Location?=null
    private var magneticHeading:Double?=null
    private var compassAccuracy=SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var rotationSensor:Sensor?=null
    private var locationListener:LocationListener?=null
    private var closed=true
    private var lastDraw=0L
    private lateinit var bearingLabel:TextView
    private lateinit var directionLabel:TextView
    private lateinit var locationLabel:TextView
    private lateinit var compass:CompassView
    private val white=Color.rgb(240,245,255)
    private val green=Color.rgb(88,231,170)
    private fun label(value:String,size:Float=14f)=TextView(this).apply {
        text=value;textSize=size;setTextColor(white);gravity=Gravity.CENTER
        setPadding(12,10,12,10)
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;setOnClickListener{action()}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(8,14,27)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8,14,27))
            setPadding(12,12,12,18)
        }
        root.addView(label("القبلة  •  QIBLA",23f))
        root.addView(label("Direction de la Kaaba depuis votre position, sans récepteur ni Internet.",13f))
        compass=CompassView()
        root.addView(compass,LinearLayout.LayoutParams(-1,0,1f))
        bearingLabel=label("Calcul de la direction…",19f)
        directionLabel=label("Posez le téléphone à plat et éloignez-le des objets métalliques.",14f)
        locationLabel=label("Position : en attente",12f)
        root.addView(bearingLabel);root.addView(directionLabel);root.addView(locationLabel)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        controls.addView(button("GPS"){updateLocation()},LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button("Position manuelle"){manualLocation()},LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button("Calibrer"){showCalibration()},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(controls)
        root.addView(label("Boussole indicative : comparez la direction vraie avec un repère fiable si le capteur est perturbé.",11f))
        setContentView(root)
        rotationSensor=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if(rotationSensor==null)directionLabel.text="Capteur d'orientation absent : direction numérique disponible."
        updateReadings()
    }
    override fun onResume(){
        super.onResume()
        closed=false
        rotationSensor?.let{sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)}
        updateLocation()
    }
    override fun onPause(){
        closed=true
        sensors.unregisterListener(this)
        locationListener?.let{try{locations.removeUpdates(it)}catch(_:Exception){}}
        locationListener=null
        super.onPause()
    }
    private fun hasLocationPermission()=
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    private fun updateLocation(){
        if(!hasLocationPermission()){
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION),64)
            locationLabel.text="Autorisez la localisation ou saisissez les coordonnées manuellement."
            return
        }
        val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{
            try{locations.isProviderEnabled(it)}catch(_:Exception){false}
        }
        val now=System.currentTimeMillis()
        val last=providers.mapNotNull{
            try{locations.getLastKnownLocation(it)}catch(_:Exception){null}
        }.filter{it.time>0 && now-it.time in 0L..43_200_000L}.minByOrNull{it.accuracy}
        if(last!=null && (position==null || position?.provider!="manual")){
            position=last;updateReadings()
        }
        val provider=providers.firstOrNull()?:run {
            locationLabel.text="GPS désactivé. Vous pouvez entrer votre position manuellement."
            return
        }
        try{
            if(Build.VERSION.SDK_INT>=30){
                locations.getCurrentLocation(provider,null,mainExecutor){result->
                    if(!closed && result!=null){position=result;updateReadings()}
                }
            }else{
                val listener=object:LocationListener{
                    override fun onLocationChanged(result:Location){
                        if(!closed){position=result;updateReadings()}
                        try{locations.removeUpdates(this)}catch(_:Exception){}
                        if(locationListener===this)locationListener=null
                    }
                }
                locationListener?.let{locations.removeUpdates(it)}
                locationListener=listener
                locations.requestSingleUpdate(provider,listener,mainLooper)
            }
        }catch(_:Exception){updateReadings()}
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==64){
            if(hasLocationPermission())updateLocation()
            else locationLabel.text="Localisation non autorisée : utilisez Position manuelle."
        }
    }
    private fun manualLocation(){
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8)}
        fun coordinate(hint:String)=EditText(this).apply{
            this.hint=hint
            setSingleLine(true)
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lat=coordinate("Latitude (ex. 33.82)")
        val lon=coordinate("Longitude (ex. -6.06)")
        position?.let{
            lat.setText(String.format(Locale.US,"%.5f",it.latitude))
            lon.setText(String.format(Locale.US,"%.5f",it.longitude))
        }
        box.addView(lat);box.addView(lon)
        AlertDialog.Builder(this).setTitle("Position de calcul").setView(box)
            .setPositiveButton("Calculer"){_,_->
                val a=lat.text.toString().replace(',','.').toDoubleOrNull()
                val b=lon.text.toString().replace(',','.').toDoubleOrNull()
                if(a!=null && b!=null && a.isFinite() && b.isFinite() &&
                    a in -90.0..90.0 && b in -180.0..180.0){
                    position=Location("manual").apply{latitude=a;longitude=b}
                    updateReadings()
                }else android.widget.Toast.makeText(this,"Coordonnées invalides",android.widget.Toast.LENGTH_LONG).show()
            }.setNegativeButton("Annuler",null).show()
    }
    private fun showCalibration(){
        AlertDialog.Builder(this).setTitle("Calibrer la boussole")
            .setMessage("Tenez le téléphone loin du métal, des supports de parabole, des aimants et du récepteur. "+
                "Déplacez-le lentement en forme de 8. Posez-le ensuite à plat. "+
                "Le résultat est calculé par rapport au nord vrai et peut être perturbé par le capteur.")
            .setPositiveButton("OK",null).show()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){
        compassAccuracy=accuracy
        updateReadings()
    }
    override fun onSensorChanged(event:SensorEvent){
        if(event.sensor.type!=Sensor.TYPE_ROTATION_VECTOR)return
        val matrix=FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix,event.values)
        val orientation=FloatArray(3)
        SensorManager.getOrientation(matrix,orientation)
        magneticHeading=(Math.toDegrees(orientation[0].toDouble())+360.0)%360.0
        val now=SystemClock.elapsedRealtime()
        if(now-lastDraw>=120){lastDraw=now;updateReadings()}
    }
    private fun updateReadings(){
        if(!::bearingLabel.isInitialized)return
        val p=position
        if(p==null){
            bearingLabel.text="Position requise pour la Qibla"
            locationLabel.text="Position : GPS ou saisie manuelle"
            compass.target=null;compass.heading=null;compass.invalidate()
            return
        }
        val target=QiblaGeometry.bearing(p.latitude,p.longitude)
        if(!target.isFinite()){
            bearingLabel.text="Direction indéterminée à cet emplacement"
            return
        }
        val declination=GeomagneticField(p.latitude.toFloat(),p.longitude.toFloat(),
            (if(p.hasAltitude())p.altitude else 0.0).toFloat(),
            System.currentTimeMillis()).declination.toDouble()
        val heading=magneticHeading?.let{(it+declination+360.0)%360.0}
        compass.target=target;compass.heading=heading;compass.invalidate()
        bearingLabel.text=String.format(Locale.FRANCE,"Qibla : %.1f° du nord vrai",target)
        val delta=heading?.let{QiblaGeometry.relativeBearing(target,it)}
        directionLabel.text=if(delta!=null){
            String.format(Locale.FRANCE,"%s %.0f° pour orienter le haut du téléphone vers la Qibla%s",
                if(delta>=0)"Tourner à droite" else "Tourner à gauche",abs(delta),
                if(compassAccuracy==SensorManager.SENSOR_STATUS_UNRELIABLE)" • recalibrage recommandé" else "")
        }else "Capteur boussole indisponible. Utilisez l'angle calculé par rapport au nord vrai."
        locationLabel.text=String.format(Locale.FRANCE,"Position %s : %.5f, %.5f",
            if(p.provider=="manual")"manuelle" else "GPS",p.latitude,p.longitude)
    }
    private inner class CompassView:View(this@QiblaActivity){
        var target:Double?=null
        var heading:Double?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val cx=width/2f
            val cy=height/2f
            val rad=(min(width,height)*0.37f).coerceAtLeast(32f)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=5f
            paint.color=Color.rgb(91,117,159)
            canvas.drawCircle(cx,cy,rad,paint)
            canvas.drawCircle(cx,cy,rad*0.13f,paint)
            paint.style=Paint.Style.FILL
            paint.textAlign=Paint.Align.CENTER
            paint.textSize=36f
            paint.color=white
            canvas.drawText("N",cx,cy-rad+48f,paint)
            canvas.drawText("S",cx,cy+rad-18f,paint)
            canvas.drawText("E",cx+rad-28f,cy+12f,paint)
            canvas.drawText("W",cx-rad+28f,cy+12f,paint)
            val goal=target?:return
            val h=heading
            if(h==null){
                paint.textSize=23f
                canvas.drawText(String.format(Locale.FRANCE,"%.1f°",goal),cx,cy+rad*0.68f,paint)
                return
            }
            val relative=Math.toRadians(QiblaGeometry.relativeBearing(goal,h))
            val dx=sin(relative).toFloat()
            val dy=(-cos(relative)).toFloat()
            val tipX=cx+dx*rad*0.88f
            val tipY=cy+dy*rad*0.88f
            val tailX=cx-dx*rad*0.27f
            val tailY=cy-dy*rad*0.27f
            paint.style=Paint.Style.STROKE;paint.strokeWidth=11f;paint.color=green
            canvas.drawLine(tailX,tailY,tipX,tipY,paint)
            val sideX=-dy;val sideY=dx
            val arrow=Path().apply{
                moveTo(tipX,tipY)
                lineTo(tipX-dx*35f+sideX*22f,tipY-dy*35f+sideY*22f)
                lineTo(tipX-dx*35f-sideX*22f,tipY-dy*35f-sideY*22f)
                close()
            }
            paint.style=Paint.Style.FILL
            canvas.drawPath(arrow,paint)
            paint.textSize=26f;paint.textAlign=Paint.Align.CENTER;paint.color=white
            canvas.drawText("القبلة",cx,cy+rad*0.64f,paint)
        }
    }
}
