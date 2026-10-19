package com.tempusfugit.solarclock;

import java.util.Locale;

/**
 * Pure functions for the longitude clock. No Android imports, so the checks can
 * run this on a plain JVM.
 *
 * target offset T = λ × 240 s, east positive.
 */
public final class SolarMath {
    public static final long DAY_MS = 86_400_000L;
    public static final double DAY_S = 86_400.0;
    public static final double SECONDS_PER_DEGREE = 240.0;
    /** Metres per degree of longitude at the equator. */
    public static final double METERS_PER_DEGREE = 111_320.0;

    static final char MINUS = '−';

    private SolarMath() {}

    /** Longitude normalised to [-180, 180). */
    public static double normalise(double longitude) {
        return ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    /** Longitudes in [-180, 180] are kept, so that ±180 keep their ±12 h meaning. */
    static double inRange(double longitude) {
        return longitude >= -180.0 && longitude <= 180.0 ? longitude : normalise(longitude);
    }

    /** Target offset in seconds: λ × 240. West is negative. */
    public static double targetSeconds(double longitude) {
        return inRange(longitude) * SECONDS_PER_DEGREE;
    }

    /** The longitude for an offset, wrapped into [-180, 180]. */
    public static double longitudeOf(double offsetSeconds) {
        double lon = offsetSeconds / SECONDS_PER_DEGREE;
        return lon >= -180.0 && lon <= 180.0 ? lon : normalise(lon);
    }

    /**
     * Seconds of offset per metre of east–west ground at a latitude: 1 m ≈ 0.0029 s
     * at Boston. Clamped near the poles, where a metre spans many degrees.
     */
    public static double secondsPerMeter(double latitude) {
        double c = Math.max(0.01, Math.cos(Math.toRadians(latitude)));
        return SECONDS_PER_DEGREE / (METERS_PER_DEGREE * c);
    }

    /** Great-circle distance in metres. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(a)));
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

    /** HH:MM:SS, or HH:MM when seconds is false. */
    public static String formatClock(long ms, boolean seconds) {
        long s = timeOfDayMs(ms) / 1000;
        return seconds
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%02d:%02d", s / 3600, s / 60 % 60);
    }

    public static String placeholder(boolean seconds) {
        return seconds ? "--:--:--" : "--:--";
    }

    /** "adjusted = UTC + λ ÷ 15 · λ = −70.00° → −4.6667 h" */
    public static String equation(double targetSeconds) {
        double longitude = longitudeOf(targetSeconds);
        return "adjusted = UTC + λ ÷ 15 · λ = " + signed(longitude, 2, false) + "° → "
                + signed(longitude / 15.0, 4, true) + " h";
    }

    /** Fixed decimals with a real minus sign and no "−0.00". */
    static String signed(double value, int decimals, boolean plus) {
        String digits = String.format(Locale.ROOT, "%." + decimals + "f", Math.abs(value));
        boolean zero = digits.replace("0", "").equals(".");
        if (value < 0 && !zero) return MINUS + digits;
        return plus && !zero ? "+" + digits : digits;
    }

    /** "12 s", "4 min 5 s", "1 h 2 min". */
    public static String formatDuration(double seconds) {
        long s = Math.round(Math.abs(seconds));
        if (s < 60) return s + " s";
        if (s < 3600) return s % 60 == 0 ? s / 60 + " min" : s / 60 + " min " + s % 60 + " s";
        long m = Math.round(s / 60.0);
        return m % 60 == 0 ? m / 60 + " h" : m / 60 + " h " + m % 60 + " min";
    }
}
