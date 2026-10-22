package io.github.mrandhawa14.aerie;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

/**
 * Keeps Amazon's home screen from ever being seen.
 *
 * On Fire OS the Home key is handled by Amazon's key policy before any app
 * sees it, and it always starts Amazon's launcher, a protected package that
 * can't be disabled, stopped or uninstalled without root. Its early signals
 * (HOME_PRESSED, CLOSE_SYSTEM_DIALOGS "homekey") need Amazon's signature.
 *
 * What we can see is Amazon's home activity becoming the foreground window,
 * which arrives ~130 ms after the press and ~120 ms before Amazon draws its
 * first frame. At that moment a pre-built full-screen overlay (accessibility
 * overlays sit above every app) is made visible, showing the last frame of
 * Aerie, and Aerie is brought forward underneath. The overlay hides again once
 * Aerie has drawn, so the hand-off is invisible.
 */
public class HomeOverrideService extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final long COVER_TIMEOUT_MS = 2500;

    private static HomeOverrideService instance;
    private static Bitmap snapshot;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView cover;
    private Bitmap shownSnapshot;
    private long lastLaunch;

    private final Runnable hideCover = () -> {
        if (cover != null) cover.setVisibility(View.GONE);
    };

    /** Called by HomeActivity as it leaves the screen, so the cover matches it. */
    static void setSnapshot(Bitmap b) {
        snapshot = b;
    }

    /** Called by HomeActivity once its first frame is on screen. */
    static void homeIsVisible() {
        HomeOverrideService s = instance;
        if (s == null) return;
        s.main.removeCallbacks(s.hideCover);
        s.main.postDelayed(s.hideCover, 80);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // Built once and kept attached; showing it is then just a visibility flip.
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(cover, lp);
        } catch (Exception e) {
            cover = null;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (cover != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeViewImmediate(cover);
            } catch (Exception ignored) {}
            cover = null;
        }
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Fire OS normally handles Home before this, but take it if offered.
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        // Only the home activity: Amazon's launcher package also hosts screens
        // we still want reachable (e.g. the full app library from Settings).
        if (AMAZON_LAUNCHER.contentEquals(pkg) && cls.toString().contains("HomeActivity")) {
            goHome();
        }
    }

    private void goHome() {
        showCover();
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 400) return;
        lastLaunch = now;
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    private void showCover() {
        if (cover == null) return;
        if (snapshot != shownSnapshot) {
            cover.setImageBitmap(snapshot);
            shownSnapshot = snapshot;
        }
        cover.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideCover);
        main.postDelayed(hideCover, COVER_TIMEOUT_MS);
    }

    @Override
    public void onInterrupt() {}
}
