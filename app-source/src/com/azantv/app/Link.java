package com.azantv.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;

/**
 * How a phone reaches the TV: through the mosque clock when it is on the network, straight to
 * the TV over Wi-Fi, or through the TV's own hotspot when there is no network at all.
 */
public final class Link {
    public static final int NONE = 0, WIFI = 1, CLOCK = 2, HOTSPOT = 3;
    /** No network for this long before the hotspot is turned on (Wi-Fi is slow after a power cut). */
    private static final long OFFLINE_WAIT = 60000;

    public interface Listener {
        void onLinkChanged();
        void onSettingsFromClock();
        void onUpdateReady(java.io.File apk);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean checking;
    private long lastCheck, offlineSince;
    private volatile long lastAppCheck;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean starting;

    public int state = NONE;
    public String clockIp, tvIp, apSsid, apPass;
    /** Why the hotspot could not start, for the screen; null when it is fine. */
    public String hotspotError;

    public Link(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    /** Called often; looks at the network at most every 30 seconds. */
    public void check() {
        long t = android.os.SystemClock.elapsedRealtime();
        if (checking || (lastCheck != 0 && t - lastCheck < 30000)) return;
        lastCheck = t;
        checking = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final String ip = networkIp(ctx);
                final String clock = ip == null ? null : Sntp.findClock(ctx);
                final boolean changed = clock != null && EspSync.sync(ctx, clock);
                long now = android.os.SystemClock.elapsedRealtime();
                if (clock != null && (lastAppCheck == 0 || now - lastAppCheck > 10 * 60000L)) {
                    lastAppCheck = now;
                    final java.io.File apk = Updater.sync(ctx, clock);
                    if (apk != null) main.post(new Runnable() {
                        @Override public void run() { listener.onUpdateReady(apk); }
                    });
                }
                if (clock != null) announce(clock, ip);
                main.post(new Runnable() {
                    @Override public void run() {
                        checking = false;
                        apply(ip, clock);
                        if (changed) listener.onSettingsFromClock();
                    }
                });
            }
        }, "azan-link").start();
    }

    /** Asks the clock for a newer app on the next look, which is made right away. */
    public void checkUpdateNow() {
        lastAppCheck = 0;
        ctx.getSharedPreferences("azan", Context.MODE_PRIVATE).edit().putLong("apkAskedAt", 0).putLong("apkId", -1).apply();
        recheck();
    }

    /** Looks again right away, e.g. after the location permission was granted. */
    public void recheck() {
        lastCheck = 0;
        check();
    }

    private void apply(String ip, String clock) {
        long t = android.os.SystemClock.elapsedRealtime();
        int old = state;
        String oldIp = tvIp, oldClock = clockIp;
        tvIp = ip;
        clockIp = clock;
        if (ip != null) {
            offlineSince = 0;
            stopHotspot();
            state = clock != null ? CLOCK : WIFI;
        } else {
            if (offlineSince == 0) offlineSince = t;
            if (t - offlineSince >= OFFLINE_WAIT) startHotspot();
            if (hotspotOn()) tvIp = hotspotIp();
            state = hotspotOn() && tvIp != null ? HOTSPOT : NONE;
        }
        if (state != old || !same(oldIp, tvIp) || !same(oldClock, clockIp)) listener.onLinkChanged();
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    private android.net.wifi.p2p.WifiP2pManager p2p;
    private android.net.wifi.p2p.WifiP2pManager.Channel p2pChannel;
    private boolean p2pOn, p2pFailed;

    private boolean hotspotOn() { return reservation != null || p2pOn; }

    /** Wi-Fi Direct group first: this TV's Wi-Fi chip can't run a hotspot while it stays on Wi-Fi. */
    private void startHotspot() {
        if (hotspotOn() || starting) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            hotspotError = "يحتاج إذن الموقع لتشغيل نقطة الاتصال";
            return;
        }
        if (!p2pFailed) {
            try {
                if (p2p == null) {
                    p2p = (android.net.wifi.p2p.WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
                    p2pChannel = p2p.initialize(ctx, Looper.getMainLooper(), null);
                }
                starting = true;
                p2p.removeGroup(p2pChannel, null); // a group left from before would block a new one
                main.postDelayed(new Runnable() {
                    @Override public void run() { createP2pGroup(); }
                }, 1500);
                return;
            } catch (Throwable e) {
                starting = false;
                p2pFailed = true;
            }
        }
        startLocalHotspot();
    }

    private void createP2pGroup() {
        try {
            p2p.createGroup(p2pChannel, new android.net.wifi.p2p.WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    main.postDelayed(new Runnable() {
                        @Override public void run() { readP2pGroup(0); }
                    }, 1500);
                }

                @Override public void onFailure(int reason) {
                    starting = false;
                    p2pFailed = true;
                    startLocalHotspot();
                }
            });
        } catch (Throwable e) {
            starting = false;
            p2pFailed = true;
            startLocalHotspot();
        }
    }

    private void readP2pGroup(final int tries) {
        try {
            p2p.requestGroupInfo(p2pChannel, new android.net.wifi.p2p.WifiP2pManager.GroupInfoListener() {
                @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup g) {
                    if (g == null || g.getPassphrase() == null) {
                        if (tries < 5) {
                            main.postDelayed(new Runnable() {
                                @Override public void run() { readP2pGroup(tries + 1); }
                            }, 1500);
                            return;
                        }
                        starting = false;
                        p2pFailed = true;
                        startLocalHotspot();
                        return;
                    }
                    starting = false;
                    p2pOn = true;
                    hotspotError = null;
                    apSsid = g.getNetworkName();
                    apPass = g.getPassphrase();
                    recheck();
                }
            });
        } catch (Throwable e) {
            starting = false;
            p2pFailed = true;
            startLocalHotspot();
        }
    }

    /** The TV's address inside its own Wi-Fi Direct group or hotspot. */
    private static String hotspotIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || !ni.getName().startsWith("p2p")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        String ip = TimeServer.localIp();
        return ip != null ? ip : "192.168.49.1";
    }

    private void startLocalHotspot() {
        if (reservation != null || starting) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            hotspotError = "يحتاج إذن الموقع لتشغيل نقطة الاتصال";
            return;
        }
        try {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isLocationEnabled()) {
                hotspotError = "شغّل الموقع من إعدادات التلفزيون حتى تشتغل نقطة الاتصال";
                return;
            }
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            starting = true;
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    starting = false;
                    reservation = r;
                    hotspotError = null;
                    try {
                        android.net.wifi.SoftApConfiguration sc = r.getSoftApConfiguration();
                        apSsid = sc.getSsid();
                        apPass = sc.getPassphrase();
                    } catch (Throwable e) {
                        apSsid = null;
                        apPass = null;
                    }
                    recheck();
                }

                @Override public void onStopped() {
                    starting = false;
                    reservation = null;
                    recheck();
                }

                @Override public void onFailed(int reason) {
                    starting = false;
                    hotspotError = "تعذر تشغيل نقطة الاتصال - رمز " + reason;
                    listener.onLinkChanged();
                }
            }, main);
        } catch (Throwable e) {
            starting = false;
            hotspotError = "تعذر تشغيل نقطة الاتصال";
        }
    }

    public void stopHotspot() {
        if (!hotspotOn()) return;
        if (p2pOn) {
            try { p2p.removeGroup(p2pChannel, null); } catch (Throwable ignored) { }
            p2pOn = false;
        }
        if (reservation != null) {
            try { reservation.close(); } catch (Throwable ignored) { }
        }
        reservation = null;
        p2pFailed = false;
        apSsid = null;
        apPass = null;
    }

    /** Tells the mosque clock where the TV is, so its page can link to the TV settings. */
    private static void announce(String clock, String ip) {
        try {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("http://" + clock
                    + "/api/tv?ip=" + ip + "&port=" + TimeServer.PORT).openConnection();
            h.setConnectTimeout(2000);
            h.setReadTimeout(2000);
            h.getResponseCode();
            h.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** The TV's address on a Wi-Fi or cable network, or null when it is not on one. */
    public static String networkIp(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || !(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
