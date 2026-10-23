package com.azantv.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.TimeZone;

/** User settings and today's prayer times. */
public final class Config {
    public static final String[] CITY_NAMES = {
        "بغداد", "البصرة", "الموصل", "أربيل", "النجف", "كربلاء", "السليمانية", "كركوك",
        "الناصرية", "الحلة", "الديوانية", "العمارة", "الكوت", "السماوة", "الرمادي", "الفلوجة",
        "بعقوبة", "تكريت", "سامراء", "دهوك",
    };
    private static final double[][] CITY_COORDS = {
        {33.3152, 44.3661}, {30.5085, 47.7804}, {36.3350, 43.1189}, {36.1911, 44.0092},
        {32.0259, 44.3462}, {32.6160, 44.0249}, {35.5613, 45.4309}, {35.4681, 44.3922},
        {31.0439, 46.2575}, {32.4637, 44.4196}, {31.9929, 44.9248}, {31.8356, 47.1440},
        {32.5128, 45.8182}, {31.3099, 45.2806}, {33.4258, 43.2997}, {33.3500, 43.7833},
        {33.7466, 44.6437}, {34.6071, 43.6782}, {34.1959, 43.8857}, {36.8669, 42.9503},
    };
    public static final String[] PRAYER_NAMES = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    public static final TimeZone TZ = TimeZone.getTimeZone("Asia/Baghdad");

    private final SharedPreferences sp;
    private final Context ctx;

    public Config(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("azan", Context.MODE_PRIVATE);
    }

