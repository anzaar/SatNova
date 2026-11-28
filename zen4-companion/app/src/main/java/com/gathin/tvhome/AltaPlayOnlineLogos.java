package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * External Badr 26.0E + Es'hailSat 25.5E channel logo resolver & automatic local disk cache.
 * Downloads the orbit index once on first run and caches each channel's PNG permanently on disk.
 */
public final class AltaPlayOnlineLogos {
    private static final String PREFS = "altaplay_zen";
    private static final String KEY_INDEX_URL = "badr_eshail_index_url";
    private static final String KEY_INDEX_UPDATED_AT = "badr_eshail_index_updated_at";
    private static final String KEY_INDEX_ETAG = "badr_eshail_index_etag";

    private static final String DEFAULT_INDEX_URL =
            "https://raw.githubusercontent.com/anzaar/SatNova/main/zen4-companion/badr-eshail-logos.tsv";

    private static final String ALLOWED_PREFIX = "https://raw.githubusercontent.com/";
    private static final String INDEX_FILE = "badr-eshail-logo-index.tsv";
    private static final String CACHE_DIR = "altaplay-online-logos";

    private static final long INDEX_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long BAD_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_LOGO_BYTES = 350_000;
    private static final int MAX_INDEX_BYTES = 2_000_000;
    private static final long MAX_CACHE_TOTAL_BYTES = 14L * 1024L * 1024L;

    private static final Pattern SUFFIX_STRIP = Pattern.compile("\\b(4k|uhd|fhd|hd|sd|hevc|h265)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\u0600-\\u06FF]+");

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ConcurrentHashMap<String, String> INDEX = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> BAD_URLS = new ConcurrentHashMap<>();
    private static volatile boolean indexLoaded = false;
    private static volatile boolean indexSyncRunning = false;

    private AltaPlayOnlineLogos() {}

