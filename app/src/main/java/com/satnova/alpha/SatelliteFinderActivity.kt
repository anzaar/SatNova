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
import android.graphics.SurfaceTexture
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.*

/** A read-only satellite finder; isolated from live video, tuner, and remote-control sockets. */
class SatelliteFinderActivity:Activity(),SensorEventListener {
    private val sensorManager by lazy {getSystemService(Context.SENSOR_SERVICE) as SensorManager}
    private val cameraManager by lazy {getSystemService(Context.CAMERA_SERVICE) as CameraManager}
    private val locationManager by lazy {getSystemService(Context.LOCATION_SERVICE) as LocationManager}
    private lateinit var cameraPreview:TextureView
    private lateinit var overlay:FinderOverlay
    private lateinit var info:TextView
    private lateinit var hints:TextView
    private lateinit var selection:Button
    private var satelliteName=""
    private var orbitalLongitude:Double?=null
    private var position:Location?=null
    private var heading:Double?=null
    private var cameraElevation:Double?=null
    private var camera:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var previewSurface:Surface?=null
    private var requestedPermissions=false
    private var lastUi=0L
    private var closed=false
    private val mainHandler=Handler(Looper.getMainLooper())
    private val white=Color.rgb(239,244,255)
    private fun text(value:String,size:Float)=TextView(this).apply {
        text=value;textSize=size;setTextColor(white);setPadding(12,10,12,10)
    }
    private fun button(value:String,click:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;setOnClickListener{click()}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.BLACK
        requestedPermissions=savedInstanceState?.getBoolean("permissionsAsked")?:false
        satelliteName=intent.getStringExtra("satellite").orEmpty()
        orbitalLongitude=SatelliteGeometry.orbitalFromName(satelliteName)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(8,13,27))}
        root.addView(text("SATNOVA • POINTAGE PARABOLE",17f),LinearLayout.LayoutParams(-1,-2))
        selection=button(""){selectSatellite()}
        root.addView(selection)
        val cameraFrame=FrameLayout(this).apply{setBackgroundColor(Color.rgb(18,25,42))}
        cameraPreview=TextureView(this)
        cameraFrame.addView(cameraPreview,FrameLayout.LayoutParams(-1,-1))
        overlay=FinderOverlay()
        cameraFrame.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        val cameraNote=text("Caméra facultative • pointez le dos du téléphone vers le ciel",12f)
        cameraNote.gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
        cameraFrame.addView(cameraNote,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        root.addView(cameraFrame,LinearLayout.LayoutParams(-1,0,1f))
        info=text("Position en attente…",16f)
        hints=text("La direction est calculée à partir de votre position et de l'orbite du satellite.",12f)
        root.addView(info)
        root.addView(hints)
        val footer=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        footer.addView(button("Position manuelle"){manualPosition()},LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(button("Calibrage"){calibrationHelp()},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(footer)
        setContentView(root)
        cameraPreview.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface:SurfaceTexture,width:Int,height:Int){openCamera()}
            override fun onSurfaceTextureSizeChanged(surface:SurfaceTexture,width:Int,height:Int){}
            override fun onSurfaceTextureDestroyed(surface:SurfaceTexture):Boolean{closeCamera();return true}
            override fun onSurfaceTextureUpdated(surface:SurfaceTexture){}
        }
        updateDisplay()
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("permissionsAsked",requestedPermissions)
        super.onSaveInstanceState(outState)
    }
    override fun onResume(){
        super.onResume()
        closed=false
        val sensor=sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if(sensor!=null) sensorManager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_UI)
        else hints.text="Capteur boussole/orientation indisponible. Le calcul d'azimut reste accessible."
        if(!requestedPermissions && (!cameraGranted() || !locationGranted())){
            requestedPermissions=true
            requestPermissions(arrayOf(Manifest.permission.CAMERA,Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION),31)
        }else{
            if(cameraPreview.isAvailable)openCamera()
            updateLocation()
        }
    }
    override fun onPause(){
        closed=true
        sensorManager.unregisterListener(this)
        closeCamera()
        super.onPause()
    }
    private fun cameraGranted()=checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    private fun locationGranted()=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==31){
            if(cameraGranted() && cameraPreview.isAvailable)openCamera()
            updateLocation()
            updateDisplay()
        }
    }
    @SuppressLint("MissingPermission")
    private fun updateLocation(){
        if(!locationGranted()){updateDisplay();return}
        val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{
            try{locationManager.isProviderEnabled(it)}catch(_:Exception){false}
        }
        position=providers.mapNotNull{provider->
            try{locationManager.getLastKnownLocation(provider)}catch(_:Exception){null}
        }.minByOrNull{it.accuracy}
        updateDisplay()
        val provider=providers.firstOrNull()?:return
        try{
            if(android.os.Build.VERSION.SDK_INT>=30){
                locationManager.getCurrentLocation(provider,null,mainExecutor){value->
                    if(!closed && value!=null){position=value;updateDisplay()}
                }
            }else{
                locationManager.requestSingleUpdate(provider,object:android.location.LocationListener{
                    override fun onLocationChanged(value:Location){
                        if(!closed){position=value;updateDisplay()}
                    }
                },Looper.getMainLooper())
            }
        }catch(_:Exception){}
    }
    private fun selectSatellite(){
        val named=intent.getStringArrayListExtra("satellites").orEmpty()
            .filter{SatelliteGeometry.orbitalFromName(it)!=null}.distinct()
        val choices=named.map{it to (SatelliteGeometry.orbitalFromName(it)?:0.0)}+
            SatelliteGeometry.common.map{it.label to it.longitude}
        AlertDialog.Builder(this).setTitle("Satellite à pointer")
            .setItems(choices.map{it.first}.toTypedArray()){_,which->
                satelliteName=choices[which].first
                orbitalLongitude=choices[which].second
                updateDisplay()
            }.setNegativeButton("Annuler",null).show()
    }
    private fun manualPosition(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(25,12,25,4)}
        val lat=EditText(this).apply{hint="Latitude (ex. 33.82)";inputType=android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED}
        val lon=EditText(this).apply{hint="Longitude (ex. -6.06)";inputType=lat.inputType}
        box.addView(lat);box.addView(lon)
        AlertDialog.Builder(this).setTitle("Position de la parabole").setView(box)
            .setPositiveButton("Appliquer"){_,_->
                val a=lat.text.toString().replace(',','.').toDoubleOrNull()
                val b=lon.text.toString().replace(',','.').toDoubleOrNull()
                if(a!=null && b!=null && a in -90.0..90.0 && b in -180.0..180.0){
                    position=Location("manual").apply{latitude=a;longitude=b;accuracy=1000f}
                    updateDisplay()
                }else android.widget.Toast.makeText(this,"Coordonnées invalides",android.widget.Toast.LENGTH_LONG).show()
            }.setNegativeButton("Annuler",null).show()
    }
    private fun calibrationHelp(){
        AlertDialog.Builder(this).setTitle("Calibrage de la boussole")
            .setMessage("Éloignez le téléphone du métal, des moteurs et du récepteur. " +
                "Faites lentement un mouvement en forme de 8 avec le téléphone. " +
                "Vérifiez que les capteurs sont présents et autorisez la localisation. " +
                "Le pointage affiché est indicatif : confirmez la qualité du signal sur le récepteur.")
            .setPositiveButton("Compris",null).show()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){
        if(accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE)
            hints.text="Boussole perturbée : éloignez-vous du métal puis recalibrez."
    }
    override fun onSensorChanged(event:SensorEvent){
        if(event.sensor.type!=Sensor.TYPE_ROTATION_VECTOR)return
        val matrix=FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix,event.values)
        // World coordinates: east, north, up. Rear camera points along device -Z.
        val east=-matrix[2].toDouble()
        val north=-matrix[5].toDouble()
        val up=-matrix[8].toDouble()
        val magneticAzimuth=(Math.toDegrees(atan2(east,north))+360.0)%360.0
        val correction=position?.let{GeomagneticField(it.latitude.toFloat(),it.longitude.toFloat(),
            it.altitude.toFloat(),System.currentTimeMillis()).declination.toDouble()}?:0.0
        heading=(magneticAzimuth+correction+360.0)%360.0
        cameraElevation=Math.toDegrees(atan2(up,hypot(east,north)))
        val now=SystemClock.elapsedRealtime()
        if(now-lastUi>120){lastUi=now;updateDisplay()}
    }
    private fun updateDisplay(){
        if(!::selection.isInitialized)return
        selection.text="Satellite : "+(satelliteName.ifBlank{"Sélectionner"})+"  ▾"
        val sat=orbitalLongitude
        val pos=position
        if(sat==null){
            info.text="Satellite actuel non identifiable automatiquement."
            hints.text="Sélectionnez sa position orbitale dans la liste du haut."
            overlay.look=null;overlay.invalidate();return
        }
        if(pos==null){
            info.text="Position GPS indisponible."
            hints.text="Autorisez la localisation ou saisissez latitude et longitude."
            overlay.look=null;overlay.invalidate();return
        }
        val target=SatelliteGeometry.look(pos.latitude,pos.longitude,sat)
        overlay.look=target
        overlay.heading=heading
        overlay.elevation=cameraElevation
        overlay.invalidate()
        info.text=String.format(Locale.FRANCE,"Azimut %.1f° • Élévation %.1f°",target.azimuth,target.elevation)
        val h=heading
        val e=cameraElevation
        val direction=if(h!=null && e!=null){
            val delta=SatelliteGeometry.signedDelta(target.azimuth,h)
            val height=target.elevation-e
            String.format(Locale.FRANCE,"Tourner %s %.0f° • %s %.0f°",
                if(delta>=0)"à droite" else "à gauche",abs(delta),
                if(height>=0)"monter" else "descendre",abs(height))
        }else "Boussole indisponible : utilisez les angles numériques."
        hints.text=(if(target.elevation<0)"Satellite sous l'horizon ! " else "")+
            direction+"\n"+(if(pos.provider=="manual")"Position manuelle" else "Position GPS")+" • Ajustez avec la qualité du signal."
    }
    @SuppressLint("MissingPermission")
    private fun openCamera(){
        if(closed || camera!=null || !cameraGranted() || !cameraPreview.isAvailable)return
        try{
            val id=cameraManager.cameraIdList.firstOrNull{
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==
                    CameraCharacteristics.LENS_FACING_BACK
            }?:return
            cameraManager.openCamera(id,object:CameraDevice.StateCallback(){
                override fun onOpened(device:CameraDevice){
                    if(closed){device.close();return}
                    camera=device
                    try{
                        val texture=cameraPreview.surfaceTexture?:return
                        val sizes=cameraManager.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(SurfaceTexture::class.java)
                        val size=sizes?.minByOrNull{abs(it.width-1280)+abs(it.height-720)}
                        texture.setDefaultBufferSize(size?.width?:1280,size?.height?:720)
                        val surface=Surface(texture)
                        previewSurface=surface
                        val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply{addTarget(surface);set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)}
                            .build()
                        device.createCaptureSession(listOf(surface),object:CameraCaptureSession.StateCallback(){
                            override fun onConfigured(active:CameraCaptureSession){
                                if(closed){active.close();return}
                                session=active
                                try{active.setRepeatingRequest(request,null,mainHandler)}catch(_:Exception){}
                            }
                            override fun onConfigureFailed(active:CameraCaptureSession){active.close()}
                        },mainHandler)
                    }catch(_:Exception){closeCamera()}
                }
                override fun onDisconnected(device:CameraDevice){device.close();if(camera===device)camera=null}
                override fun onError(device:CameraDevice,error:Int){device.close();if(camera===device)camera=null}
            },mainHandler)
        }catch(_:Exception){}
    }
    private fun closeCamera(){
        try{session?.close()}catch(_:Exception){}
        try{camera?.close()}catch(_:Exception){}
        try{previewSurface?.release()}catch(_:Exception){}
        session=null;camera=null;previewSurface=null
    }
    private inner class FinderOverlay:View(this@SatelliteFinderActivity){
        var look:SatelliteGeometry.Look?=null
        var heading:Double?=null
        var elevation:Double?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val cx=width/2f;val cy=height/2f
            paint.color=Color.WHITE;paint.strokeWidth=3f;paint.style=Paint.Style.STROKE
            canvas.drawCircle(cx,cy,24f,paint)
            canvas.drawLine(cx-45f,cy,cx+45f,cy,paint)
            canvas.drawLine(cx,cy-45f,cx,cy+45f,paint)
            val target=look?:return
            val h=heading?:return
            val el=elevation?:return
            val p=position
            if(p!=null){
                // Other visible GEO satellites appear as reference circles, never as tuning commands.
                for(item in SatelliteGeometry.common){
                    val next=SatelliteGeometry.look(p.latitude,p.longitude,item.longitude)
                    if(next.elevation<0.0)continue
                    val a=SatelliteGeometry.signedDelta(next.azimuth,h)
                    val e=next.elevation-el
                    if(abs(a)>29.0 || abs(e)>24.0)continue
                    val px=(cx+a/30.0*cx).toFloat()
                    val py=(cy-e/25.0*cy).toFloat()
                    paint.color=Color.rgb(126,201,255)
                    paint.strokeWidth=3f
                    paint.style=Paint.Style.STROKE
                    canvas.drawCircle(px,py,17f,paint)
                    paint.style=Paint.Style.FILL
                    paint.textSize=24f;paint.textAlign=Paint.Align.CENTER
                    canvas.drawText(item.label.substringAfter("•").trim(),px,py-22f,paint)
                }
            }
            paint.style=Paint.Style.STROKE
            val dAz=SatelliteGeometry.signedDelta(target.azimuth,h)
            val dEl=target.elevation-el
            // Approximate wide-angle camera field of view; align centre then refine on receiver.
            val x=(cx+dAz/30.0*cx).toFloat().coerceIn(32f,width-32f)
            val y=(cy-dEl/25.0*cy).toFloat().coerceIn(32f,height-32f)
            paint.color=if(abs(dAz)<2.0 && abs(dEl)<2.0)Color.rgb(74,241,152) else Color.YELLOW
            paint.strokeWidth=6f
            canvas.drawCircle(x,y,28f,paint)
            canvas.drawLine(x-42f,y,x+42f,y,paint)
            canvas.drawLine(x,y-42f,x,y+42f,paint)
            paint.style=Paint.Style.FILL
            paint.textSize=34f;paint.textAlign=Paint.Align.CENTER
            canvas.drawText("SAT",x,(y-44f).coerceAtLeast(42f),paint)
        }
    }
}
