package com.satnova.alpha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logos are not part of the G-MScreen channel-name response on which this UI
 * was originally based. CHMAX may render its own receiver-side icons without
 * exposing their bytes/URLs to third-party LAN clients.
 *
 * Prefer an explicit logo URL supplied by the receiver when present.
 * Otherwise match only known channels to sourced public artwork. No guessed
 * third-party logo (or incorrect look-alike) is shown for other channels.
 */
internal object ChannelLogoSource {
    private const val community = "https://raw.githubusercontent.com/t4r1k2-dev/minitv-logos/main/"

    fun verifiedHttpLogo(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 2048) return null
        return try {
            val url = URI(raw.trim())
            if (url.scheme?.lowercase(Locale.ROOT) != "https" ||
                url.host.isNullOrBlank() || url.userInfo != null || url.port !in -1..443) null
            else {
                val host = url.host.lowercase(Locale.ROOT)
                if (host == "localhost" || host.endsWith(".local") ||
                    host.matches(Regex("[0-9.]+")) ||
                    host == "::1" || host.startsWith("192.168.") ||
                    host.startsWith("10.") || host.startsWith("172.")) null
                else url.toASCIIString()
            }
        } catch (_: Exception) { null }
    }

    /**
     * A deliberately small, verified matching table (file names checked in
     * the public project). These images are for identification only; not a
     * substitute for the receiver's CHMAX icon service.
     */
    fun urls(channel: ZenChannel): List<String> {
        val saved = verifiedHttpLogo(channel.logoUrl)
        val name = Normalizer.normalize(channel.name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\b(uhd|fhd|fullhd|full|hd|sd|4k|h265|hevc|backup|bkp|maroc|morocco|ma)\\b"), " ")
            .trim().replace(Regex("\\s+"), " ")

        val source = when (name) {
            "al aoula", "al oula", "aloula", "alawla", "الأولى", "الاولى" ->
                listOf(community + "al-aoula.png")
            "2m", "2m monde" -> listOf(community + "2m.png")
            "arryadia", "arriadia", "aryadia", "الرياضية" ->
                listOf(community + "arryadia.png")
            "athaqafia", "atthaqafia", "attaqafia", "الثقافية" ->
                listOf(
                    "https://freebox.cdn.scw.iliad.fr/medium_Athaqafia_fdc90e055e.png",
                    "https://i.imgur.com/mrwFI2L.png"
                )
            "mbc 1", "mbc1" -> listOf(community + "mbc1.png")
            "mbc 2", "mbc2" -> listOf(community + "mbc2.png")
            "mbc 3", "mbc3" -> listOf(community + "mbc3.png")
            "mbc 4", "mbc4" -> listOf(community + "mbc4.png")
            "mbc 5", "mbc5" -> listOf(community + "mbc5.png")
            "medi 1", "medi 1 tv", "medi1", "medi1 tv" ->
                listOf(community + "medi1.png")
            else -> emptyList()
        }
        return (listOfNotNull(saved) + source).mapNotNull(::verifiedHttpLogo).distinct()
    }
}

/** Non-blocking, memory+disk-cached HTTPS logo loading with bounded responses. */
internal class ChannelLogoStore(context: Context, private val onIndexReady: () -> Unit = {}) {
    @Volatile private var badr: Badr26Picons? = null
    private val root = File(context.filesDir, "channel-logos").apply { mkdirs() }
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pending = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private val failed = mutableSetOf<String>()
    private val lock = Any()

    init {
        // The bundled picon indexes can be large: never parse them on the UI thread.
        val appContext = context.applicationContext
        executor.execute {
            val index = try { Badr26Picons(appContext) } catch (_: Exception) { null }
            badr = index
            if (index != null) main.post { onIndexReady() }
        }
    }

    /** A logo chosen by the user stays on this television even when offline.
     * Normalize broadcast-quality suffixes so SD/HD/UHD variants share a logo.
     * All files stay in this application's private storage, without storage permissions. */
    private fun manualFile(channel: ZenChannel): File {
        val normalized = Normalizer.normalize(channel.name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("\\b(uhd|fhd|hd|sd|4k|hevc|h265|backup|bkp)\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val key = normalized.ifEmpty { channel.id }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, "manual-$hash.png")
    }

    fun hasCustomLogo(channel: ZenChannel): Boolean = manualFile(channel).isFile

    fun removeCustomLogo(channel: ZenChannel) {
        manualFile(channel).delete()
        synchronized(lock) { cache.evictAll(); failed.clear() }
    }

    /** Imports only a bounded, decodable user-selected graphic, never an arbitrary file path. */
    fun importCustomLogo(channel: ZenChannel, input: InputStream): Boolean {
        return try {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16_384)
        while (true) {
            val got = input.read(chunk)
            if (got < 0) break
            if (out.size() + got > 700_000) return false
            out.write(chunk, 0, got)
        }
        val data = out.toByteArray()
        val svg = String(data.copyOfRange(0, minOf(data.size, 200)), Charsets.UTF_8)
            .contains("<svg", ignoreCase = true)
        val bitmap = decode(data, if (svg) "manual.svg" else "manual.png")
            ?: return false
        val factor = minOf(1f, 400f / maxOf(bitmap.width, bitmap.height))
        val resized = if (factor < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1), true) else bitmap
        val tmp = File(root, "user-logo-upload.tmp")
        val result = tmp.outputStream().use { resized.compress(Bitmap.CompressFormat.PNG, 95, it) }
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        if (!result || tmp.length() !in 1..700_000) { tmp.delete(); return false }
        val dest = manualFile(channel)
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        synchronized(lock) { cache.evictAll(); failed.clear() }
        true
        } catch (_: Exception) { false }
    }

