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
    private data class City(val name: String, val id: Int, val lat: Double, val lon: Double,
        val country: String = "Morocco", val timezone: String = "Africa/Casablanca")
    private val cities = listOf(
        City("الخميسات", 2, 33.8242, -6.0663),
        City("الرباط", 1, 34.0209, -6.8416),
        City("تيفلت", 3, 33.8940, -6.3060)
    )
    private data class PrayerDay(val times: Map<String, LocalTime>, val hijri: String)
    private fun zone(): ZoneId {
        val selected = city()
        // GMT was restored by Morocco in September 2026.
        if (selected.country.equals("Morocco", true) ||
            selected.country.equals("المغرب", true)) return java.time.ZoneOffset.UTC
        return try { ZoneId.of(selected.timezone) }
            catch (_: Exception) { ZoneId.systemDefault() }
    }
    private val arabic = Locale("ar", "MA")
    private val names = listOf("الفجر" to "fajr", "الظهر" to "dhuhr",
        "العصر" to "asr", "المغرب" to "maghrib", "العشاء" to "ishaa")
    private val prayers = mutableMapOf<LocalDate, PrayerDay>()
    private val adhan by lazy { SatNovaAdhan(activity, prefs) }
    fun chooseAdhan() = adhan.choose()
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var prayerFetchAt = 0L
    private var weatherFetchAt = 0L
    private var loadingPrayer = false
    private var loadingWeather = false
    private var weather = "جاري تحميل الطقس…"
    private var stopped = false
    private var tickScheduled = false
    private var cachedCityId = -1
    private var prayerSource = "habous"
    private fun loadPrayerCache() {
        val selected = city().id
        if (cachedCityId == selected) return
        cachedCityId = selected
        prayers.clear()
        try {
            val raw = prefs.getString("habous_cache_$selected", null) ?: return
            prayerSource = prefs.getString("prayer_source_$selected", "habous") ?: "habous"
            prayers.putAll(if (prayerSource == "aladhan") parseAladhanMonth(raw) else parseMonth(raw))
        } catch (_: Exception) { }
    }
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
    private fun city(): City {
        val custom = prefs.getString("user_city_name", "").orEmpty()
        if (custom.isNotBlank()) {
            val lat = java.lang.Double.longBitsToDouble(
                prefs.getLong("user_city_lat", java.lang.Double.doubleToRawLongBits(33.8242)))
            val lon = java.lang.Double.longBitsToDouble(
                prefs.getLong("user_city_lon", java.lang.Double.doubleToRawLongBits(-6.0663)))
            val country = prefs.getString("user_city_country", "Morocco") ?: "Morocco"
            val tz = prefs.getString("user_city_timezone", "Africa/Casablanca") ?: "Africa/Casablanca"
            return City(custom, -10000 - (custom + lat + lon).hashCode().and(0x7fff),
                lat, lon, country, tz)
        }
        return cities.firstOrNull { it.id == prefs.getInt("morocco_city_id", 2) } ?: cities[0]
    }
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

    private val wallpaper by lazy { SatNovaWallpaper(activity, prefs) }
    fun refreshBackground() = wallpaper.reload()
    fun backdrop(): Drawable = wallpaper

    fun build(): View {
        loadPrayerCache()
        val panelShape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(Color.argb(224, 3, 46, 34))
            setStroke(dp(1), Color.rgb(177, 188, 98))
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            background = panelShape
            setPadding(dp(10), dp(9), dp(10), dp(9))
            minimumHeight = dp(if (preview) 130 else 144)
        }
        fun column(weight: Float): LinearLayout {
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER
            }
            panel.addView(col, LinearLayout.LayoutParams(0, -2, weight))
            return col
        }
        fun center(v: TextView): TextView = v.apply {
            gravity = Gravity.CENTER
            maxLines = 2
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        fun divider() {
            panel.addView(View(activity).apply {
                setBackgroundColor(Color.argb(115, 186, 177, 123))
            }, LinearLayout.LayoutParams(dp(1), dp(76)).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }

        // The lantern stays decorative; no content/permission/network cost.
        panel.addView(IslamicLanternView(activity), LinearLayout.LayoutParams(0, dp(112), 0.73f))
        divider()

        val weatherCol = column(.96f)
        weatherLabel = center(text(weather, if (preview) 15f else 19f, Color.WHITE, true))
        weatherCol.addView(center(text("☁ الطقس الآن", 15f, Color.rgb(199, 219, 198), true)))
        weatherCol.addView(weatherLabel)
        divider()

        val cityCol = column(.77f)
        cityLabel = center(text("📍 " + city().name, 17f, Color.rgb(236, 239, 222), true))
        cityCol.addView(cityLabel)
        divider()

        val prayerCol = column(1.48f)
        nextPrayer = center(text("الصلاة المقبلة", 16f, Color.rgb(201, 229, 169), true))
        countdown = center(text("--:--:--", if (preview) 21f else 27f,
            Color.rgb(229, 246, 203), true))
        prayerClock = center(text("الوقت: —", 13f))
        prayerCol.addView(nextPrayer)
        prayerCol.addView(countdown)
        prayerCol.addView(prayerClock)
        divider()

        val hijriCol = column(1.40f)
        hijri = center(text("بانتظار التقويم المغربي", if (preview) 14f else 18f, Color.WHITE, true))
        hijriCol.addView(center(text("التقويم الهجري المغربي", 12f,
            Color.rgb(208, 224, 192))))
        hijriCol.addView(hijri)
        divider()

        val gregorianCol = column(1.43f)
        date = center(text("التاريخ الميلادي", if (preview) 15f else 20f, Color.WHITE, true))
        gregorianCol.addView(date)
        divider()

        val timeCol = column(1.33f)
        day = center(text("اليوم", 23f, Color.rgb(205, 234, 170), true))
        clock = center(text("--:--", if (preview) 37f else 48f, Color.WHITE, true))
        timeCol.addView(day)
        timeCol.addView(clock)

        // Kept for the network-source metadata and state updates, but not
        // displayed as a bulky fourth row inside the narrow header.
        sourceLabel = text("", 11f)
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
        val now = ZonedDateTime.now(zone())
        clock.text = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
        day.text = now.format(DateTimeFormatter.ofPattern("EEEE", arabic))
        date.text = now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", arabic)) + " م"
        cityLabel.text = "📍 " + city().name
        weatherLabel.text = weather
        val today = manualDay(now.toLocalDate()) ?: prayers[now.toLocalDate()]
        sourceLabel.text = when {
            manualDay(now.toLocalDate()) != null -> "مواقيت أدخلتها من الحصة الرسمية"
            today != null && prayerSource == "aladhan" -> "حساب AlAdhan · الطريقة المغربية 21 (غير رسمي)"
            today != null -> "مواقيت منقولة عن الوزارة · مصدر غير رسمي"
            else -> "تعذّر جلب الحصة الرسمية"
        }
        // Morocco follows the ministry's moon-sighting announcements, not
        // a generic astronomical Hijri converter. 20 Nov 2026 = 10 Jumada II
        // 1448, per the ministry's dated Supreme Scientific Council notice.
        // This verified anchor is deliberately limited to the known month.
        val officialHijri = officialMoroccanHijri(now.toLocalDate())
        hijri.text = officialHijri ?: "التقويم الهجري الرسمي غير متاح"
        var nextAt: ZonedDateTime? = null
        var name = ""
        for (offset in 0..1) {
            val d = now.toLocalDate().plusDays(offset.toLong())
            val times = (manualDay(d) ?: prayers[d])?.times ?: continue
            for ((label, key) in names) {
                val time = times[key] ?: continue
                val candidate = d.atTime(time).atZone(zone())
                if (candidate.isAfter(now)) { nextAt = candidate; name = label; break }
            }
            if (nextAt != null) break
        }
        val todayForAdhan = now.toLocalDate()
        val available = (manualDay(todayForAdhan) ?: prayers[todayForAdhan])?.times
        if (available != null) {
            for ((_, code) in names) {
                val time = available[code] ?: continue
                if (time.hour == now.hour && time.minute == now.minute) {
                    adhan.onPrayer(todayForAdhan.toString() + ":" + code)
                    break
                }
            }
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

    private fun officialMoroccanHijri(date: LocalDate): String? {
        // Ministry of Habous: 20-21 Nov 2026 = 10-11 Jumada II 1448.
        // Do not extrapolate into the next month before moon-sighting.
        val first = LocalDate.of(2026, 11, 11)
        val last = LocalDate.of(2026, 12, 9)
        if (date.isBefore(first) || date.isAfter(last)) return null
        val day = java.time.temporal.ChronoUnit.DAYS.between(first, date) + 1L
        return "$day جمادى الآخرة 1448 هـ"
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
                var response = ""
                var source = "habous"
                var result = if (target.id > 0) try {
                    response = fetch("https://habous-prayer-times-api.onrender.com/api/v1/prayer-times?cityId=" + target.id)
                    parseMonth(response)
                } catch (_: Exception) { emptyMap() } else emptyMap()
                if (result.isEmpty()) {
                    // Fallback is a calculated Morocco-method schedule, NOT the
                    // ministry's directly published city schedule.
                    try {
                        val today = LocalDate.now(zone())
                        val url = "https://api.aladhan.com/v1/calendarByCity/" +
                            today.year + "/" + today.monthValue +
                            "?city=" + java.net.URLEncoder.encode(target.name, "UTF-8") +
                            "&country=" + java.net.URLEncoder.encode(target.country, "UTF-8") +
                            "&method=" + if (target.country.equals("Morocco", true)) "21" else "3"
                        val fallback = fetch(url)
                        val parsed = parseAladhanMonth(fallback)
                        if (parsed.isNotEmpty()) {
                            response = fallback
                            result = parsed
                            source = "aladhan"
                        }
                    } catch (_: Exception) { }
                }
                ui.post {
                    if (stopped) return@post
                    if (city().id == target.id) {
                        if (result.isNotEmpty()) {
                            prayers.clear()
                            prayers.putAll(result)
                            prayerSource = source
                            prefs.edit().putString("habous_cache_" + target.id, response)
                                .putString("prayer_source_" + target.id, source).apply()
                        }
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
                        "&current=temperature_2m,weather_code&timezone=auto"
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
        c.connectTimeout = 25000
        c.readTimeout = 25000
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) error("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parseAladhanMonth(body: String): Map<LocalDate, PrayerDay> {
        val root = JSONObject(body)
        if (root.optInt("code") != 200) return emptyMap()
        val items = root.optJSONArray("data") ?: return emptyMap()
        val result = mutableMapOf<LocalDate, PrayerDay>()
        val mapping = mapOf("fajr" to "Fajr", "dhuhr" to "Dhuhr",
            "asr" to "Asr", "maghrib" to "Maghrib", "ishaa" to "Isha")
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val greg = entry.optJSONObject("date")?.optJSONObject("gregorian") ?: continue
            val date = try {
                val parts = greg.getString("date").split("-")
                LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
            } catch (_: Exception) { continue }
            if (kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.now(zone()), date)) > 36) continue
            val times = entry.optJSONObject("timings") ?: continue
            val mapped = mutableMapOf<String, LocalTime>()
            for ((key, apiKey) in mapping) {
                val raw = times.optString(apiKey).trim()
                val clock = Regex("^([0-2][0-9]):([0-5][0-9])").find(raw)?.value
                if (clock != null) try { mapped[key] = LocalTime.parse(clock) }
                    catch (_: Exception) { }
            }
            if (mapped.size == 5) result[date] = PrayerDay(mapped, "")
        }
        return result
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
                    LocalDate.now(zone()), d)) > 36) continue
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
        val date = LocalDate.now(zone()).plusDays(if (tomorrow) 1 else 0)
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
        val options = cities.map { it.name + " · المغرب" } + "🔎 البحث عن مدينة أخرى (جميع البلدان)"
        AlertDialog.Builder(activity)
            .setTitle("مدينة الطقس ومواقيت الصلاة")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == cities.size) {
                    searchCity(onChange)
                } else {
                    prefs.edit().putString("user_city_name", "")
                        .putInt("morocco_city_id", cities[which].id).apply()
                    resetLocation()
                    onChange()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun resetLocation() {
        prayers.clear()
        cachedCityId = -1
        prayerFetchAt = 0L; weatherFetchAt = 0L
        loadingPrayer = false; loadingWeather = false
        weather = "جاري تحميل الطقس…"
    }

    private fun searchCity(onChange: () -> Unit) {
        val entry = EditText(activity).apply {
            hint = "اسم المدينة · Rabat, Paris, Cairo…"
            isSingleLine = true
            setText(city().name)
        }
        AlertDialog.Builder(activity).setTitle("البحث عن مدينة")
            .setView(entry).setPositiveButton("بحث") { _, _ ->
                val term = entry.text.toString().trim()
                if (term.length < 2) {
                    Toast.makeText(activity, "أدخل اسم المدينة", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                executor.execute {
                    try {
                        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                            java.net.URLEncoder.encode(term, "UTF-8") +
                            "&count=20&language=ar&format=json"
                        val locations = JSONObject(fetch(url)).optJSONArray("results") ?: org.json.JSONArray()
                        ui.post {
                            if (stopped) return@post
                            if (locations.length() == 0) {
                                Toast.makeText(activity, "لم تُعثر على المدينة", Toast.LENGTH_LONG).show()
                                return@post
                            }
                            val shown = (0 until locations.length()).map { i ->
                                val item = locations.getJSONObject(i)
                                item.optString("name") + " · " +
                                    item.optString("admin1") + " · " + item.optString("country")
                            }.toTypedArray()
                            AlertDialog.Builder(activity).setTitle("اختر مدينتك")
                                .setItems(shown) { _, index ->
                                    val item = locations.getJSONObject(index)
                                    val lat = item.getDouble("latitude")
                                    val lon = item.getDouble("longitude")
                                    val name = item.getString("name")
                                    val country = if (item.optString("country_code") == "MA")
                                        "Morocco" else item.optString("country")
                                    prefs.edit()
                                        .putString("user_city_name", name)
                                        .putString("user_city_country", country)
                                        .putString("user_city_timezone", item.optString("timezone", "UTC"))
                                        .putLong("user_city_lat", java.lang.Double.doubleToRawLongBits(lat))
                                        .putLong("user_city_lon", java.lang.Double.doubleToRawLongBits(lon))
                                        .apply()
                                    resetLocation()
                                    onChange()
                                }.setNegativeButton("إلغاء", null).show()
                        }
                    } catch (_: Exception) {
                        ui.post { Toast.makeText(activity, "فشل البحث عن المدينة", Toast.LENGTH_LONG).show() }
                    }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    fun stop() {
        adhan.close()
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
