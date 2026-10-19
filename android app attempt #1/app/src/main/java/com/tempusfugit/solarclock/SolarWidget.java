package com.tempusfugit.solarclock;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Home-screen widget. A TextClock pinned to a custom "GMT±hh:mm" zone built from
 * the stored target offset ticks by itself, so the widget needs no update alarms
 * (updatePeriodMillis is 0). TextClock.setTimeZone is a @RemotableViewMethod on
 * API 30 and later.
 */
public class SolarWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, build(context));
    }

    /** Push the stored offset to every placed widget. */
    static void push(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, SolarWidget.class));
        if (ids.length > 0) manager.updateAppWidget(ids, build(context));
    }

    static RemoteViews build(Context context) {
        Store store = new Store(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_solar);
        if (store.hasOffset()) {
            long seconds = store.offsetSeconds();
            views.setString(R.id.widget_time, "setTimeZone", SolarMath.gmtZoneId(seconds));
            views.setViewVisibility(R.id.widget_time, View.VISIBLE);
            views.setViewVisibility(R.id.widget_placeholder, View.GONE);
            views.setTextViewText(R.id.widget_label,
                    context.getString(R.string.widget_label, SolarMath.gmtLabel(seconds)));
        } else {
            views.setViewVisibility(R.id.widget_time, View.GONE);
            views.setViewVisibility(R.id.widget_placeholder, View.VISIBLE);
            views.setTextViewText(R.id.widget_label, context.getString(R.string.no_fix_yet));
        }
        Intent open = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE));
        return views;
    }
}
