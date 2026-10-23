package com.azantv.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Config cfg = new Config(c);
        if (!cfg.clockValid(cfg.now())) return;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "azantv:azan");
        wl.acquire(60000);

        Intent a = new Intent(c, MainActivity.class);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (intent.getBooleanExtra(Scheduler.EXTRA_WAKE, false)) { // end of sleep mode: just bring the screen back
            c.startActivity(a);
            return;
        }
        a.putExtra(Scheduler.EXTRA_PRAYER, intent.getIntExtra(Scheduler.EXTRA_PRAYER, -1));
        a.putExtra(Scheduler.EXTRA_TIME, intent.getLongExtra(Scheduler.EXTRA_TIME, 0));
        c.startActivity(a);

        Scheduler.scheduleNext(c);
    }
}
