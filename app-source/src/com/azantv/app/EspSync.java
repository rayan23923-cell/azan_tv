package com.azantv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps the TV settings and the mosque clock's settings page the same: the clock holds a
 * versioned copy; the TV seeds it once, applies newer versions, and sends its own changes back.
 */
public final class EspSync {
    private EspSync() {}

    private static final String[] PRAYER_KEYS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};
    private static final String[] METHOD_KEYS = {"jafari", "mwl", "makkah", "egypt", "karachi"};
    private static final int[] IQAMA_DEFAULT = {20, 0, 15, 15, 5, 10};

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("azan", Context.MODE_PRIVATE);
    }

    /** Syncs once with the clock at {@code host}; returns true when it changed the TV settings. Blocks. */
    public static boolean sync(Context c, String host) {
        SharedPreferences sp = sp(c);
        try {
            JSONObject got = new JSONObject(http("GET", "http://" + host + "/api/settings", null));
            int version = got.optInt("version", 0);
            String mine = toEsp(c).toString();
            // Changed on the TV since the last sync (settings screen or the TV page).
            boolean dirty = !mine.equals(sp.getString("espSent", ""));
            if (version == 0 || (dirty && version <= sp.getInt("espVersion", -1))) {
                // First time (empty clock) or a change made on the TV: send the TV settings.
                String pin = sp.getString("espPin", "1234");
                JSONObject r = new JSONObject(http("POST", "http://" + host + "/api/settings"
                        + (version == 0 ? "" : "?pin=" + java.net.URLEncoder.encode(pin, "UTF-8")), mine));
                sp.edit().putInt("espVersion", r.optInt("version", version + 1)).putString("espSent", mine).commit();
                return false;
            }
            if (version != sp.getInt("espVersion", -1)) {
                JSONObject s = got.optJSONObject("settings");
                if (s != null) apply(c, s);
                sp.edit().putInt("espVersion", version).putString("espSent", toEsp(c).toString()).commit();
                return s != null;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static JSONObject toEsp(Context c) throws Exception {
        SharedPreferences sp = sp(c);
        JSONObject o = new JSONObject();
        o.put("city", Config.CITY_NAMES[Math.min(sp.getInt("city", 0), Config.CITY_NAMES.length - 1)]);
        o.put("method", METHOD_KEYS[Math.min(sp.getInt("method", 0), METHOD_KEYS.length - 1)]);
        o.put("asr", sp.getInt("asr", 1) == 2 ? "hanafi" : "standard");
        for (int i = 0; i < 6; i++) {
            o.put("adj_" + PRAYER_KEYS[i], sp.getInt("off" + i, 0));
            if (i == 1) continue;
            o.put("azan_" + PRAYER_KEYS[i], sp.getBoolean("azan" + i, true));
            o.put("iqama_" + PRAYER_KEYS[i], sp.getInt("iq" + i, IQAMA_DEFAULT[i]));
        }
        o.put("prayer_min", sp.getInt("pray", 10));
        int adhkar = sp.getInt("adhkar", 10);
        o.put("adhkar_on", adhkar > 0);
        o.put("adhkar_min", adhkar > 0 ? adhkar : sp.getInt("adhkarLast", 10));
        o.put("fajr_first_min", sp.getInt("fajr1", 30));
        o.put("jumua_first_min", sp.getInt("jumua1", 30));
        o.put("khutbah_min", sp.getInt("khutbah", 25));
        o.put("hijri_adj", sp.getInt("hijri", 0));
        o.put("azan_volume", sp.getInt("volume", 100));
        o.put("azan_all", sp.getBoolean("azanAll", true));
        o.put("sleep_mode", sp.getInt("sleep", 0));
        o.put("sleep_after_min", sp.getInt("sleepAfter", 15));
        o.put("wake_before_min", sp.getInt("wakeBefore", 10));
        o.put("show_qr", sp.getBoolean("showQr", true));
        double[] l = new Config(c).customLocation();
        if (l != null) {
            o.put("lat", l[0]).put("lng", l[1]).put("loc_name", sp.getString("locName", ""));
        }
        return o;
    }

    /** Writes the clock's settings; keys it does not have keep the TV value. */
    static void apply(Context c, JSONObject s) {
        SharedPreferences.Editor e = sp(c).edit();
        String city = s.optString("city", null);
        for (int i = 0; city != null && i < Config.CITY_NAMES.length; i++) {
            if (Config.CITY_NAMES[i].equals(city)) e.putInt("city", i);
        }
        String m = s.optString("method", null);
        for (int i = 0; m != null && i < METHOD_KEYS.length; i++) {
            if (METHOD_KEYS[i].equals(m)) e.putInt("method", i);
        }
        if (s.has("asr")) e.putInt("asr", "hanafi".equals(s.optString("asr")) ? 2 : 1);
        for (int i = 0; i < 6; i++) {
            num(s, e, "adj_" + PRAYER_KEYS[i], "off" + i, -60, 60);
            if (i == 1) continue;
            bool(s, e, "azan_" + PRAYER_KEYS[i], "azan" + i);
            num(s, e, "iqama_" + PRAYER_KEYS[i], "iq" + i, 0, 120);
        }
        num(s, e, "prayer_min", "pray", 0, 120);
        if (s.has("adhkar_on") || s.has("adhkar_min")) {
            int min = s.optInt("adhkar_min", sp(c).getInt("adhkarLast", 10));
            if (min > 0) e.putInt("adhkarLast", min);
            e.putInt("adhkar", s.optBoolean("adhkar_on", true) ? Math.max(0, Math.min(120, min)) : 0);
        }
        num(s, e, "fajr_first_min", "fajr1", 0, 180);
        num(s, e, "jumua_first_min", "jumua1", 0, 180);
        num(s, e, "khutbah_min", "khutbah", 0, 120);
        num(s, e, "hijri_adj", "hijri", -3, 3);
        num(s, e, "azan_volume", "volume", 0, 100);
        bool(s, e, "azan_all", "azanAll");
        num(s, e, "sleep_mode", "sleep", 0, 2);
        num(s, e, "sleep_after_min", "sleepAfter", 1, 240);
        num(s, e, "wake_before_min", "wakeBefore", 1, 120);
        bool(s, e, "show_qr", "showQr");
        if (s.has("lat") && s.has("lng")) {
            double lat = s.optDouble("lat", 0), lng = s.optDouble("lng", 0);
            if (Config.validLocation(lat, lng)) {
                e.putString("lat", String.valueOf(lat)).putString("lng", String.valueOf(lng))
                        .putString("locName", s.optString("loc_name", ""));
            }
        }
        e.putBoolean("configured", true);
        e.commit();
    }

    private static void num(JSONObject s, SharedPreferences.Editor e, String k, String pref, int min, int max) {
        if (!s.has(k)) return;
        try {
            e.putInt(pref, Math.max(min, Math.min(max, (int) Math.round(s.getDouble(k)))));
        } catch (Exception ignored) {
        }
    }

    private static void bool(JSONObject s, SharedPreferences.Editor e, String k, String pref) {
        if (!s.has(k)) return;
        Object v = s.opt(k);
        e.putBoolean(pref, Boolean.TRUE.equals(v) || "true".equals(v) || "1".equals(String.valueOf(v)));
    }

    private static String http(String method, String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(3000);
        h.setReadTimeout(4000);
        h.setRequestMethod(method);
        if (body != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            OutputStream o = h.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
        }
        if (h.getResponseCode() >= 300) throw new java.io.IOException("HTTP " + h.getResponseCode());
        InputStream in = h.getInputStream();
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
