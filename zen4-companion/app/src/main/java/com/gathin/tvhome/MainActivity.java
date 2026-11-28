package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Clean, self-contained Android TV Companion for SecretoTVLauncher:
 * - Connects to Zen 4 on LAN via ZenClient
 * - Displays TV & Radio channels with cached Badr 26E + Es'hailSat 25.5E logos
 * - Tunes selected channel & switches immediately to HDMI via AltaPlayHdmi1
 * - Configures AltaPlayHdmiRemoteService so TV remote controls Zen 4 inside HDMI
 */
public final class MainActivity extends Activity {
    private ZenClient client;
    private TextView statusView;
    private Button tvTabBtn;
    private Button radioTabBtn;
    private GridView gridView;
    private ChannelAdapter adapter;
    private boolean showRadio = false;
    private final List<ZenClient.Channel> visibleChannels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1220"));
        int pad = dp(24);
        root.setPadding(pad, dp(18), pad, dp(18));

        // Top header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Zen4 TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        titleCol.addView(title);

        statusView = new TextView(this);
        statusView.setText("جاري الاتصال بالريسيفر...");
        statusView.setTextColor(Color.parseColor("#94A3B8"));
        statusView.setTextSize(13f);
        titleCol.addView(statusView);

        header.addView(titleCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(dp(8));

        tvTabBtn = makeActionButton("قنوات TV", v -> {
            showRadio = false;
            refreshChannels();
        });
        header.addView(tvTabBtn);

        radioTabBtn = makeActionButton("الراديو", v -> {
            showRadio = true;
            refreshChannels();
        });
        header.addView(radioTabBtn, btnLp);

        header.addView(makeActionButton("IP الريسيفر", v -> openHostDialog()), btnLp);
        header.addView(makeActionButton("إعداد HDMI", v -> openHdmiSetup()), btnLp);
        header.addView(makeActionButton("تفعيل الريموت", v -> openAccessibilitySettings()), btnLp);
        header.addView(makeActionButton("تحديث الشعارات", v -> {
            AltaPlayOnlineLogos.forceRefreshIndex(this);
            Toast.makeText(this, "جاري تحديث فهرس شعارات بدر وسهيل سات...", Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged();
        }), btnLp);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Channel Grid
        gridView = new GridView(this);
        gridView.setNumColumns(5);
        gridView.setHorizontalSpacing(dp(12));
        gridView.setVerticalSpacing(dp(12));
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setDrawSelectorOnTop(false);
        gridView.setSelector(makeCardSelector());

        adapter = new ChannelAdapter();
        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visibleChannels.size()) return;
            ZenClient.Channel ch = visibleChannels.get(position);
            onChannelSelected(ch);
        });

        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(16);
        root.addView(gridView, gridLp);

        setContentView(root);

        client = ZenClient.get(this);
        client.setListener(this::refreshChannels);
        client.start();
        refreshChannels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (client != null) {
            client.setListener(this::refreshChannels);
            refreshChannels();
        }
    }

    private void onChannelSelected(ZenClient.Channel ch) {
        if (ch == null) return;
        client.tune(ch);
        boolean switched = false;
        try {
            switched = AltaPlayHdmi1.open(this);
        } catch (Throwable ignored) {
        }
        String msg = switched
                ? "تم التوجيه إلى " + ch.name + " (HDMI)"
                : "تم إرسال القناة: " + ch.name;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void refreshChannels() {
        if (client == null) return;
        List<ZenClient.Channel> all = client.channels();
        visibleChannels.clear();
        if (all != null) {
            for (ZenClient.Channel ch : all) {
                boolean isRadio = (ch.tvState != 0);
                if (isRadio == showRadio) {
                    visibleChannels.add(ch);
                }
            }
        }
        String host = client.host();
        String st = client.getStatus();
        String label = (client.isOnline() ? "متصل" : "غير متصل")
                + (TextUtils.isEmpty(host) ? "" : " (" + host + ")")
                + " • " + visibleChannels.size() + (showRadio ? " إذاعة" : " قناة")
                + (TextUtils.isEmpty(st) ? "" : " — " + st);
        statusView.setText(label);
        tvTabBtn.setAlpha(showRadio ? 0.65f : 1.0f);
        radioTabBtn.setAlpha(showRadio ? 1.0f : 0.65f);
        adapter.notifyDataSetChanged();
    }

    private void openHostDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.1.x");
        if (client != null && !TextUtils.isEmpty(client.host())) {
            input.setText(client.host());
        }
        new AlertDialog.Builder(this)
                .setTitle("عنوان IP جهاز الاستقبال Zen 4")
                .setView(input)
                .setPositiveButton("حفظ واتصال", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        client.setHost(ip);
                        Toast.makeText(this, "جاري الاتصال بـ " + ip, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
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
        b.setTextSize(13f);
        b.setBackground(makeButtonBg());
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setFocusable(true);
        b.setOnClickListener(listener);
        return b;
    }

    private StateListDrawable makeButtonBg() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#0F766E"));
        focused.setCornerRadius(dp(10));
        focused.setStroke(dp(2), Color.parseColor("#2DD4BF"));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.parseColor("#1E293B"));
        normal.setCornerRadius(dp(10));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{android.R.attr.state_selected}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private StateListDrawable makeCardSelector() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#16283D"));
        focused.setCornerRadius(dp(12));
        focused.setStroke(dp(3), Color.parseColor("#2DD4BF"));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        normal.setCornerRadius(dp(12));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{android.R.attr.state_selected}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visibleChannels.size();
        }

        @Override
        public Object getItem(int position) {
            return visibleChannels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            ImageView logoView;
            TextView fallbackBadge;
            TextView nameView;

            if (convertView == null) {
                card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                card.setPadding(dp(10), dp(12), dp(10), dp(10));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#131E32"));
                bg.setCornerRadius(dp(12));
                card.setBackground(bg);

                FrameLayout logoBox = new FrameLayout(MainActivity.this);
                logoView = new ImageView(MainActivity.this);
                logoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                logoBox.addView(logoView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                fallbackBadge = new TextView(MainActivity.this);
                fallbackBadge.setGravity(Gravity.CENTER);
                fallbackBadge.setTextColor(Color.parseColor("#38BDF8"));
                fallbackBadge.setTextSize(16f);
                logoBox.addView(fallbackBadge, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                card.addView(logoBox, new LinearLayout.LayoutParams(dp(96), dp(58)));

                nameView = new TextView(MainActivity.this);
                nameView.setTextColor(Color.WHITE);
                nameView.setTextSize(13f);
                nameView.setMaxLines(1);
                nameView.setEllipsize(TextUtils.TruncateAt.END);
                nameView.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = dp(8);
                card.addView(nameView, nlp);

                card.setTag(new Holder(logoView, fallbackBadge, nameView));
            } else {
                card = (LinearLayout) convertView;
                Holder h = (Holder) card.getTag();
                logoView = h.logo;
                fallbackBadge = h.badge;
                nameView = h.name;
            }

            ZenClient.Channel ch = visibleChannels.get(position);
            String cname = ch.name != null ? ch.name : "";
            nameView.setText(cname);
            fallbackBadge.setText(cname.length() > 3 ? cname.substring(0, 3).toUpperCase() : cname);
            fallbackBadge.setVisibility(View.VISIBLE);
            logoView.setImageDrawable(null);
            logoView.setVisibility(View.GONE);

            // 1) Local bundled or receiver-cached logo
            File local = null;
            try {
                local = client.logo(cname);
            } catch (Throwable ignored) {
            }
            if (local == null || !local.isFile()) {
                try {
                    local = AltaPlayChannelLogos.fromName(MainActivity.this, cname);
                } catch (Throwable ignored) {
                }
            }
            if (local != null && local.isFile()) {
                Bitmap bmp = BitmapFactory.decodeFile(local.getAbsolutePath());
                if (bmp != null) {
                    logoView.setImageBitmap(bmp);
                    logoView.setVisibility(View.VISIBLE);
                    fallbackBadge.setVisibility(View.GONE);
                    return card;
                }
            }

            // 2) External Badr 26E + Es'hailSat 25.5E index + permanent disk cache
            AltaPlayOnlineLogos.loadInto(MainActivity.this, cname, logoView, fallbackBadge);
            return card;
        }
    }

    private static final class Holder {
        final ImageView logo;
        final TextView badge;
        final TextView name;

        Holder(ImageView logo, TextView badge, TextView name) {
            this.logo = logo;
            this.badge = badge;
            this.name = name;
        }
    }
}
