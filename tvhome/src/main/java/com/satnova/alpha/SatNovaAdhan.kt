package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.widget.Toast
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.Executors

/** Opt-in adhan audio. Cache individual selected MP3 only on first playback. */
internal class SatNovaAdhan(
    private val activity: Activity, private val prefs: SharedPreferences
) {
    private val pool = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    private val names = arrayOf("المغرب · كمال المرعوش", "مصر · عبد الباسط", "السعودية · الحرم المكي")
    // Public adhan collection. Download on first playback rather than bundling audio.
    private val files = arrayOf(
        "Kamal_Muhammad_Al_Marush_2_-_Morocco_(كمال_محمد_المرعوش_-_المغرب).mp3",
        "Abdulbasit_Abdusamad_1_-_Egypt_(عبد_الباسط_عبد_الصمد_-_مصر).mp3",
        "Ali_Ibn_Ahmad_Mala_1_-_Al_Haram_Al_Maki_(علي_بن_أحمد_ملا_-_الحرم_المكي).mp3"
    )
    fun enabled() = prefs.getBoolean("adhan_audio_enabled", false)
    fun choose() {
        val options = arrayOf(
            if (enabled()) "إيقاف صوت الأذان" else "تفعيل صوت الأذان",
            "اختيار المؤذن (المغرب / مصر / السعودية)",
            "تجربة الصوت المختار",
            "إيقاف الصوت الجاري"
        )
        AlertDialog.Builder(activity).setTitle("صوت الأذان")
            .setItems(options) { _, choice ->
                when (choice) {
                    0 -> {
                        prefs.edit().putBoolean("adhan_audio_enabled", !enabled()).apply()
                        if (!enabled()) stop()
                    }
                    1 -> AlertDialog.Builder(activity).setTitle("اختيار صوت الأذان")
                        .setSingleChoiceItems(names, prefs.getInt("adhan_voice", 0)) { d, index ->
                            prefs.edit().putInt("adhan_voice", index).apply()
                            d.dismiss()
                        }.setNegativeButton("إغلاق", null).show()
                    2 -> play(false)
                    3 -> stop()
                }
            }.setNegativeButton("إغلاق", null).show()
    }
    fun onPrayer(key: String) {
        if (!enabled()) return
        // One playback per prayer date; never retrigger after rebuilding UI.
        if (prefs.getString("last_adhan_played", "") == key) return
        prefs.edit().putString("last_adhan_played", key).apply()
        play(true)
    }
    private fun play(automatic: Boolean) {
        val selection = prefs.getInt("adhan_voice", 0).coerceIn(0, 2)
        val folder = File(activity.filesDir, "adhan").apply { mkdirs() }
        val file = File(folder, "voice_" + selection + ".mp3")
        if (file.isFile && file.length() > 1024) {
            start(file)
            return
        }
        if (!automatic) Toast.makeText(activity,
            "تحميل الأذان المختار لأول مرة…", Toast.LENGTH_LONG).show()
        pool.execute {
            try {
                val encoded = android.net.Uri.encode(files[selection])
                val address = "https://raw.githubusercontent.com/Kiwifu/adhan-mp3/main/" + encoded
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 12000
                c.readTimeout = 25000
                val part = File(folder, "voice_" + selection + ".part")
                try {
                    require(c.responseCode == 200)
                    var count = 0L
                    c.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val bytes = ByteArray(16384)
                            while (true) {
                                val n = input.read(bytes)
                                if (n < 0) break
                                count += n
                                require(count < 12_000_000)
                                output.write(bytes, 0, n)
                            }
                        }
                    }
                    require(count > 1024)
                    require(part.renameTo(file))
                } finally { part.delete(); c.disconnect() }
                activity.runOnUiThread { start(file) }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "فشل تحميل الأذان، تحقق من الشبكة", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun start(file: File) {
        try {
            stop()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stop() }
                prepareAsync()
            }
        } catch (_: Exception) { stop() }
    }
    fun stop() { try { player?.stop() } catch (_: Exception) {}; player?.release(); player = null }
    fun close() { stop(); pool.shutdownNow() }
}
