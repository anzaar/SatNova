package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.util.List;

/**
 * Switches the Android TV display to the configured HDMI input using standard
 * TvInputManager + TvContract passthrough URIs.
 */
final class AltaPlayHdmi1 {
    private static final String PREFS = "altaplay_zen";
    private static final String FALLBACK_INPUT_ID =
            "com.mediatek.tvinput/.hdmi.HDMIInputService/HW5";

    private AltaPlayHdmi1() {}

    static boolean open(Activity activity) {
        if (activity == null) return false;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedInput = prefs.getString("hdmi_input_id", "").trim();
        String savedPkg = prefs.getString("hdmi_package", "").trim();

        String inputId = !savedInput.isEmpty() ? savedInput : detectFirstHdmiInput(activity);
        if (inputId == null || inputId.isEmpty()) {
            inputId = FALLBACK_INPUT_ID;
        }

        if (launchPassthrough(activity, inputId, savedPkg)) return true;
        if (!FALLBACK_INPUT_ID.equals(inputId) && launchPassthrough(activity, FALLBACK_INPUT_ID, savedPkg)) {
            return true;
        }
        if (!savedPkg.isEmpty()) {
            try {
                Intent launch = activity.getPackageManager().getLaunchIntentForPackage(savedPkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(launch);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String detectFirstHdmiInput(Context ctx) {
        try {
            TvInputManager mgr = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (mgr == null) return null;
            List<TvInputInfo> list = mgr.getTvInputList();
            if (list == null) return null;
            for (TvInputInfo info : list) {
                if (info != null && info.getType() == TvInputInfo.TYPE_HDMI) {
                    return info.getId();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean launchPassthrough(Activity activity, String inputId, String pkg) {
        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg != null && !pkg.isEmpty()) {
                intent.setPackage(pkg);
                if (intent.resolveActivity(activity.getPackageManager()) != null) {
                    activity.startActivity(intent);
                    return true;
                }
                intent.setPackage(null);
            }
            if (intent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(intent);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
