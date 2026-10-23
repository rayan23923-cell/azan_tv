package com.azantv.app;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Asks the mosque clock (ESP32) for the time over NTP: as the Wi-Fi gateway (its own Masjid-Time
 * network, 192.168.4.1), or anywhere on the same network through a broadcast request. The 4G USB
 * stick (192.168.100.1) also answers NTP with the mobile network's time, over its Wi-Fi or USB.
 */
public final class Sntp {
    private static final long NTP_EPOCH_OFFSET = 2208988800L;

    private Sntp() {}

    /** Returns the current UTC time in millis from the network clock, or -1. Blocks; call off the main thread. */
    public static long query(Context c) {
        for (String host : new String[]{systemNtp(c), gateway(c), "192.168.4.1", "192.168.100.1", "255.255.255.255"}) {
            if (host == null) continue;
            long t = queryHost(host);
            if (t > 0) return t;
        }
        return -1;
    }

    /**
     * The address of the mosque clock (ESP32) when it answers, or null. Blocks; call off the main
     * thread. The TV's NTP setting and the clock's own access point are trusted; any other
     * answer must also say it is the mosque clock on its web page.
     */
    public static String findClock(Context c) {
        String sys = systemNtp(c);
        for (String host : new String[]{sys, "192.168.4.1", "255.255.255.255", gateway(c)}) {
            if (host == null) continue;
            String from = answerFrom(host);
            if (from == null) continue;
            if (from.equals(sys) || from.equals("192.168.4.1") || isClockPage(from)) return from;
        }
        return null;
    }

    private static boolean isClockPage(String host) {
        try {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("http://" + host + "/").openConnection();
            h.setConnectTimeout(2000);
            h.setReadTimeout(2000);
            java.io.InputStream in = h.getInputStream();
            byte[] b = new byte[4096];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            in.close();
            String page = new String(b, 0, n, "UTF-8");
            return page.contains("Masjid") || page.contains("مسجد");
        } catch (Exception e) {
            return false;
        }
    }

    /** Sends one NTP request and returns the address that answered with a valid time, or null. */
    private static String answerFrom(String host) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(2000);
            s.setBroadcast(true);
            byte[] buf = new byte[48];
            buf[0] = 0x23;
            s.send(new DatagramPacket(buf, 48, InetAddress.getByName(host), 123));
            DatagramPacket resp = new DatagramPacket(new byte[48], 48);
            s.receive(resp);
            byte[] r = resp.getData();
            int stratum = r[1] & 0xff;
            if ((r[0] & 0x7) != 4 || stratum == 0 || stratum > 15) return null;
            return resp.getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    /** The TV's own NTP server setting, when it was pointed at the mosque clock (e.g. 192.168.1.66). */
    private static String systemNtp(Context c) {
        try {
            String s = android.provider.Settings.Global.getString(c.getContentResolver(), "ntp_server");
            return s == null || s.isEmpty() || s.contains("pool.ntp.org") || s.contains("android") ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String gateway(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return null;
            int g = d.gateway;
            return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }

    private static long queryHost(String host) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(2000);
            s.setBroadcast(true);
            byte[] buf = new byte[48];
            buf[0] = 0x23; // version 4, client
            InetAddress addr = InetAddress.getByName(host);
            long sent = android.os.SystemClock.elapsedRealtime();
            s.send(new DatagramPacket(buf, 48, addr, 123));
            DatagramPacket resp = new DatagramPacket(new byte[48], 48);
            s.receive(resp);
            long rtt = android.os.SystemClock.elapsedRealtime() - sent;
            byte[] r = resp.getData();
            int stratum = r[1] & 0xff;
            if ((r[0] & 0x7) != 4 || stratum == 0 || stratum > 15) return -1;
            long sec = ((r[40] & 0xffL) << 24) | ((r[41] & 0xffL) << 16) | ((r[42] & 0xffL) << 8) | (r[43] & 0xffL);
            long frac = ((r[44] & 0xffL) << 24) | ((r[45] & 0xffL) << 16) | ((r[46] & 0xffL) << 8) | (r[47] & 0xffL);
            long ms = (sec - NTP_EPOCH_OFFSET) * 1000L + (frac * 1000L >>> 32);
            return ms + rtt / 2;
        } catch (Exception e) {
            return -1;
        } finally {
            if (s != null) s.close();
        }
    }
}
