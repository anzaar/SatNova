package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 114 surahs, authentic Uthmani text retrieved from Al Quran Cloud.
 * MP3 reciter directories are resolved from MP3Quran's official catalogue.
 * No Quran verse or audio is invented or hard-coded. Downloads only when opened.
 */
internal class QuranLibrary(private val activity: Activity) {
    private val io = Executors.newSingleThreadExecutor()
    private val home = File(activity.filesDir, "quran").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var catalogue: JSONArray? = null
    private val readerNames = arrayOf("عمر القزابري", "عبد الرحمن السديس")
    private val readerKeys = arrayOf("قزابري", "سديس")

    fun open() {
        val cached = File(home, "surahs.json")
        if (cached.isFile) try {
            catalogue = JSONObject(cached.readText()).getJSONObject("data").getJSONArray("surahs")
        } catch (_: Exception) {}
        if (catalogue != null) { showList(); return }
        Toast.makeText(activity, "جاري تحميل فهرس القرآن (114 سورة)…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val json = fetch("https://api.alquran.cloud/v1/meta")
                val a = JSONObject(json).getJSONObject("data").getJSONArray("surahs")
                require(a.length() == 114)
                cached.writeText(json)
                activity.runOnUiThread { catalogue = a; showList() }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "تعذر تحميل الفهرس؛ تحقق من الإنترنت", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showList() {
        val a = catalogue ?: return
        val options = (0 until a.length()).map {
            val item = a.getJSONObject(it)
            item.getInt("number").toString() + " · " + item.optString("name", "سورة")
        }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("القرآن الكريم · 114 سورة")
            .setItems(options) { _, index ->
                val chosen = a.getJSONObject(index)
                read(chosen.getInt("number"), chosen.optString("name", "سورة"))
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun read(number: Int, name: String) {
        val file = File(home, "surah_" + number + ".json")
        if (file.isFile) try { showSurah(number, name, file.readText()); return }
            catch (_: Exception) {}
        Toast.makeText(activity, "تحميل نص " + name + "…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val json = fetch("https://api.alquran.cloud/v1/surah/" + number + "/quran-uthmani")
                val root = JSONObject(json)
                require(root.optInt("code") == 200)
                require(root.getJSONObject("data").getJSONArray("ayahs").length() > 0)
                file.writeText(json)
                activity.runOnUiThread { showSurah(number, name, json) }
            } catch (_: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "نص السورة غير متاح", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSurah(number: Int, name: String, json: String) {
        val arr = JSONObject(json).getJSONObject("data").getJSONArray("ayahs")
        val scroll = ScrollView(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 16, 26, 24)
        }
        val title = TextView(activity).apply {
            text = name
            textSize = 27f
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 16)
        }
        layout.addView(title)
        val verses = TextView(activity).apply {
            text = (0 until arr.length()).joinToString("  ") { idx ->
                val item = arr.getJSONObject(idx)
                item.getString("text") + " ﴿" + item.getInt("numberInSurah") + "﴾"
            }
            textSize = 27f
            setLineSpacing(12f, 1.12f)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        }
        layout.addView(verses)
        scroll.addView(layout)
        AlertDialog.Builder(activity).setTitle("القرآن الكريم")
            .setView(scroll)
            .setPositiveButton("استماع MP3") { _, _ ->
                AlertDialog.Builder(activity).setTitle("اختيار القارئ · " + name)
                    .setItems(readerNames) { _, idx -> play(number, idx) }
                    .setNegativeButton("رجوع", null).show()
            }
            .setNeutralButton("إيقاف التلاوة") { _, _ -> stopAudio() }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun play(chapter: Int, index: Int) {
        val saved = File(home, "reciter_" + index + "_surah_" + chapter + ".mp3")
        if (saved.isFile && saved.length() > 1024) { startAudio(saved); return }
        Toast.makeText(activity, "تحميل MP3 عند أول استماع؛ قد يستغرق بعض الوقت", Toast.LENGTH_LONG).show()
        io.execute {
            try {
                val reciters = JSONObject(fetch("https://mp3quran.net/api/v3/reciters?language=ar"))
                    .getJSONArray("reciters")
                var audioUrl: String? = null
                for (i in 0 until reciters.length()) {
                    val reciter = reciters.optJSONObject(i) ?: continue
                    val reciterName = reciter.optString("name")
                    if (!reciterName.contains(readerKeys[index])) continue
                    val mosahif = reciter.optJSONArray("moshaf") ?: continue
                    for (m in 0 until mosahif.length()) {
                        val mushaf = mosahif.optJSONObject(m) ?: continue
                        val available = ("," + mushaf.optString("surah_list") + ",")
                            .contains("," + chapter + ",")
                        val server = mushaf.optString("server")
                        if (available && server.startsWith("https://") &&
                            URL(server).host.endsWith("mp3quran.net")) {
                            audioUrl = server.trimEnd('/') + "/" +
                                String.format(java.util.Locale.US, "%03d", chapter) + ".mp3"
                            break
                        }
                    }
                    if (audioUrl != null) break
                }
                val url = audioUrl ?: error("Recitation unavailable for this reader")
                download(url, saved, 45_000_000L)
                activity.runOnUiThread { startAudio(saved) }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "لم تتوفر هذه التلاوة لدى المزود أو فشل التنزيل", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startAudio(file: File) {
        try {
            stopAudio()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stopAudio() }
                prepareAsync()
            }
            Toast.makeText(activity, "التلاوة · محفوظة على التلفاز بعد أول تحميل", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            stopAudio()
            Toast.makeText(activity, "تعذر تشغيل الملف الصوتي", Toast.LENGTH_LONG).show()
        }
    }
    fun stopAudio() { try { player?.stop() } catch (_: Exception) {}; player?.release(); player = null }
    fun close() { stopAudio(); io.shutdownNow() }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 22000
        try {
            require(c.responseCode == 200)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun download(link: String, dest: File, maxBytes: Long) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val c = URL(link).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            require(c.responseCode == 200)
            require(c.contentLengthLong <= maxBytes || c.contentLengthLong < 0)
            var size = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val bytes = ByteArray(16384)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        size += n
                        require(size <= maxBytes)
                        out.write(bytes, 0, n)
                    }
                }
            }
            require(size > 1024)
            require(tmp.renameTo(dest))
        } finally {
            c.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }
}
