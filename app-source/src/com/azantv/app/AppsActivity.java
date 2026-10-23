package com.azantv.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All installed apps, so the TV stays usable when this app is the home screen. */
public class AppsActivity extends Activity {
    private float u;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        u = getResources().getDisplayMetrics().widthPixels / 1366f;
        ScrollView sv = new ScrollView(this);
        sv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.setBackground(Glass.screen(this, 0x99000000));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (40 * u), (int) (24 * u), (int) (40 * u), (int) (24 * u));
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("كل التطبيقات");
        title.setTextColor(MainActivity.GOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40 * u);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, (int) (16 * u));
        col.addView(title);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        col.addView(grid);

        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !apps.containsKey(pkg)) apps.put(pkg, ri);
            }
        }
        final PackageManager fpm = pm;
        List<ResolveInfo> list = new ArrayList<>(apps.values());
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(fpm)).compareToIgnoreCase(String.valueOf(b.loadLabel(fpm)));
            }
        });

        int cell = (int) ((1366 - 80 - 6 * 16) * u / 6);
        View first = null;
        for (final ResolveInfo ri : list) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setPadding((int) (8 * u), (int) (14 * u), (int) (8 * u), (int) (14 * u));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_focused}, Glass.panel(u, true, 16 * u));
            sl.addState(new int[]{}, Glass.panel(u, false, 16 * u));
            tile.setBackground(sl);

            View icon = new View(this);
            Drawable d = ri.loadIcon(pm);
            icon.setBackground(d);
            tile.addView(icon, new LinearLayout.LayoutParams((int) (72 * u), (int) (72 * u)));
            TextView name = new TextView(this);
            name.setText(ri.loadLabel(pm));
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20 * u);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setPadding(0, (int) (8 * u), 0, 0);
            tile.addView(name);

            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = fpm.getLeanbackLaunchIntentForPackage(ri.activityInfo.packageName);
                    if (i == null) i = fpm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                    if (i != null) SystemLinks.open(AppsActivity.this, i);
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.setMargins((int) (8 * u), (int) (8 * u), (int) (8 * u), (int) (8 * u));
            grid.addView(tile, lp);
            if (first == null) first = tile;
        }
        setContentView(sv);
        if (first != null) first.requestFocus();
    }

    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(14 * u);
        if (stroke != 0) g.setStroke((int) (3 * u), stroke);
        return g;
    }
}
