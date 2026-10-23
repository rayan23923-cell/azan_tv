package com.azantv.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int GOLD = 0xFFDDB45E;
    static final int BG_TOP = 0xFF0A3D2E;
    static final int BG_BOTTOM = 0xFF06261D;

    private static final Locale AR = new Locale("ar");
    private static final long AZAN_WINDOW = 5 * 60000L;

    private final Handler handler = new Handler();
    private Config cfg;
    private float u; // one unit = 1px on a 1366px wide screen

    private TextView clock, ampm, date, next, cityLine, dua, hint;
    private java.util.List<String> duas;
    private int duaIndex = -1;
    private static Typeface naskh, naskhBold;

    private final Runnable nextDua = new Runnable() {
        @Override public void run() {
            if (duas == null || duas.isEmpty()) return;
            duaIndex = (duaIndex + 1) % duas.size();
            final String d = duas.get(duaIndex);
            dua.animate().alpha(0f).setDuration(600).withEndAction(new Runnable() {
                @Override public void run() {
                    dua.setText(d);
                    dua.animate().alpha(1f).setDuration(800).start();
                }
            }).start();
            handler.postDelayed(this, 25000);
        }
    };

    private boolean showHijri = true;
    private String hijriText = "", gregText = "";
    private final boolean[] cardIsNext = new boolean[6];

    /** Fades the date line between Hijri and Gregorian, driven by the clock (8 s each). */
    private void flipDate(final boolean hijri) {
        showHijri = hijri;
        date.animate().cancel();
        date.animate().alpha(0f).setDuration(450).withEndAction(new Runnable() {
            @Override public void run() {
                date.setText(showHijri ? hijriText : gregText);
                date.setTextColor(showHijri ? GOLD : 0xEEFFFFFF);
                date.animate().alpha(1f).setDuration(650).start();
            }
        }).start();
    }

    private Drawable glass(boolean gold, float radius) { return Glass.panel(u, gold, radius); }

    private final Runnable hideHint = new Runnable() {
        @Override public void run() { hint.animate().alpha(0f).setDuration(800).start(); }
    };

    private void showHint() {
        hint.animate().cancel();
        hint.setAlpha(1f);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 8000);
    }
    private final TextView[] cardNames = new TextView[6];
    private final TextView[] cardTimes = new TextView[6];
    private final TextView[] cardIqama = new TextView[6];
    // Iqama countdown / prayer in progress / adhkar screen.
    private View phaseOverlay;
    private TextView phaseTitle, phaseBig, phaseNote;
    private CountdownRing ring;
    private View ringBox;
    private long lastPulse = -1;
    private int phaseMode;
    private long phaseKey, dismissedKey;
    private LinearLayout phaseBox, adhkarGrid;
    private View phaseTint, adhkarBg, mainContent, wallpaperView;
    private boolean blurred;
    // Sleep mode: black screen or screen off between prayers.
    private View sleepView;
    private boolean sleeping;
    private long lastUser, phaseEnd, sleepPreviewUntil;
    private int adhkarFor = -1;
    // Preview from Settings (or adb: --es preview adhkar|iqama); never touches the clock or alarms.
    private int previewMode;
    private boolean previewIqama, silentPreview;
    private PulseRing azanPulse, phasePulse;
    private TextView azanTime;
    private long previewStart;
    private int previewPrayer = PrayTimes.FAJR;
    private boolean previewKhutbah;
    private final LinearLayout[] cards = new LinearLayout[6];
    private View azanOverlay;
    private TextView azanTitle, azanNote;

    private MediaPlayer player;
    private ToneGenerator tone;
    private boolean azanPlaying;
    private boolean clockOk = true;
    private View clockFix;
    private android.widget.ImageView qrView;
    private TextView qrText;
    private TimeServer timeServer;
    private File pendingUpdate;
    private Link link;
    private View linkBadge;
    private android.widget.ImageView linkQr;
    private TextView linkText;
    private String linkShown;

    private final SimpleDateFormat fmtClock = new SimpleDateFormat("h:mm", AR);
    private final SimpleDateFormat fmtAmPm = new SimpleDateFormat("a", AR);
    private final SimpleDateFormat fmtSec = new SimpleDateFormat(":ss", AR);
    private final SimpleDateFormat fmtTime = new SimpleDateFormat("h:mm a", AR);
    private final SimpleDateFormat fmtShort = new SimpleDateFormat("h:mm", AR);
    private final SimpleDateFormat fmtDate = new SimpleDateFormat("EEEE d MMMM yyyy", AR);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Config(this);
        fmtClock.setTimeZone(Config.TZ);
        fmtAmPm.setTimeZone(Config.TZ);
        fmtSec.setTimeZone(Config.TZ);
        fmtTime.setTimeZone(Config.TZ);
        fmtDate.setTimeZone(Config.TZ);
        fmtShort.setTimeZone(Config.TZ);
        java.text.DateFormatSymbols sym = new java.text.DateFormatSymbols(AR);
        sym.setMonths(new String[]{"كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
                "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"});
        fmtDate.setDateFormatSymbols(sym); // Iraqi month names instead of يناير/أكتوبر

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        u = getResources().getDisplayMetrics().widthPixels / 1366f;
        setContentView(buildUi());

        // The TV page (settings and phone time) stays up all the time, for the phone and the mosque clock.
        timeServer = new TimeServer(this, new TimeServer.Listener() {
            @Override public void onPhoneTime(long phoneMillis) {
                cfg.setPhoneTime(phoneMillis);
                Scheduler.scheduleNext(MainActivity.this);
                refresh();
            }

            @Override public void onTestAzan(int azan) {
                if (sleeping) exitSleep();
                stopAzan();
                if (azan < 0) iqamaTone();
                else startAzan(azan);
            }

            @Override public void onStopAzan() { stopAzan(); }

            @Override public void onSettingsChanged() {
                Scheduler.scheduleNext(MainActivity.this);
                linkShown = null;
                refresh();
            }
        });
        timeServer.start();
        link = new Link(this, new Link.Listener() {
            @Override public void onLinkChanged() {
                if (clockFix.getVisibility() == View.VISIBLE) showClockFix(true);
                linkShown = null;
                updateLinkBadge();
            }

            @Override public void onUpdateReady(File apk) {
                android.util.Log.i("AzanUpdater", "update ready, waiting for a quiet moment");
                pendingUpdate = apk;
            }

            @Override public void onSettingsFromClock() {
                Scheduler.scheduleNext(MainActivity.this);
                linkShown = null;
                refresh();
            }
        });

        java.util.ArrayList<String> ask = new java.util.ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ask.add(Manifest.permission.ACCESS_FINE_LOCATION); // Android needs it for the TV hotspot
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 1);
        if (!cfg.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
        handleAzanIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAzanIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Scheduler.scheduleNext(this);
        KeepAliveService.start(this);
        visible = true;
        KeepAliveService.markShown(this);
        linkShown = null;
        lastUser = android.os.SystemClock.elapsedRealtime();
        duas = Duas.load();
        handler.removeCallbacks(nextDua);
        handler.post(nextDua);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    static boolean visible;

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        handler.removeCallbacks(nextDua);
        handler.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        link.recheck();
    }

    @Override
    protected void onDestroy() {
        timeServer.stop();
        link.stopHotspot();
        stopAzan();
        super.onDestroy();
    }

    private void handleAzanIntent(Intent i) {
        if (i == null) return;
        String pv = i.getStringExtra("preview");
        if ("sleep".equals(pv)) { // one-minute try of sleep mode
            i.removeExtra("preview");
            sleepPreviewUntil = cfg.now() + 60000;
            pv = null;
        }
        if ("update".equals(pv)) { // adb or settings: look for a newer app on the mosque clock now
            i.removeExtra("preview");
            if (link != null) link.checkUpdateNow();
            pv = null;
        }
        if ("friday".equals(pv)) { // adb preview of the Friday main screen for two minutes
            i.removeExtra("preview");
            Config.forceFridayUntil = System.currentTimeMillis() + 120000;
            pv = null;
        }
        if ("azanScreen".equals(pv)) { // the azan screen for a minute, without sound
            i.removeExtra("preview");
            String pr = i.getStringExtra("prayer");
            i.removeExtra("prayer");
            int idx = java.util.Arrays.asList("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha").indexOf(pr);
            silentPreview = true;
            startAzan(idx >= 0 && idx != PrayTimes.SUNRISE ? idx : PrayTimes.DHUHR);
            return;
        }
        previewIqama = "iqamaScreen".equals(pv);
        if (pv != null) {
            i.removeExtra("preview");
            previewMode = "iqama".equals(pv) || "khutbah".equals(pv) || previewIqama ? 1 : 3;
            previewKhutbah = "khutbah".equals(pv);
            previewStart = cfg.now() - (previewIqama ? 90000 : 0); // starts right at the iqama
            String pr = i.getStringExtra("prayer"); // adb: --es prayer fajr|dhuhr|asr|maghrib|isha
            i.removeExtra("prayer");
            int idx = java.util.Arrays.asList("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha").indexOf(pr);
            previewPrayer = idx >= 0 && idx != PrayTimes.SUNRISE ? idx : PrayTimes.FAJR;
            dismissedKey = 0;
        }
        int prayer = i.getIntExtra(Scheduler.EXTRA_PRAYER, -1);
        long time = i.getLongExtra(Scheduler.EXTRA_TIME, 0);
        i.removeExtra(Scheduler.EXTRA_PRAYER);
        if (prayer < 0) return;
        long late = cfg.now() - time;
        if (late > -60000 && late < AZAN_WINDOW && cfg.claimAzan(time)) startAzan(prayer);
    }

    /** Frosts the main screen behind any full-screen glass overlay (real blur needs Android 12). */
    private void updateBlur() {
        boolean on = phaseOverlay.getVisibility() == View.VISIBLE || azanOverlay.getVisibility() == View.VISIBLE
                || clockFix.getVisibility() == View.VISIBLE;
        if (on == blurred) return;
        blurred = on;
        hint.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        linkBadge.setAlpha(on ? 0f : 1f); // it shows through the glass of the azan and iqama screens
        if (Build.VERSION.SDK_INT >= 31) {
            android.graphics.RenderEffect fx = on ? android.graphics.RenderEffect.createBlurEffect(
                    28 * u, 28 * u, android.graphics.Shader.TileMode.CLAMP) : null;
            mainContent.setRenderEffect(fx);
            wallpaperView.setRenderEffect(fx);
        } else {
            mainContent.setAlpha(on ? 0f : 1f); // no blur before Android 12: hide the text instead
        }
    }

    private void refresh() {
        updateBlur(); // follows the overlays shown by the previous tick or a key press
        long now = cfg.now();
        Date d = new Date(now);
        clock.setText(ar(fmtClock.format(d)));
        ampm.setText(ar(fmtSec.format(d) + "  " + fmtAmPm.format(d)));
        gregText = ar(fmtDate.format(d));
        hijriText = ar(Hijri.format(now, cfg.hijriAdjust()));
        boolean wantHijri = (System.currentTimeMillis() / 8000) % 2 == 0;
        if (wantHijri != showHijri) flipDate(wantHijri);
        else if (date.getAlpha() == 1f) date.setText(showHijri ? hijriText : gregText);
        // No brackets: they render mirrored with this font's RTL layout.
        cityLine.setText(cfg.placeName() + "  •  " + PrayTimes.METHODS[cfg.method()].name.replace(" (", " - ").replace(")", ""));

        long[] t = cfg.timesFor(now);
        long[] np = cfg.nextPrayer(now);
        // After Isha the cards show tomorrow, so Fajr is highlighted with tomorrow's time.
        long[] shown = now > t[5] ? cfg.timesFor(now + 86400000L) : t;
        for (int i = 0; i < 6; i++) {
            cardTimes[i].setText(ar(fmtTime.format(new Date(shown[i]))));
            cardNames[i].setText(Config.prayerName(i, shown[i]));
            int iq = i == PrayTimes.DHUHR && Config.isFriday(shown[i]) ? cfg.khutbahMinutes() : cfg.iqama(i);
            cardIqama[i].setVisibility(iq > 0 ? View.VISIBLE : View.INVISIBLE);
            if (iq > 0) cardIqama[i].setText(ar("الإقامة " + fmtTime.format(new Date(shown[i] + iq * 60000L))));
            int first = i == PrayTimes.FAJR ? cfg.fajrFirst() : i == PrayTimes.DHUHR && Config.isFriday(shown[i]) ? cfg.jumuaFirst() : 0;
            if (first > 0 && cfg.azanEnabled(i)) { // e.g. "أول ٤:١٩ • إقامة ٥:٠٩"
                cardIqama[i].setVisibility(View.VISIBLE);
                String fa = "أول " + fmtShort.format(new Date(shown[i] - first * 60000L));
                cardIqama[i].setText(ar(iq > 0 ? fa + " • إقامة " + fmtShort.format(new Date(shown[i] + iq * 60000L)) : fa));
            }
            boolean isNext = np != null && np[0] == i && np[1] == shown[i];
            if (cards[i].getBackground() == null || cardIsNext[i] != isNext) {
                cards[i].setBackground(glass(isNext, 20 * u));
                cards[i].setElevation((isNext ? 10 : 5) * u);
                cardIsNext[i] = isNext;
            }
            int fg = isNext ? BG_BOTTOM : Color.WHITE;
            cardNames[i].setTextColor(isNext ? BG_BOTTOM : 0xCCFFFFFF);
            cardTimes[i].setTextColor(fg);
            cardIqama[i].setTextColor(isNext ? 0xCC06261D : 0xAAFFFFFF);
        }
        if (np != null) {
            String label = Config.prayerName((int) np[0], np[1]);
            long at = np[1];
            long[] na = cfg.nextAzan(now + 1);
            if (na != null && na[0] >= Config.FAJR_FIRST && na[1] < at) { // a first azan comes before it
                label = na[0] == Config.FAJR_FIRST ? "الأذان الأول للفجر" : "الأذان الأول للجمعة";
                at = na[1];
            }
            long left = (at - now) / 1000;
            next.setText(ar(String.format(AR, "%s بعد  %d:%02d:%02d", label, left / 3600, (left / 60) % 60, left % 60)));
        }

        link.check();
        if (linkShown == null) updateLinkBadge();
        // A newer app from the mosque clock: ask to install it only in a quiet moment, never near an azan.
        if (pendingUpdate != null && phaseMode == 0 && !azanPlaying && !sleeping) {
            long[] na = cfg.nextAzan(now);
            if (na == null || na[1] - now > 15 * 60000L) {
                File apk = pendingUpdate;
                pendingUpdate = null;
                Updater.install(this, apk);
            }
        }
        boolean ok = cfg.clockValid(now);
        if (!ok) askMosqueClock();
        if (ok != clockOk || (!ok && clockFix.getVisibility() != View.VISIBLE)) showClockFix(!ok);
        clockOk = ok;
        if (!clockOk) return;
        cfg.rememberGoodTime(now);
        updatePhase(now, t);
        updateSleep(now);

        // Play the azan from here too, in case the alarm was delayed or the screen was already open.
        if (!azanPlaying) {
            for (long[] a : cfg.azansFor(now, t)) {
                if (now >= a[1] && now - a[1] < 60000 && cfg.claimAzan(a[1])) {
                    startAzan((int) a[0]);
                    break;
                }
            }
        }
    }

    /** Always shows Arabic-Indic digits; some formatters on this TV fall back to Latin ones. */
    static String ar(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? (char) ('\u0660' + (c - '0')) : c);
        }
        return b.toString();
    }

    // ---------- clock fix from the mosque clock (ESP32) ----------

    private long lastClockAsk;
    private volatile boolean asking;

    private void askMosqueClock() {
        long t = android.os.SystemClock.elapsedRealtime();
        if (asking || t - lastClockAsk < 10000) return;
        lastClockAsk = t;
        asking = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final long net = Sntp.query(MainActivity.this);
                asking = false;
                if (net < 1767225600000L) return;
                handler.post(new Runnable() {
                    @Override public void run() {
                        cfg.setPhoneTime(net);
                        Scheduler.scheduleNext(MainActivity.this);
                        refresh();
                    }
                });
            }
        }).start();
    }

    // ---------- clock fix by phone ----------

    private void showClockFix(boolean show) {
        if (!show) {
            clockFix.setVisibility(View.GONE);
            return;
        }
        timeServer.start();
        String ip = link.state == Link.NONE ? Link.networkIp(this) : link.tvIp;
        if (link.state == Link.HOTSPOT && link.apSsid != null) {
            // No network: the phone joins the TV's own hotspot first, then opens the TV page.
            qrView.setImageBitmap(qrBitmap(wifiQr()));
            qrView.setVisibility(View.VISIBLE);
            qrText.setText("امسح الرمز للاتصال بشبكة التلفزيون: " + link.apSsid + "\nكلمة السر: " + link.apPass
                    + "\nبعدها افتح  http://" + ip + ":" + TimeServer.PORT);
        } else if (ip == null) {
            qrView.setVisibility(View.GONE);
            qrText.setText(link.hotspotError != null ? link.hotspotError
                    : "التلفزيون غير متصل بالواي فاي، راح تشتغل شبكة التلفزيون بعد دقيقة");
        } else {
            String url = "http://" + ip + ":" + TimeServer.PORT + "/";
            qrView.setImageBitmap(qrBitmap(url));
            qrView.setVisibility(View.VISIBLE);
            qrText.setText(url);
        }
        clockFix.setVisibility(View.VISIBLE);
    }

    private String wifiQr() {
        return "WIFI:T:WPA;S:" + qrEscape(link.apSsid) + ";P:" + qrEscape(link.apPass) + ";;";
    }

    private static String qrEscape(String s) {
        return s == null ? "" : s.replaceAll("([\\\\;,:\"])", "\\\\$1");
    }

    /**
     * Small QR in the corner of the main screen: the mosque clock page when it is on the network,
     * the TV page on Wi-Fi, or the TV's own hotspot when there is no network.
     */
    private void updateLinkBadge() {
        boolean want = cfg.showQr() && link.state != Link.NONE;
        String code = null, label = null;
        if (want) {
            if (link.state == Link.CLOCK) {
                code = "http://" + link.clockIp + "/settings";
                label = "إعدادات الساعة والأذان\n" + link.clockIp;
            } else if (link.state == Link.WIFI) {
                code = "http://" + link.tvIp + ":" + TimeServer.PORT + "/settings";
                label = "إعدادات الأذان\n" + link.tvIp + ":" + TimeServer.PORT;
            } else if (link.apSsid != null) {
                code = wifiQr();
                label = "شبكة التلفزيون: " + link.apSsid + "\nالسر: " + link.apPass
                        + "\nثم افتح " + link.tvIp + ":" + TimeServer.PORT;
            }
        }
        if (code == null) {
            linkBadge.setVisibility(View.GONE);
            linkShown = null;
            return;
        }
        if (!code.equals(linkShown)) {
            linkQr.setImageBitmap(qrBitmap(code));
            linkText.setText(label);
            linkShown = code;
        }
        linkBadge.setVisibility(View.VISIBLE);
    }

    /** A background the user dropped in Download (azan_bg.jpg/png) replaces the built-in one. */
    private android.graphics.Bitmap customBackground() { return customBackground("azan_bg"); }

    private android.graphics.Bitmap customBackground(String name) {
        java.io.File dir = new java.io.File(Environment.getExternalStorageDirectory(), "Download");
        for (String n : new String[]{name + ".jpg", name + ".png", name + ".webp"}) {
            java.io.File f = new java.io.File(dir, n);
            if (!f.isFile()) continue;
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 1366) sample *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static android.graphics.Bitmap qrBitmap(String text) {
        io.nayuki.qrcodegen.QrCode qr = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int border = 2, scale = 8, n = qr.size + border * 2;
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(n * scale, n * scale,
                android.graphics.Bitmap.Config.RGB_565);
        for (int y = 0; y < n * scale; y++) {
            for (int x = 0; x < n * scale; x++) {
                boolean dark = qr.getModule(x / scale - border, y / scale - border);
                bm.setPixel(x, y, dark ? Color.BLACK : Color.WHITE);
            }
        }
        return bm;
    }

    // ---------- sleep mode ----------

    /** Sleeps from a while after the adhkar until a few minutes before the next azan. */
    private void updateSleep(long now) {
        long wakeAt;
        boolean should;
        if (now < sleepPreviewUntil) {
            wakeAt = sleepPreviewUntil;
            should = true;
        } else {
            int mode = cfg.sleepMode();
            long[] na = cfg.nextAzan(now);
            long[] np = cfg.nextPrayer(now);
            long target = Math.min(na != null ? na[1] : Long.MAX_VALUE, np != null ? np[1] : Long.MAX_VALUE);
            wakeAt = target == Long.MAX_VALUE ? 0 : target - cfg.wakeBefore() * 60000L;
            long idle = android.os.SystemClock.elapsedRealtime() - lastUser;
            should = mode != 0 && wakeAt > 0 && phaseMode == 0 && !azanPlaying
                    && now >= phaseEnd + cfg.sleepAfter() * 60000L && now < wakeAt && idle > 5 * 60000L;
        }
        if (should && !sleeping) enterSleep(wakeAt);
        else if (should && wakeAt != sleepWakeAt) { // prayer times changed while asleep
            sleepWakeAt = wakeAt;
            Scheduler.scheduleWake(this, wakeAt);
        }
        else if (!should && sleeping) exitSleep();
    }

    private long sleepWakeAt;

    private void enterSleep(long wakeAt) {
        sleeping = true;
        sleepView.setVisibility(View.VISIBLE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;
        getWindow().setAttributes(lp);
        sleepWakeAt = wakeAt;
        Scheduler.scheduleWake(this, wakeAt);
        if (cfg.sleepMode() == 2 && sleepPreviewUntil == 0) AdminReceiver.screenOff(this);
    }

    private void exitSleep() {
        sleeping = false;
        sleepView.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        Scheduler.scheduleWake(this, 0);
    }

    // ---------- iqama and adhkar ----------

    /** Works out from the clock alone which stage we're in, so it survives restarts and reboots. */
    private void updatePhase(long now, long[] t) {
        int p = -1;
        for (int i = 5; i >= 0; i--) {
            if (i != PrayTimes.SUNRISE && t[i] <= now) { p = i; break; }
        }
        int mode = 0;
        boolean jumua = false;
        long iq = 0, prayEnd = 0, end = 0;
        if (p >= 0) {
            jumua = p == PrayTimes.DHUHR && Config.isFriday(t[p]);
            // On Friday the sermon fills the time between the second azan and the prayer.
            iq = t[p] + (jumua ? cfg.khutbahMinutes() : cfg.iqama(p)) * 60000L;
            prayEnd = iq + cfg.prayerMinutes() * 60000L;
            end = prayEnd + cfg.adhkarMinutes() * 60000L;
            phaseEnd = end;
            boolean wait = jumua ? cfg.khutbahMinutes() > 0 : cfg.iqama(p) > 0;
            if (wait && now < iq) mode = 1;
            else if (wait && now < prayEnd) mode = 2;
            else if (now >= prayEnd && now < end) mode = 3; // with no iqama set, adhkar follow the azan
        }
        if (previewMode != 0) {
            long age = now - previewStart;
            if (age > (previewIqama ? 180000 : previewMode == 1 ? 105000 : 300000)) previewMode = 0;
            else {
                p = previewPrayer; // Fajr by default: it shows every dhikr, including the ×10 one
                jumua = previewKhutbah;
                iq = previewStart + 90000;
                prayEnd = previewMode == 1 ? iq + (previewIqama ? 90000 : 15000) : previewStart;
                mode = previewMode == 3 ? 3 : age < 90000 ? 1 : 2;
            }
        }
        if (p < 0) phaseEnd = 0;
        long key = previewMode != 0 ? -10 - mode : p < 0 ? 0 : t[p] * 4 + mode;
        if (mode == 2 && phaseMode == 1 && key != phaseKey) {
            iqamaTone();
            phaseBox.setAlpha(0f);
            phaseBox.animate().alpha(1f).setDuration(900).start();
        }
        phaseMode = mode;
        phaseKey = key;
        boolean show = mode != 0 && key != dismissedKey;
        phaseOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        dua.setVisibility(mode == 0 ? View.VISIBLE : View.INVISIBLE);
        if (!show) return;
        phaseBox.setVisibility(mode == 3 ? View.GONE : View.VISIBLE);
        adhkarGrid.setVisibility(mode == 3 ? View.VISIBLE : View.GONE);
        phaseTint.setBackgroundColor(mode == 3 ? 0x40523A16 : 0x8C000000);
        adhkarBg.setVisibility(mode == 3 ? View.VISIBLE : View.GONE); // tone the cream down: it glares on this TV

        String name = jumua ? "الجمعة" : Config.PRAYER_NAMES[p];
        phasePulse.setVisibility(View.GONE);
        if (mode == 1 && jumua) {
            ringBox.setVisibility(View.GONE);
            phaseTitle.setText("أنصتوا للخطبة");
            phaseTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 72 * u);
            phaseNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * u);
            phaseNote.setText("قال رسول الله ﷺ: «إِذَا قُلْتَ لِصَاحِبِكَ يَوْمَ الْجُمُعَةِ أَنْصِتْ وَالْإِمَامُ يَخْطُبُ فَقَدْ لَغَوْتَ»\n\nيرجى إغلاق الهواتف");
        } else if (mode == 1) {
            long left = Math.max(0, (iq - now + 999) / 1000);
            phaseTitle.setText("إقامة صلاة " + name + " بعد");
            phaseTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 48 * u);
            ringBox.setVisibility(View.VISIBLE);
            ring.setTimes(iq - (previewMode == 1 ? 90000L : cfg.iqama(p) * 60000L), iq);
            phaseBig.setText(ar(String.format(Locale.US, "%d:%02d", left / 60, left % 60)));
            boolean last = CountdownRing.lastMinute(left * 1000);
            phaseBig.setTextColor(last ? 0xFFFFD9A8 : Color.WHITE);
            if (last && left != lastPulse && left > 0) { // gentle beat each second in the last minute
                lastPulse = left;
                phaseBig.setScaleX(1.08f);
                phaseBig.setScaleY(1.08f);
                phaseBig.animate().scaleX(1f).scaleY(1f).setDuration(450).start();
            }
            phaseNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * u);
            phaseNote.setText(ar("موعد الإقامة " + fmtTime.format(new Date(iq))));
        } else if (mode == 2 && now - iq < 120000) { // the first two minutes: the iqama itself
            phaseTitle.setText("حان الآن موعد إقامة صلاة " + name);
            phaseTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 64 * u);
            ringBox.setVisibility(View.GONE);
            phasePulse.setVisibility(View.VISIBLE);
            phasePulse.invalidate();
            phaseNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, 36 * u);
            phaseNote.setText("سَوُّوا صُفُوفَكُمْ، فَإِنَّ تَسْوِيَةَ الصُّفُوفِ مِنْ تَمَامِ الصَّلَاةِ\n\nأغلقوا الهواتف رجاءً");
        } else if (mode == 2) {
            phasePulse.setVisibility(View.GONE);
            phaseTitle.setText("أُقيمت صلاة " + name);
            phaseTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 72 * u);
            ringBox.setVisibility(View.GONE);
            phaseNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * u);
            phaseNote.setText("اسْتَوُوا وَاعْتَدِلُوا، وأغلقوا الهواتف");
        } else {
            if (adhkarFor != p) buildAdhkar(p);
        }
    }

    /** Every dhikr in one glass card, one under another, at the largest size that fits. */
    private void buildAdhkar(int prayer) {
        adhkarFor = prayer;
        adhkarGrid.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        adhkarGrid.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout[] col = {new LinearLayout(this)};
        col[0].setOrientation(LinearLayout.VERTICAL);
        col[0].setGravity(Gravity.CENTER_VERTICAL);
        box.addView(col[0], new LinearLayout.LayoutParams(-1, -1));

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (Adhkar.Item it : Adhkar.forPrayer(prayer)) {
            if (sb.length() > 0) {
                int s0 = sb.length();
                sb.append("\n\n");
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.35f), s0, sb.length(), 0); // small gap between adhkar
            }
            sb.append(it.text);
            if (!it.label.isEmpty()) { // count or name at the end (left) of the same line
                int s0 = sb.length();
                sb.append("   ").append(it.label);
                sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF94681E), s0, sb.length(), 0);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), s0, sb.length(), 0);
            }
        }
        TextView t = text(30, 0xFF0F3A2B, false); // dark green ink on the cream page
        t.setText(sb);
        t.setLineSpacing(0, 1.05f);
        t.setPadding((int) (36 * u), (int) (16 * u), (int) (36 * u), (int) (16 * u));
        t.setBackground(Glass.light(u, 28 * u));
        t.setElevation(6 * u);
        col[0].addView(t, new LinearLayout.LayoutParams(-1, -2));
        fitAdhkar(col, 50);
    }

    /** One text size for every card: the largest at which the list fits the screen. */
    private void fitAdhkar(final LinearLayout[] col, final int size) {
        for (LinearLayout c : col) {
            for (int i = 0; i < c.getChildCount(); i++) {
                ((TextView) c.getChildAt(i)).setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
            }
        }
        if (size <= 14) return;
        col[0].post(new Runnable() {
            @Override public void run() {
                for (LinearLayout c : col) {
                    int used = 0;
                    for (int i = 0; i < c.getChildCount(); i++) {
                        View v = c.getChildAt(i);
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                        v.measure(View.MeasureSpec.makeMeasureSpec(c.getWidth(), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        used += v.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                    }
                    if (c.getHeight() > 0 && used > c.getHeight()) {
                        fitAdhkar(col, size - 1);
                        return;
                    }
                }
            }
        });
    }

    private MediaPlayer iqPlayer;

    private void iqamaTone() {
        if (azanPlaying) return;
        File f = AzanFiles.uploaded(this, "iqama");
        if (f != null) { // the recording uploaded from a phone, else a short beep
            try {
                if (iqPlayer != null) iqPlayer.release();
                iqPlayer = new MediaPlayer();
                iqPlayer.setDataSource(f.getPath());
                float v = cfg.volume() / 100f;
                iqPlayer.setVolume(v, v);
                iqPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) { mp.release(); if (iqPlayer == mp) iqPlayer = null; }
                });
                iqPlayer.prepare();
                iqPlayer.start();
                return;
            } catch (Exception e) {
                if (iqPlayer != null) iqPlayer.release();
                iqPlayer = null;
            }
        }
        try {
            final ToneGenerator g = new ToneGenerator(AudioManager.STREAM_MUSIC, cfg.volume());
            g.startTone(ToneGenerator.TONE_PROP_BEEP2, 1500);
            handler.postDelayed(new Runnable() {
                @Override public void run() { g.release(); }
            }, 2000);
        } catch (Exception ignored) {
        }
    }

    // ---------- azan ----------

    private void startAzan(int prayer) {
        stopAzan();
        if (sleeping) exitSleep();
        azanPlaying = true;
        long day = cfg.now();
        String title;
        if (prayer == Config.FAJR_FIRST) title = "الأذان الأول لصلاة الفجر";
        else if (prayer == Config.JUMUA_FIRST) title = "الأذان الأول لصلاة الجمعة";
        else if (prayer == PrayTimes.FAJR && cfg.fajrFirst() > 0) title = "الأذان الثاني لصلاة الفجر";
        else if (prayer == PrayTimes.DHUHR && Config.isFriday(day)) title = cfg.jumuaFirst() > 0 ? "الأذان الثاني لصلاة الجمعة" : "حان الآن موعد أذان الجمعة";
        else title = "حان الآن موعد أذان " + Config.PRAYER_NAMES[prayer];
        azanTitle.setText(title);
        azanTime.setText(ar(fmtTime.format(new Date(day))));
        azanNote.setText("اضغط أي زر لإيقاف الأذان");
        azanPulse.invalidate();
        if (silentPreview) { // adb/settings preview of the screen without sound, for a minute
            silentPreview = false;
            azanOverlay.setVisibility(View.VISIBLE);
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (player == null && tone == null) stopAzan(); }
            }, 60000);
            return;
        }
        azanOverlay.setVisibility(View.VISIBLE);

        File f = AzanFiles.forAzan(this, prayer);
        Uri uri = f != null ? Uri.fromFile(f) : null;
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            azanNote.setText("لم يتم العثور على ملف الأذان (azan.mp3)، يتم تشغيل منبه بديل\nاضغط أي زر للإيقاف");
        }
        try {
            player = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            }
            player.setDataSource(this, uri);
            float v = cfg.volume() / 100f;
            player.setVolume(v, v);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { stopAzan(); }
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            if (player != null) player.release();
            player = null;
            beep();
        }
    }

    private void beep() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, cfg.volume());
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 20000);
            handler.postDelayed(new Runnable() {
                @Override public void run() { stopAzan(); }
            }, 20000);
        } catch (Exception ignored) {
        }
    }

    private void stopAzan() {
        azanPlaying = false;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (iqPlayer != null) {
            try { iqPlayer.stop(); } catch (Exception ignored) { }
            iqPlayer.release();
            iqPlayer = null;
        }
        if (azanOverlay != null) azanOverlay.setVisibility(View.GONE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        lastUser = android.os.SystemClock.elapsedRealtime();
        if (sleeping) { // any key wakes the screen
            sleepPreviewUntil = 0;
            exitSleep();
            return true;
        }
        showHint();
        boolean volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (azanPlaying && !volume) {
            stopAzan();
            return true;
        }
        if (phaseOverlay.getVisibility() == View.VISIBLE && !volume) {
            previewMode = 0;
            dismissedKey = phaseKey; // hide this screen until the next stage
            phaseOverlay.setVisibility(View.GONE);
            return true;
        }
        if (!clockOk && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            SystemLinks.openFirst(this, new Intent(android.provider.Settings.ACTION_DATE_SETTINGS));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            startActivity(new Intent(this, AppsActivity.class));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && SystemLinks.homeEnabled(this)) {
            return true; // this is the home screen; nothing to go back to
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- UI ----------

    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        if (naskh == null) {
            try {
                naskh = Typeface.createFromAsset(getAssets(), "amiri.ttf");
                naskhBold = Typeface.createFromAsset(getAssets(), "amiri_bold.ttf");
            } catch (Exception e) {
                naskh = Typeface.DEFAULT;
                naskhBold = Typeface.DEFAULT_BOLD;
            }
        }
        t.setTypeface(bold ? naskhBold : naskh);
        return t;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG_BOTTOM}));
        android.widget.ImageView wallpaper = new android.widget.ImageView(this);
        wallpaper.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        android.graphics.Bitmap custom = customBackground();
        if (custom != null) wallpaper.setImageBitmap(custom);
        else wallpaper.setImageResource(R.drawable.bg);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        wallpaperView = wallpaper;
        View shade = new View(this); // keeps the white text readable over the gold lines
        shade.setBackgroundColor(0x5C000000); // the TV's vivid mode oversaturates greens
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding((int) (40 * u), (int) (245 * u), (int) (40 * u), (int) (10 * u));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        mainContent = col;

        // Glass panel holding the clock, the alternating date and the countdown.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding((int) (36 * u), 0, (int) (36 * u), (int) (6 * u));
        top.setBackground(glass(false, 28 * u));
        top.setElevation(6 * u);
        col.addView(top, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.CENTER_HORIZONTAL); // top gravity keeps baseline alignment
        clockRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        clock = text(118, Color.WHITE, true);
        ampm = text(32, 0xCCFFFFFF, false);
        clockRow.addView(clock);
        clockRow.addView(ampm);
        // Amiri's line box is ~1.76em but the digits only use the middle of it: trim the empty
        // space above and below so the whole clock fits on a 720p screen without clipping.
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMargins(0, (int) (-24 * u), 0, (int) (-50 * u));
        top.addView(clockRow, clp);

        date = text(26, GOLD, true);
        top.addView(date);
        next = text(26, GOLD, true);
        top.addView(next);
        cityLine = text(18, 0x99FFFFFF, false);
        cityLine.setPadding(0, (int) (2 * u), 0, (int) (6 * u));
        col.addView(cityLine);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (190 * u), 0, (int) (190 * u), 0); // keep the cards inside the arch
        for (int i = 0; i < 6; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(0, (int) (6 * u), 0, (int) (6 * u));
            cardNames[i] = text(22, 0xCCFFFFFF, false);
            cardNames[i].setText(Config.PRAYER_NAMES[i]);
            cardTimes[i] = text(26, Color.WHITE, true);
            card.addView(cardNames[i]);
            card.addView(cardTimes[i]);
            cardIqama[i] = text(16, 0xAAFFFFFF, false);
            card.addView(cardIqama[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins((int) (6 * u), (int) (4 * u), (int) (6 * u), (int) (8 * u));
            row.addView(card, lp);
            cards[i] = card;
        }
        col.addView(row, new LinearLayout.LayoutParams(-1, -2));

        dua = text(27, 0xFFF3E3B5, false);
        dua.setMaxLines(2);
        dua.setPadding((int) (30 * u), (int) (8 * u), (int) (30 * u), (int) (8 * u));
        dua.setMinHeight((int) (64 * u));
        dua.setBackground(glass(false, 24 * u));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.setMargins((int) (196 * u), (int) (6 * u), (int) (196 * u), 0);
        col.addView(dua, dlp);

        hint = text(17, 0x88FFFFFF, false);
        hint.setText("OK للإعدادات   •   ▲ أو ▼ لكل التطبيقات");
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        showHint();

        LinearLayout lb = new LinearLayout(this);
        lb.setOrientation(LinearLayout.VERTICAL);
        lb.setGravity(Gravity.CENTER_HORIZONTAL);
        lb.setPadding((int) (10 * u), (int) (10 * u), (int) (10 * u), (int) (8 * u));
        lb.setBackground(glass(false, 18 * u));
        // no elevation: the screens added after it (adhkar, azan, sleep) must cover it
        linkQr = new android.widget.ImageView(this);
        linkQr.setBackgroundColor(Color.WHITE);
        lb.addView(linkQr, new LinearLayout.LayoutParams((int) (130 * u), (int) (130 * u)));
        linkText = text(13, 0xCCFFFFFF, false);
        linkText.setPadding(0, (int) (6 * u), 0, 0);
        lb.addView(linkText, new LinearLayout.LayoutParams((int) (160 * u), -2));
        lb.setVisibility(View.GONE);
        FrameLayout.LayoutParams lbp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        lbp.setMargins((int) (18 * u), 0, 0, (int) (34 * u));
        root.addView(lb, lbp);
        linkBadge = lb;

        FrameLayout phf = new FrameLayout(this);
        // Framed manuscript-style background; Download/adhkar_bg.jpg replaces it if present.
        final android.widget.ImageView iv = new android.widget.ImageView(this);
        adhkarBg = iv;
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY); // keep the frame on all four edges
        android.graphics.Bitmap abg = customBackground("adhkar_bg");
        if (abg != null) iv.setImageBitmap(abg);
        else iv.setImageResource(R.drawable.adhkar_bg);
        phf.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        phaseTint = new View(this); // dark for the iqama screens, clear for the light adhkar page
        phf.addView(phaseTint, new FrameLayout.LayoutParams(-1, -1));
        adhkarGrid = new LinearLayout(this);
        adhkarGrid.setOrientation(LinearLayout.VERTICAL);
        adhkarGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        adhkarGrid.setPadding((int) (62 * u), (int) (68 * u), (int) (62 * u), (int) (54 * u)); // inside the gold frame
        phf.addView(adhkarGrid, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout ph = new LinearLayout(this);
        ph.setOrientation(LinearLayout.VERTICAL);
        ph.setGravity(Gravity.CENTER);
        ph.setPadding((int) (150 * u), (int) (26 * u), (int) (150 * u), (int) (34 * u));
        ph.setBackground(glass(false, 40 * u));
        ph.setElevation(10 * u);
        FrameLayout.LayoutParams phl = new FrameLayout.LayoutParams(-1, -1); // full-screen glass
        int gm = (int) (22 * u);
        phl.setMargins(gm, gm, gm, gm);
        phf.addView(ph, phl);
        phaseBox = ph;
        phaseTitle = text(48, GOLD, true);
        FrameLayout rb = new FrameLayout(this);
        ring = new CountdownRing(this, cfg, u);
        rb.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        phaseBig = text(110, Color.WHITE, true);
        phaseBig.setTranslationY(-8 * u); // Amiri sits the digits low in their line box
        rb.addView(phaseBig, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        ringBox = rb;
        int rs = (int) (430 * u);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(rs, rs);
        bp.setMargins(0, (int) (12 * u), 0, 0);
        phaseNote = text(30, 0xDDFFFFFF, false);
        phaseNote.setPadding(0, (int) (20 * u), 0, 0);
        phasePulse = new PulseRing(this, u);
        phasePulse.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams((int) (200 * u), (int) (200 * u));
        pp.setMargins(0, 0, 0, (int) (16 * u));
        ph.addView(phasePulse, pp);
        ph.addView(phaseTitle);
        ph.addView(rb, bp);
        ph.addView(phaseNote);
        phf.setVisibility(View.GONE);
        root.addView(phf, new FrameLayout.LayoutParams(-1, -1));
        phaseOverlay = phf;

        LinearLayout ov = new LinearLayout(this);
        ov.setOrientation(LinearLayout.VERTICAL);
        ov.setGravity(Gravity.CENTER);
        ov.setBackgroundColor(0x8C000000);
        LinearLayout ovCard = new LinearLayout(this);
        ovCard.setOrientation(LinearLayout.VERTICAL);
        ovCard.setGravity(Gravity.CENTER);
        ovCard.setPadding((int) (70 * u), (int) (50 * u), (int) (70 * u), (int) (50 * u));
        ovCard.setBackground(glass(false, 40 * u));
        ovCard.setElevation(10 * u);
        LinearLayout.LayoutParams ocl = new LinearLayout.LayoutParams(-1, -1); // full-screen glass
        ocl.setMargins((int) (22 * u), (int) (22 * u), (int) (22 * u), (int) (22 * u));
        ov.addView(ovCard, ocl);
        azanTitle = text(80, GOLD, true);
        azanNote = text(30, 0xCCFFFFFF, false);
        azanNote.setPadding(0, (int) (30 * u), 0, 0);
        azanPulse = new PulseRing(this, u);
        ovCard.addView(azanPulse, new LinearLayout.LayoutParams((int) (190 * u), (int) (190 * u)));
        ovCard.addView(azanTitle);
        azanTime = text(40, Color.WHITE, true);
        ovCard.addView(azanTime);
        ovCard.addView(azanNote);
        TextView after = text(26, 0xFFF3E3B5, false);
        after.setText("اللَّهُمَّ رَبَّ هَذِهِ الدَّعْوَةِ التَّامَّةِ، وَالصَّلَاةِ الْقَائِمَةِ، آتِ مُحَمَّدًا الْوَسِيلَةَ وَالْفَضِيلَةَ،\nوَابْعَثْهُ مَقَامًا مَحْمُودًا الَّذِي وَعَدْتَهُ");
        after.setPadding((int) (40 * u), (int) (34 * u), (int) (40 * u), 0);
        ovCard.addView(after);
        ov.setVisibility(View.GONE);
        root.addView(ov, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        azanOverlay = ov;

        LinearLayout fix = new LinearLayout(this);
        fix.setOrientation(LinearLayout.VERTICAL);
        fix.setGravity(Gravity.CENTER);
        fix.setBackground(new LayerDrawable(new Drawable[]{
                new android.graphics.drawable.ColorDrawable(0xD9041510), glass(false, 40 * u)}));
        fix.setElevation(10 * u);
        TextView ft = text(44, GOLD, true);
        ft.setText("ساعة التلفزيون غير مضبوطة");
        fix.addView(ft);
        TextView fs = text(26, 0xDDFFFFFF, false);
        fs.setText("امسح الرمز بكاميرا الموبايل (لازم يكون على نفس الواي فاي)\nوالتلفزيون ياخذ الوقت من الموبايل تلقائياً");
        fs.setPadding(0, (int) (10 * u), 0, (int) (20 * u));
        fix.addView(fs);
        qrView = new android.widget.ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        int qs = (int) (300 * u);
        fix.addView(qrView, new LinearLayout.LayoutParams(qs, qs));
        qrText = text(24, 0xCCFFFFFF, false);
        qrText.setPadding(0, (int) (16 * u), 0, 0);
        fix.addView(qrText);
        TextView fm = text(22, 0x99FFFFFF, false);
        fm.setText("أو اضغط OK لضبط الوقت يدوياً من الإعدادات");
        fm.setPadding(0, (int) (14 * u), 0, 0);
        fix.addView(fm);
        fix.setVisibility(View.GONE);
        root.addView(fix, new FrameLayout.LayoutParams(-1, -1));
        clockFix = fix;

        sleepView = new View(this);
        sleepView.setBackgroundColor(Color.BLACK);
        sleepView.setElevation(100 * u); // above the glass cards' shadows (the corner QR has elevation)
        sleepView.setVisibility(View.GONE);
        root.addView(sleepView, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }
}
