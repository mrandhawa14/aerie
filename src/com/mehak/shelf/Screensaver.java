package com.mehak.shelf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;

/**
 * tvOS-style aerial screensaver: the scene chosen in Settings, full-screen,
 * center-cropped, at half speed, looping forever.
 *
 * The scene files are pre-cut so their last frame flows into their first (see
 * findloop.py). To restart without the decoder hiccup a plain seek-to-0 would
 * cause, the next pass is prepared on a second, hidden layer and parked on its
 * first frame; the instant the current pass ends, that layer is revealed and
 * resumed. It sits under the home screen's grid and clock, so the clock never
 * moves and the apps can rise back in over the still-playing scene.
 */
public class Screensaver extends FrameLayout {

    private static final float SPEED = 0.5f;
    private static final long PRELOAD_MS = 2500;   // real time before the end

    private final File scene;
    private final Handler handler = new Handler();
    private final Layer[] layers = new Layer[2];
    private final Scrim scrim;
    private int active;
    private boolean preloading, stopped, slowing;
    private final android.widget.ImageView still;   // the frame the stop settles on
    private Bitmap settled;

    private final class Layer implements TextureView.SurfaceTextureListener {
        final TextureView view;
        Surface surface;
        MediaPlayer player;
        boolean parked;          // prepared and waiting on its first frame
        int videoW, videoH;
        Runnable onSurface;

        Layer(Context c) {
            view = new TextureView(c);
            view.setSurfaceTextureListener(this);
            view.setAlpha(0f);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            surface = new Surface(st);
            if (onSurface != null) { Runnable r = onSurface; onSurface = null; r.run(); }
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { crop(); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

        /** Scale like CENTER_CROP so the scene fills the screen with no bars. */
        void crop() {
            int vw = view.getWidth(), vh = view.getHeight();
            if (videoW == 0 || videoH == 0 || vw == 0 || vh == 0) return;
            float s = Math.max((float) vw / videoW, (float) vh / videoH);
            Matrix m = new Matrix();
            m.setScale(s * videoW / vw, s * videoH / vh, vw / 2f, vh / 2f);
            view.setTransform(m);
        }

        void release() {
            parked = false;
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) {}
                player.release();
                player = null;
            }
        }
    }

    public Screensaver(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        scene = Scenes.file(c, Prefs.scene(c));

        for (int i = 0; i < 2; i++) {
            layers[i] = new Layer(c);
            addView(layers[i].view, new LayoutParams(-1, -1));
        }
        still = new android.widget.ImageView(c);
        still.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        still.setAlpha(0f);
        addView(still, new LayoutParams(-1, -1));

        // Same shading as the Scenery wallpaper, so the hand-over is invisible.
        scrim = new Scrim(c);
        addView(scrim, new LayoutParams(-1, -1));
        setKeepScreenOn(true);
    }

    // ------------------------------------------------------------ lifecycle

    void start() {
        setAlpha(0f);
        animate().alpha(1f).setDuration(1400).start();
        if (scene == null) return;
        prepare(layers[active], true);
        handler.post(watch);
    }