    private int bootCount() {
        return android.provider.Settings.Global.getInt(ctx.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
    }

    /**
     * The current time: the TV clock when it is right, otherwise the TV clock corrected by the
     * phone time sent this boot (the TV has no clock battery and no internet to fix it).
     */
    public long now() {
        long raw = System.currentTimeMillis();
        if (clockValid(raw)) return raw;
        // The phone time is kept against the uptime counter, so changes to the TV clock can't skew it.
        if (sp.getInt("fixBoot", -2) != bootCount() || !sp.contains("fixPhone")) return raw;
        long since = android.os.SystemClock.elapsedRealtime() - sp.getLong("fixElapsed", 0);
        if (since < 0) return raw;
        return sp.getLong("fixPhone", 0) + since;
    }

    /** Difference to add to the TV clock to get {@link #now()}. */
    public long correction() {
        return now() - System.currentTimeMillis();
    }

    public void setPhoneTime(long phoneMillis) {
        SharedPreferences.Editor e = sp.edit().putLong("fixPhone", phoneMillis)
                .putLong("fixElapsed", android.os.SystemClock.elapsedRealtime())
                .putInt("fixBoot", bootCount()).remove("fixOffset");
        // The phone is trusted: a lastGoodTime from a wrong future clock must not lock us out.
        if (sp.getLong("lastGoodTime", 0) > phoneMillis) e.putLong("lastGoodTime", phoneMillis);
        e.commit();
    }

    SharedPreferences sp() { return sp; }
    public boolean showQr() { return sp.getBoolean("showQr", true); }
    public void setShowQr(boolean v) { sp.edit().putBoolean("showQr", v).apply(); }

    public boolean isConfigured() { return sp.getBoolean("configured", false); }
    public void setConfigured() { sp.edit().putBoolean("configured", true).apply(); }

    public int city() { return sp.getInt("city", 0); }
    /** Picking a city drops the exact location sent from a phone. */
    public void setCity(int v) { sp.edit().putInt("city", v).remove("lat").remove("lng").remove("locName").apply(); }

    /** Exact location sent from a phone, or null when the city's coordinates are used. */
    public double[] customLocation() {
        if (!sp.contains("lat") || !sp.contains("lng")) return null;
        try {
            return new double[]{Double.parseDouble(sp.getString("lat", "")), Double.parseDouble(sp.getString("lng", ""))};
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean validLocation(double lat, double lng) {
        return lat >= -66 && lat <= 66 && lng >= -180 && lng <= 180 && !(lat == 0 && lng == 0);
    }

    public void setCustomLocation(double lat, double lng, String name) {
        sp.edit().putString("lat", String.valueOf(lat)).putString("lng", String.valueOf(lng))
                .putString("locName", name == null ? "" : name.trim()).putBoolean("configured", true).commit();
    }

    public void clearCustomLocation() { sp.edit().remove("lat").remove("lng").remove("locName").commit(); }

    /** The place name shown on screen: the city, or the name given with the exact location. */
    public String placeName() {
        if (customLocation() == null) return CITY_NAMES[city()];
        String n = sp.getString("locName", "");
        return n.isEmpty() ? "الموقع الدقيق" : n;
    }
    public int method() { return sp.getInt("method", 0); }
    public void setMethod(int v) { sp.edit().putInt("method", v).apply(); }
    public int asrFactor() { return sp.getInt("asr", 1); }
    public void setAsrFactor(int v) { sp.edit().putInt("asr", v).apply(); }
    public int offset(int prayer) { return sp.getInt("off" + prayer, 0); }
    public void setOffset(int prayer, int v) { sp.edit().putInt("off" + prayer, v).apply(); }
    public int hijriAdjust() { return sp.getInt("hijri", 0); }
    public void setHijriAdjust(int v) { sp.edit().putInt("hijri", v).apply(); }
    public boolean azanEnabled(int prayer) { return prayer != PrayTimes.SUNRISE && azanOn() && sp.getBoolean("azan" + prayer, true); }
    public boolean azanEnabledSetting(int prayer) { return prayer != PrayTimes.SUNRISE && sp.getBoolean("azan" + prayer, true); }
    /** Master switch for every azan. */
    public boolean azanOn() { return sp.getBoolean("azanAll", true); }
    public void setAzanOn(boolean v) { sp.edit().putBoolean("azanAll", v).apply(); }
    /** 0 = never sleep, 1 = black screen, 2 = screen off. */
    public int sleepMode() { return sp.getInt("sleep", 0); }
    public void setSleepMode(int v) { sp.edit().putInt("sleep", v).apply(); }
    /** Minutes after the adhkar end before sleeping. */
    public int sleepAfter() { return sp.getInt("sleepAfter", 15); }
    public void setSleepAfter(int v) { sp.edit().putInt("sleepAfter", v).apply(); }
    /** Minutes before the next azan (or prayer time) to wake up. */
    public int wakeBefore() { return sp.getInt("wakeBefore", 10); }
    public void setWakeBefore(int v) { sp.edit().putInt("wakeBefore", v).apply(); }
    public void setAzanEnabled(int prayer, boolean v) { sp.edit().putBoolean("azan" + prayer, v).apply(); }
    public boolean openOnBoot() { return sp.getBoolean("boot", true); }
    public void setOpenOnBoot(boolean v) { sp.edit().putBoolean("boot", v).apply(); }
    private static final int[] IQAMA_DEFAULT = {20, 0, 15, 15, 5, 10};
    /** Minutes from azan to iqama; 0 turns the countdown off for that prayer. */
    public int iqama(int prayer) { return prayer == PrayTimes.SUNRISE ? 0 : sp.getInt("iq" + prayer, IQAMA_DEFAULT[prayer]); }
    public void setIqama(int prayer, int v) { sp.edit().putInt("iq" + prayer, v).apply(); }
    /** How long the prayer itself lasts, before the adhkar are shown. */
    public int prayerMinutes() { return sp.getInt("pray", 10); }
    public void setPrayerMinutes(int v) { sp.edit().putInt("pray", v).apply(); }
    /** Minutes the post-prayer adhkar stay on screen; 0 turns them off. */
    public int adhkarMinutes() { return sp.getInt("adhkar", 10); }
    public void setAdhkarMinutes(int v) { sp.edit().putInt("adhkar", v).apply(); }
    public int volume() { return sp.getInt("volume", 100); }
    public void setVolume(int v) { sp.edit().putInt("volume", v).apply(); }

    /**
     * False when the TV clock was reset (power cut with no internet): it is before 2026 or
     * earlier than a time we already saw. Azans are held back until the clock is set again.
     */
    public boolean clockValid(long now) {
        Calendar cal = Calendar.getInstance(TZ);
        cal.setTimeInMillis(now);
        if (cal.get(Calendar.YEAR) < 2026 || cal.get(Calendar.YEAR) > 2040) return false;
        return now >= sp.getLong("lastGoodTime", 0) - 86400000L;
    }

    /** Remembers a trusted time; called about once a minute while the clock is valid. */
    public void rememberGoodTime(long ignored) {
        long now = System.currentTimeMillis(); // only ever the TV's own clock, never a corrected one
        if (!clockValid(now)) return;
        if (now - sp.getLong("lastGoodTime", 0) > 60000L) sp.edit().putLong("lastGoodTime", now).apply();
    }

    /** Marks a prayer's azan as played; returns false if it was already played. */
    public boolean claimAzan(long prayerMillis) {
        if (sp.getLong("lastAzan", 0) == prayerMillis) return false;
        sp.edit().putLong("lastAzan", prayerMillis).commit();
        return true;
    }

    /** Prayer times (epoch millis) for the day containing {@code dayMillis}. */
    public long[] timesFor(long dayMillis) {
        Calendar cal = Calendar.getInstance(TZ);
        cal.setTimeInMillis(dayMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        double tz = TZ.getOffset(midnight + 12 * 3600000L) / 3600000.0;
        double[] c = customLocation();
        if (c == null) c = CITY_COORDS[city()];
        double[] h = PrayTimes.compute(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH), c[0], c[1], tz, PrayTimes.METHODS[method()], asrFactor());
        long[] out = new long[h.length];
        for (int i = 0; i < h.length; i++) {
            long minutes = Math.round(h[i] * 60.0) + offset(i);
            out[i] = midnight + minutes * 60000L;
        }
        return out;
    }

    /** Extra azan ids beside the six prayer indexes. */
    public static final int FAJR_FIRST = 6, JUMUA_FIRST = 7;

    /** Minutes before Fajr for its first azan; 0 = none. */
    public int fajrFirst() { return sp.getInt("fajr1", 30); }
    public void setFajrFirst(int v) { sp.edit().putInt("fajr1", v).apply(); }
    /** Minutes before the second Friday azan (at Dhuhr time) for the first one; 0 = none. */
    public int jumuaFirst() { return sp.getInt("jumua1", 30); }
    public void setJumuaFirst(int v) { sp.edit().putInt("jumua1", v).apply(); }
    /** How long the Friday sermon screen stays up after the second azan. */
    public int khutbahMinutes() { return sp.getInt("khutbah", 25); }
    public void setKhutbahMinutes(int v) { sp.edit().putInt("khutbah", v).apply(); }

    /** Preview only: treat today as Friday until this time (System.currentTimeMillis). */
    static long forceFridayUntil;

    public static boolean isFriday(long millis) {
        if (System.currentTimeMillis() < forceFridayUntil) return true;
        Calendar cal = Calendar.getInstance(TZ);
        cal.setTimeInMillis(millis);
        return cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
    }

    /** Name of a prayer on that day: Dhuhr is Jumu'a on Fridays. */
    public static String prayerName(int prayer, long day) {
        return prayer == PrayTimes.DHUHR && isFriday(day) ? "الجمعة" : PRAYER_NAMES[prayer];
    }

    /** {azanId, millis} of every azan on that day, in time order. */
    public java.util.List<long[]> azansFor(long day, long[] t) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        if (azanEnabled(PrayTimes.FAJR) && fajrFirst() > 0) out.add(new long[]{FAJR_FIRST, t[0] - fajrFirst() * 60000L});
        for (int i = 0; i < t.length; i++) {
            if (i == PrayTimes.DHUHR && isFriday(day) && azanEnabled(i) && jumuaFirst() > 0) {
                out.add(new long[]{JUMUA_FIRST, t[i] - jumuaFirst() * 60000L});
            }
            if (azanEnabled(i)) out.add(new long[]{i, t[i]});
        }
        return out;
    }

    /** {azanId, millis} of the next azan (first azans included, sunrise and disabled prayers skipped). */
    public long[] nextAzan(long now) {
        for (int d = 0; d < 3; d++) {
            long day = now + d * 86400000L;
            for (long[] a : azansFor(day, timesFor(day))) if (a[1] >= now) return a;
        }
        return null;
    }

    /** {prayerIndex, millis} of the next prayer time including sunrise, after now. */
    public long[] nextPrayer(long now) {
        for (int d = 0; d < 2; d++) {
            long[] t = timesFor(now + d * 86400000L);
            for (int i = 0; i < t.length; i++) if (t[i] > now) return new long[]{i, t[i]};
        }
        return null;
    }
}
