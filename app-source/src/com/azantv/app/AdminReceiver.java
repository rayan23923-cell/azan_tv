package com.azantv.app;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/** Device admin with only the "lock screen" policy, so sleep mode can turn the screen off. */
public class AdminReceiver extends DeviceAdminReceiver {
    public static ComponentName name(Context c) { return new ComponentName(c, AdminReceiver.class); }

    public static boolean active(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(name(c));
    }

    /** Turns the screen off now; false when the permission isn't granted. */
    public static boolean screenOff(Context c) {
        if (!active(c)) return false;
        try {
            ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
