package com.azantv.app;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/** iOS-style frosted glass panels: a tinted fill, a white sheen fading down, and a thin bright rim. */
public final class Glass {
    private Glass() {}

    /** Dark glass, or gold glass for the highlighted item. */
    public static Drawable panel(float u, boolean gold, float radius) {
        GradientDrawable base = gold
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xFFD6B262, 0xFFB58C38, 0xFF8A6626})
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x66000000, 0x59000000, 0x66000000});
        return layers(u, base, radius,
                gold ? new int[]{0x66FFFFFF, 0x18FFFFFF, 0x00FFFFFF, 0x00FFFFFF}
                     : new int[]{0x2EFFFFFF, 0x0AFFFFFF, 0x00FFFFFF, 0x05FFFFFF},
                gold ? 0x99FFE9B0 : 0x40FFFFFF);
    }

    /** Light glass for the cream adhkar page. */
    public static Drawable light(float u, float radius) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFF6E0, 0x14FFF6E0, 0x24FFF6E0});
        return layers(u, base, radius, new int[]{0x4DFFFFFF, 0x12FFFFFF, 0x00FFFFFF, 0x0AFFFFFF}, 0xB3FFFFFF);
    }

    private static Drawable layers(float u, GradientDrawable base, float radius, int[] sheenColors, int rimColor) {
        base.setCornerRadius(radius);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, sheenColors);
        sheen.setCornerRadius(radius);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(radius);
        rim.setColor(0);
        rim.setStroke(Math.max(1, (int) (1.5f * u)), rimColor);
        return new LayerDrawable(new Drawable[]{base, sheen, rim});
    }

    /** The patterned wallpaper, dimmed, for full-screen pages behind glass panels. */
    @SuppressWarnings("deprecation")
    public static Drawable screen(Context c, int dim) {
        return new LayerDrawable(new Drawable[]{c.getResources().getDrawable(R.drawable.bg), new ColorDrawable(dim)});
    }
}