    fun bind(image: ImageView, channel: ZenChannel) {
        val custom = manualFile(channel)
        if (custom.isFile) {
            val key = "manual:" + custom.name
            image.tag = key
            val bmp = synchronized(lock) { cache.get(key) } ?: try {
                decode(custom.readBytes(), "manual.png")?.also {
                    synchronized(lock) { cache.put(key, it) }
                }
            } catch (_: Exception) { null }
            if (bmp != null) { image.setImageBitmap(bmp); return }
            custom.delete() // Corrupt cache: use verified online sources instead.
        }

        val urls = (listOfNotNull(
            ChannelLogoSource.verifiedHttpLogo(channel.logoUrl),
            badr?.url(channel)) + ChannelLogoSource.urls(channel)).distinct()
        if (urls.isEmpty()) return
        val key = urls.joinToString("\n")
        image.tag = key
        synchronized(lock) {
            cache.get(key)?.let { image.setImageBitmap(it); return }
            if (key in failed) return
            val group = pending[key]
            if (group != null) {
                group.add(WeakReference(image))
                return
            }
            pending[key] = mutableListOf(WeakReference(image))
        }
        executor.execute {
            var found: Bitmap? = null
            for (url in urls) {
                found = readDisk(url) ?: download(url)
                if (found != null) break
            }
            synchronized(lock) {
                if (found != null) cache.put(key, found)
                else failed.add(key)
            }
            main.post {
                val waiting = synchronized(lock) { pending.remove(key).orEmpty() }
                if (found != null) for (entry in waiting) {
                    entry.get()?.let { view ->
                        if (view.tag == key) view.setImageBitmap(found)
                    }
                }
            }
        }
    }

    fun retryFailed() = synchronized(lock) { failed.clear() }

    fun close() {
        executor.shutdownNow()
        synchronized(lock) { pending.clear() }
    }

    private fun cacheFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, "$hash.img")
    }

    /** Do not crop pixels of an original broadcast logo. Even translucent
     * edges belong to the artwork; FIT_CENTER fits the whole image. */
    private fun preserveWholeLogo(source: Bitmap): Bitmap = source

    private fun decode(data: ByteArray, sourceUrl: String): Bitmap? {
        if (data.isEmpty() || data.size > 700_000) return null
        if (sourceUrl.substringBefore('?').endsWith(".svg", ignoreCase = true)) {
            return try {
                val svg = SVG.getFromString(String(data, Charsets.UTF_8))
                val image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                canvas.drawPicture(svg.renderToPicture(320, 320))
                preserveWholeLogo(image)
            } catch (_: Exception) { null }
        }
        // Do not decode unexpectedly huge bitmap images into the UI heap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth !in 1..2400 || bounds.outHeight !in 1..2400) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = when {
                bounds.outWidth > 1200 || bounds.outHeight > 1200 -> 4
                bounds.outWidth > 600 || bounds.outHeight > 600 -> 2
                else -> 1
            }
        }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)?.let(::preserveWholeLogo)
        } catch (_: Exception) { null }
    }

    private fun readDisk(url: String): Bitmap? = try {
        val file = cacheFile(url)
        if (!file.isFile || file.length() !in 1..700_000) null
        else decode(file.readBytes(), url)
    } catch (_: Exception) { null }

    private fun download(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            // A checked public HTTPS URL, no HTTP downgrade or redirects.
            val valid = ChannelLogoSource.verifiedHttpLogo(url) ?: return null
            conn = URL(valid).openConnection() as HttpURLConnection
            conn.connectTimeout = 4500
            conn.readTimeout = 5500
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "image/png,image/jpeg,image/webp")
            if (conn.responseCode != 200) return null
            val mime = conn.contentType?.substringBefore(';')?.lowercase(Locale.ROOT)
            val svg = valid.substringBefore('?').endsWith(".svg", ignoreCase = true)
            val allowed = if (svg) listOf("image/svg+xml", "text/xml", "text/plain", "application/xml",
                "application/octet-stream")
                else listOf("image/png", "image/jpeg", "image/jpg", "image/webp")
            if (mime !in allowed) return null
            if (conn.contentLengthLong > 700_000L) return null
            val data = conn.inputStream.use { input ->
                val bytes = ByteArray(700_001)
                var offset = 0
                while (offset < bytes.size) {
                    val got = input.read(bytes, offset, bytes.size - offset)
                    if (got <= 0) break
                    offset += got
                }
                if (offset > 700_000) return null
                bytes.copyOf(offset)
            }
            val bmp = decode(data, valid) ?: return null
            try { cacheFile(valid).writeBytes(data) } catch (_: Exception) {}
            bmp
        } catch (_: Exception) { null }
        finally { conn?.disconnect() }
    }
}
