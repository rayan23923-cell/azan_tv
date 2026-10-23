package com.azantv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Gold rings that slowly grow and fade around a still centre, for the azan and iqama screens. */
public final class PulseRing extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float u;

    public PulseRing(Context c, float u) {
        super(c);
        this.u = u;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * u);
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(5 * u);
        dot.setColor(0xFFE8BE5A);
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy) - 4 * u, min = max * 0.32f;
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            float f = ((t + i * 1000L) % 3000L) / 3000f; // each ring takes 3 s to reach the edge
            ring.setColor(((int) ((1 - f) * 200) << 24) | 0xE8BE5A);
            c.drawCircle(cx, cy, min + (max - min) * f, ring);
        }
        c.drawCircle(cx, cy, min, dot);
        if (getVisibility() == VISIBLE) postInvalidateDelayed(40);
    }
}
