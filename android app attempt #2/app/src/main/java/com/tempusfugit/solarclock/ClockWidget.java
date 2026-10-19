package com.tempusfugit.solarclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.widget.RemoteViews;

import java.time.ZoneId;

/**
 * Home-screen widget (8.3): the app's analog view drawn into a bitmap, laid out
 * landscape or stacked for the widget's shape, from the stored model alone.
 *
 * Cadence: minutes from AlarmManager.setInexactRepeating (no exact alarms, no
 * second hand), or seconds from setExactAndAllowWhileIdle. AlarmManager will not
 * schedule an app's alarm sooner than about 5 s ahead, so each seconds alarm
 * draws a short burst of per-second frames and re-arms the next one. Both use
 * the non-wakeup RTC clock and run only while a widget is placed.
 */
public class ClockWidget extends AppWidgetProvider {
    static final String ACTION_TICK = "com.tempusfugit.solarclock.WIDGET_TICK";
    static final long BURST_MS = 5_000;
    /** Below this face diameter the widget falls back to the digital layout. */
    static final float MIN_FACE_DP = 96;
    static final int DEFAULT_WIDTH_DP = 250, DEFAULT_HEIGHT_DP = 110;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final SparseArray<Bitmap> bitmaps = new SparseArray<>();
    private static final Canvas canvas = new Canvas();
    private static FaceRenderer renderer;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) draw(context, manager, id);
        schedule(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        draw(context, manager, id);
    }

    @Override
    public void onEnabled(Context context) {
        schedule(context);
    }

    @Override
    public void onDisabled(Context context) {
        context.getSystemService(AlarmManager.class).cancel(tickIntent(context));
        bitmaps.clear();
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) bitmaps.remove(id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_TICK.equals(action)) {
            tick(context);
        } else if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            refresh(context);
            schedule(context);
        }
    }

    private void tick(Context context) {
        if (!secondsActive(context)) {
            refresh(context);
            return;
        }
        // Re-arm first, then draw one frame per displayed second until it fires.
        long start = System.currentTimeMillis();
        armSeconds(context, start + BURST_MS);
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        Runnable frame = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                double shown = refresh(app);
                long delay = 1000 - Math.floorMod((long) Math.floor(shown), 1000L) + 3;
                if (now + delay >= start + BURST_MS - 50) {
                    pending.finish();
                } else {
                    handler.postDelayed(this, delay);
                }
            }
        };
        frame.run();
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, ClockWidget.class);
    }

    static boolean secondsActive(Context context) {
        return new Store(context).settings().widgetSeconds
                && context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    /** (Re)schedule the cadence; cancels everything when no widget is placed. */
    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent tick = tickIntent(context);
        alarms.cancel(tick);
        int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(component(context));
        if (ids.length == 0) return;
        long now = System.currentTimeMillis();
        if (secondsActive(context)) {
            armSeconds(context, now + BURST_MS);
            Log.d(LocationSource.TAG, "widget cadence: seconds");
        } else {
            // First tick at the next minute of the displayed time, then every minute.
            Model m = new Store(context).model();
            double shown = m.hasAnchor ? m.displayedMs(now) : now;
            long next = now + 60_000 - Math.floorMod((long) Math.floor(shown), 60_000L) + 500;
            alarms.setInexactRepeating(AlarmManager.RTC, next, 60_000, tick);
            Log.d(LocationSource.TAG, "widget cadence: minutes");
        }
    }

    private static void armSeconds(Context context, long at) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, at, tickIntent(context));
        } catch (SecurityException e) {
            // Exact alarms were revoked: fall back to minutes.
            Log.w(LocationSource.TAG, "exact alarm refused", e);
        }
    }

    private static PendingIntent tickIntent(Context context) {
        Intent i = new Intent(context, ClockWidget.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Redraw every placed widget now. Returns the shown time used, in ms. */
    static double refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(component(context));
        double shown = System.currentTimeMillis();
        for (int id : ids) shown = draw(context, manager, id);
        return shown;
    }

    private static double draw(Context context, AppWidgetManager manager, int id) {
        if (renderer == null) renderer = new FaceRenderer(context.getApplicationContext());
        Store store = new Store(context);
        Settings s = store.settings();
        boolean seconds = secondsActive(context);
        long now = System.currentTimeMillis();
        ClockFrame f = ClockFrame.compute(store.model(), now, seconds, store.zoneId(), ZoneId.systemDefault());

        Bundle o = manager.getAppWidgetOptions(id);
        int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0 || hDp <= 0) {
            wDp = DEFAULT_WIDTH_DP;
            hDp = DEFAULT_HEIGHT_DP;
        }
        float density = Math.min(context.getResources().getDisplayMetrics().density, 2.5f);
        int w = Math.max(1, Math.round(wDp * density)), h = Math.max(1, Math.round(hDp * density));
        Bitmap bmp = bitmaps.get(id);
        if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmaps.put(id, bmp);
        }
        bmp.eraseColor(0);
        canvas.setBitmap(bmp);
        layout(canvas, w, h, density, f, s.widgetAnalog, seconds);
        canvas.setBitmap(null);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
        views.setImageViewBitmap(R.id.widget_image, bmp);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(id, views);
        return f.hasFix ? f.shownMs : now;
    }

    /** Face left and readouts right when wide, stacked when tall, digital when too small. */
    static void layout(Canvas c, int w, int h, float density, ClockFrame f, boolean analog, boolean seconds) {
        float pad = 8 * density;
        float minFace = MIN_FACE_DP * density;
        if (analog) {
            if (w >= 1.3f * h) {
                float face = Math.min(h - 2 * pad, w - 3 * pad - 100 * density);
                if (face >= minFace) {
                    renderer.drawAnalog(c, pad + face / 2, h / 2f, face / 2, f, seconds);
                    float left = 2 * pad + face;
                    renderer.drawReadouts(c, left, pad, w - left - pad, h - 2 * pad, f);
                    return;
                }
            } else {
                float readH = Math.max(44 * density, Math.min(70 * density, h * 0.28f));
                float face = Math.min(w - 2 * pad, h - readH - 3 * pad);
                if (face >= minFace) {
                    renderer.drawAnalog(c, w / 2f, pad + face / 2, face / 2, f, seconds);
                    renderer.drawReadouts(c, pad, 2 * pad + face, w - 2 * pad, readH, f);
                    return;
                }
            }
        }
        renderer.drawReadouts(c, pad, pad, w - 2 * pad, h - 2 * pad, f);
    }
}
