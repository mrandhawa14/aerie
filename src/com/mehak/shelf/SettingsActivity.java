package com.mehak.shelf;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shelf's own settings, plus direct shortcuts into the real Fire TV settings.
 * A plain focusable list styled like the home grid — no Amazon chrome.
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private Wallpaper wallpaper;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);

        FrameLayout root = new FrameLayout(this);
        wallpaper = new Wallpaper(this);
        int[] pal = Prefs.paletteColors(Prefs.wallpaper(this));
        wallpaper.setPalette(pal != null ? pal : new int[]{0xFF1C2A4A, 0xFF0E1018});
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(dp(80), dp(56), dp(80), dp(56));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // Room for the focused row's slight grow, so its rounded ends aren't clipped.
        list.setPadding(dp(14), 0, dp(14), 0);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        sv.setClipChildren(false);
        sv.addView(list, new ScrollView.LayoutParams(dp(708), -2));
        root.addView(sv, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL));
        setContentView(root);

        build();
    }

    private void build() {
        // Keep focus on the row that was just changed, not back at the top.
        int keep = list.indexOfChild(list.getFocusedChild());
        list.removeAllViews();
        View first = null;

        header("APPEARANCE");
        View w = row("Wallpaper", wallName(Prefs.wallpaper(this)), this::pickWallpaper);
        first = w;
        row("Items per row", String.valueOf(Prefs.columns(this)), this::pickColumns);
        row("Screensaver", saverName(Prefs.screensaverMin(this)), this::pickScreensaver);
        if (!Scenes.names(this).isEmpty()) {
            row("Aerial scene", Scenes.label(Prefs.scene(this)), this::pickScene);
        }

        header("SYSTEM");
        sysRow("Network", "android.settings.WIFI_SETTINGS", null);
        sysRow("Display & Sound", null,
                "com.amazon.tv.settings.v2/.tv.display_sounds.DisplayAndSoundsActivity");
        sysRow("Applications", "android.settings.APPLICATION_SETTINGS", null);
        sysRow("Remotes & Bluetooth", null,
                "com.amazon.tv.settings.v2/.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity");
        sysRow("My Account", null, "com.amazon.tv.settings.v2/.tv.my_account.MyAccountActivity");
        sysRow("Preferences", null, "com.amazon.tv.settings.v2/.tv.preferences.PreferencesActivity");
        sysRow("About this TV", "android.settings.DEVICE_INFO_SETTINGS", null);

        View again = keep >= 0 && keep < list.getChildCount() ? list.getChildAt(keep) : null;
        if (again != null && again.isFocusable()) again.requestFocus();
        else if (first != null) first.requestFocus();
    }

    // -------------------------------------------------------------- rows

    private void header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF8E8E93);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(8), list.getChildCount() == 0 ? 0 : dp(28), 0, dp(10));
        list.addView(t);
    }

    private View row(String title, String value, Runnable onClick) {
        LinearLayout r = rowShell();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.gravity = Gravity.CENTER_VERTICAL;
        r.addView(t, tp);

        TextView v = new TextView(this);
        v.setText(value == null ? "" : value);
        v.setTextColor(0xFF9AA0AA);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.setTag(v);
        r.setOnClickListener(x -> onClick.run());
        list.addView(r);
        return r;
    }

    private void sysRow(String title, String action, String component) {
        row(title, "›", () -> {
            Intent i = (action != null) ? new Intent(action) : new Intent();
            if (component != null) {
                String[] p = component.split("/");
                i.setClassName(p[0], p[0] + p[1]);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception e) {
                toast("Not available on this TV");
            }
        });
    }

    private LinearLayout rowShell() {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(22), dp(18), dp(22), dp(18));
        r.setFocusable(true);
        r.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x00000000);
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x00000000);
            ((TextView) ((ViewGroup) v).getChildAt(0)).setTextColor(has ? 0xFF101012 : Color.WHITE);
            View val = ((ViewGroup) v).getChildAt(1);
            if (val instanceof TextView) ((TextView) val).setTextColor(has ? 0xFF55585E : 0xFF9AA0AA);
            v.animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.015f : 1f).setDuration(150).start();
        });
        return r;
    }

    // ----------------------------------------------------------- pickers

    private void pickWallpaper() {
        choose("Wallpaper", Prefs.WALL_NAMES, idx -> {
            prefs.edit().putString(Prefs.WALLPAPER, Prefs.WALL_IDS[idx]).apply();
            int[] pal = Prefs.paletteColors(Prefs.WALL_IDS[idx]);
            wallpaper.setPalette(pal);
            if (pal == null) wallpaper.setPalette(new int[]{0xFF1C2A4A, 0xFF0E1018});
            build();
        });
    }

    private void pickColumns() {
        String[] names = {"4", "5", "6"};
        choose("Items per row", names, idx -> {
            prefs.edit().putInt(Prefs.COLUMNS, Prefs.COLUMN_CHOICES[idx]).apply();
            build();
        });
    }

    private void pickScreensaver() {
        choose("Screensaver", Prefs.SAVER_NAMES, idx -> {
            prefs.edit().putInt(Prefs.SCREENSAVER_MIN, Prefs.SAVER_CHOICES[idx]).apply();
            build();
        });
    }

    private void pickScene() {
        final java.util.List<String> names = Scenes.names(this);
        String[] labels = new String[names.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Scenes.label(names.get(i));
        choose("Aerial scene", labels, idx -> {
            prefs.edit().putString(Prefs.SCENE, names.get(idx)).apply();
            build();
        });
    }

    private interface Pick { void at(int index); }

    private void choose(String title, String[] options, Pick pick) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(options, (d, which) -> pick.at(which))
                .show();
    }

    private String wallName(String id) {
        for (int i = 0; i < Prefs.WALL_IDS.length; i++) if (Prefs.WALL_IDS[i].equals(id)) return Prefs.WALL_NAMES[i];
        return "Dynamic";
    }

    private String saverName(int min) {
        for (int i = 0; i < Prefs.SAVER_CHOICES.length; i++) if (Prefs.SAVER_CHOICES[i] == min) return Prefs.SAVER_NAMES[i];
        return min + " min";
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
