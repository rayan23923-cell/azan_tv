package com.azantv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private Config cfg;
    private float u;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Config(this);
        u = getResources().getDisplayMetrics().widthPixels / 1366f;
        ScrollView sv = new ScrollView(this);
        sv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.setBackground(Glass.screen(this, 0x99000000));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding((int) (200 * u), (int) (30 * u), (int) (200 * u), (int) (30 * u));
        sv.addView(list);
        setContentView(sv);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        list.removeAllViews();
        TextView title = new TextView(this);
        title.setText("الإعدادات");
        title.setTextColor(MainActivity.GOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 44 * u);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, (int) (20 * u));
        list.addView(title);

        if (cfg.customLocation() != null) {
            double[] l = cfg.customLocation();
            row("الموقع الدقيق من الموبايل: " + cfg.placeName() + "  " + String.format(java.util.Locale.US, "%.4f, %.4f", l[0], l[1])
                    + " - اضغط للرجوع للمدينة", new Runnable() {
                @Override public void run() { cfg.clearCustomLocation(); build(); }
            });
        }
        Button first = row("المدينة: " + Config.CITY_NAMES[cfg.city()], new Runnable() {
            @Override public void run() {
                choose("اختر المدينة", Config.CITY_NAMES, cfg.city(), new Pick() {
                    @Override public void picked(int i) { cfg.setCity(i); }
                });
            }
        });
        row("طريقة الحساب: " + PrayTimes.METHODS[cfg.method()].name, new Runnable() {
            @Override public void run() {
                String[] names = new String[PrayTimes.METHODS.length];
                for (int i = 0; i < names.length; i++) names[i] = PrayTimes.METHODS[i].name;
                choose("طريقة الحساب", names, cfg.method(), new Pick() {
                    @Override public void picked(int i) { cfg.setMethod(i); }
                });
            }
        });
        row("وقت العصر: " + (cfg.asrFactor() == 2 ? "الحنفي" : "الجمهور (الشافعي/الجعفري)"), new Runnable() {
            @Override public void run() {
                choose("وقت العصر", new String[]{"الجمهور (الشافعي/الجعفري)", "الحنفي"}, cfg.asrFactor() - 1, new Pick() {
                    @Override public void picked(int i) { cfg.setAsrFactor(i + 1); }
                });
            }
        });

        for (int p = 0; p < 6; p++) {
            final int prayer = p;
            String label = "تعديل " + Config.PRAYER_NAMES[p] + ": " + signed(cfg.offset(p)) + " دقيقة";
            if (p != PrayTimes.SUNRISE) label += cfg.azanEnabledSetting(p) ? "   •   الأذان مفعّل" : "   •   الأذان متوقف";
            row(label, new Runnable() {
                @Override public void run() { editPrayer(prayer); }
            });
        }

        section("الأذان ووضع النوم");
        row("الأذان: " + (cfg.azanOn() ? "مفعّل" : "متوقف لكل الصلوات"), new Runnable() {
            @Override public void run() { cfg.setAzanOn(!cfg.azanOn()); build(); }
        });
        final String[] modes = {"متوقف", "شاشة سوداء", "إطفاء الشاشة - تجريبي، قد لا يصحو لوحده"};
        row("وضع النوم بين الصلوات: " + modes[cfg.sleepMode()], new Runnable() {
            @Override public void run() {
                choose("وضع النوم", modes, cfg.sleepMode(), new Pick() {
                    @Override public void picked(int i) {
                        cfg.setSleepMode(i);
                        if (i == 2 && !AdminReceiver.active(SettingsActivity.this)) askAdmin();
                    }
                });
            }
        });
        if (cfg.sleepMode() != 0) {
            row("ينام بعد انتهاء الأذكار بـ " + cfg.sleepAfter() + " دقيقة", new Runnable() {
                @Override public void run() {
                    minutes("ينام بعد انتهاء الأذكار بـ", new int[]{1, 5, 10, 15, 20, 30, 45, 60}, cfg.sleepAfter(), new Pick() {
                        @Override public void picked(int v) { cfg.setSleepAfter(v); }
                    });
                }
            });
            row("يصحى قبل الأذان بـ " + cfg.wakeBefore() + " دقيقة", new Runnable() {
                @Override public void run() {
                    minutes("يصحى قبل الأذان بـ", new int[]{1, 5, 10, 15, 20, 30}, cfg.wakeBefore(), new Pick() {
                        @Override public void picked(int v) { cfg.setWakeBefore(v); }
                    });
                }
            });
        }
        if (cfg.sleepMode() == 2) {
            row(check(AdminReceiver.active(this)) + "صلاحية إطفاء الشاشة", new Runnable() {
                @Override public void run() { askAdmin(); }
            });
        }
        row("▶ تجربة وضع النوم (دقيقة وحدة، أي زر يصحّيه)", new Runnable() {
            @Override public void run() { preview("sleep"); }
        });

        section("الإقامة والأذكار");
        for (int p = 0; p < 6; p++) {
            if (p == PrayTimes.SUNRISE) continue;
            final int prayer = p;
            int m = cfg.iqama(p);
            row("الإقامة بعد أذان " + Config.PRAYER_NAMES[p] + ": " + (m == 0 ? "بدون عدّاد" : m + " دقيقة"), new Runnable() {
                @Override public void run() {
                    String[] opts = new String[61];
                    opts[0] = "بدون عدّاد إقامة";
                    for (int i = 1; i <= 60; i++) opts[i] = i + " دقيقة";
                    choose("الإقامة بعد أذان " + Config.PRAYER_NAMES[prayer], opts, cfg.iqama(prayer), new Pick() {
                        @Override public void picked(int i) { cfg.setIqama(prayer, i); }
                    });
                }
            });
        }
        row("الأذان الأول للفجر: " + (cfg.fajrFirst() == 0 ? "متوقف" : "قبل الفجر بـ " + cfg.fajrFirst() + " دقيقة"), new Runnable() {
            @Override public void run() {
                minutes("الأذان الأول للفجر (قبل الأذان الثاني)", new int[]{0, 10, 15, 20, 30, 40, 45, 60}, cfg.fajrFirst(), new Pick() {
                    @Override public void picked(int v) { cfg.setFajrFirst(v); }
                });
            }
        });
        row("الأذان الأول للجمعة: " + (cfg.jumuaFirst() == 0 ? "متوقف" : "قبل الأذان الثاني بـ " + cfg.jumuaFirst() + " دقيقة"), new Runnable() {
            @Override public void run() {
                minutes("الأذان الأول للجمعة (الثاني في وقت الظهر)", new int[]{0, 10, 15, 20, 30, 40, 45, 60}, cfg.jumuaFirst(), new Pick() {
                    @Override public void picked(int v) { cfg.setJumuaFirst(v); }
                });
            }
        });
        row("شاشة الإنصات للخطبة: " + (cfg.khutbahMinutes() == 0 ? "متوقفة" : cfg.khutbahMinutes() + " دقيقة بعد الأذان الثاني"), new Runnable() {
            @Override public void run() {
                minutes("مدة خطبة الجمعة", new int[]{0, 15, 20, 25, 30, 35, 40, 45, 60}, cfg.khutbahMinutes(), new Pick() {
                    @Override public void picked(int v) { cfg.setKhutbahMinutes(v); }
                });
            }
        });
        row("مدة الصلاة (قبل ظهور الأذكار): " + cfg.prayerMinutes() + " دقيقة", new Runnable() {
            @Override public void run() {
                String[] opts = new String[28];
                for (int i = 0; i < opts.length; i++) opts[i] = (i + 3) + " دقيقة";
                choose("مدة الصلاة", opts, cfg.prayerMinutes() - 3, new Pick() {
                    @Override public void picked(int i) { cfg.setPrayerMinutes(i + 3); }
                });
            }
        });
        row("أذكار ما بعد الصلاة: " + (cfg.adhkarMinutes() == 0 ? "متوقفة" : "تظهر " + cfg.adhkarMinutes() + " دقيقة"), new Runnable() {
            @Override public void run() {
                final int[] vals = {0, 3, 5, 7, 10, 15, 20, 30};
                String[] opts = new String[vals.length];
                int cur = 0;
                for (int i = 0; i < vals.length; i++) {
                    opts[i] = vals[i] == 0 ? "إيقاف الأذكار" : vals[i] + " دقيقة";
                    if (vals[i] == cfg.adhkarMinutes()) cur = i;
                }
                choose("مدة عرض الأذكار", opts, cur, new Pick() {
                    @Override public void picked(int i) { cfg.setAdhkarMinutes(vals[i]); }
                });
            }
        });

        row("▶ عرض تجريبي لشاشة الأذكار", new Runnable() {
            @Override public void run() { preview("adhkar"); }
        });
        row("▶ عرض تجريبي لعدّاد الإقامة", new Runnable() {
            @Override public void run() { preview("iqama"); }
        });
        row("▶ عرض تجريبي لشاشة الأذان - بدون صوت", new Runnable() {
            @Override public void run() { preview("azanScreen"); }
        });
        row("▶ عرض تجريبي لشاشة الإقامة", new Runnable() {
            @Override public void run() { preview("iqamaScreen"); }
        });
        row("▶ عرض تجريبي لشاشة خطبة الجمعة", new Runnable() {
            @Override public void run() { preview("khutbah"); }
        });

        section("عام");
        row("تعديل التاريخ الهجري: " + signed(cfg.hijriAdjust()) + " يوم", new Runnable() {
            @Override public void run() {
                final String[] opts = {"-2", "-1", "0", "+1", "+2"};
                choose("تعديل التاريخ الهجري", opts, cfg.hijriAdjust() + 2, new Pick() {
                    @Override public void picked(int i) { cfg.setHijriAdjust(i - 2); }
                });
            }
        });
        row("مستوى صوت الأذان: " + cfg.volume() + "%", new Runnable() {
            @Override public void run() {
                final String[] opts = {"25%", "50%", "75%", "100%"};
                choose("مستوى صوت الأذان", opts, cfg.volume() / 25 - 1, new Pick() {
                    @Override public void picked(int i) { cfg.setVolume((i + 1) * 25); }
                });
            }
        });
        row("رمز QR للإعدادات من الموبايل على الشاشة: " + (cfg.showQr() ? "ظاهر" : "مخفي"), new Runnable() {
            @Override public void run() { cfg.setShowQr(!cfg.showQr()); build(); }
        });
        row("مدة تثبيت التطبيق بالواجهة بعد تشغيل التلفزيون: " + KeepAliveService.SETTLE_NAMES[KeepAliveService.settleChoice(this)]
                + (KeepAliveService.settleChoice(this) == 0 ? " - " + KeepAliveService.settleMs(this) / 1000 + " ثانية" : "")
                + (KeepAliveService.lastShownSeconds(this) > 0 ? " • آخر تشغيل ظهر بعد " + KeepAliveService.lastShownSeconds(this) + " ثانية" : ""), new Runnable() {
            @Override public void run() {
                choose("مدة تثبيت التطبيق بعد التشغيل", KeepAliveService.SETTLE_NAMES, KeepAliveService.settleChoice(SettingsActivity.this), new Pick() {
                    @Override public void picked(int i) { KeepAliveService.setSettleChoice(SettingsActivity.this, i); build(); }
                });
            }
        });
        row("فحص تحديث التطبيق من الساعة الآن - النسخة الحالية " + Updater.myVersion(this), new Runnable() {
            @Override public void run() { preview("update"); }
        });
        row("فتح التطبيق عند تشغيل التلفزيون أو الرجوع من الاستعداد: " + (cfg.openOnBoot() ? "نعم" : "لا"), new Runnable() {
            @Override public void run() { cfg.setOpenOnBoot(!cfg.openOnBoot()); build(); }
        });
        row("▶ تجربة الأذان", new Runnable() {
            @Override public void run() { testAzan(PrayTimes.DHUHR); }
        });
        row("▶ تجربة الأذان الأول للفجر", new Runnable() {
            @Override public void run() { testAzan(Config.FAJR_FIRST); }
        });
        row("▶ تجربة الأذان الأول للجمعة", new Runnable() {
            @Override public void run() { testAzan(Config.JUMUA_FIRST); }
        });
        section("التشغيل التلقائي");
        row(check(SystemLinks.canOverlay(this)) + "صلاحية الظهور فوق التطبيقات (مطلوبة للفتح التلقائي)", new Runnable() {
            @Override public void run() {
                SystemLinks.openFirst(SettingsActivity.this, SystemLinks.overlay(SettingsActivity.this),
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), SystemLinks.appDetails(SettingsActivity.this));
            }
        });
        row(check(SystemLinks.batteryExempt(this)) + "استثناء التطبيق من توفير الطاقة", new Runnable() {
            @Override public void run() {
                SystemLinks.openFirst(SettingsActivity.this, SystemLinks.batteryRequest(SettingsActivity.this),
                        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), SystemLinks.appDetails(SettingsActivity.this));
            }
        });
        row("إدارة التشغيل التلقائي للتطبيقات (Autostart)", new Runnable() {
            @Override public void run() { SystemLinks.openAutostart(SettingsActivity.this); }
        });
        row(check(SystemLinks.homeEnabled(this)) + "استخدام التطبيق كواجهة رئيسية (يفتح دائماً عند التشغيل)", new Runnable() {
            @Override public void run() { toggleHome(); }
        });
        row("اختيار الواجهة الرئيسية الافتراضية", new Runnable() {
            @Override public void run() {
                SystemLinks.openFirst(SettingsActivity.this, new Intent(Settings.ACTION_HOME_SETTINGS),
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
            }
        });
        row("معلومات التطبيق والصلاحيات", new Runnable() {
            @Override public void run() {
                SystemLinks.openFirst(SettingsActivity.this, SystemLinks.appDetails(SettingsActivity.this));
            }
        });

        long lastBoot = getSharedPreferences("azan", MODE_PRIVATE).getLong("lastBoot", 0);
        TextView bootInfo = new TextView(this);
        bootInfo.setTextColor(0x99FFFFFF);
        bootInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * u);
        bootInfo.setPadding((int) (8 * u), (int) (6 * u), (int) (8 * u), 0);
        bootInfo.setText(lastBoot == 0 ? "لم يُسجَّل تشغيل تلقائي بعد (أعد تشغيل التلفزيون للتجربة)"
                : "آخر مرة اشتغل التطبيق تلقائياً مع التلفزيون: "
                + new java.text.SimpleDateFormat("d/M  h:mm a", new java.util.Locale("ar")).format(new java.util.Date(lastBoot)));
        list.addView(bootInfo);

        row("كل التطبيقات", new Runnable() {
            @Override public void run() {
                startActivity(new Intent(SettingsActivity.this, AppsActivity.class));
            }
        });
        row("فتح واجهة التلفزيون الأصلية", new Runnable() {
            @Override public void run() { openStockLauncher(); }
        });

        section("إعدادات نظام أندرويد");
        row("إعدادات أندرويد الأصلية (الكاملة)", new Runnable() {
            @Override public void run() {
                SystemLinks.openFirst(SettingsActivity.this,
                        SystemLinks.component("com.android.settings", "com.android.settings.Settings"),
                        new Intent(Settings.ACTION_SETTINGS));
            }
        });
        sys("إعدادات التلفزيون", Settings.ACTION_SETTINGS);
        sys("خيارات المطوّر", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        sys("التاريخ والوقت", Settings.ACTION_DATE_SETTINGS);
        sys("الشبكة والواي فاي", Settings.ACTION_WIFI_SETTINGS);
        sys("الصوت", Settings.ACTION_SOUND_SETTINGS);
        sys("الشاشة وشاشة التوقف", Settings.ACTION_DISPLAY_SETTINGS);
        sys("التطبيقات", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
        sys("التخزين", Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        sys("اللغة", Settings.ACTION_LOCALE_SETTINGS);
        sys("حول الجهاز", Settings.ACTION_DEVICE_INFO_SETTINGS);

        row("✓ حفظ والعودة", new Runnable() {
            @Override public void run() { finish(); }
        });

        TextView info = new TextView(this);
        info.setTextColor(0x99FFFFFF);
        info.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20 * u);
        info.setPadding(0, (int) (20 * u), 0, 0);
        File f = AzanFiles.find(this, false);
        String s = f != null ? "ملف الأذان: " + f.getAbsolutePath()
                : "لم يتم العثور على ملف الأذان. ضع ملف azan.mp3 في مجلد Download (واختيارياً fajr.mp3 لأذان الفجر).";
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            s += "\nتنبيه: صلاحية الظهور فوق التطبيقات غير مفعّلة، فقد لا يفتح التطبيق تلقائياً.";
        }
        info.setText(s);
        list.addView(info);

        cfg.setConfigured();
        Scheduler.scheduleNext(this);
        first.requestFocus();
    }

    private void editPrayer(final int prayer) {
        List<String> opts = new ArrayList<>();
        for (int m = -15; m <= 15; m++) opts.add(signed(m) + " دقيقة");
        if (prayer != PrayTimes.SUNRISE) opts.add(cfg.azanEnabledSetting(prayer) ? "إيقاف الأذان لهذه الصلاة" : "تفعيل الأذان لهذه الصلاة");
        choose("تعديل " + Config.PRAYER_NAMES[prayer], opts.toArray(new String[0]), cfg.offset(prayer) + 15, new Pick() {
            @Override public void picked(int i) {
                if (i == 31) cfg.setAzanEnabled(prayer, !cfg.azanEnabledSetting(prayer));
                else cfg.setOffset(prayer, i - 15);
            }
        });
    }

    /** Picks one of the given minute values; 0 is shown as "off". */
    private void minutes(String title, final int[] vals, int current, final Pick pick) {
        String[] opts = new String[vals.length];
        int cur = 0;
        for (int i = 0; i < vals.length; i++) {
            opts[i] = vals[i] == 0 ? "إيقاف" : vals[i] + " دقيقة";
            if (vals[i] == current) cur = i;
        }
        choose(title, opts, cur, new Pick() {
            @Override public void picked(int i) { pick.picked(vals[i]); }
        });
    }

    private void askAdmin() {
        Intent i = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.name(this));
        i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "حتى يطفي التطبيق الشاشة بين الصلوات ويشغلها قبل الأذان");
        if (!SystemLinks.open(this, i)) Toast.makeText(this, "تعذّر فتح صفحة الصلاحية", Toast.LENGTH_LONG).show();
    }

    private void preview(String what) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("preview", what);
        startActivity(i);
        finish();
    }

    private void testAzan(int azan) {
        android.content.Intent i = new android.content.Intent(this, MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(Scheduler.EXTRA_PRAYER, azan);
        long t = cfg.now();
        i.putExtra(Scheduler.EXTRA_TIME, t);
        startActivity(i);
        finish();
    }

    private void toggleHome() {
        final boolean on = !SystemLinks.homeEnabled(this);
        SystemLinks.setHomeEnabled(this, on);
        if (on) {
            Toast.makeText(this, "اختر \"مواقيت الأذان\" ثم \"دائماً\"", Toast.LENGTH_LONG).show();
            SystemLinks.openFirst(this, new Intent(Settings.ACTION_HOME_SETTINGS),
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        }
        build();
    }

    private void openStockLauncher() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || ri.activityInfo.name.endsWith("FallbackHome")) continue;
            Intent i = new Intent(home).setClassName(pkg, ri.activityInfo.name);
            if (SystemLinks.open(this, i)) return;
        }
        Toast.makeText(this, "لم يتم العثور على واجهة التلفزيون الأصلية", Toast.LENGTH_LONG).show();
    }

    private void sys(String label, final String action) {
        row(label, new Runnable() {
            @Override public void run() { SystemLinks.openFirst(SettingsActivity.this, new Intent(action)); }
        });
    }

    private static String check(boolean ok) { return ok ? "✓ " : "✗ "; }

    private void section(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(MainActivity.GOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * u);
        t.setPadding((int) (8 * u), (int) (26 * u), (int) (8 * u), (int) (6 * u));
        list.addView(t);
    }

    private static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    interface Pick { void picked(int i); }

    private void choose(String title, String[] items, int current, final Pick pick) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        pick.picked(which);
                        d.dismiss();
                        build();
                    }
                })
                .show();
    }

    private Button row(String label, final Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * u);
        btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        btn.setPadding((int) (24 * u), (int) (14 * u), (int) (24 * u), (int) (14 * u));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, Glass.panel(u, true, 16 * u));
        sl.addState(new int[]{}, Glass.panel(u, false, 16 * u));
        btn.setBackground(sl);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, (int) (6 * u), 0, (int) (6 * u));
        list.addView(btn, lp);
        return btn;
    }

    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(14 * u);
        if (stroke != 0) g.setStroke((int) (3 * u), stroke);
        return g;
    }
}
