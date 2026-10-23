package com.azantv.app;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/** Finds the azan recording: fajr.mp3 for fajr (if present), otherwise azan.mp3. */
public final class AzanFiles {
    private AzanFiles() {}

    public static File[] dirs(Context c) {
        File sd = Environment.getExternalStorageDirectory();
        return new File[]{
            c.getExternalFilesDir(null),
            new File(sd, "Download"),
            new File(sd, "Music"),
            sd,
        };
    }

    /** Recordings a phone can upload: the regular azan, the Fajr azan and the first azan. */
    public static final String[] SLOTS = {"azan", "fajr", "first", "iqama"};
    public static final String[] EXTS = {".mp3", ".ogg", ".m4a", ".wav", ".aac", ".opus", ".flac", ".amr", ".3gp"};

    /** The recording for an azan id: its own slot first, then the regular azan. */
    public static File forAzan(Context c, int azan) {
        if (azan == Config.FAJR_FIRST) return find(c, new String[]{"first", "fajr", "azan"});
        if (azan == Config.JUMUA_FIRST) return find(c, new String[]{"first", "azan"});
        return find(c, azan == PrayTimes.FAJR);
    }

    /** The file uploaded from a phone for a slot, or null. */
    public static File uploaded(Context c, String slot) {
        File d = c.getExternalFilesDir(null);
        if (d == null) return null;
        for (String e : EXTS) {
            File f = new File(d, slot + e);
            if (f.isFile()) return f;
        }
        return null;
    }

    public static File find(Context c, boolean fajr) {
        return find(c, fajr ? new String[]{"fajr", "azan"} : new String[]{"azan"});
    }

    private static File find(Context c, String[] names) {
        String[] exts = EXTS;
        for (String n : names) {
            for (File d : dirs(c)) {
                if (d == null) continue;
                for (String e : exts) {
                    File f = new File(d, n + e);
                    if (f.isFile() && f.canRead()) return f;
                }
            }
        }
        return null;
    }
}
