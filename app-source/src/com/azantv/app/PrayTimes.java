package com.azantv.app;

/** Prayer time calculation (based on the PrayTimes.org algorithm). Results are hours in local time. */
public final class PrayTimes {
    public static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;

    /** Calculation method: angles in degrees; a negative maghrib/isha value means minutes after the previous time. */
    public static final class Method {
        public final String name;
        public final double fajr, maghrib, isha;
        public Method(String name, double fajr, double maghrib, double isha) {
            this.name = name; this.fajr = fajr; this.maghrib = maghrib; this.isha = isha;
        }
    }

    public static final Method[] METHODS = {
        new Method("الجعفري (معهد قم)", 16, 4, 14),
        new Method("رابطة العالم الإسلامي", 18, -0, 17),
        new Method("أم القرى (مكة)", 18.5, -0, -90),
        new Method("الهيئة المصرية", 19.5, -0, 17.5),
        new Method("جامعة كراتشي", 18, -0, 18),
    };

    private PrayTimes() {}

    /**
     * @param asrFactor 1 = standard (Shafi'i, Maliki, Hanbali, Ja'fari), 2 = Hanafi
     * @return six times (fajr..isha) in local hours
     */
    public static double[] compute(int year, int month, int day, double lat, double lng, double tz,
                                   Method m, int asrFactor) {
        double jDate = julian(year, month, day) - lng / (15 * 24.0);
        double[] t = {5, 6, 12, 13, 18, 18, 18}; // fajr, sunrise, dhuhr, asr, sunset, maghrib, isha
        for (int iter = 0; iter < 2; iter++) {
            double[] p = new double[7];
            for (int i = 0; i < 7; i++) p[i] = t[i] / 24.0;
            double fajr = sunAngleTime(jDate, lat, m.fajr, p[0], true);
            double sunrise = sunAngleTime(jDate, lat, 0.833, p[1], true);
            double dhuhr = midDay(jDate, p[2]);
            double asr = asrTime(jDate, lat, asrFactor, p[3]);
            double sunset = sunAngleTime(jDate, lat, 0.833, p[4], false);
            double maghrib = m.maghrib > 0 ? sunAngleTime(jDate, lat, m.maghrib, p[5], false) : sunset;
            double isha = m.isha > 0 ? sunAngleTime(jDate, lat, m.isha, p[6], false) : Double.NaN;
            t = new double[]{fajr, sunrise, dhuhr, asr, sunset, maghrib, isha};
            if (m.isha < 0) t[6] = t[5] - m.isha / 60.0;
            if (m.maghrib < 0) t[5] = t[4] - m.maghrib / 60.0;
        }
        double adj = tz - lng / 15.0;
        double[] out = {t[0], t[1], t[2], t[3], t[5], t[6]};
        for (int i = 0; i < out.length; i++) out[i] += adj;
        return out;
    }

    private static double julian(int year, int month, int day) {
        if (month <= 2) { year -= 1; month += 12; }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4.0);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** returns {declination, equationOfTime} */
    private static double[] sunPosition(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0;
        double eqt = q / 15.0 - fixHour(ra);
        double decl = darcsin(dsin(e) * dsin(l));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jDate, double time) {
        double eqt = sunPosition(jDate + time)[1];
        return fixHour(12 - eqt);
    }

    private static double sunAngleTime(double jDate, double lat, double angle, double time, boolean ccw) {
        double decl = sunPosition(jDate + time)[0];
        double noon = midDay(jDate, time);
        double v = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat));
        double tt = darccos(Math.max(-1, Math.min(1, v))) / 15.0;
        return noon + (ccw ? -tt : tt);
    }

    private static double asrTime(double jDate, double lat, int factor, double time) {
        double decl = sunPosition(jDate + time)[0];
        double angle = -Math.toDegrees(Math.atan(1.0 / (factor + dtan(Math.abs(lat - decl)))));
        return sunAngleTime(jDate, lat, angle, time, false);
    }

    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double fixAngle(double a) { a = a - 360.0 * Math.floor(a / 360.0); return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a = a - 24.0 * Math.floor(a / 24.0); return a < 0 ? a + 24 : a; }
}
