package com.azantv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Iqama countdown: a ring that empties over the interval, with 60 second-ticks that fill like a clock. */
public class CountdownRing extends View {
    private final Config cfg;
    private final float u;
    private long start, end;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    public CountdownRing(Context c, Config cfg, float u) {
        super(c);
        this.cfg = cfg;
        this.u = u;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(14 * u);
        track.setColor(0x26FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(14 * u);
        arc.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeWidth(Math.max(2, 3 * u));
        tick.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setTimes(long start, long end) {
        this.start = start;
        this.end = end;
        invalidate();
    }

    /** True in the last minute, when the ring turns amber. */
    public static boolean lastMinute(long left) { return left <= 60000; }

    @Override
    protected void onDraw(Canvas c) {
        long now = cfg.now();
        long left = Math.max(0, end - now);
        float frac = end > start ? Math.min(1f, left / (float) (end - start)) : 0f;
        int color = lastMinute(left) ? 0xFFE8913A : MainActivity.GOLD;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 12 * u;
        box.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(box, 0, 360, false, track);
        arc.setColor(color);
        c.drawArc(box, -90, -360 * frac, false, arc); // empties anticlockwise from the top

        // Second ticks inside the ring: the ones still to come this minute stay bright.
        float sec = (left % 60000) / 1000f;
        float r1 = r - 30 * u, r2 = r - 44 * u;
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(-90 - i * 6);
            boolean on = i < sec;
            tick.setColor(on ? (i % 5 == 0 ? 0xFFFFFFFF : 0xCCFFFFFF) : 0x22FFFFFF);
            float in = i % 5 == 0 ? r2 - 6 * u : r2;
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in, tick);
        }
        // A bright dot at the head of the ring.
        double a = Math.toRadians(-90 - 360 * frac);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, 11 * u, dot);
        dot.setColor(color);
        c.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, 7 * u, dot);

        if (getVisibility() == VISIBLE && isShown()) postInvalidateDelayed(40); // ~25 fps is smooth and light
    }
}
