package com.tempusfugit.solarclock;

import java.util.ArrayList;

/**
 * Adaptive location interval (plan section 7.1):
 *   interval = clamp(distance_budget / s, floor, 60 s), in whole seconds
 * with a smoothed ground speed s, a dead band for noise, hysteresis on the
 * interval, and the GPS / network provider choice. Coordinates live only in
 * this object's memory; nothing here is stored.
 */
public final class PingPolicy {
    public static final int MAX_INTERVAL_S = 60;
    public static final int NETWORK_FLOOR_S = 30;
    /** Fixes worse than this are network positions. */
    public static final double NETWORK_ACCURACY_M = 50;
    public static final double SPEED_TAU_S = 30;
    public static final double DEAD_BAND_WINDOW_S = 60;
    public static final long STILL_FOR_NETWORK_MS = 300_000;
    public static final double GPS_RESUME_SPEED = 1.0;
    /** A platform speed is trusted when its reported accuracy is at most this. */
    public static final double TRUSTED_SPEED_ACCURACY = 1.0;

    /** {t ms, lat, lon, accuracy m} for the last minute or so. */
    private final ArrayList<double[]> fixes = new ArrayList<>();
    private double speed;
    private boolean hasSpeed;
    private int requestedS = -1;
    private int lastComputedS = -1;
    private boolean network;
    private long stillSinceMs = Long.MIN_VALUE;
    private boolean networkOnlyFixes;

    /** The interval for a ground speed, before hysteresis. */
    public static int intervalFor(double speed, int floorS, int budgetM) {
        double raw = speed > 0 ? budgetM / speed : MAX_INTERVAL_S;
        double clamped = Math.max(floorS, Math.min(MAX_INTERVAL_S, raw));
        // Whole seconds, rounded down so the distance budget is never exceeded.
        return (int) Math.floor(clamped + 1e-9);
    }

    public int floorS(Settings settings, boolean approximateOnly) {
        boolean networkOnly = approximateOnly || networkOnlyFixes;
        return networkOnly ? Math.max(settings.pingFloorS, NETWORK_FLOOR_S) : settings.pingFloorS;
    }

    /**
     * The interval to register now. Settings changed since the last fix apply
     * here, at the next location request.
     */
    public int requestIntervalS(Settings settings, boolean approximateOnly) {
        int floor = floorS(settings, approximateOnly);
        int base = requestedS < 0 ? floor : requestedS;
        requestedS = Math.max(floor, Math.min(MAX_INTERVAL_S, base));
        return requestedS;
    }

    public boolean useNetwork() {
        return network;
    }

    public double speed() {
        return speed;
    }

    /**
     * Feed an accepted fix. platformSpeed is NaN unless the platform's reading is
     * present and trusted. Returns true when the request must be re-registered
     * (interval or provider changed).
     */
    public boolean onFix(long t, double lat, double lon, double accuracyM, double platformSpeed,
                         Settings settings, boolean approximateOnly) {
        double[] prev = fixes.isEmpty() ? null : fixes.get(fixes.size() - 1);
        if (prev != null && t <= prev[0]) return false;
        networkOnlyFixes = accuracyM > NETWORK_ACCURACY_M;

        if (prev != null) {
            double dt = (t - prev[0]) / 1000.0;
            double computed = SolarMath.distanceMeters(prev[1], prev[2], lat, lon) / dt;
            // The platform's reading wins only when the positions agree with it,
            // within their accuracy: some sources report a confident speed of 0.
            double slack = 1 + (prev[3] + accuracyM) / dt;
            double observed = !Double.isNaN(platformSpeed) && Math.abs(platformSpeed - computed) <= slack
                    ? platformSpeed : computed;
            if (!hasSpeed) speed = observed;
            else speed += (observed - speed) * (1 - Math.exp(-dt / SPEED_TAU_S));
            hasSpeed = true;
        }
        fixes.add(new double[] {t, lat, lon, accuracyM});
        // Keep the last minute, and always the fix before the newest.
        while (fixes.size() > 2 && (t - fixes.get(1)[0]) / 1000.0 > DEAD_BAND_WINDOW_S) fixes.remove(0);

        // Dead band, the same rule as the rate's: movement within twice the accuracy is noise.
        if (fixes.size() >= 2) {
            double moved = 0, maxAcc = 0;
            for (double[] f : fixes) {
                moved = Math.max(moved, SolarMath.distanceMeters(f[1], f[2], lat, lon));
                maxAcc = Math.max(maxAcc, f[3]);
            }
            if (moved <= 2 * maxAcc) speed = 0;
        }

        boolean changed = false;
        int computed = intervalFor(speed, floorS(settings, approximateOnly), settings.distanceBudgetM);
        if (requestedS < 0) requestIntervalS(settings, approximateOnly);
        if (computed == lastComputedS && computed != requestedS) {
            requestedS = computed;
            changed = true;
        }
        lastComputedS = computed;

        if (speed == 0) {
            if (stillSinceMs == Long.MIN_VALUE) stillSinceMs = t;
            if (!network && t - stillSinceMs >= STILL_FOR_NETWORK_MS) {
                network = true;
                changed = true;
            }
        } else {
            stillSinceMs = Long.MIN_VALUE;
            if (network && speed > GPS_RESUME_SPEED) {
                network = false;
                changed = true;
            }
        }
        return changed;
    }
}
