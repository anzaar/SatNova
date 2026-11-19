package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioAttributes
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
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Local index of all 114 surahs: choosing a chapter requires no network
 * request or full-Quran download. Uthmani text is fetched per chapter.
 * Recitation starts from a verified MP3Quran URL immediately, with optional
 * per-surah caching on a separate worker. No bulk audio download is started.
 */
internal class QuranLibrary(private val activity: Activity) {
    companion object {
        val SURAH_NAMES = listOf(
            "الفاتحة",
            "البقرة",
            "آل عمران",
            "النساء",
            "المائدة",
            "الأنعام",
            "الأعراف",
            "الأنفال",
            "التوبة",
            "يونس",
            "هود",
            "يوسف",
            "الرعد",
            "إبراهيم",
            "الحجر",
            "النحل",
            "الإسراء",
            "الكهف",
            "مريم",
            "طه",
            "الأنبياء",
            "الحج",
            "المؤمنون",
            "النور",
            "الفرقان",
            "الشعراء",
            "النمل",
            "القصص",
            "العنكبوت",
            "الروم",
            "لقمان",
            "السجدة",
            "الأحزاب",
            "سبأ",
            "فاطر",
            "يس",
            "الصافات",
            "ص",
            "الزمر",
            "غافر",
            "فصلت",
            "الشورى",
            "الزخرف",
            "الدخان",
            "الجاثية",
            "الأحقاف",
            "محمد",
            "الفتح",
            "الحجرات",
            "ق",
            "الذاريات",
            "الطور",
            "النجم",
            "القمر",
            "الرحمن",
            "الواقعة",
            "الحديد",
            "المجادلة",
            "الحشر",
            "الممتحنة",
            "الصف",
            "الجمعة",
            "المنافقون",
            "التغابن",
            "الطلاق",
            "التحريم",
            "الملك",
            "القلم",
            "الحاقة",
            "المعارج",
            "نوح",
            "الجن",
            "المزمل",
            "المدثر",
            "القيامة",
            "الإنسان",
            "المرسلات",
            "النبأ",
            "النازعات",
            "عبس",
            "التكوير",
            "الانفطار",
            "المطففين",
            "الانشقاق",
            "البروج",
            "الطارق",
            "الأعلى",
            "الغاشية",
            "الفجر",
            "البلد",
            "الشمس",
            "الليل",
            "الضحى",
            "الشرح",
            "التين",
            "العلق",
            "القدر",
            "البينة",
            "الزلزلة",
            "العاديات",
            "القارعة",
            "التكاثر",
            "العصر",
            "الهمزة",
            "الفيل",
            "قريش",
            "الماعون",
            "الكوثر",
            "الكافرون",
            "النصر",
            "المسد",
            "الإخلاص",
            "الفلق",
            "الناس"
        )
        fun surahs(): List<Pair<Int, String>> =
            SURAH_NAMES.mapIndexed { index, name -> (index + 1) to name }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val downloads = Executors.newSingleThreadExecutor()
    private val home = File(activity.filesDir, "quran").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var requestId = 0L
    private var failedStreamId = -1L
    private val readerNames = arrayOf("عمر القزابري", "عبد الرحمن السديس")
    private val readerKeys = arrayOf("قزابري", "سديس")

    /** Compatibility with the existing Settings menu: no remote metadata needed. */
    fun open() {
        val options = surahs().map { it.first.toString() + " · " + it.second }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("اختر سورة · القرآن الكريم")
            .setItems(options) { _, index -> selectSurah(index + 1) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    /** Called by the 114 directly visible surah tiles in the TV launcher. */
    fun selectSurah(number: Int) {
        val name = SURAH_NAMES.getOrNull(number - 1) ?: return
        val options = arrayOf(
            "قراءة سورة " + name,
            "استماع مباشر وحفظ السورة للاستماع لاحقاً",
            "إيقاف التلاوة"
        )
        AlertDialog.Builder(activity)
            .setTitle(number.toString() + " · سورة " + name)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> read(number, name)
                    1 -> chooseReciter(number)
                    2 -> stopAudio()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun read(number: Int, name: String) {
        val file = File(home, "surah_" + number + ".json")
        if (file.isFile) {
            try {
                val stored = file.readText()
                require(JSONObject(stored).getJSONObject("data").getJSONArray("ayahs").length() > 0)
                showSurah(number, name, stored)
                return
            } catch (_: Exception) {
                file.delete()
            }
        }
        Toast.makeText(activity, "تحميل نص سورة " + name + " فقط…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val json = fetch("https://api.alquran.cloud/v1/surah/" + number + "/quran-uthmani")
                val root = JSONObject(json)
                require(root.optInt("code") == 200)
                require(root.getJSONObject("data").getJSONArray("ayahs").length() > 0)
                file.writeText(json)
                activity.runOnUiThread { showSurah(number, name, json) }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity,
                        "تعذر تحميل نص السورة من المصدر؛ تحقق من الإنترنت", Toast.LENGTH_LONG).show()
                }
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
        layout.addView(TextView(activity).apply {
            text = "سورة " + name
            textSize = 27f
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 16)
        })
        layout.addView(TextView(activity).apply {
            text = (0 until arr.length()).joinToString("  ") { idx ->
                val item = arr.getJSONObject(idx)
                item.getString("text") + " ﴿" + item.getInt("numberInSurah") + "﴾"
            }
            textSize = 27f
            setLineSpacing(12f, 1.12f)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        })
        scroll.addView(layout)
        AlertDialog.Builder(activity).setTitle("القرآن الكريم · " + name)
            .setView(scroll)
            .setPositiveButton("استماع مباشر") { _, _ -> chooseReciter(number) }
            .setNeutralButton("إيقاف الصوت") { _, _ -> stopAudio() }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun chooseReciter(number: Int) {
        AlertDialog.Builder(activity)
            .setTitle("اختر القارئ · سورة " + SURAH_NAMES[number - 1])
            .setItems(readerNames) { _, index -> play(number, index) }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun play(chapter: Int, index: Int) {
        if (chapter !in 1..114 || index !in readerNames.indices) return
        val saved = File(home, "reciter_" + index + "_surah_" + chapter + ".mp3")
        val token = ++requestId
        failedStreamId = -1
        releaseAudio()
        if (saved.isFile && saved.length() > 1024L) {
            startSavedAudio(saved, token)
            return
        }
        Toast.makeText(activity, "جاري فتح التلاوة المباشرة للسورة…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val audioUrl = resolveRecitationUrl(chapter, index)
                activity.runOnUiThread {
                    if (token != requestId || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    startStreamingAudio(audioUrl, token)
                    // Streaming does not wait for this worker; one surah at a time
                    // is cached independently for future offline playback.
                    downloads.execute {
                        try {
                            download(audioUrl, saved, 120_000_000L)
                            activity.runOnUiThread {
                                if (token == requestId && !activity.isFinishing) {
                                    if (failedStreamId == token) {
                                        Toast.makeText(activity,
                                            "اكتمل التحميل؛ تشغيل النسخة المحفوظة", Toast.LENGTH_SHORT).show()
                                        failedStreamId = -1
                                        startSavedAudio(saved, token)
                                    } else {
                                        Toast.makeText(activity,
                                            "حُفظت سورة " + SURAH_NAMES[chapter - 1] +
                                                " للاستماع بلا إنترنت", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            activity.runOnUiThread {
                                if (token == requestId && !activity.isFinishing) {
                                    Toast.makeText(activity,
                                        "لم يكتمل حفظ السورة؛ الاستماع المباشر يبقى متاحاً",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    if (token == requestId && !activity.isFinishing) {
                        Toast.makeText(activity,
                            "تعذر الحصول على رابط التلاوة لهذا القارئ؛ تحقق من الاتصال",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resolveRecitationUrl(chapter: Int, index: Int): String {
        val recitersCache = File(home, "reciters_ar.json")
        val reciters: JSONArray = try {
            val data = JSONObject(fetch("https://www.mp3quran.net/api/v3/reciters?language=ar"))
            val array = data.getJSONArray("reciters")
            recitersCache.writeText(data.toString())
            array
        } catch (_: Exception) {
            JSONObject(recitersCache.readText()).getJSONArray("reciters")
        }
        for (i in 0 until reciters.length()) {
            val reciter = reciters.optJSONObject(i) ?: continue
            if (!reciter.optString("name").contains(readerKeys[index])) continue
            val mosahif = reciter.optJSONArray("moshaf") ?: continue
            for (m in 0 until mosahif.length()) {
                val mushaf = mosahif.optJSONObject(m) ?: continue
                val available = ("," + mushaf.optString("surah_list") + ",")
                    .contains("," + chapter + ",")
                val server = mushaf.optString("server").trim()
                val host = try { URL(server).host.lowercase(Locale.US) }
                    catch (_: Exception) { "" }
                if (!available || !server.startsWith("https://") ||
                    (host != "mp3quran.net" && !host.endsWith(".mp3quran.net"))) continue
                return server.trimEnd('/') + "/" +
                    String.format(Locale.US, "%03d", chapter) + ".mp3"
            }
        }
        error("Requested surah is not available for this reader")
    }

    private fun startStreamingAudio(url: String, token: Long) {
        releaseAudio()
        try {
            val mp = newPlayer(token)
            player = mp
            mp.setOnErrorListener { _, _, _ ->
                if (token == requestId) {
                    failedStreamId = token
                    Toast.makeText(activity,
                        "تعثر البث؛ سيُجرّب الملف المحفوظ إن اكتمل تحميله",
                        Toast.LENGTH_LONG).show()
                }
                true
            }
            mp.setDataSource(url)
            mp.prepareAsync()
        } catch (_: Exception) {
            failedStreamId = token
            releaseAudio()
            Toast.makeText(activity,
                "تعذر بدء البث؛ نواصل محاولة حفظ السورة", Toast.LENGTH_LONG).show()
        }
    }

    private fun startSavedAudio(file: File, token: Long) {
        releaseAudio()
        try {
            val mp = newPlayer(token)
            player = mp
            mp.setOnErrorListener { _, _, _ ->
                if (token == requestId) {
                    Toast.makeText(activity, "تعذر تشغيل MP3 المحفوظ", Toast.LENGTH_LONG).show()
                }
                true
            }
            mp.setDataSource(file.absolutePath)
            mp.prepareAsync()
        } catch (_: Exception) {
            releaseAudio()
            Toast.makeText(activity, "تعذر تشغيل MP3 المحفوظ", Toast.LENGTH_LONG).show()
        }
    }

    private fun newPlayer(token: Long) = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
        setOnPreparedListener {
            if (token == requestId) {
                it.start()
                Toast.makeText(activity, "التلاوة بدأت", Toast.LENGTH_SHORT).show()
            }
        }
        setOnCompletionListener {
            if (token == requestId) stopAudio()
        }
    }

    private fun releaseAudio() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun stopAudio() {
        ++requestId
        releaseAudio()
    }

    fun close() {
        stopAudio()
        io.shutdownNow()
        downloads.shutdownNow()
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 12000
        c.readTimeout = 22000
        try {
            require(c.responseCode == 200)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Cache one MP3 (never all Quran) using an atomic file rename. */
    private fun download(link: String, dest: File, maxBytes: Long) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val c = URL(link).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000
        c.readTimeout = 45000
        try {
            require(c.responseCode == 200)
            require(c.contentLengthLong in -1L..maxBytes)
            var size = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val bytes = ByteArray(16384)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) error("Download interrupted")
                        val n = input.read(bytes)
                        if (n < 0) break
                        size += n
                        require(size <= maxBytes)
                        out.write(bytes, 0, n)
                    }
                }
            }
            require(size > 1024L)
            require(tmp.renameTo(dest))
        } finally {
            c.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }
}