    /**
     * Pauses on the current frame and returns it, so the caller can keep it on
     * screen as the wallpaper while the home screen comes back.
     */
    Bitmap freeze() {
        handler.removeCallbacksAndMessages(null);
        for (Layer l : layers) if (l.player != null) {
            try { l.player.pause(); } catch (Exception ignored) {}
        }
        if (settled != null) return settled;   // already showing this exact frame
        Layer l = layers[active];
        try {
            return l.view.isAvailable() ? l.view.getBitmap() : null;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        for (Layer l : layers) l.release();
    }

    // ------------------------------------------------------------- playback

    /** Prepares a pass of the scene; plays it now, or parks it on frame one. */
    private void prepare(final Layer l, final boolean playNow) {
        if (l.surface == null) {
            l.onSurface = () -> prepare(l, playNow);
            return;
        }
        l.release();
        try {
            final MediaPlayer mp = new MediaPlayer();
            l.player = mp;
            mp.setSurface(l.surface);
            mp.setVolume(0f, 0f);
            mp.setDataSource(scene.getAbsolutePath());
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                l.videoW = w;
                l.videoH = h;
                l.crop();
            });
            mp.setOnPreparedListener(p -> {
                if (stopped || l.player != p) return;
                try {
                    // A non-zero speed also starts playback.
                    p.setPlaybackParams(new PlaybackParams().setSpeed(SPEED));
                } catch (Exception ignored) {
                    // Decoder can't change speed: play at normal speed instead.
                }
                p.start();
                if (playNow) {
                    l.view.setAlpha(1f);
                } else {
                    // Let the first frame reach the surface, then hold it there.
                    handler.postDelayed(() -> {
                        if (l.player == p) {
                            try { p.pause(); p.seekTo(0); } catch (Exception ignored) {}
                            l.parked = true;
                        }
                    }, 60);
                }
            });
            mp.setOnCompletionListener(p -> handOff());
            mp.setOnErrorListener((p, what, extra) -> true);
            mp.prepareAsync();
        } catch (Exception ignored) {
        }
    }

    /** Near the end of a pass, get the next one ready on the hidden layer. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            MediaPlayer mp = layers[active].player;
            if (mp != null && !preloading) {
                try {
                    int dur = mp.getDuration();
                    long realLeft = (long) ((dur - mp.getCurrentPosition()) / SPEED);
                    if (dur > 0 && realLeft < PRELOAD_MS) {
                        preloading = true;
                        prepare(layers[1 - active], false);
                    }
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 200);
        }
    };

    /** The pass ended: reveal the parked layer on its first frame and resume. */
    private void handOff() {
        if (stopped || slowing) return;   // winding down: just hold the last frame
        final Layer out = layers[active];
        final Layer in = layers[1 - active];
        if (in.player == null || !in.parked) {
            // Preload didn't finish in time: restart in place instead.
            preloading = false;
            prepare(out, true);
            return;
        }
        in.view.bringToFront();
        scrim.bringToFront();
        in.view.setAlpha(1f);
        in.parked = false;
        try { in.player.start(); } catch (Exception ignored) {}
        active = 1 - active;
        preloading = false;
        // Drop the finished pass a moment later, once the new one is drawing.
        handler.postDelayed(() -> { out.view.setAlpha(0f); out.release(); }, 150);
    }

    /**
     * Winds playback down instead of stopping dead: the speed eases from
     * normal to nothing over {@code ms}, like a scene settling. Then
     * {@code done} runs (the caller freezes the frame and hands it over).
     */
    void slowToStop(final long ms, final Runnable done) {
        final MediaPlayer mp = layers[active].player;
        if (mp == null) { done.run(); return; }
        slowing = true;
        final long t0 = android.os.SystemClock.uptimeMillis();
        final PlaybackParams params = new PlaybackParams();
        // Below ~0.2x the decoder only delivers a few new frames a second and
        // the motion stutters, so the speed bottoms out there; the last stretch
        // of the stop is a dissolve onto a still, which settles smoothly.
        final float floor = 0.2f;
        final long rampMs = (long) (ms * 0.45f);
        handler.post(new Runnable() {
            @Override public void run() {
                if (layers[active].player != mp) { done.run(); return; }
                float p = Math.min(1f, (android.os.SystemClock.uptimeMillis() - t0) / (float) rampMs);
                float k = 1f - p;
                float speed = floor + (SPEED - floor) * k * k;   // ease-out
                try { mp.setPlaybackParams(params.setSpeed(speed)); } catch (Exception ignored) {}
                if (p < 1f) handler.postDelayed(this, 200);
                else dissolveToStill(ms - rampMs, done);
            }
        });
    }

    /** Fades the current frame in as a still over the slow-moving scene. */
    private void dissolveToStill(long ms, final Runnable done) {
        Layer l = layers[active];
        try {
            settled = l.view.isAvailable() ? l.view.getBitmap() : null;
        } catch (Exception | OutOfMemoryError e) {
            settled = null;
        }
        if (settled == null) { done.run(); return; }
        still.setImageBitmap(settled);
        still.setAlpha(0f);
        still.bringToFront();
        scrim.bringToFront();
        still.animate().alpha(1f).setDuration(ms)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(done).start();
    }

    /** The apps are rising back in: shade the bottom like the wallpaper does. */
    void returningHome(long ms) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(scrim.underApps, 1f);
        a.setDuration(ms);
        a.addUpdateListener(v -> {
            scrim.underApps = (float) v.getAnimatedValue();
            scrim.invalidate();
        });
        a.start();
    }

    private static final class Scrim extends View {
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.DITHER_FLAG);
        float underApps;

        Scrim(Context c) { super(c); }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            Wallpaper.drawSceneScrims(canvas, getWidth(), getHeight(), paint, underApps);
        }
    }
}
