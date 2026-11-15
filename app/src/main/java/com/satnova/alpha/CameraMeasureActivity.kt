package com.satnova.alpha

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/** Camera-guided angular survey. An ordinary photo alone cannot establish absolute scale. */
class CameraMeasureActivity:Activity(),SensorEventListener {
    private val sensors by lazy{getSystemService(SENSOR_SERVICE) as SensorManager}
    private val cameras by lazy{getSystemService(CAMERA_SERVICE) as CameraManager}
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var preview:TextureView
    private lateinit var status:TextView
    private lateinit var output:TextView
    private lateinit var height:EditText
    private var currentElevation:Double?=null
    private var lower:Double?=null
    private var upper:Double?=null
    private var camera:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var surface:Surface?=null
    private var stopped=false
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(9,16,32))
            setPadding(10,8,10,12)
        }
        fun line(label:String,size:Float)=TextView(this).apply{
            text=label;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER
            setPadding(8,6,8,6)
        }
        fun btn(label:String,callback:()->Unit)=Button(this).apply{
            text=label;isAllCaps=false;setOnClickListener{callback()}
        }
        root.addView(line("SATNOVA • TÉLÉMÈTRE ANGULAIRE",18f))
        root.addView(line("Indiquez une hauteur connue. Sans dimension connue ou capteur de profondeur, une caméra seule ne peut pas donner les mètres.",12f))
        val photo=FrameLayout(this).apply{setBackgroundColor(Color.rgb(23,31,47))}
        preview=TextureView(this)
        photo.addView(preview,FrameLayout.LayoutParams(-1,-1))
        photo.addView(object:View(this@CameraMeasureActivity){
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas:Canvas){
                val x=width/2f;val y=height/2f
                paint.color=Color.rgb(76,234,173);paint.strokeWidth=4f
                canvas.drawLine(x-55,y,x+55,y,paint)
                canvas.drawLine(x,y-55,x,y+55,paint)
                paint.style=Paint.Style.STROKE;canvas.drawCircle(x,y,20f,paint)
            }
        },FrameLayout.LayoutParams(-1,-1))
        root.addView(photo,LinearLayout.LayoutParams(-1,0,1f))
        status=line("Placez le réticule au pied de l'objet",15f)
        root.addView(status)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(btn("1 • Marquer le bas"){lower=currentElevation;updateResult()},
            LinearLayout.LayoutParams(0,-2,1f))
        row.addView(btn("2 • Marquer le haut"){upper=currentElevation;updateResult()},
            LinearLayout.LayoutParams(0,-2,1f))
        root.addView(row)
        val row2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row2.addView(line("Hauteur réelle (m)",13f),LinearLayout.LayoutParams(0,-2,1f))
        height=EditText(this).apply{
            hint="ex. 2.0";setSingleLine(true);setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        row2.addView(height,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(row2)
        output=line("Distance : en attente\nMarquer les deux extrémités, puis calculer.",14f)
        root.addView(output)
        root.addView(btn("Calculer la distance"){updateResult(true)})
        root.addView(line("Mesure approximative : tenez le téléphone au MÊME endroit pour les deux visées, cible verticale, sans obstacle. À ne pas utiliser pour sécurité ou travaux de précision.",11f))
        setContentView(root)
        preview.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(texture:SurfaceTexture,width:Int,height:Int){openCamera()}
            override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,width:Int,height:Int){}
            override fun onSurfaceTextureDestroyed(texture:SurfaceTexture):Boolean{closeCamera();return true}
            override fun onSurfaceTextureUpdated(texture:SurfaceTexture){}
        }
    }
    override fun onResume(){
        super.onResume()
        stopped=false
        val rot=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if(rot!=null)sensors.registerListener(this,rot,SensorManager.SENSOR_DELAY_UI)
        else status.text="Capteur d'orientation indisponible : télémètre désactivé."
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA),61)
        else if(preview.isAvailable)openCamera()
    }
    override fun onPause(){
        stopped=true;sensors.unregisterListener(this);closeCamera();super.onPause()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,results)
        if(requestCode==61 && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED &&
            preview.isAvailable)openCamera()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    override fun onSensorChanged(event:SensorEvent){
        if(event.sensor.type!=Sensor.TYPE_ROTATION_VECTOR)return
        val m=FloatArray(9)
        SensorManager.getRotationMatrixFromVector(m,event.values)
        val up=-m[8].toDouble()
        currentElevation=Math.toDegrees(atan2(up,hypot(-m[2].toDouble(),-m[5].toDouble())))
        status.text=String.format(Locale.FRANCE,"Angle caméra : %+.1f° • Visez le bas puis le haut",currentElevation)
    }
    private fun updateResult(calculate:Boolean=false){
        val a=lower
        val b=upper
        if(a==null || b==null){
            output.text="Angle bas : "+(a?.let{String.format(Locale.FRANCE,"%+.1f°",it)}?:"—")+
                " • angle haut : "+(b?.let{String.format(Locale.FRANCE,"%+.1f°",it)}?:"—")
            return
        }
        val h=height.text.toString().replace(',','.').toDoubleOrNull()
        if(h==null || h<=0.0){
            output.text="Angles mémorisés. Saisissez une hauteur réelle positive (en mètres)."
            return
        }
        if(b<=a+0.5){
            output.text="Visée invalide : l'angle du haut doit dépasser celui du bas d'au moins 0,5°."
            return
        }
        val distance=SatelliteGeometry.horizontalDistance(h,b,a)
        output.text=if(distance==null)"Mesure instable : déplacez-vous ou augmentez l'écart d'angle."
            else String.format(Locale.FRANCE,
                "Distance horizontale estimée : %.2f m\nH %.2f m • bas %+.1f° • haut %+.1f°",
                distance,h,a,b)
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera(){
        if(stopped || !preview.isAvailable || camera!=null ||
            checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return
        try{
            val id=cameras.cameraIdList.firstOrNull{
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==
                    CameraCharacteristics.LENS_FACING_BACK
            }?:return
            cameras.openCamera(id,object:CameraDevice.StateCallback(){
                override fun onOpened(device:CameraDevice){
                    if(stopped){device.close();return}
                    camera=device
                    try{
                        val texture=preview.surfaceTexture?:return
                        val sizes=cameras.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(SurfaceTexture::class.java)
                        val size=sizes?.minByOrNull{abs(it.width-1280)+abs(it.height-720)}
                        texture.setDefaultBufferSize(size?.width?:1280,size?.height?:720)
                        val outputSurface=Surface(texture);surface=outputSurface
                        val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{
                            addTarget(outputSurface)
                            set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        device.createCaptureSession(listOf(outputSurface),object:CameraCaptureSession.StateCallback(){
                            override fun onConfigured(s:CameraCaptureSession){
                                if(stopped){s.close();return}
                                session=s
                                try{s.setRepeatingRequest(request,null,handler)}catch(_:Exception){}
                            }
                            override fun onConfigureFailed(s:CameraCaptureSession){s.close()}
                        },handler)
                    }catch(_:Exception){closeCamera()}
                }
                override fun onDisconnected(device:CameraDevice){device.close();if(camera===device)camera=null}
                override fun onError(device:CameraDevice,error:Int){device.close();if(camera===device)camera=null}
            },handler)
        }catch(_:Exception){}
    }
    private fun closeCamera(){
        try{session?.close()}catch(_:Exception){}
        try{camera?.close()}catch(_:Exception){}
        try{surface?.release()}catch(_:Exception){}
        session=null;camera=null;surface=null
    }
}
