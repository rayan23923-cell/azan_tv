package com.azantv.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Keeps one exact alarm armed for the next azan. */
public final class Scheduler {
    public static final String EXTRA_PRAYER = "prayer";
    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_WAKE = "wake";

    private Scheduler() {}

    public static void scheduleNext(Context c) {
        Config cfg = new Config(c);
        long now = cfg.now();
        long[] next = cfg.clockValid(now) ? cfg.nextAzan(now + 1000) : null;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, AlarmReceiver.class);
        PendingIntent old = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_NO_CREATE | immutable());
        if (old != null) am.cancel(old);
        // Kept for the TV page's /api/status, to check from a phone that an azan is armed.
        c.getSharedPreferences("azan", Context.MODE_PRIVATE).edit()
                .putLong("alarmAt", next == null ? 0 : next[1]).putInt("alarmPrayer", next == null ? -1 : (int) next[0])
                .putLong("alarmSetAt", now).apply();
        if (next == null) return;
        i.putExtra(EXTRA_PRAYER, (int) next[0]);
        i.putExtra(EXTRA_TIME, next[1]);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[1] - cfg.correction(), pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, next[1] - cfg.correction(), pi);
        }
    }

    /** Wakes the TV from sleep mode at {@code atCorrected} (app clock); cancels when 0. */
    public static void scheduleWake(Context c, long at) {
        Config cfg = new Config(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, AlarmReceiver.class).putExtra(EXTRA_WAKE, true);
        PendingIntent pi = PendingIntent.getBroadcast(c, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        am.cancel(pi);
        if (at <= 0) return;
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - cfg.correction(), pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, at - cfg.correction(), pi);
        }
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
