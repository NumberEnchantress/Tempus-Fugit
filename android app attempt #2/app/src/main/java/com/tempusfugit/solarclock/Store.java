package com.tempusfugit.solarclock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * SharedPreferences for the settings and the stored model (plan 6.4). No
 * coordinates are ever written: fixes are stored as UTC ms and target seconds.
 */
final class Store {
    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getSharedPreferences("tempus_fugit", Context.MODE_PRIVATE);
    }

    // ---- settings ----

    Settings settings() {
        Settings s = new Settings();
        s.instant = prefs.getBoolean("mode_instant", s.instant);
        s.xMinutes = prefs.getInt("x_minutes", s.xMinutes);
        s.trendAware = prefs.getBoolean("trend_aware_setting", s.trendAware);
        s.precisionSeconds = prefs.getBoolean("precision_seconds", s.precisionSeconds);
        s.analog = prefs.getBoolean("face_analog", s.analog);
        s.widgetAnalog = prefs.getBoolean("widget_face_analog", s.widgetAnalog);
        s.widgetSeconds = prefs.getBoolean("widget_seconds", s.widgetSeconds);
        s.freeze = prefs.getBoolean("freeze", s.freeze);
        s.pingFloorS = prefs.getInt("ping_frequency_s", s.pingFloorS);
        s.distanceBudgetM = prefs.getInt("distance_budget_m", s.distanceBudgetM);
        s.locationOn = prefs.getBoolean("location_on", s.locationOn);
        return s;
    }

    void saveSettings(Settings s) {
        prefs.edit()
                .putBoolean("mode_instant", s.instant)
                .putInt("x_minutes", s.xMinutes)
                .putBoolean("trend_aware_setting", s.trendAware)
                .putBoolean("precision_seconds", s.precisionSeconds)
                .putBoolean("face_analog", s.analog)
                .putBoolean("widget_face_analog", s.widgetAnalog)
                .putBoolean("widget_seconds", s.widgetSeconds)
                .putBoolean("freeze", s.freeze)
                .putInt("ping_frequency_s", s.pingFloorS)
                .putInt("distance_budget_m", s.distanceBudgetM)
                .putBoolean("location_on", s.locationOn)
                .apply();
    }

    // ---- model ----

    Model model() {
        Model m = new Model();
        m.hasAnchor = prefs.getBoolean("has_anchor", false);
        m.tA = prefs.getLong("t_a", 0);
        m.targetA = d("T_a", 0);
        m.vA = d("v_a", 0);
        m.gA = d("g_a", 0);
        m.tauA = d("tau_a", 0);
        m.xSeconds = d("X_seconds", 300);
        m.instant = prefs.getBoolean("mode", false);
        m.trendAware = prefs.getBoolean("trend_aware", false);
        m.lastShownMs = d("last_shown_ms", Double.NaN);
        m.intervalS = prefs.getInt("current_interval_s", 15);
        m.lastFixMs = prefs.getLong("last_fix_ms", Long.MIN_VALUE);
        m.trendSeeded = prefs.getBoolean("trend_seeded", false);
        m.vTrend = d("v_trend", 0);
        m.correctionUntilMs = prefs.getLong("correction_until_ms", Long.MIN_VALUE);
        String fixes = prefs.getString("recent_fixes", "");
        for (String pair : fixes.split(";")) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            try {
                m.fixes.add(new double[] {Long.parseLong(pair.substring(0, colon)),
                        Double.parseDouble(pair.substring(colon + 1)), Double.NaN});
            } catch (NumberFormatException ignored) {
                // A damaged entry only costs one point of the rate window.
            }
        }
        return m;
    }

    void saveModel(Model m) {
        StringBuilder fixes = new StringBuilder();
        for (double[] f : m.fixes) {
            if (fixes.length() > 0) fixes.append(';');
            fixes.append((long) f[0]).append(':').append(String.format(Locale.ROOT, "%.4f", f[1]));
        }
        prefs.edit()
                .putBoolean("has_anchor", m.hasAnchor)
                .putLong("t_a", m.tA)
                .putLong("T_a", bits(m.targetA))
                .putLong("v_a", bits(m.vA))
                .putLong("g_a", bits(m.gA))
                .putLong("tau_a", bits(m.tauA))
                .putLong("X_seconds", bits(m.xSeconds))
                .putBoolean("mode", m.instant)
                .putBoolean("trend_aware", m.trendAware)
                .putLong("last_shown_ms", bits(m.lastShownMs))
                .putInt("current_interval_s", m.intervalS)
                .putLong("last_fix_ms", m.lastFixMs)
                .putBoolean("trend_seeded", m.trendSeeded)
                .putLong("v_trend", bits(m.vTrend))
                .putLong("correction_until_ms", m.correctionUntilMs)
                .putString("recent_fixes", fixes.toString())
                .apply();
    }

    // ---- zone and location bookkeeping ----

    String zoneId() {
        return prefs.getString("zone_id", null);
    }

    void saveZone(String zoneId, double edgeM, String tile) {
        prefs.edit()
                .putString("zone_id", zoneId)
                .putLong("zone_edge_m", bits(edgeM))
                .putString("loaded_tile", tile)
                .apply();
    }

    String provider() {
        return prefs.getString("provider", null);
    }

    void saveProvider(String provider) {
        prefs.edit().putString("provider", provider).apply();
    }

    /** The user asked for widget seconds and is granting exact alarms in system settings. */
    boolean widgetSecondsPending() {
        return prefs.getBoolean("widget_seconds_pending", false);
    }

    void setWidgetSecondsPending(boolean pending) {
        prefs.edit().putBoolean("widget_seconds_pending", pending).apply();
    }

    private double d(String key, double fallback) {
        return prefs.contains(key) ? Double.longBitsToDouble(prefs.getLong(key, 0)) : fallback;
    }

    private static long bits(double v) {
        return Double.doubleToRawLongBits(v);
    }
}
