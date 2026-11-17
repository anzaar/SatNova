package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Moroccan home information: no approximated prayer times or guessed Hijri dates. */
internal class MoroccanHomePanel(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val preview: Boolean
) {
    private data class City(val name: String, val id: Int, val lat: Double, val lon: Double)
    private val cities = listOf(
        City("الخميسات", 2, 33.8242, -6.0663),
        City("الرباط", 1, 34.0209, -6.8416),
        City("تيفلت", 3, 33.8940, -6.3060)
    )
    private data class PrayerDay(val times: Map<String, LocalTime>, val hijri: String)
    private val zone = ZoneId.of("Africa/Casablanca")
    private val arabic = Locale("ar", "MA")
    private val names = listOf("الفجر" to "fajr", "الظهر" to "dhuhr",
        "العصر" to "asr", "المغرب" to "maghrib", "العشاء" to "ishaa")
    private val prayers = mutableMapOf<LocalDate, PrayerDay>()
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var prayerFetchAt = 0L
    private var weatherFetchAt = 0L
    private var loadingPrayer = false
    private var loadingWeather = false
    private var weather = "جاري تحميل الطقس…"
    private var stopped = false
    private var tickScheduled = false
    private lateinit var clock: TextView
    private lateinit var day: TextView
    private lateinit var date: TextView
    private lateinit var hijri: TextView
    private lateinit var cityLabel: TextView
    private lateinit var nextPrayer: TextView
    private lateinit var prayerClock: TextView
    private lateinit var countdown: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var sourceLabel: TextView

    private fun dp(n: Int): Int = (n * activity.resources.displayMetrics.density + .5f).toInt()
    private fun city(): City = cities.firstOrNull { it.id == prefs.getInt("morocco_city_id", 2) } ?: cities[0]
    private fun text(s: String, size: Float, color: Int = Color.rgb(240, 242, 236),
                     bold: Boolean = false) = TextView(activity).apply {
        this.text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        includeFontPadding = false
        maxLines = 2
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    fun backdrop(): Drawable = IslamicGeometryDrawable(activity.resources.displayMetrics.density)

    fun build(): View {
        val panelShape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(218, 19, 36, 38))
            setStroke(dp(1), Color.rgb(64, 90, 82))
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = panelShape
            setPadding(dp(24), dp(17), dp(24), dp(17))
            minimumHeight = dp(if (preview) 180 else 196)
        }
        fun column(weight: Float): LinearLayout {
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
            }
            panel.addView(col, LinearLayout.LayoutParams(0, -1, weight))
            return col
        }
        val a = column(1.35f)
        day = text("اليوم", 29f, Color.rgb(212, 223, 181), true)
        clock = text("--:--", if (preview) 43f else 58f, Color.WHITE, true)
        date = text("التاريخ الميلادي", 26f)
        hijri = text("التاريخ الهجري: في انتظار التقويم الرسمي", 23f, Color.rgb(231, 202, 151))
        a.addView(day); a.addView(clock); a.addView(date); a.addView(hijri)
        val b = column(1.18f)
        cityLabel = text("📍 " + city().name, 20f, Color.rgb(176, 221, 198), true)
        nextPrayer = text("الصلاة المقبلة", 21f)
        countdown = text("--:--:--", if (preview) 25f else 32f, Color.rgb(217, 228, 161), true)
        prayerClock = text("الوقت: —", 17f)
        b.addView(cityLabel); b.addView(nextPrayer); b.addView(countdown); b.addView(prayerClock)
        sourceLabel = text("مواقيت منقولة عن الوزارة · مصدر غير رسمي", 12f, Color.rgb(161, 181, 176))
        b.addView(sourceLabel)
        val c = column(.82f)
        weatherLabel = text(weather, if (preview) 17f else 22f, Color.WHITE, true)
        c.addView(text("الطقس الآن", 20f, Color.rgb(176, 221, 198), true))
        c.addView(weatherLabel)
        c.addView(text("Open-Meteo", 12f, Color.rgb(161, 181, 176)))
        updateClock()
        requestData()
        if (!tickScheduled) { tickScheduled = true; ui.post(ticker) }
        return panel
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (stopped) return
            updateClock()
            requestData()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun updateClock() {
        if (!::clock.isInitialized) return
        val now = ZonedDateTime.now(zone)
        clock.text = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
        day.text = now.format(DateTimeFormatter.ofPattern("EEEE", arabic))
        date.text = now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", arabic)) + " م"
        cityLabel.text = "📍 " + city().name
        weatherLabel.text = weather
        val today = manualDay(now.toLocalDate()) ?: prayers[now.toLocalDate()]
        sourceLabel.text = when {
            manualDay(now.toLocalDate()) != null -> "مواقيت أدخلتها من الحصة الرسمية"
            today != null -> "مواقيت منقولة عن الوزارة · مصدر غير رسمي"
            else -> "تعذّر جلب الحصة الرسمية"
        }
        hijri.text = if (today != null && today.hijri.isNotBlank()) today.hijri + " هـ"
            else "التاريخ الهجري: في انتظار التقويم الرسمي"
        var nextAt: ZonedDateTime? = null
        var name = ""
        for (offset in 0..1) {
            val d = now.toLocalDate().plusDays(offset.toLong())
            val times = (manualDay(d) ?: prayers[d])?.times ?: continue
            for ((label, key) in names) {
                val time = times[key] ?: continue
                val candidate = d.atTime(time).atZone(zone)
                if (candidate.isAfter(now)) { nextAt = candidate; name = label; break }
            }
            if (nextAt != null) break
        }
        val next = nextAt
        if (next == null) {
            nextPrayer.text = "الصلاة المقبلة"
            countdown.text = if (loadingPrayer) "جاري جلب المواقيت…" else "المواقيت غير متاحة"
            prayerClock.text = "راجع الحصة الرسمية لمدينتك"
        } else {
            val seconds = java.time.Duration.between(now, next).seconds.coerceAtLeast(0L)
            nextPrayer.text = "الصلاة المقبلة: " + name
            countdown.text = String.format(Locale.US, "%02d:%02d:%02d",
                seconds / 3600, seconds / 60 % 60, seconds % 60)
            prayerClock.text = "على الساعة " + next.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
        }
    }

    private fun requestData() {
        if (stopped) return
        val now = System.currentTimeMillis()
        if (!loadingPrayer && (prayerFetchAt == 0L || now - prayerFetchAt > 3_600_000L)) {
            loadingPrayer = true
            prayerFetchAt = now
            val target = city()
            executor.execute {
                // The upstream endpoint is independently operated; do not mark its data as
                // a live verified ministry API, and reject old/mismatched calendar dates.
                val result = try { parseMonth(fetch(
                    "https://habous-prayer-times-api.onrender.com/api/v1/prayer-times?cityId=" + target.id)) }
                    catch (_: Exception) { emptyMap() }
                ui.post {
                    if (stopped) return@post
                    if (city().id == target.id) {
                        if (result.isNotEmpty()) { prayers.clear(); prayers.putAll(result) }
                        loadingPrayer = false
                        updateClock()
                    }
                }
            }
        }
        if (!loadingWeather && (weatherFetchAt == 0L || now - weatherFetchAt > 1_800_000L)) {
            loadingWeather = true
            weatherFetchAt = now
            val target = city()
            executor.execute {
                val result = try {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=" + target.lat +
                        "&longitude=" + target.lon +
                        "&current=temperature_2m,weather_code&timezone=Africa%2FCasablanca"
                    val current = JSONObject(fetch(url)).getJSONObject("current")
                    val degrees = current.getDouble("temperature_2m")
                    val code = current.optInt("weather_code", -1)
                    val condition = when (code) {
                        0 -> "صحو"
                        1, 2 -> "غائم جزئياً"
                        3 -> "غائم"
                        45, 48 -> "ضباب"
                        in 51..67 -> "أمطار"
                        in 71..77 -> "ثلوج"
                        in 80..82 -> "زخات"
                        in 95..99 -> "عواصف"
                        else -> "حالة الطقس"
                    }
                    condition + "\n" + String.format(Locale.US, "%.0f°C", degrees)
                } catch (_: Exception) { "الطقس غير متاح" }
                ui.post {
                    if (stopped) return@post
                    if (city().id == target.id) {
                        weather = result
                        loadingWeather = false
                        updateClock()
                    }
                }
            }
        }
    }

    private fun fetch(link: String): String {
        val c = URL(link).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 9000
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) error("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parseMonth(body: String): Map<LocalDate, PrayerDay> {
        val all = mutableMapOf<LocalDate, PrayerDay>()
        val array = JSONObject(body).getJSONObject("data").getJSONArray("timings")
        val months = listOf("January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December")
        val keys = names.map { it.second }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val date = item.optJSONObject("date") ?: continue
            val greg = date.optJSONObject("gregorian") ?: continue
            val month = months.indexOfFirst { it.equals(greg.optString("month"), true) } + 1
            if (month !in 1..12) continue
            val d = try { LocalDate.of(greg.getInt("year"), month, greg.getInt("day")) }
                catch (_: Exception) { continue }
            if (kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.now(zone), d)) > 36) continue
            val p = item.optJSONObject("prayers") ?: continue
            val timings = mutableMapOf<String, LocalTime>()
            for (key in keys) try { timings[key] = LocalTime.parse(p.getString(key)) }
                catch (_: Exception) { }
            if (timings.size != 5) continue
            val h = date.optJSONObject("hijri")
            val hMonth = h?.optString("month").orEmpty()
            val hijriText = if (hMonth.isNotBlank())
                h?.optInt("day").toString() + " " + hMonth + " " + h?.optInt("year") else ""
            all[d] = PrayerDay(timings, hijriText)
        }
        return all
    }

    private fun manualDay(date: LocalDate): PrayerDay? {
        val key = "manual_habous_" + city().id + "_" + date.toString()
        return try {
            val raw = prefs.getString(key, null) ?: return null
            val data = JSONObject(raw)
            val times = names.associate { (label, field) ->
                field to LocalTime.parse(data.getString(field))
            }
            PrayerDay(times, data.optString("hijri", ""))
        } catch (_: Exception) { null }
    }

    /** Manual backup for ministry-issued city schedule when mirror is unavailable. */
    fun editOfficialDay(tomorrow: Boolean, onChange: () -> Unit) {
        val date = LocalDate.now(zone).plusDays(if (tomorrow) 1 else 0)
        val previous = manualDay(date)
        val input = EditText(activity).apply {
            minLines = 3
            maxLines = 5
            setText(previous?.let { p ->
                names.joinToString(",") { p.times[it.second]?.toString().orEmpty() } +
                    "\n" + p.hijri
            } ?: "")
            hint = "05:26,12:14,15:00,17:26,18:42\n7 جمادى الآخرة 1448"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(24, 20, 24, 20)
        }
        AlertDialog.Builder(activity)
            .setTitle("الحصة الرسمية · " + city().name + " · " + date.toString())
            .setMessage("أدخل مواقيت الصبح والظهر والعصر والمغرب والعشاء بالترتيب، مفصولة بفواصل، ثم التاريخ الهجري في السطر الثاني. المصدر: habous.gov.ma. لا تُقبل الساعات غير الصحيحة.")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val lines = input.text.toString().trim().lines()
                val values = lines.firstOrNull().orEmpty().split(",").map { it.trim() }
                try {
                    require(values.size == 5)
                    val json = JSONObject()
                    names.forEachIndexed { i, (_, key) ->
                        val t = LocalTime.parse(values[i])
                        json.put(key, t.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)))
                    }
                    json.put("hijri", lines.getOrNull(1).orEmpty().trim())
                    prefs.edit().putString(
                        "manual_habous_" + city().id + "_" + date.toString(), json.toString()
                    ).apply()
                    updateClock()
                    onChange()
                    Toast.makeText(activity, "تم حفظ أوقات " + date.toString(), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(activity, "صيغة غير صحيحة: استعمل HH:mm لخمس صلوات", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    fun chooseCity(onChange: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("مدينة مواقيت الصلاة والطقس")
            .setSingleChoiceItems(cities.map { it.name }.toTypedArray(),
                cities.indexOf(city())) { dialog, which ->
                prefs.edit().putInt("morocco_city_id", cities[which].id).apply()
                prayers.clear()
                prayerFetchAt = 0L; weatherFetchAt = 0L
                loadingPrayer = false; loadingWeather = false
                weather = "جاري تحميل الطقس…"
                dialog.dismiss()
                onChange()
            }.setNegativeButton("إلغاء", null).show()
    }

    fun stop() {
        stopped = true
        ui.removeCallbacks(ticker)
        executor.shutdownNow()
    }
}

/** A soft, low-contrast star pattern drawn without images or GPU animations. */
internal class IslamicGeometryDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(Color.rgb(12, 25, 28))
        paint.shader = RadialGradient(b.width() * .75f, b.height() * .18f,
            b.width().coerceAtLeast(b.height()) * .9f,
            intArrayOf(Color.rgb(33, 53, 47), Color.rgb(12, 25, 28)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = null
        paint.color = Color.argb(20, 197, 181, 137)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        val step = 145f * density
        val radius = 43f * density
        val star = Path()
        var x = -step
        while (x < b.width() + step) {
            var y = -step
            while (y < b.height() + step) {
                star.reset()
                for (n in 0 until 16) {
                    val angle = Math.PI * n / 8
                    val r = if (n % 2 == 0) radius else radius * .51f
                    val px = x + kotlin.math.cos(angle).toFloat() * r
                    val py = y + kotlin.math.sin(angle).toFloat() * r
                    if (n == 0) star.moveTo(px, py) else star.lineTo(px, py)
                }
                star.close()
                canvas.drawPath(star, paint)
                y += step
            }
            x += step
        }
        paint.style = Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
