package com.tempusfugit.solarclock;

import java.util.Locale;

/**
 * Pure functions for the longitude clock. No Android imports, so SolarMathCheck
 * can run this on a plain JVM.
 *
 * adjusted = UTC + λ / 15 hours, i.e. 240 s (240 000 ms) of clock per degree.
 */
public final class SolarMath {
    public static final long DAY_MS = 86_400_000L;
    public static final long MS_PER_DEGREE = 240_000L;
    /** A new fix must move the target by at least this much to be applied. */
    public static final long SIGNIFICANT_CHANGE_MS = 1_000L;

    static final char MINUS = '−';

    private SolarMath() {}

    /** Longitude normalised to [-180, 180). */
    public static double normalise(double longitude) {
        return ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    /**
     * Longitudes already in [-180, 180] are kept as they are, so that ±180 keep
     * their ±12 h meaning. Anything outside that range is normalised.
     */
    static double inRange(double longitude) {
        return longitude >= -180.0 && longitude <= 180.0 ? longitude : normalise(longitude);
    }

    /** Offset in whole seconds: λ × 240. West is negative. */
    public static long offsetSeconds(double longitude) {
        return Math.round(inRange(longitude) * 240.0);
    }

    /** Target offset T in milliseconds: λ × 240 000. */
    public static long targetMs(double longitude) {
        return Math.round(inRange(longitude) * MS_PER_DEGREE);
    }

    /** Offset in milliseconds rounded to whole seconds, as it is persisted. */
    public static long secondsOf(long offsetMs) {
        return Math.round(offsetMs / 1000.0);
    }

    /** The longitude that produces an offset (the inverse of targetMs). */
    public static double longitudeOf(long offsetMs) {
        return offsetMs / (double) MS_PER_DEGREE;
    }

    public static boolean isSignificantChange(long currentMs, long newMs) {
        return Math.abs(newMs - currentMs) >= SIGNIFICANT_CHANGE_MS;
    }

    /** Time of day in [0, 86 400 000) for an absolute millisecond count. */
    public static long timeOfDayMs(long ms) {
        return Math.floorMod(ms, DAY_MS);
    }

    /** Shortest distance between two times of day, in milliseconds. */
    public static long timeOfDayDistance(long a, long b) {
        long d = Math.floorMod(a - b, DAY_MS);
        return Math.min(d, DAY_MS - d);
    }

    /** HH:MM:SS of the time of day. */
    public static String formatClock(long ms) {
        long s = timeOfDayMs(ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** HH:MM of the time of day. */
    public static String formatClockMinutes(long ms) {
        long m = timeOfDayMs(ms) / 60_000;
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    /** ±h:mm:ss, rounded to the nearest second. */
    public static String formatSignedDuration(long ms) {
        long s = Math.round(Math.abs(ms) / 1000.0);
        char sign = s == 0 ? '+' : ms < 0 ? MINUS : '+';
        return String.format(Locale.ROOT, "%c%d:%02d:%02d", sign, s / 3600, s / 60 % 60, s % 60);
    }

    /** Offset rounded to whole minutes, the granularity of a GMT±hh:mm zone. */
    public static long roundToMinutes(long offsetSeconds) {
        return Math.round(offsetSeconds / 60.0);
    }

    /** Java custom zone id for an offset, e.g. "GMT-04:40". ASCII sign, minute precision. */
    public static String gmtZoneId(long offsetSeconds) {
        return gmt(roundToMinutes(offsetSeconds), '-');
    }

    /** The same zone label for display, with a proper minus sign: "GMT−04:40". */
    public static String gmtLabel(long offsetSeconds) {
        return gmt(roundToMinutes(offsetSeconds), MINUS);
    }

    private static String gmt(long minutes, char minus) {
        long m = Math.abs(minutes);
        return String.format(Locale.ROOT, "GMT%c%02d:%02d", minutes < 0 ? minus : '+', m / 60, m % 60);
    }

    /** "adjusted = UTC + λ ÷ 15      λ = −70.00° → −4.6667 h" */
    public static String equation(long targetMs) {
        double longitude = longitudeOf(targetMs);
        return "adjusted = UTC + λ ÷ 15      λ = " + signed(longitude, 2, false) + "° → "
                + signed(longitude / 15.0, 4, true) + " h";
    }

    /** Fixed decimals with a real minus sign and no "−0.00". */
    static String signed(double value, int decimals, boolean plus) {
        String digits = String.format(Locale.ROOT, "%." + decimals + "f", Math.abs(value));
        boolean zero = digits.replace("0", "").equals(".");
        if (value < 0 && !zero) return MINUS + digits;
        return plus && !zero ? "+" + digits : digits;
    }
}