    public static void forceRefreshIndex(Context ctx) {
        final Context app = ctx.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_INDEX_UPDATED_AT, 0L)
                .apply();
        ensureIndexReady(app, true);
    }

    public static String normalized(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').replace('_', ' ');
        s = SUFFIX_STRIP.matcher(s).replaceAll(" ");
        s = NON_ALNUM.matcher(s).replaceAll(" ").trim();
        return s.replaceAll("\\s+", " ");
    }

    public static String sourceFor(Context ctx, String channel) {
        if (ctx == null || channel == null) return null;
        ensureIndexReady(ctx.getApplicationContext(), false);
        String key = normalized(channel);
        if (key.isEmpty()) return null;
        String direct = INDEX.get(key);
        if (direct != null) return direct;
        return INDEX.get(key.replace(" ", ""));
    }

    public static void loadInto(Context ctx, String channelName, ImageView target, View fallbackText) {
        if (ctx == null || target == null || channelName == null) return;
        final Context app = ctx.getApplicationContext();
        final String key = normalized(channelName);
        if (key.isEmpty()) return;

        target.setTag(key);
        ensureIndexReady(app, false);

        EXEC.execute(() -> {
            String url = sourceFor(app, channelName);
            if (!isSafeLogoUrl(url)) return;

            File cached = cacheFileFor(app, url);
            if (cached.isFile() && cached.length() > 100) {
                cached.setLastModified(System.currentTimeMillis());
                applyBitmap(target, fallbackText, key, cached);
                return;
            }

            Long failedAt = BAD_URLS.get(url);
            if (failedAt != null && (System.currentTimeMillis() - failedAt) < BAD_TTL_MS) {
                return;
            }

            if (downloadLogo(url, cached)) {
                trimCacheIfNeeded(new File(app.getFilesDir(), CACHE_DIR));
                applyBitmap(target, fallbackText, key, cached);
            } else {
                BAD_URLS.put(url, System.currentTimeMillis());
            }
        });
    }

    private static void ensureIndexReady(Context app, boolean forceRemote) {
        File localFile = new File(app.getFilesDir(), INDEX_FILE);
        if (!indexLoaded && localFile.isFile() && localFile.length() > 0) {
            loadIndexFromFile(localFile);
            indexLoaded = true;
        }

        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastUpdated = prefs.getLong(KEY_INDEX_UPDATED_AT, 0L);
        boolean needsFetch = forceRemote
                || !localFile.isFile()
                || (System.currentTimeMillis() - lastUpdated) > INDEX_TTL_MS;

        if (needsFetch && !indexSyncRunning) {
            indexSyncRunning = true;
            EXEC.execute(() -> {
                try {
                    String indexUrl = prefs.getString(KEY_INDEX_URL, DEFAULT_INDEX_URL);
                    if (isSafeIndexUrl(indexUrl) && downloadIndex(app, indexUrl, localFile, prefs)) {
                        loadIndexFromFile(localFile);
                        indexLoaded = true;
                    }
                } finally {
                    indexSyncRunning = false;
                }
            });
        }
    }

    private static boolean downloadIndex(Context app, String indexUrl, File dest, SharedPreferences prefs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(indexUrl).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(false);
            String etag = prefs.getString(KEY_INDEX_ETAG, "");
            if (!etag.isEmpty() && dest.isFile()) {
                conn.setRequestProperty("If-None-Match", etag);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.edit().putLong(KEY_INDEX_UPDATED_AT, System.currentTimeMillis()).apply();
                return false;
            }
            if (code != HttpURLConnection.HTTP_OK) return false;

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            try (InputStream in = conn.getInputStream()) {
                while ((n = in.read(chunk)) > 0) {
                    if (buf.size() + n > MAX_INDEX_BYTES) return false;
                    buf.write(chunk, 0, n);
                }
            }
            File tmp = new File(app.getFilesDir(), INDEX_FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(buf.toByteArray());
            }
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) return false;

            String newEtag = conn.getHeaderField("ETag");
            SharedPreferences.Editor ed = prefs.edit()
                    .putLong(KEY_INDEX_UPDATED_AT, System.currentTimeMillis());
            if (newEtag != null && !newEtag.isEmpty()) ed.putString(KEY_INDEX_ETAG, newEtag);
            ed.apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void loadIndexFromFile(File file) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            ConcurrentHashMap<String, String> fresh = new ConcurrentHashMap<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = normalized(line.substring(0, tab));
                String url = line.substring(tab + 1).trim();
                if (!key.isEmpty() && isSafeLogoUrl(url)) {
                    fresh.put(key, url);
                    fresh.putIfAbsent(key.replace(" ", ""), url);
                }
            }
            if (!fresh.isEmpty()) {
                INDEX.clear();
                INDEX.putAll(fresh);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean downloadLogo(String url, File dest) {
        HttpURLConnection conn = null;
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(7000);
            conn.setInstanceFollowRedirects(false);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            String ctype = conn.getContentType();
            if (ctype == null || !ctype.toLowerCase(Locale.ROOT).startsWith("image/")) return false;

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            try (InputStream in = conn.getInputStream()) {
                while ((n = in.read(chunk)) > 0) {
                    if (buf.size() + n > MAX_LOGO_BYTES) return false;
                    buf.write(chunk, 0, n);
                }
            }
            byte[] data = buf.toByteArray();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (opts.outWidth < 8 || opts.outHeight < 8 || opts.outWidth > 2000 || opts.outHeight > 2000) {
                return false;
            }
            File tmp = new File(parent, dest.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (dest.exists()) dest.delete();
            return tmp.renameTo(dest);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void applyBitmap(ImageView target, View fallbackText, String expectedKey, File file) {
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp == null) return;
        MAIN.post(() -> {
            if (!expectedKey.equals(target.getTag())) return;
            target.setImageBitmap(bmp);
            target.setVisibility(View.VISIBLE);
            if (fallbackText != null) fallbackText.setVisibility(View.GONE);
            else if (target.getParent() instanceof FrameLayout) {
                FrameLayout fl = (FrameLayout) target.getParent();
                for (int i = 0; i < fl.getChildCount(); i++) {
                    View child = fl.getChildAt(i);
                    if (child != target) child.setVisibility(View.GONE);
                }
            }
        });
    }

    private static File cacheFileFor(Context app, String url) {
        return new File(new File(app.getFilesDir(), CACHE_DIR), sha256Prefix(url) + ".png");
    }

    private static void trimCacheIfNeeded(File dir) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        long total = 0L;
        for (File f : files) total += f.length();
        if (total <= MAX_CACHE_TOTAL_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= MAX_CACHE_TOTAL_BYTES * 3 / 4) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    private static boolean isSafeIndexUrl(String url) {
        return url != null
                && url.startsWith(ALLOWED_PREFIX)
                && url.length() < 350
                && !url.contains("..")
                && !url.contains("?")
                && !url.contains("#")
                && url.endsWith(".tsv");
    }

    private static boolean isSafeLogoUrl(String url) {
        return url != null
                && url.startsWith(ALLOWED_PREFIX)
                && url.length() < 350
                && !url.contains("..")
                && !url.contains("?")
                && !url.contains("#")
                && url.endsWith(".png");
    }

    private static String sha256Prefix(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16 && i < hash.length; i++) {
                sb.append(String.format(Locale.US, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Throwable e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
