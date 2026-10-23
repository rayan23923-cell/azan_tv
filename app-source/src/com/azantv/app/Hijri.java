package com.azantv.app;

import android.os.Build;

import java.util.Date;

public final class Hijri {
    private Hijri() {}

    public static String format(long now, int adjustDays) {
        if (Build.VERSION.SDK_INT < 24) return "";
        try {
            android.icu.text.SimpleDateFormat f = new android.icu.text.SimpleDateFormat("d MMMM y",
                    new android.icu.util.ULocale("ar@calendar=islamic-umalqura"));
            f.setTimeZone(android.icu.util.TimeZone.getTimeZone("Asia/Baghdad"));
            return f.format(new Date(now + adjustDays * 86400000L)) + " هـ";
        } catch (Exception e) {
            return "";
        }
    }
}
