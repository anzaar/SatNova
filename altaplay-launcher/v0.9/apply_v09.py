#!/usr/bin/env python3
"""AltaPlay Launcher v0.9 source patch for the verified MediaTek/Vestel HDMI 1
installation. Run after v0.8, before Gradle build. Does not modify TV settings.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
launcher = root / "launcher" if (root / "launcher/app/build.gradle").is_file() else root
java = launcher / "app/src/main/java/com/gathin/tvhome"
panel = java / "AltaPlayZenPanel.java"
service = java / "AltaPlayHdmiRemoteService.java"
assert panel.is_file() and service.is_file(), "First apply overlays v0.1 through v0.8"

s = panel.read_text(encoding="utf-8")
start = s.index("    private void tune(ZenClient.Channel item){")
end = s.index("    private GradientDrawable tileBackground(", start)
original = s[start:end]
assert 'new Intent("com.android.tv.action.VIEW_INPUTS")' in original, "Existing input chooser was changed"
s = s[:start] + '''    private void tune(ZenClient.Channel item){
        // The receiver network command is already proven on this installation.
        client.tune(item);
        // HDMI 1 = HW5 on this MediaTek/Vestel TV, confirmed using ADB.
        // Never show the system input picker for a channel click.
        if(!AltaPlayHdmi1.open(activity)){
            Toast.makeText(activity,
                "تعذر فتح HDMI 1 تلقائياً · استعمل زر مصادر الإدخال",
                Toast.LENGTH_LONG).show();
        }
    }
''' + s[end:]

assert s.count("placeholder.setMaxLines(1);") == 1
s = s.replace(
    "placeholder.setMaxLines(1);",
    """placeholder.setMaxLines(2);
            placeholder.setPadding(dp(5),dp(3),dp(5),dp(3));
            // Branded fallback remains readable when the channel has no logo,
            // when the downloaded image fails, and without Wi-Fi/Zen.
            GradientDrawable fallback=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xC92C5A63,0xDB172F40});
            fallback.setCornerRadius(dp(9));
            placeholder.setBackground(fallback);""", 1)

assert s.count("img.setPadding(dp(2),dp(1),dp(2),dp(1));") == 1
s = s.replace("img.setPadding(dp(2),dp(1),dp(2),dp(1));",
              "img.setPadding(0,0,0,0);", 1)
assert s.count("card.addView(logoBox,new LinearLayout.LayoutParams(dp(119),dp(46)));") == 1
s = s.replace("card.addView(logoBox,new LinearLayout.LayoutParams(dp(119),dp(46)));",
              "card.addView(logoBox,new LinearLayout.LayoutParams(dp(125),dp(47)));", 1)
# Editable remote package field uses the known, tested MediaTek HDMI viewer by
# default, without overwriting any explicitly stored user setting.
s = s.replace(
    'getString("hdmi_package","")',
    'getString("hdmi_package","com.mediatek.wwtv.tvcenter")',
    1)
panel.write_text(s, encoding="utf-8")

remote = service.read_text(encoding="utf-8")
assert remote.count('prefs.getBoolean("hdmi_remote_enabled",false)') == 1
remote = remote.replace(
    'prefs.getBoolean("hdmi_remote_enabled",false)',
    # Accessibility itself must be explicitly enabled by the user; never call
    # secure settings. Respect a subsequently saved explicit false preference.
    'prefs.getBoolean("hdmi_remote_enabled",true)', 1)
assert remote.count('prefs.getString("hdmi_package","")') == 1
remote = remote.replace(
    'prefs.getString("hdmi_package","")',
    'prefs.getString("hdmi_package","com.mediatek.wwtv.tvcenter")', 1)
assert remote.count('AltaPlayRemoteKeys.handle(this,code);') == 1
remote = remote.replace(
    'AltaPlayRemoteKeys.handle(this,code);',
    '''{
                android.util.Log.d("AltaPlayHdmiRemote","Forward key "+code+" to Zen 4");
                AltaPlayRemoteKeys.handle(this,code);
            }''', 1)
service.write_text(remote, encoding="utf-8")

# Use the exact Android passthrough URI confirmed on the physical TV.
(java / "AltaPlayHdmi1.java").write_text('''package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

final class AltaPlayHdmi1 {
    private static final String INPUT_ID =
        "com.mediatek.tvinput/.hdmi.HDMIInputService/HW5";
    private AltaPlayHdmi1(){}

    static boolean open(Activity activity){
        try{
            Uri uri = TvContract.buildChannelUriForPassthroughInput(INPUT_ID);
            Intent intent = new Intent(Intent.ACTION_VIEW,uri);
            // Same action/data as the physical-TV ADB command that opened HW5.
            // No VIEW_INPUTS action, no guessed activity, no system preference edits.
            activity.startActivity(intent);
            return true;
        }catch(Exception failure){
            Log.w("AltaPlayHdmi1","Unable to launch verified HDMI 1 viewer",failure);
            return false;
        }
    }
}
''', encoding="utf-8")
print("PASS v0.9: HW5 passthrough, bounded FIT_CENTER logos, offline fallback, HDMI scoped remote")
