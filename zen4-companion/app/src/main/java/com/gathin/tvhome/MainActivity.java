package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Lightweight companion TV activity designed to run alongside SecretoTVLauncher.
 * Provides Zen 4 LAN connection, TV/Radio channels with cached Badr 26E + Es'hailSat 25.5E logos,
 * one-click HDMI switching, and guided HDMI remote accessibility setup.
 */
public final class MainActivity extends Activity {
    private FrameLayout panelHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1220"));
        int pad = dp(24);
        root.setPadding(pad, dp(18), pad, dp(18));

        // Compact header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Zen4 TV • قنوات الريسيفر");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        Button hdmiBtn = makeActionButton("إعداد HDMI والريموت", v -> openHdmiSetup());
        header.addView(hdmiBtn);

        Button accessBtn = makeActionButton("تفعيل الريموت", v -> openAccessibilitySettings());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(dp(10));
        header.addView(accessBtn, btnLp);

        Button refreshLogosBtn = makeActionButton("تحديث الشعارات", v -> {
            AltaPlayOnlineLogos.forceRefreshIndex(this);
            Toast.makeText(this, "جاري تحديث فهرس شعارات بدر وسهيل سات...", Toast.LENGTH_SHORT).show();
        });
        header.addView(refreshLogosBtn, btnLp);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Main channel panel container
        panelHost = new FrameLayout(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        hostLp.topMargin = dp(16);
        root.addView(panelHost, hostLp);

        setContentView(root);

        // Start Zen 4 client and attach AltaPlayZenPanel (copied from SatNova during build)
        ZenClient.get(this).start();
        attachZenPanel();
    }

    private void attachZenPanel() {
        try {
            // AltaPlayZenPanel is supplied from SatNova/altaplay-launcher/v0.5/AltaPlayZenPanel.java
            View panel = AltaPlayZenPanel.attach(this, panelHost);
            if (panel != null && panel.getParent() == null) {
                panelHost.addView(panel);
            }
        } catch (Throwable t) {
            TextView fallback = new TextView(this);
            fallback.setText("تعذّر تحميل لوحة قنوات Zen 4: " + t.getMessage());
            fallback.setTextColor(Color.parseColor("#FCA5A5"));
            fallback.setTextSize(16f);
            panelHost.addView(fallback);
        }
    }

    private void openHdmiSetup() {
        try {
            AltaPlayHdmiGuidedSetup.show(this, () ->
                    Toast.makeText(this, "تم حفظ إعداد منفذ HDMI بنجاح", Toast.LENGTH_SHORT).show());
        } catch (Throwable t) {
            Toast.makeText(this, "تعذّر فتح معالج HDMI: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "افتح إعدادات إمكانية الوصول لتفعيل Zen4 TV", Toast.LENGTH_LONG).show();
        }
    }

    private Button makeActionButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#1E293B"));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setFocusable(true);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
