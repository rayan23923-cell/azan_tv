package com.azantv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** The app settings a phone can read and change over the network (TV page or mosque clock page). */
public final class WebSettings {
    private WebSettings() {}

    private static final String[] YES_NO = {"لا", "نعم"};

    /** {section, key, label, type, default, min, max}; type: b = on/off, i = number, s = choice. */
    private static final Object[][] FIELDS = {
        {"عام", "city", "المدينة", "s", 0, 0, 0},
        {"عام", "method", "طريقة الحساب", "s", 0, 0, 0},
        {"عام", "asr", "وقت العصر", "s", 1, 1, 2},
        {"عام", "hijri", "تعديل التاريخ الهجري - أيام", "i", 0, -3, 3},
        {"عام", "volume", "مستوى صوت الأذان %", "i", 100, 0, 100},
        {"عام", "boot", "فتح التطبيق عند تشغيل التلفزيون", "b", 1, 0, 1},
        {"عام", "showQr", "إظهار رمز الإعدادات على الشاشة", "b", 1, 0, 1},
        {"الأذان", "azanAll", "تشغيل الأذان", "b", 1, 0, 1},
        {"الأذان", "azan0", "أذان الفجر", "b", 1, 0, 1},
        {"الأذان", "azan2", "أذان الظهر", "b", 1, 0, 1},
        {"الأذان", "azan3", "أذان العصر", "b", 1, 0, 1},
        {"الأذان", "azan4", "أذان المغرب", "b", 1, 0, 1},
        {"الأذان", "azan5", "أذان العشاء", "b", 1, 0, 1},
        {"الأذان", "off0", "تعديل وقت الفجر - دقائق", "i", 0, -30, 30},
        {"الأذان", "off1", "تعديل وقت الشروق - دقائق", "i", 0, -30, 30},
        {"الأذان", "off2", "تعديل وقت الظهر - دقائق", "i", 0, -30, 30},
        {"الأذان", "off3", "تعديل وقت العصر - دقائق", "i", 0, -30, 30},
        {"الأذان", "off4", "تعديل وقت المغرب - دقائق", "i", 0, -30, 30},
        {"الأذان", "off5", "تعديل وقت العشاء - دقائق", "i", 0, -30, 30},
        {"الإقامة والأذكار", "iq0", "الإقامة بعد أذان الفجر - دقائق", "i", 20, 0, 60},
        {"الإقامة والأذكار", "iq2", "الإقامة بعد أذان الظهر - دقائق", "i", 15, 0, 60},
        {"الإقامة والأذكار", "iq3", "الإقامة بعد أذان العصر - دقائق", "i", 15, 0, 60},
        {"الإقامة والأذكار", "iq4", "الإقامة بعد أذان المغرب - دقائق", "i", 5, 0, 60},
        {"الإقامة والأذكار", "iq5", "الإقامة بعد أذان العشاء - دقائق", "i", 10, 0, 60},
        {"الإقامة والأذكار", "fajr1", "الأذان الأول للفجر قبل الثاني - دقائق، 0 يلغيه", "i", 30, 0, 120},
        {"الإقامة والأذكار", "jumua1", "الأذان الأول للجمعة قبل الثاني - دقائق، 0 يلغيه", "i", 30, 0, 120},
        {"الإقامة والأذكار", "khutbah", "مدة الخطبة - دقائق", "i", 25, 0, 90},
        {"الإقامة والأذكار", "pray", "مدة الصلاة - دقائق", "i", 10, 0, 60},
        {"الإقامة والأذكار", "adhkar", "مدة الأذكار بعد الصلاة - دقائق، 0 يلغيها", "i", 10, 0, 60},
        {"وضع النوم", "sleep", "وضع النوم", "s", 0, 0, 2},
        {"وضع النوم", "sleepAfter", "النوم بعد الأذكار - دقائق", "i", 15, 1, 240},
        {"وضع النوم", "wakeBefore", "التشغيل قبل الأذان - دقائق", "i", 10, 1, 120},
    };

    private static String[] options(String key) {
        if (key.equals("city")) return Config.CITY_NAMES;
        if (key.equals("method")) {
            String[] n = new String[PrayTimes.METHODS.length];
            for (int i = 0; i < n.length; i++) n[i] = PrayTimes.METHODS[i].name;
            return n;
        }
        if (key.equals("asr")) return new String[]{"الجمهور - الشافعي والجعفري", "الحنفي"};
        if (key.equals("sleep")) return new String[]{"بدون نوم", "شاشة سوداء", "إطفاء الشاشة - تجريبي، قد لا يصحو لوحده"};
        return YES_NO;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("azan", Context.MODE_PRIVATE);
    }

    public static String json(Context c) {
        SharedPreferences sp = sp(c);
        JSONArray out = new JSONArray();
        try {
            for (Object[] f : FIELDS) {
                String key = (String) f[1], type = (String) f[3];
                int def = (Integer) f[4];
                JSONObject o = new JSONObject();
                o.put("section", f[0]).put("key", key).put("label", f[2]).put("type", type);
                if (type.equals("b")) {
                    o.put("value", sp.getBoolean(key, def != 0));
                } else {
                    o.put("value", sp.getInt(key, def));
                    o.put("min", f[5]).put("max", f[6]);
                }
                if (type.equals("s")) {
                    JSONArray opts = new JSONArray();
                    for (String s : options(key)) opts.put(s);
                    o.put("options", opts);
                    o.put("first", key.equals("asr") ? 1 : 0); // value of the first option
                }
                out.put(o);
            }
            JSONObject r = new JSONObject().put("app", "AzanTV").put("fields", out);
            double[] l = new Config(c).customLocation();
            if (l != null) r.put("location", new JSONObject().put("lat", l[0]).put("lng", l[1]).put("name", sp.getString("locName", "")));
            return r.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Saves one setting; returns false for an unknown key or a bad value. */
    public static boolean set(Context c, String key, String value) {
        for (Object[] f : FIELDS) {
            if (!f[1].equals(key)) continue;
            String type = (String) f[3];
            SharedPreferences.Editor e = sp(c).edit();
            if (type.equals("b")) {
                e.putBoolean(key, value.equals("1") || value.equals("true"));
            } else {
                int v;
                try { v = Integer.parseInt(value.trim()); } catch (NumberFormatException x) { return false; }
                int min = (Integer) f[5], max = (Integer) f[6];
                if (type.equals("s")) {
                    int first = key.equals("asr") ? 1 : 0;
                    min = first;
                    max = first + options(key).length - 1;
                }
                if (v < min || v > max) return false;
                e.putInt(key, v);
            }
            e.putBoolean("configured", true);
            e.commit();
            return true;
        }
        return false;
    }
}
