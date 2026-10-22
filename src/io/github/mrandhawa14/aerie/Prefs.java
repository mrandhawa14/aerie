package io.github.mrandhawa14.aerie;

import android.content.Context;
import android.content.SharedPreferences;

/** One place for every stored setting, so Home and Settings never disagree. */
public final class Prefs {

    public static final String FILE = "aerie";

    // keys
    public static final String ORDER = "order";
    public static final String HIDDEN = "hidden";
    public static final String WALLPAPER = "wallpaper";        // palette id, or "dynamic"
    public static final String COLUMNS = "columns";            // 4 / 5 / 6
    public static final String SCREENSAVER_MIN = "screensaver_min"; // 0 = off
    public static final String SCENE = "scene";                // aerial scene name
    // Accessibility (Aerie's own; Fire TV's are in its settings)
    public static final String REDUCE_MOTION = "reduce_motion";   // fades instead of zoom/slide
    public static final String ALWAYS_LABELS = "always_labels";   // app names under every tile
    public static final String LARGE_TEXT = "large_text";         // bigger names and clock

    // Wallpapers. "scenery" = aerial still (the screensaver's last frame);
    // "dynamic" = tint follows the focused app; the rest are fixed palettes.
    public static final String[] WALL_IDS = {"scenery", "dynamic", "midnight", "aurora", "ember", "forest", "mono"};
    public static final String[] WALL_NAMES = {"Scenery", "Dynamic", "Midnight", "Aurora", "Ember", "Forest", "Mono"};
    private static final int[] WALL_A = {0, 0, 0xFF1C2A4A, 0xFF143A3A, 0xFF4A1E24, 0xFF18341F, 0xFF2A2A2E};
    private static final int[] WALL_B = {0, 0, 0xFF0E1018, 0xFF0C1E2A, 0xFF2A1014, 0xFF0C1A12, 0xFF151518};

    public static final int[] COLUMN_CHOICES = {4, 5, 6};
    public static final int[] SAVER_CHOICES = {0, 1, 3, 5, 10};
    public static final String[] SAVER_NAMES = {"Off", "1 minute", "3 minutes", "5 minutes", "10 minutes"};

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isScenery(String wallId) {
        return "scenery".equals(wallId);
    }

    /** Returns {cornerColor, farColor} for a fixed palette, or null for scenery/dynamic. */
    public static int[] paletteColors(String wallId) {
        for (int i = 0; i < WALL_IDS.length; i++) {
            if (WALL_IDS[i].equals(wallId) && WALL_A[i] != 0) return new int[]{WALL_A[i], WALL_B[i]};
        }
        return null;
    }

    public static int columns(Context c) {
        int v = get(c).getInt(COLUMNS, 5);
        return (v == 4 || v == 5 || v == 6) ? v : 5;
    }

    public static String wallpaper(Context c) {
        return get(c).getString(WALLPAPER, "scenery");
    }

    public static String scene(Context c) {
        return get(c).getString(SCENE, "waterfall");
    }

    public static boolean reduceMotion(Context c) {
        return get(c).getBoolean(REDUCE_MOTION, false);
    }

    public static boolean alwaysLabels(Context c) {
        return get(c).getBoolean(ALWAYS_LABELS, false);
    }

    public static boolean largeText(Context c) {
        return get(c).getBoolean(LARGE_TEXT, false);
    }

    public static int screensaverMin(Context c) {
        return get(c).getInt(SCREENSAVER_MIN, 3);
    }
}
