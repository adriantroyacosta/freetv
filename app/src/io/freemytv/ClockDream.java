package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Random;

/**
 * The minimal screensaver (home_style=minimal): the time and date on black, moved to another spot every minute so
 * nothing stays burnt into the screen. No photos, no network, nothing else.
 */
final class ClockDream extends View {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Paint clockPaint, datePaint;
    private String time = "", date = "";
    private float x, y;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            time = Ui.clock(now);
            String d = Ui.date(now);
            date = d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
            move();
            invalidate();
            main.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    ClockDream(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
    }

    void start() {
        main.removeCallbacks(tick);
        tick.run();
    }

    void stop() {
        main.removeCallbacks(tick);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float k = w / 1920f;
        clockPaint = Ui.text(Ui.thin, 150 * k, 0xFFE5E5EA);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        datePaint = Ui.text(Ui.regular, 36 * k, 0xFF8E8E93);
        datePaint.setTextAlign(Paint.Align.CENTER);
        move();
    }

    /** A random spot that keeps the clock and the date fully on screen. */
    private void move() {
        if (clockPaint == null || getWidth() == 0) return;
        float halfW = Math.max(clockPaint.measureText("00:00"), datePaint.measureText(date)) / 2 + 40;
        float top = clockPaint.getTextSize(), bottom = datePaint.getTextSize() * 2.2f;
        x = halfW + random.nextFloat() * Math.max(1, getWidth() - 2 * halfW);
        y = top + random.nextFloat() * Math.max(1, getHeight() - top - bottom);
    }

    @Override protected void onDraw(Canvas c) {
        if (clockPaint == null) return;
        c.drawText(time, x, y, clockPaint);
        c.drawText(date, x, y + datePaint.getTextSize() * 1.8f, datePaint);
    }
}
