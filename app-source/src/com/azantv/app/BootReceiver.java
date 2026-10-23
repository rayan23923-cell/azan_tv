package com.azantv.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_LAUNCH = "com.azantv.app.LAUNCH";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            // Arrives well before BOOT_COMPLETED, before the TV's memory manager force-stops
            // background apps. User storage may still be locked, so touch no preferences here.
            KeepAliveService.startAfterBoot(c);
            return;
        }
        try {
            handle(c, action);
        } catch (Exception ignored) {
        }
    }

    private void handle(Context c, String action) {
        Scheduler.scheduleNext(c);
        if (ACTION_LAUNCH.equals(action)) {
            if (new Config(c).openOnBoot() && KeepAliveService.inBootWindow(c)) KeepAliveService.bringToFront(c);
            return;
        }
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (boot) KeepAliveService.startAfterBoot(c);
        else KeepAliveService.start(c);
        if (!boot) return;
        c.getSharedPreferences("azan", Context.MODE_PRIVATE).edit()
                .putLong("lastBoot", System.currentTimeMillis()).apply();
        if (new Config(c).openOnBoot()) {
            KeepAliveService.bringToFront(c);
            // The TV's own launcher often comes up after BOOT_COMPLETED and covers us; try again later.
            long settle = KeepAliveService.settleMs(c);
            relaunchAt(c, Math.min(10000, settle), 2);
            if (settle > 20000) relaunchAt(c, Math.min(30000, settle - 2000), 3);
        }
    }

    private static void relaunchAt(Context c, long delay, int code) {
        Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_LAUNCH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(c, code, i, flags);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pi);
    }
}
