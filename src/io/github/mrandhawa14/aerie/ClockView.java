package io.github.mrandhawa14.aerie;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Time + date, parked top-left. Home and the screensaver both use this, laid
 * out with {@link #layoutParams}, so the clock never moves when one hands
 * over to the other.
 */
public class ClockView extends LinearLayout {

    private final TextView time;
    private final TextView date;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            // Re-sync to the top of the next minute.
            long now = System.currentTimeMillis();
            postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    public ClockView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        float d = c.getResources().getDisplayMetrics().density;

        time = new TextView(c);
        time.setTextColor(0xFFFFFFFF);
        time.setTextSize(56);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setShadowLayer(16 * d, 0, 2 * d, 0x80000000);

        date = new TextView(c);
        date.setTextColor(0xD9FFFFFF);
        date.setTextSize(17);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setShadowLayer(12 * d, 0, d, 0x80000000);
        date.setPadding(Math.round(3 * d), Math.round(6 * d), 0, 0);

        addView(time);
        addView(date);
        update();
    }

    /** The one place the clock's position is defined. */
    public static FrameLayout.LayoutParams layoutParams(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(Math.round(56 * d), Math.round(40 * d), 0, 0);
        return lp;
    }

    private void update() {
        Date now = new Date();
        time.setText(timeFmt.format(now));
        date.setText(dateFmt.format(now));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
