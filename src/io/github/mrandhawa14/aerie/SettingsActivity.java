package io.github.mrandhawa14.aerie;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Aerie's settings, plus every Fire TV settings page a third-party app is
 * allowed to open. Many Fire TV pages (Accessibility, Display & Audio,
 * Preferences, Parental controls, Inputs, Account) are guarded by
 * Amazon-signature permissions, so for those the "Fire TV Settings" row opens
 * Amazon's full settings grid, which contains all of them.
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private Wallpaper wallpaper;
    private LinearLayout list;
    private boolean large;

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
        large = Prefs.largeText(this);
        View first;

        header("HOME SCREEN");
        first = row("Wallpaper", null, wallName(Prefs.wallpaper(this)), this::pickWallpaper);
        if (!Scenes.names(this).isEmpty()) {
            row("Aerial scene", "Plays in the screensaver and sets the Scenery wallpaper",
                    Scenes.label(Prefs.scene(this)), this::pickScene);
        }
        row("Screensaver", null, saverName(Prefs.screensaverMin(this)), this::pickScreensaver);
        row("Items per row", null, String.valueOf(Prefs.columns(this)), this::pickColumns);
        int hidden = hiddenApps().size();
        row("Hidden apps", hidden == 0 ? "Hold Select on a tile to hide it" : null,
                hidden == 0 ? "None" : String.valueOf(hidden), this::manageHidden);

        header("ACCESSIBILITY");
        toggle("Reduce motion", "Fades instead of zooming and sliding", Prefs.REDUCE_MOTION);
        toggle("Always show app names", "Names under every tile, not just the focused one", Prefs.ALWAYS_LABELS);
        toggle("Larger text", "Bigger app names, clock and settings", Prefs.LARGE_TEXT);
        link("Fire TV accessibility", "VoiceView, captions, magnifier, high contrast text: "
                + "opens Fire TV Settings, then choose Accessibility", mainSettings());

        header("FIRE TV");
        link("Fire TV Settings", "Inputs, Display & Audio, Preferences, Parental controls, "
                + "Account & Profiles, Equipment Control, Live TV, Sleep Timer, Help", mainSettings());
        link("Network", "Wi-Fi and Ethernet", action("android.settings.WIFI_SETTINGS"));
        link("Remotes & Bluetooth", "Remotes, game controllers, headphones",
                action("com.amazon.device.settings.action.CONTROLLERS"));
        link("Pair a Bluetooth device", null,
                action("com.amazon.device.settings.action.DISCOVER_INPUT_DEVICE"));
        link("Applications", "Installed apps, storage, Appstore",
                action("com.amazon.device.settings.action.APPLICATIONS"));
        link("Notifications", "Which apps can notify you",
                action("com.amazon.device.settings.action.NOTIFICATION_SETTING"));
        link("Notification center", "Your recent notifications",
                action("com.amazon.tv.action.NOTIFICATION_CENTER"));
        link("Soundbar", null, action("com.amazon.device.settings.action.SOUNDBAR_SETTINGS"));

        header("DEVICE");
        link("About this TV", "Model, storage, software version",
                action("com.amazon.device.settings.action.ABOUT"));
        link("Check for updates", null,
                action("com.amazon.device.settings.about.action.CHECK_FOR_UPDATES"));
        link("USB drive", "Eject or format", action("com.amazon.device.settings.action.EXTERNAL_STORAGE"));
        link("Developer options", "ADB debugging, unknown apps",
                action("com.amazon.device.settings.action.APPLICATION_DEVELOPMENT_SETTINGS"));

        View again = keep >= 0 && keep < list.getChildCount() ? list.getChildAt(keep) : null;
        if (again != null && again.isFocusable()) again.requestFocus();
        else first.requestFocus();
    }

    // ------------------------------------------------------------ intents

    private static Intent action(String action) {
        return new Intent(action);
    }

    /** Amazon's full settings grid (exported without a permission). */
    private static Intent mainSettings() {
        return new Intent().setClassName("com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.MainSettingsActivity");
    }

    // -------------------------------------------------------------- rows

    private float sp(float base) {
        return large ? base * 1.25f : base;
    }

    private void header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF8E8E93);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(8), list.getChildCount() == 0 ? 0 : dp(28), 0, dp(10));
        list.addView(t);
    }

    private View row(String title, String subtitle, String value, Runnable onClick) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(22), dp(subtitle == null ? 18 : 13), dp(22), dp(subtitle == null ? 18 : 13));
        r.setFocusable(true);
        r.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x00000000);
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(17));
        texts.addView(t);
        final TextView s = new TextView(this);
        if (subtitle != null) {
            s.setText(subtitle);
            s.setTextColor(0xFF8E8E93);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12.5f));
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView v = new TextView(this);
        v.setText(value == null ? "" : value);
        v.setTextColor(0xFF9AA0AA);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(15));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.leftMargin = dp(16);
        r.addView(v, vp);

        r.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x00000000);
            t.setTextColor(has ? 0xFF101012 : Color.WHITE);
            s.setTextColor(has ? 0xFF55585E : 0xFF8E8E93);
            v.setTextColor(has ? 0xFF55585E : 0xFF9AA0AA);
            float k = has && !Prefs.reduceMotion(this) ? 1.015f : 1f;
            x.animate().scaleX(k).scaleY(k).setDuration(150).start();
            // Scroll a row early: keep one row (or header) of look-ahead above
            // and below the focused one, so it never rides the screen's edge.
            if (has) x.post(() -> {
                int ahead = x.getHeight() + dp(16);
                x.requestRectangleOnScreen(
                        new android.graphics.Rect(0, -ahead, x.getWidth(), x.getHeight() + ahead), false);
            });
        });
        r.setOnClickListener(x -> onClick.run());
        list.addView(r);
        return r;
    }

    private void toggle(String title, String subtitle, final String key) {
        boolean on = prefs.getBoolean(key, false);
        row(title, subtitle, on ? "On" : "Off", () -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply();
            build();
        });
    }

    private void link(String title, String subtitle, final Intent intent) {
        row(title, subtitle, "›", () -> {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception e) {
                toast("Not available on this TV");
            }
        });
    }

    // ----------------------------------------------------------- pickers

    private void pickWallpaper() {
        choose("Wallpaper", Prefs.WALL_NAMES, idx -> {
            prefs.edit().putString(Prefs.WALLPAPER, Prefs.WALL_IDS[idx]).apply();
            int[] pal = Prefs.paletteColors(Prefs.WALL_IDS[idx]);
            wallpaper.setPalette(pal != null ? pal : new int[]{0xFF1C2A4A, 0xFF0E1018});
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
        final List<String> names = Scenes.names(this);
        String[] labels = new String[names.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Scenes.label(names.get(i));
        choose("Aerial scene", labels, idx -> {
            prefs.edit().putString(Prefs.SCENE, names.get(idx)).apply();
            build();
        });
    }

    /** Lists hidden apps by name; picking one shows it again. */
    private void manageHidden() {
        final List<String> ids = hiddenApps();
        if (ids.isEmpty()) {
            toast("No hidden apps. Hold Select on a tile to hide it.");
            return;
        }
        PackageManager pm = getPackageManager();
        final List<String> labels = new ArrayList<>();
        for (String id : ids) {
            String label = id;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(id, 0);
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (PackageManager.NameNotFoundException ignored) {
                label = id + " (not installed)";
            }
            labels.add("Show " + label);
        }
        labels.add("Show all");
        choose("Hidden apps", labels.toArray(new String[0]), idx -> {
            Set<String> keep = new HashSet<>(ids);
            if (idx == ids.size()) keep.clear();
            else keep.remove(ids.get(idx));
            prefs.edit().putStringSet(Prefs.HIDDEN, keep).apply();
            build();
        });
    }

    private List<String> hiddenApps() {
        List<String> out = new ArrayList<>(prefs.getStringSet(Prefs.HIDDEN, new HashSet<String>()));
        java.util.Collections.sort(out);
        return out;
    }

    private interface Pick { void at(int index); }

    private void choose(String title, String[] options, Pick pick) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
