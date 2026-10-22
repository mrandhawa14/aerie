package io.github.mrandhawa14.aerie;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The aerial scenes on the TV: {@code <name>.mp4} (a seamless loop) plus an
 * optional {@code <name>.jpg} still, in the app's external files/aerials folder.
 */
public final class Scenes {

    private Scenes() {}

    private static File dir(Context c) {
        return new File(c.getExternalFilesDir(null), "aerials");
    }

    /** Scene names (file names without .mp4), sorted. */
    public static List<String> names(Context c) {
        List<String> out = new ArrayList<>();
        File[] files = dir(c).listFiles((d, n) -> n.toLowerCase().endsWith(".mp4"));
        if (files != null) for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 4));
        Collections.sort(out);
        return out;
    }

    /** The chosen scene's video, falling back to the first one available. */
    public static File file(Context c, String name) {
        File f = new File(dir(c), name + ".mp4");
        if (f.exists()) return f;
        List<String> all = names(c);
        return all.isEmpty() ? null : new File(dir(c), all.get(0) + ".mp4");
    }

    /** The scene's still, decoded for the wallpaper, or null if there isn't one. */
    public static Bitmap still(Context c, String name) {
        File mp4 = file(c, name);
        if (mp4 == null) return null;
        File jpg = new File(mp4.getParentFile(), mp4.getName().replace(".mp4", ".jpg"));
        if (!jpg.exists()) return null;
        try {
            return BitmapFactory.decodeFile(jpg.getAbsolutePath());
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** "tropical" -> "Tropical". */
    public static String label(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
