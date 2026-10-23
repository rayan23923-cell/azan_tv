package com.azantv.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps every TV on the newest app the mosque clock holds: a TV with a newer app gives it to the
 * clock, and a TV with an older one downloads it and asks to install it (one press on the remote).
 */
public final class Updater {
    private Updater() {}

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(3000);
        h.setReadTimeout(4000);
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
        InputStream in = h.getInputStream();
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    public static int myVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Compares with the clock (GET /api/apk gives an upload id; the version is read from the file).
     * Returns a downloaded, checked APK when the clock's is newer; gives the clock this app when
     * it holds an older one or none. Blocks; call off the main thread.
     */
    public static File sync(Context c, String host) {
        android.content.SharedPreferences sp = c.getSharedPreferences("azan", Context.MODE_PRIVATE);
        int mine = myVersion(c);
        long id;
        try {
            id = new JSONObject(get("http://" + host + "/api/apk")).optLong("id", -1);
        } catch (Exception e) {
            return null; // a clock without the app store
        }
        if (id < 0) return null;
        android.util.Log.i("AzanUpdater", "clock apk id " + id + ", installed " + mine);
        if (id == 0) { // empty: give it this app
            upload(c, host, sp.getString("espPin", "1234"));
            return null;
        }
        int theirs = sp.getLong("apkId", -1) == id ? sp.getInt("apkVersion", 0) : -1;
        File f = new File(c.getCacheDir(), "update.apk");
        if (theirs < 0) { // a new upload on the clock: fetch it to read its version
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + "/app.apk").openConnection();
                h.setConnectTimeout(4000);
                h.setReadTimeout(15000);
                InputStream in = h.getInputStream();
                OutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                theirs = pi != null && c.getPackageName().equals(pi.packageName) ? pi.versionCode : 0;
            } catch (Exception e) {
                f.delete();
                return null;
            }
            // A new upload is asked about right away, even if an older one was dismissed lately.
            sp.edit().putLong("apkId", id).putInt("apkVersion", theirs).putLong("apkAskedAt", 0).apply();
            android.util.Log.i("AzanUpdater", "downloaded id " + id + " versionCode " + theirs);
        }
        if (theirs > mine && f.isFile()) {
            // Ask again at most every 6 hours if the install screen was dismissed.
            long last = sp.getLong("apkAskedAt", 0), now = System.currentTimeMillis();
            if (Math.abs(now - last) < 6 * 3600000L) {
                android.util.Log.i("AzanUpdater", "newer app waits: asked " + (now - last) / 60000 + " min ago");
                return null;
            }
            sp.edit().putLong("apkAskedAt", now).apply();
            return f;
        }
        f.delete();
        if (theirs < mine) upload(c, host, sp.getString("espPin", "1234"));
        return null;
    }

    /** Sends this app to the clock as the multipart form its /update page uses. */
    private static void upload(Context c, String host, String pin) {
        try {
            File apk = new File(c.getApplicationInfo().sourceDir);
            String b = "----azantv" + System.currentTimeMillis();
            byte[] head = ("--" + b + "\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"AzanTV-"
                    + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName + ".apk\"\r\n"
                    + "Content-Type: application/vnd.android.package-archive\r\n\r\n").getBytes("UTF-8");
            byte[] tail = ("\r\n--" + b + "--\r\n").getBytes("UTF-8");
            HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + "/api/apk?pin="
                    + java.net.URLEncoder.encode(pin, "UTF-8")).openConnection();
            h.setConnectTimeout(4000);
            h.setReadTimeout(60000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(head.length + apk.length() + tail.length);
            h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
            OutputStream out = h.getOutputStream();
            out.write(head);
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.write(tail);
            out.close();
            h.getResponseCode();
            h.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** Hands the APK to Android, which shows its install screen. */
    public static void install(Context c, File apk) {
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            int id = pi.createSession(p);
            PackageInstaller.Session s = pi.openSession(id);
            OutputStream out = s.openWrite("app.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            s.fsync(out);
            out.close();
            Intent done = new Intent(c, Result.class);
            int flags = android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            android.util.Log.i("AzanUpdater", "installer session " + id + " committed");
            s.commit(PendingIntent.getBroadcast(c, 7, done, flags | PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
            s.close();
        } catch (Exception e) {
            android.util.Log.w("AzanUpdater", "install failed", e);
        }
    }

    /** Android asks the person to confirm: open that screen. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, 0);
            android.util.Log.i("AzanUpdater", "installer status " + st + " " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            if (st != PackageInstaller.STATUS_PENDING_USER_ACTION) return;
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(confirm); } catch (Exception ignored) { }
        }
    }
}
