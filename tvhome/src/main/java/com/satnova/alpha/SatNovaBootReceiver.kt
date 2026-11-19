package com.satnova.alpha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Opt-in best-effort request for SatNova HOME after a full device boot.
 *
 * Firmware-controlled "Last source" may show HDMI; Android 10+ may also
 * silently refuse a background activity launch. The authorized Accessibility
 * fallback is one-shot and time-bounded; it never changes TV input settings,
 * disables the stock launcher or creates a permanent boot loop.
 */
class SatNovaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("zen4_hdmi_bridge", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("boot_home_enabled", false)) return

        // Elapsed-realtime is only valid within the current boot.
        // The fallback consumes this marker on the first launch attempt.
        prefs.edit()
            .putLong("boot_home_pending_until_elapsed", SystemClock.elapsedRealtime() + 45_000L)
            .putString("last_boot_home_result", "BOOT_COMPLETED reçu : tentative HOME")
            .apply()
        try {
            context.startActivity(Intent(context, MinimalHomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            Log.i("SatNovaBoot", "Requested SatNova HOME on user-opted-in boot")
        } catch (error: Exception) {
            prefs.edit().putString("last_boot_home_result",
                "Démarrage direct refusé : " + error.javaClass.simpleName).apply()
            Log.w("SatNovaBoot", "Boot activity launch rejected; Accessibility fallback pending", error)
        }
    }
}
