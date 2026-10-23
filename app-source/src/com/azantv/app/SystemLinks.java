package com.azantv.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

/** Opens Android system settings screens that TV skins often hide from their menus. */
public final class SystemLinks {
    private SystemLinks() {}

    /** Vendor "auto start" / "startup manager" screens; the first one that exists is used. */
    private static final String[][] AUTOSTART = {
        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
        {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
        {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
        {"com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"},
        {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        {"com.tcl.guard", "com.tcl.guard.activity.AutoStartActivity"},
        {"com.tcl.packageinstaller", "com.tcl.packageinstaller.autostart.AutoStartActivity"},
        {"com.android.settings", "com.android.settings.Settings$AppAndNotificationDashboardActivity"},
    };

    public static boolean open(Activity a, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Tries each intent in turn; shows a message if none of them exists on this TV. */
    public static void openFirst(Activity a, Intent... intents) {
        for (Intent i : intents) if (i != null && open(a, i)) return;
        Toast.makeText(a, "هذه الشاشة غير موجودة في نظام هذا التلفزيون", Toast.LENGTH_LONG).show();
    }

    public static Intent appDetails(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
    }

    public static Intent overlay(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
    }

    @SuppressWarnings("BatteryLife")
    public static Intent batteryRequest(Context c) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
    }

    public static Intent component(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }

    public static void openAutostart(Activity a) {
        PackageManager pm = a.getPackageManager();
        for (String[] s : AUTOSTART) {
            Intent i = component(s[0], s[1]);
            if (i.resolveActivity(pm) != null && open(a, i)) return;
        }
        openFirst(a, appDetails(a), new Intent(Settings.ACTION_SETTINGS));
    }

    public static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    public static boolean batteryExempt(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    // ----- "use as home screen": a HOME alias that is off unless the user turns it on -----

    public static ComponentName homeAlias(Context c) {
        return new ComponentName(c, "com.azantv.app.HomeAlias");
    }

    public static boolean homeEnabled(Context c) {
        return c.getPackageManager().getComponentEnabledSetting(homeAlias(c))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static void setHomeEnabled(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(homeAlias(c),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
