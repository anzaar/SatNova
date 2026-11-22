#!/usr/bin/env python3
"""AltaPlay v0.11: no black cold-start while decoding user's custom wallpaper.

The upstream WallpaperPlayer decodes 4K full-frame wallpaper synchronously in
MainActivity.onCreate. Move only that bitmap decode to its existing worker and
paint a tiny packaged Islamic fallback immediately, preserving the owner's
selected wallpaper, rotation, caching, focus, accessibility, and 2D graphics.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1]).resolve()
launcher=root/"launcher" if (root/"launcher/app/build.gradle").is_file() else root
src=launcher/"app/src/main/java/com/gathin/tvhome/WallpaperPlayer.java"
styles=launcher/"app/src/main/res/values/styles.xml"
if not src.is_file() or not styles.is_file():raise RuntimeError("upstream wallpaper source missing")
s=src.read_text(encoding="utf-8")
old='''            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();'''
new='''            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Immediate inexpensive draw, never block Home on a full-resolution
            // bitmap decode. It is replaced with the exact user-selected image.
            image.setImageResource(R.drawable.altaplay_islamic);
            showFirst();'''
if s.count(old)!=1:raise RuntimeError("wallpaper constructor anchor changed")
s=s.replace(old,new)
start=s.index("    /**\n     * The picture Home opens with is decoded right here")
end=s.index("\n    void start() {",start)
s=s[:start]+'''    /**
     * Cold-start never decodes a potentially multi-megabyte bitmap on the UI
     * thread. The default drawable is already visible behind the app dock.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        if (first == null) return;
        final File next = first;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable first image");
                    if (active) update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                showCaption(false);
                if (!displayed.equals(previous))
                    preferences.edit().putString("image",displayed)
                        .putLong("changed",System.currentTimeMillis()).apply();
                if (active) update();
            });
        });
    }
'''+s[end:]
src.write_text(s,encoding="utf-8")
t=styles.read_text(encoding="utf-8")
needle='<item name="android:windowBackground">@android:color/black</item>'
if t.count(needle)!=1:raise RuntimeError("window background anchor changed")
t=t.replace(needle,
'''<!-- Show ready artwork while Android creates Home, not an opaque black
             starting window. The owner's wallpaper loads asynchronously. -->
        <item name="android:windowBackground">@drawable/altaplay_islamic</item>''')
styles.write_text(t,encoding="utf-8")
print("PASS v0.11 fast first frame, no synchronous wallpaper decode, preserved user wallpaper")
