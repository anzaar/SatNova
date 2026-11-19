package com.satnova.alpha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper

/**
 * A small user-visible connected-device foreground service.
 * Retains one shared Zen 4 G-MScreen socket while HDMI and other apps
 * are foreground. Zen4Client itself retries the last IP and LAN discovery.
 * No app-key monitoring; that remains opt-in via Accessibility.
 */
class Zen4PersistentService : Service() {
    private val notificationId = 40314
    private val channelId = "satnova_zen4_connection"
    private var clientAttached = false
    private val main = Handler(Looper.getMainLooper())
    private var lastNotifiedStatus = ""
    private val notifyTask = Runnable {
        if (status != lastNotifiedStatus) {
            lastNotifiedStatus = status
            try { updateNotification() } catch (_: Exception) { }
        }
    }
    private var status = "Recherche du récepteur Zen 4…"
    private val observer = object : Zen4Client.Listener {
        override fun state(connected: Boolean, message: String) {
            status = if (connected) "Connecté · Zen 4" else "Reconnexion · Zen 4"
            main.removeCallbacks(notifyTask)
            main.postDelayed(notifyTask, 800L)
        }
        override fun catalogue(channels: List<ZenChannel>) {}
        override fun playing(channelId: String) {}
        override fun tuneConfirmed(channel: ZenChannel) {}
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            channelId, "SatNova · Connexion Zen 4", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Connexion et reconnexion Wi-Fi à Zen 4 en arrière-plan" })
        val initial = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(notificationId, initial)
        }
        Zen4SessionHub.attach(applicationContext, this, observer)
        clientAttached = true
    }

    private fun notification(): Notification {
        val intent = Intent(this, AltaPlayZenActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AltaPlay Launcher · Zen")
            .setContentText(status)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(notificationId, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(notifyTask)
        if (clientAttached) {
            Zen4SessionHub.detach(this)
            clientAttached = false
        }
        super.onDestroy()
    }
}
