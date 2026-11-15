package com.satnova.alpha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.IBinder

/** User-controlled foreground process protection; the Activity remains the only protocol owner. */
class BackgroundLinkService:Service() {
    override fun onCreate(){
        super.onCreate()
        val mgr=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("satnova_connection","SatNova • connexion locale",
            NotificationManager.IMPORTANCE_LOW).apply{description="Maintien de la session LAN lorsque l'application passe en arrière-plan"})
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val launch=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice=Notification.Builder(this,"satnova_connection")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SatNova • maintien en arrière-plan")
            .setContentText("Connexion LAN active lorsque l'application reste ouverte")
            .setContentIntent(launch)
            .setOngoing(true).build()
        if(android.os.Build.VERSION.SDK_INT>=29)
            startForeground(209,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(209,notice)
        return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
