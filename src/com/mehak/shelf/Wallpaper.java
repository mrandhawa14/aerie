package com.mehak.shelf;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The tvOS-style backdrop: two soft light pools tinted by the focused app,
 * fading to black behind the app grid. Cross-fades when focus moves.
 */
public class Wallpaper extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private int primary = 0xFF1C2333;
    private int secondary = 0xFF0E1018;
    private ValueAnimator animator;
    // When set, the backdrop is fixed to this palette and ignores app tint.
    private int[] fixed;

    // Scenery mode: a still from the aerials, cross-faded when it changes.
    private Bitmap scene, previousScene;
    private float sceneFade = 1f;
    private ValueAnimator sceneAnimator;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Matrix cropMatrix = new Matrix();

    public Wallpaper(Context c) {
        super(c);
    }

    /** null = dynamic (tint follows focused app); otherwise {corner, far} held steady. */
    public void setPalette(int[] colors) {
        fixed = colors;
        if (fixed != null) animateTo(fixed[0], fixed[1]);
    }

    /**
     * Shows a still image as the backdrop. With {@code instant} it swaps in the
     * same frame (used when the screensaver hands its frozen frame over), so
     * the change is invisible; otherwise it cross-fades.
     */
    public void setScene(Bitmap b, boolean instant) {
        if (b == scene) return;
        if (sceneAnimator != null) sceneAnimator.cancel();
        previousScene = instant ? null : scene;
        scene = b;
        if (instant || previousScene == null) {
            sceneFade = 1f;
            invalidate();
            return;
        }
        sceneFade = 0f;
        sceneAnimator = ValueAnimator.ofFloat(0f, 1f);
        sceneAnimator.setDuration(1200);
        sceneAnimator.setInterpolator(new DecelerateInterpolator());
        sceneAnimator.addUpdateListener(a -> {
            sceneFade = (float) a.getAnimatedValue();
            invalidate();
        });
        sceneAnimator.start();
    }

    public void clearScene() {
        scene = previousScene = null;
        invalidate();
    }

    public boolean hasScene() {
        return scene != null;
    }

    public void tintTo(int color) {
        if (fixed != null || scene != null) return;  // fixed palette or scenery: no app recolor
        animateTo(mix(color, Color.BLACK, 0.42f), mix(shiftHue(color, 28f), Color.BLACK, 0.70f));
    }

    private void animateTo(int toA, int toB) {
        final int fromA = primary, fromB = secondary;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(650);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            primary = (int) argb.evaluate(t, fromA, toA);
            secondary = (int) argb.evaluate(t, fromB, toB);
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);

        if (scene != null) {
            if (previousScene != null && sceneFade < 1f) drawCropped(canvas, previousScene, 255);
            drawCropped(canvas, scene, Math.round(255 * sceneFade));
            drawSceneScrims(canvas, w, h, paint, 1f);
            return;
        }

        paint.setShader(new RadialGradient(w * 0.22f, h * -0.05f, w * 0.85f,
                primary, primary & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        paint.setShader(new RadialGradient(w * 0.88f, h * 0.30f, w * 0.60f,
                secondary, secondary & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        paint.setShader(new LinearGradient(0, h * 0.38f, 0, h,
                0x00000000, 0xF2000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    /**
     * Shading over a scene: always a soft corner behind the clock, plus (scaled
     * by {@code underApps}) darkening under the tiles. The screensaver draws
     * the same thing, so its last frame and the Scenery wallpaper match.
     */
    static void drawSceneScrims(Canvas canvas, int w, int h, Paint paint, float underApps) {
        paint.setShader(new RadialGradient(0, 0, w * 0.45f,
                0x73000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        if (underApps > 0f) {
            int a = Math.round(0xD9 * underApps);
            paint.setShader(new LinearGradient(0, h * 0.42f, 0, h,
                    0x00000000, a << 24, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
    }

    private void drawCropped(Canvas canvas, Bitmap b, int alpha) {
        int w = getWidth(), h = getHeight();
        float s = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        cropMatrix.setScale(s, s);
        cropMatrix.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
        bitmapPaint.setAlpha(alpha);
        canvas.drawBitmap(b, cropMatrix, bitmapPaint);
    }

    static int mix(int a, int b, float amountOfB) {
        float k = 1f - amountOfB;
        return Color.rgb(
                (int) (Color.red(a) * k + Color.red(b) * amountOfB),
                (int) (Color.green(a) * k + Color.green(b) * amountOfB),
                (int) (Color.blue(a) * k + Color.blue(b) * amountOfB));
    }

    private static int shiftHue(int color, float degrees) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + degrees) % 360f;
        return Color.HSVToColor(hsv);
    }
}
