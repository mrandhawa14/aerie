package com.mehak.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HomeActivity extends Activity {

    private static final String SETTINGS_ID = "#settings";

    private int columns = 5;

    // First-run order; anything else installed later goes after these.
    private static final List<String> PREFERRED = Arrays.asList(
            "com.netflix.ninja",
            "com.amazon.firetv.youtube",
            "com.kodjodevf.mangayomi",
            "com.apple.airplay.amazon.airplay",
            "com.esaba.downloader");

    // Never shown: this app, and launchers that would compete with it.
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "com.mehak.shelf",
            "com.spocky.projengmenu",
            "com.amazon.tv.launcher"));

    private SharedPreferences prefs;
    private FrameLayout root;
    private Wallpaper wallpaper;
    private ScrollView scroller;
    private GridLayout grid;
    private FrameLayout dock;
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    private boolean dirty = true;

    private static class Tile {
        String id;
        String label;
        Drawable art;
        boolean isBanner;
        int tint;
        ComponentName component;
    }

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            if (hasWindowFocus()) rebuild(null);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("shelf", MODE_PRIVATE);

        columns = Prefs.columns(this);

        root = new FrameLayout(this);
        wallpaper = new Wallpaper(this);
        applyWallpaper();
        root.addView(wallpaper, match());

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setSmoothScrollingEnabled(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        // The empty top shelf: on tvOS this is the wallpaper above the first row.
        column.setPadding(0, dp(268), 0, dp(56));

        grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        // Plain container for the rows (spacing only, no backdrop of its own).
        dock = new FrameLayout(this);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(dp(28), dp(24), dp(28), dp(16));
        dock.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dockLp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(dock, dockLp);

        scroller.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, match());

        // Same spot as the screensaver's clock, so it stays put when that starts.
        clock = new ClockView(this);
        root.addView(clock, ClockView.layoutParams(this));
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> fitClock());

        root.setClipChildren(false);
        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isPaused = false;
        // Never return from an app to a faded/shrunk grid.
        scroller.setAlpha(1f);
        scroller.setScaleX(1f);
        scroller.setScaleY(1f);
        // Settings may have changed while we were away.
        int newColumns = Prefs.columns(this);
        if (newColumns != columns) {
            columns = newColumns;
            grid.setColumnCount(columns);
            dirty = true;
        }
        applyWallpaper();
        if (dirty) rebuild(null);
        armScreensaver();
        // Lift the service's cover once our first frame is actually on screen.
        View decor = getWindow().getDecorView();
        decor.postOnAnimation(() -> decor.postOnAnimation(HomeOverrideService::homeIsVisible));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            HomeOverrideService.homeIsVisible();
            // Have a cover ready even for the first Home press after boot.
            scroller.postDelayed(snapshotTask, 900);
        }
    }

    @Override
    protected void onPause() {
        isPaused = true;
        scroller.removeCallbacks(snapshotTask);
        snapshot();
        stopScreensaver();
        finishSaver(true);
        handler.removeCallbacks(showScreensaver);
        super.onPause();
    }

    private final Runnable snapshotTask = this::snapshot;

    // ---------------------------------------------------- wallpaper + clock

    private ClockView clock;
    private String sceneShown;   // scene whose still is on the wallpaper

    private void applyWallpaper() {
        String id = Prefs.wallpaper(this);
        if (Prefs.isScenery(id)) {
            String name = Prefs.scene(this);
            // Keep the current frame (e.g. the screensaver's) unless the scene changed.
            if (name.equals(sceneShown) && wallpaper.hasScene()) return;
            Bitmap b = Scenes.still(this, name);
            if (b != null) {
                wallpaper.setPalette(null);
                wallpaper.setScene(b, false);
                sceneShown = name;
                return;
            }
            // No still on the TV: fall back to the dynamic tint.
            id = "dynamic";
        }
        sceneShown = null;
        wallpaper.clearScene();
        wallpaper.setPalette(Prefs.paletteColors(id));
    }

    /** Fades the clock out only if the app rows scroll up into its space. */
    private void fitClock() {
        if (clock == null || dock == null) return;
        int panelTopOnScreen = dock.getTop() - scroller.getScrollY();
        float room = panelTopOnScreen - clock.getBottom() - dp(12);
        clock.setAlpha(Math.max(0f, Math.min(1f, room / dp(40))));
    }

    /**
     * Scrolls so the focused tile and its name are fully on screen in one
     * press; the first row always rests at the top, like tvOS.
     */
    private void revealRow(View cell, boolean firstRow) {
        int target;
        if (firstRow) {
            target = 0;
        } else {
            int origin = dock.getTop() + grid.getTop();
            int bottom = origin + cell.getBottom() + dock.getPaddingBottom() + dp(26);  // label + lift
            int top = origin + cell.getTop() - dp(20);
            int y = scroller.getScrollY(), h = scroller.getHeight();
            target = y;
            if (bottom > y + h) target = bottom - h;
            if (top < target) target = Math.max(0, top);
        }
        if (target != scroller.getScrollY()) scroller.smoothScrollTo(0, target);
    }

    // ------------------------------------------------------- screensaver

    private final android.os.Handler handler = new android.os.Handler();
    private Screensaver saver;
    private boolean isPaused;

    private final Runnable showScreensaver = () -> {
        if (saver != null || isPaused) return;
        finishSaver(true);                   // a previous one still settling
        saver = new Screensaver(this);
        // Above the wallpaper, below the grid and clock: the clock never moves,
        // and the apps can come back over the scene while it's still playing.
        root.addView(saver, 1, match());
        saver.start();                       // aerial fades itself in
        // Apps gently recede as the aerial takes over.
        scroller.animate().alpha(0f).scaleX(0.93f).scaleY(0.93f)
                .setDuration(900).setInterpolator(new DecelerateInterpolator()).start();
    };

    private void armScreensaver() {
        handler.removeCallbacks(showScreensaver);
        int min = Prefs.screensaverMin(this);
        if (min > 0 && saver == null) handler.postDelayed(showScreensaver, min * 60_000L);
    }

    private Screensaver leaving;         // dismissed, still playing under the apps
    private static final long APPS_RETURN_MS = 650;
    private static final long SETTLE_MS = 4200;

    /**
     * Dismissal: the apps rise back in over the still-moving scene, whose
     * motion then eases to a stop over a few seconds and settles into a still
     * (the Scenery wallpaper, if chosen). Returns false if nothing was showing.
     */
    private boolean stopScreensaver() {
        handler.removeCallbacks(showScreensaver);
        if (saver == null) return false;
        leaving = saver;
        saver = null;
        leaving.returningHome(APPS_RETURN_MS);
        scroller.animate().cancel();
        scroller.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(APPS_RETURN_MS).setInterpolator(new DecelerateInterpolator()).start();
        // The scene keeps moving under the returning apps and winds down
        // slowly to a standstill before it becomes the still wallpaper.
        final Screensaver s = leaving;
        s.slowToStop(SETTLE_MS, () -> { if (leaving == s) finishSaver(false); });
        return true;
    }

    /** Freezes the leaving scene, hands its frame to the wallpaper, then removes it. */
    private void finishSaver(boolean immediately) {
        final Screensaver s = leaving;
        if (s == null) return;
        leaving = null;
        Bitmap still = s.freeze();
        if (still != null && Prefs.isScenery(Prefs.wallpaper(this))) {
            wallpaper.setScene(still, true);
            sceneShown = Prefs.scene(this);
        }
        if (immediately) {
            s.stop();
            root.removeView(s);
            return;
        }
        s.animate().alpha(0f).setDuration(700)
                .withEndAction(() -> { s.stop(); root.removeView(s); }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any key wakes the screensaver and is then swallowed, so the press
        // that dismisses it doesn't also move focus or open an app.
        if (event.getAction() == KeyEvent.ACTION_DOWN && stopScreensaver()) {
            armScreensaver();
            return true;
        }
        armScreensaver();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        stopScreensaver();
        armScreensaver();
    }

    /** The cover the service shows on Home is this frame, so the hand-off is seamless. */
    private void snapshot() {
        View decor = getWindow().getDecorView();
        if (decor.getWidth() == 0) return;
        try {
            Bitmap b = Bitmap.createBitmap(decor.getWidth() / 2, decor.getHeight() / 2,
                    Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.scale(0.5f, 0.5f);
            decor.draw(c);
            HomeOverrideService.setSnapshot(b);
        } catch (OutOfMemoryError ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already home: back to the first app, like tvOS.
        if (hasWindowFocus()) {
            scroller.smoothScrollTo(0, 0);
            if (grid.getChildCount() > 0) grid.getChildAt(0).findViewWithTag("tile").requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home is the bottom of the stack; Back does nothing here.
    }

    // ---------------------------------------------------------------- data

    private void loadTiles() {
        tiles.clear();
        PackageManager pm = getPackageManager();
        Map<String, Tile> found = new LinkedHashMap<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (EXCLUDED.contains(ai.packageName) || found.containsKey(ai.packageName)) continue;
            Tile t = new Tile();
            t.id = ai.packageName;
            t.label = String.valueOf(ri.loadLabel(pm));
            t.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            if (banner != null) {
                t.art = banner;
                t.isBanner = true;
            } else {
                t.art = ri.loadIcon(pm);
            }
            t.tint = dominantColor(t.art);
            found.put(t.id, t);
        }

        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        List<String> order = new ArrayList<>();
        String saved = prefs.getString("order", null);
        if (saved != null && !saved.isEmpty()) order.addAll(Arrays.asList(saved.split(",")));
        for (String p : PREFERRED) if (!order.contains(p)) order.add(p);
        for (String p : found.keySet()) if (!order.contains(p)) order.add(p);
        if (!order.contains(SETTINGS_ID)) order.add(SETTINGS_ID);

        for (String id : order) {
            if (hidden.contains(id)) continue;
            if (SETTINGS_ID.equals(id)) {
                Tile s = new Tile();
                s.id = SETTINGS_ID;
                s.label = "Settings";
                s.art = getDrawable(R.drawable.ic_settings);
                s.tint = 0xFF5A5F6B;
                tiles.put(s.id, s);
            } else if (found.containsKey(id)) {
                tiles.put(id, found.get(id));
            }
        }
    }

    private void saveOrder() {
        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        List<String> order = new ArrayList<>(tiles.keySet());
        // Keep hidden apps' slots so un-hiding returns them where they were.
        String old = prefs.getString("order", "");
        for (String p : old.split(",")) if (hidden.contains(p) && !order.contains(p)) order.add(p);
        prefs.edit().putString("order", join(order)).apply();
    }

    // ------------------------------------------------------------------ UI

    private void rebuild(String focusId) {
        dirty = false;
        loadTiles();
        grid.removeAllViews();
        View toFocus = null;
        int i = 0;
        for (Tile t : tiles.values()) {
            View cell = makeCell(t, i % columns == 0, i < columns);
            grid.addView(cell);
            if (toFocus == null || t.id.equals(focusId)) toFocus = cell.findViewWithTag("tile");
            i++;
        }
        if (toFocus != null) toFocus.requestFocus();
    }

    private View makeCell(final Tile t, boolean firstInRow, boolean firstRow) {
        final int w = tileWidth(), h = Math.round(w * 0.6f), radius = dp(12);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.setMargins(firstInRow ? 0 : dp(24), firstRow ? 0 : dp(30), 0, 0);
        cell.setLayoutParams(lp);

        final FrameLayout tile = new FrameLayout(this);
        tile.setTag("tile");
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });

        ImageView art = new ImageView(this);
        art.setImageDrawable(t.art);
        if (t.isBanner) {
            tile.setBackgroundColor(Color.BLACK);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(art, match());
        } else {
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Wallpaper.mix(t.tint, Color.WHITE, 0.10f), Wallpaper.mix(t.tint, Color.BLACK, 0.25f)});
            tile.setBackground(bg);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = SETTINGS_ID.equals(t.id) ? dp(26) : dp(16);
            art.setPadding(pad, pad, pad, pad);
            tile.addView(art, match());
        }

        // Specular sheen that appears on focus, like the light on a tvOS icon.
        final View sheen = new View(this);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF}));
        sheen.setAlpha(0f);
        tile.addView(sheen, match());

        // Soft halo in the app's colour, shown only behind the focused tile.
        final View glow = new View(this);
        GradientDrawable halo = new GradientDrawable();
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        halo.setGradientRadius(dp(70));
        // Stays strong past the tile's edge so a band of colour shows around it.
        halo.setColors(new int[]{withAlpha(t.tint, 0x99), withAlpha(t.tint, 0x99),
                withAlpha(t.tint, 0x5C), withAlpha(t.tint, 0x1F), withAlpha(t.tint, 0)});
        glow.setBackground(halo);
        glow.setAlpha(0f);
        final float glowX = (w + dp(92)) / (float) dp(140);
        final float glowY = (h + dp(76)) / (float) dp(140);
        glow.setScaleX(glowX * 0.85f);
        glow.setScaleY(glowY * 0.85f);

        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        holder.addView(glow, new FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER));
        holder.addView(tile, new FrameLayout.LayoutParams(w, h));
        cell.addView(holder, new LinearLayout.LayoutParams(w, h));

        final TextView label = new TextView(this);
        label.setText(t.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setAlpha(0f);
        label.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(14);
        cell.addView(label, llp);

        tile.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.12f : 1f;
            tile.animate().scaleX(s).scaleY(s).translationZ(has ? dp(18) : 0)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(200).start();
            sheen.animate().alpha(has ? 1f : 0f).setDuration(200).start();
            float g = has ? 1f : 0.85f;
            glow.animate().alpha(has ? 1f : 0f).scaleX(glowX * g).scaleY(glowY * g)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(has ? 260 : 200).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (has) {
                wallpaper.tintTo(t.tint);
                // After ScrollView's own minimal scroll, show the whole cell.
                cell.post(() -> revealRow(cell, firstRow));
            }
        });
        tile.setOnClickListener(v -> tile.animate().scaleX(1.04f).scaleY(1.04f).setDuration(90)
                .withEndAction(() -> {
                    tile.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start();
                    open(t);
                }).start());
        tile.setOnLongClickListener(v -> {
            showTileMenu(t);
            return true;
        });
        return cell;
    }

    private void open(Tile t) {
        if (SETTINGS_ID.equals(t.id)) {
            // Internal screen: stack it in our own task, no NEW_TASK (which the
            // home activity's singleTask/clearTaskOnLaunch would wipe away).
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(t.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            dirty = true;
            rebuild(null);
        }
    }

    private void showTileMenu(final Tile t) {
        final List<String> ids = new ArrayList<>(tiles.keySet());
        final int pos = ids.indexOf(t.id);
        final Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));

        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (pos > 0) {
            labels.add("Move Left");
            actions.add(() -> move(ids, pos, pos - 1, t.id));
        }
        if (pos < ids.size() - 1) {
            labels.add("Move Right");
            actions.add(() -> move(ids, pos, pos + 1, t.id));
        }
        if (pos >= columns) {
            labels.add("Move to Top Row");
            actions.add(() -> move(ids, pos, 0, t.id));
        }
        if (!SETTINGS_ID.equals(t.id)) {
            labels.add("Hide “" + t.label + "”");
            actions.add(() -> {
                hidden.add(t.id);
                prefs.edit().putStringSet("hidden", hidden).apply();
                rebuild(null);
            });
        }
        if (!hidden.isEmpty()) {
            labels.add("Show Hidden Apps (" + hidden.size() + ")");
            actions.add(() -> {
                prefs.edit().remove("hidden").apply();
                rebuild(t.id);
            });
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void move(List<String> ids, int from, int to, String focusId) {
        String id = ids.remove(from);
        ids.add(to, id);
        Map<String, Tile> reordered = new LinkedHashMap<>();
        for (String k : ids) reordered.put(k, tiles.get(k));
        tiles.clear();
        tiles.putAll(reordered);
        saveOrder();
        rebuild(focusId);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The remote's Menu (≡) key opens the tile menu too.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && "tile".equals(f.getTag())) {
                f.performLongClick();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------- helpers

    private static int dominantColor(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(c);
            float r = 0, g = 0, bl = 0, weight = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    // Favour vivid pixels so logos beat white/black backgrounds.
                    float w = 0.05f + hsv[1] * hsv[2];
                    r += Color.red(p) * w;
                    g += Color.green(p) * w;
                    bl += Color.blue(p) * w;
                    weight += w;
                }
            }
            b.recycle();
            if (weight == 0) return 0xFF2A2F3A;
            return Color.rgb((int) (r / weight), (int) (g / weight), (int) (bl / weight));
        } catch (Exception e) {
            return 0xFF2A2F3A;
        }
    }

    /**
     * Tiles shrink to fit the chosen row count on screen (never wider than
     * 150dp), so every column stays fully visible, focus lift and glow included.
     */
    private int tileWidth() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int avail = screen - 2 * dp(56) - (columns - 1) * dp(24);
        return Math.min(dp(150), avail / columns);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }
}
