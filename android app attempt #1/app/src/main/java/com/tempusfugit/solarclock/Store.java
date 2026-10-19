package com.tempusfugit.solarclock;

import android.content.Context;
import android.content.SharedPreferences;

/** The three persisted values. Coordinates are never stored, only the offset. */
final class Store {
    private static final String OFFSET_SECONDS = "offset_seconds";
    private static final String FIX_TIME_MS = "fix_time_ms";
    private static final String LOCATION_ON = "location_on";

    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getSharedPreferences("solar_clock", Context.MODE_PRIVATE);
    }

    boolean hasOffset() {
        return prefs.contains(OFFSET_SECONDS);
    }

    long offsetSeconds() {
        return prefs.getLong(OFFSET_SECONDS, 0);
    }

    void setOffsetSeconds(long seconds) {
        prefs.edit().putLong(OFFSET_SECONDS, seconds).apply();
    }

    boolean hasFixTime() {
        return prefs.contains(FIX_TIME_MS);
    }

    long fixTimeMs() {
        return prefs.getLong(FIX_TIME_MS, 0);
    }

    void setFixTimeMs(long ms) {
        prefs.edit().putLong(FIX_TIME_MS, ms).apply();
    }

    boolean locationOn() {
        return prefs.getBoolean(LOCATION_ON, false);
    }

    void setLocationOn(boolean on) {
        prefs.edit().putBoolean(LOCATION_ON, on).apply();
    }
}
