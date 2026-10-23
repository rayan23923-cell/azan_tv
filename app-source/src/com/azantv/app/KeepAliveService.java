package com.azantv.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/**
 * Stays running so the app can come back to the front when the TV wakes from standby
 * (remote power button), which sends no boot broadcast.
 */
public class KeepAliveService extends Service {
    private final Handler handler = new Handler();

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            try {
                if (!new Config(c).openOnBoot()) return;
                Scheduler.scheduleNext(c);
            } catch (Exception ignored) {
            }
            watch(20000, 0);
        }
    };

    static final String EXTRA_BOOT = "boot";

    /** Choices for how long after power-on the app keeps coming back to the front: 0 = by the TV's memory. */
    static final int[] SETTLE_SECONDS = {0, 5, 10, 20, 45, 90};
    static final String[] SETTLE_NAMES = {"تلقائي حسب التلفزيون", "5 ثواني", "10 ثواني", "20 ثانية", "45 ثانية", "90 ثانية"};
    /** Uptime when the boot watch began; 0 when not after a boot. */
    static long bootStart;

    /** Kept in device storage: it is read early in boot, before the user's storage unlocks. */
    private static android.content.SharedPreferences bootPrefs(Context c) {
        Context d = Build.VERSION.SDK_INT >= 24 ? c.createDeviceProtectedStorageContext() : c;
        return d.getSharedPreferences("boot", MODE_PRIVATE);
    }

    static int settleChoice(Context c) {
        int v = bootPrefs(c).getInt("settle", 0);
        return v >= 0 && v < SETTLE_SECONDS.length ? v : 0;
    }

    static void setSettleChoice(Context c, int v) { bootPrefs(c).edit().putInt("settle", v).commit(); }

    /** Fast TVs show their own launcher sooner and for less time, so they need a shorter watch. */
    static long settleMs(Context c) {
        int s = SETTLE_SECONDS[settleChoice(c)];
        if (s > 0) return s * 1000L;
        try {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) c.getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            long mb = mi.totalMem / (1024 * 1024);
            int cores = Runtime.getRuntime().availableProcessors();
            if (mb > 2600 && cores >= 4) return 10000;
            if (mb > 1600) return 20000;
        } catch (Exception ignored) {
        }
        return 45000;
    }

    /** Seconds from power-on until the app was first on screen, for the settings; -1 if unknown. */
    static long lastShownSeconds(Context c) { return bootPrefs(c).getLong("shownAt", -1000) / 1000; }

    /** Called when the main screen appears: remembers how long this boot took to show it. */
    static void markShown(Context c) {
        if (bootStart == 0 || shownMarked) return;
        shownMarked = true;
        try { bootPrefs(c).edit().putLong("shownAt", android.os.SystemClock.elapsedRealtime()).apply(); } catch (Exception ignored) { }
    }

    private static boolean shownMarked;

    /** True while the boot watch is still allowed to pull the app to the front. */
    static boolean inBootWindow(Context c) {
        return bootStart == 0 || android.os.SystemClock.elapsedRealtime() - bootStart < settleMs(c);
    }

    public static void start(Context c) {
        start(c, new Intent(c, KeepAliveService.class));
    }

    /** Starts the service and has it bring the app up a few times while the TV finishes booting. */
    public static void startAfterBoot(Context c) {
        start(c, new Intent(c, KeepAliveService.class).putExtra(EXTRA_BOOT, true));
    }

    private static void start(Context c, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void bringToFront(Context c) {
        Intent a = new Intent(c, MainActivity.class);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            c.startActivity(a);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenOn, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("keepalive", "مواقيت الأذان",
                    NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "keepalive");
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("مواقيت الأذان").setContentText("يعمل في الخلفية لتشغيل الأذان")
                .setSmallIcon(R.drawable.icon).setOngoing(true);
        startForeground(1, b.build());
        if (intent != null && intent.getBooleanExtra(EXTRA_BOOT, false)) {
            // The TV's own launcher comes up late in boot and covers us; keep coming back for a while.
            bootStart = android.os.SystemClock.elapsedRealtime();
            long settle = settleMs(this);
            watch(Math.max(settle, 10000) * 2, settle);
        }
        return START_STICKY;
    }

    private long watchUntil, watchSettleAt;

    /**
     * Brings the app to the front every 1.5 s for {@code duration} ms while it is not on screen.
     * Stops early once it is on screen and {@code settle} ms have passed, so a user who then opens
     * another app is left alone.
     */
    private void watch(long duration, long settle) {
        long now = System.currentTimeMillis();
        watchUntil = Math.max(watchUntil, now + duration);
        watchSettleAt = now + settle;
        handler.removeCallbacks(watcher);
        handler.postDelayed(watcher, 300);
    }

    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (now > watchUntil) return;
            if (MainActivity.visible) {
                if (now >= watchSettleAt) return;
            } else {
                afterBoot();
            }
            handler.postDelayed(this, 1000); // retry every second until it holds the screen
        }
    };

    private void afterBoot() {
        boolean open = true;
        try {
            // Throws while user storage is still locked early in boot.
            Config cfg = new Config(this);
            open = cfg.openOnBoot();
            getSharedPreferences("azan", MODE_PRIVATE).edit().putLong("lastBoot", System.currentTimeMillis()).apply();
            Scheduler.scheduleNext(this);
        } catch (Exception ignored) {
        }
        if (open && !MainActivity.visible) bringToFront(this);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenOn);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
