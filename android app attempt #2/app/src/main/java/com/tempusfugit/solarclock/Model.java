package com.tempusfugit.solarclock;

import java.util.ArrayList;

/**
 * The clock model (plan section 3): anchors, an extrapolated target, a display
 * that closes the gap, renewals, and the monotonic guard.
 *
 * All times are UTC milliseconds passed in by the caller, and all offsets are
 * seconds. No Android imports, so ModelCheck runs it on a plain JVM and the
 * widget can evaluate it from the stored fields alone.
 *
 * Closed form from the anchor (t_a, T_a, v_a, g_a, τ_a), τ' = t − t_a:
 *   T(t) = T_a + v_a τ'      g(t) = g_a e^(−τ'/τ_a)      O(t) = T(t) − g(t)
 * Instant-mode anchors have g_a = 0, so the same formula covers both modes.
 */
public final class Model {
    /** Tolerance ε: a gap smaller than this is on target. */
    public static final double EPS = 0.5;
    /** Shape K of the settle curve. */
    public static final double K = 9.4;
    /** Time constant W of the trend-aware rate. */
    public static final double TREND_W_S = 3600;
    /** Fixes further apart than this (3 × the longest ping interval) give v = 0. */
    public static final double MAX_FIX_GAP_S = 180;
    public static final double MIN_WINDOW_S = 30;
    public static final long MAX_RENEWAL_MS = 120_000;
    /** A fix this soon after a forced correction is applied with no gap. */
    public static final long CORRECTION_FIX_MS = 30_000;
    /** Fix timestamps jitter; a fix this far past the window edge still counts. */
    static final double WINDOW_SLACK_S = 1.5;

    // Anchor.
    public boolean hasAnchor;
    public long tA;
    public double targetA, vA, gA, tauA;
    /** X, mode and rate option in force for the current anchor. */
    public double xSeconds = 300;
    public boolean instant;
    public boolean trendAware;

    /** Guard floor: the last displayed time in UTC ms, NaN when cleared. */
    public double lastShownMs = Double.NaN;
    /** The ping interval in force; the renewal period is twice this. */
    public int intervalS = 15;
    /** Fixes in the rate window: {t ms, T s, accuracy s (NaN when restored)}. */
    public final ArrayList<double[]> fixes = new ArrayList<>();
    public long lastFixMs = Long.MIN_VALUE;
    public boolean trendSeeded;
    public double vTrend;
    public long correctionUntilMs = Long.MIN_VALUE;

    /** True while the guard holds the display still. Not stored. */
    public boolean holding;

    // ---- settle law (3.3) ----

    /** Settle time X·u²/(u² + K²), u = |g| − ε. Zero at or below the tolerance. */
    public static double settleTime(double gap, double x) {
        double u = Math.abs(gap) - EPS;
        if (u <= 0) return 0;
        double u2 = u * u;
        return x * u2 / (u2 + K * K);
    }

    /** Time constant τ = settle / ln(|g| / ε), so the gap reaches ε at the settle time. */
    public static double tau(double gap, double x) {
        if (Math.abs(gap) <= EPS) return 0;
        return settleTime(gap, x) / Math.log(Math.abs(gap) / EPS);
    }

    // ---- closed form ----

    public double target(long t) {
        return targetA + vA * (t - tA) / 1000.0;
    }

    public double gap(long t) {
        if (gA == 0) return 0;
        double dt = Math.max(0, (t - tA) / 1000.0);
        return gA * Math.exp(-dt / tauA);
    }

    public double offset(long t) {
        return target(t) - gap(t);
    }

    /** 1 + dO/dt: how fast the display runs relative to real time. */
    public double speed(long t) {
        return 1 + vA + (gA == 0 ? 0 : gap(t) / tauA);
    }

    /** A_k, the settle time of the current anchor. */
    public double settleA() {
        return settleTime(gA, xSeconds);
    }

    /** Seconds left until the gap is inside the tolerance. */
    public double settleRemaining(long t) {
        if (gA == 0) return 0;
        return Math.max(0, settleA() - Math.max(0, (t - tA) / 1000.0));
    }

    // ---- anchors ----

    private void setAnchor(long t, double target, double v, double g) {
        if (Math.abs(g) <= EPS) g = 0;
        hasAnchor = true;
        tA = t;
        targetA = target;
        vA = v;
        gA = g;
        tauA = tau(g, xSeconds);
    }

    public long renewalMs() {
        return Math.min(MAX_RENEWAL_MS, 2_000L * intervalS);
    }

    /**
     * Apply every renewal due by time t (3.6): each S with no fix, re-anchor with
     * v = 0 at the old trajectory's target and gap. Call before evaluating.
     */
    public void renew(long t) {
        if (!hasAnchor) return;
        long s = renewalMs();
        while (t - tA >= s) {
            if (vA == 0 && gA == 0) {
                // Nothing left to change: skip straight to the last renewal.
                tA += (t - tA) / s * s;
                return;
            }
            long at = tA + s;
            setAnchor(at, target(at), 0, gap(at));
        }
    }

    /**
     * An accepted fix at UTC time t (3.1). accuracyS is the fix's reported
     * accuracy converted to seconds of offset.
     */
    public void onFix(long t, double longitude, double accuracyS, Settings settings) {
        if (hasAnchor) {
            renew(t);
            if (t < tA) t = tA;
        }
        double target = SolarMath.targetSeconds(longitude);
        double before = hasAnchor ? offset(t) : target;
        // Keep the target continuous with the display across the antimeridian.
        target += SolarMath.DAY_S * Math.round((before - target) / SolarMath.DAY_S);

        double v = estimateRate(t, target, accuracyS, settings.trendAware);

        boolean correction = t <= correctionUntilMs;
        boolean jump = !hasAnchor || settings.instant || correction;
        if (jump) lastShownMs = Double.NaN;
        if (correction) correctionUntilMs = Long.MIN_VALUE;
        instant = settings.instant;
        trendAware = settings.trendAware;
        xSeconds = settings.xSeconds();
        setAnchor(t, target, v, jump ? 0 : target - before);
    }

    /** Freeze or location off (3.7): the target holds at its value now, v = 0. */
    public void hold(long t) {
        if (!hasAnchor) return;
        renew(t);
        if (t < tA) t = tA;
        setAnchor(t, target(t), 0, gap(t));
    }

    /** Forced correction (3.8): display = target now, guard floor cleared. */
    public void correct(long t) {
        correctionUntilMs = t + CORRECTION_FIX_MS;
        if (!hasAnchor) return;
        renew(t);
        if (t < tA) t = tA;
        lastShownMs = Double.NaN;
        setAnchor(t, target(t), 0, 0);
    }

    // ---- rate estimate (3.2) ----

    double estimateRate(long t, double target, double accuracyS, boolean trend) {
        boolean stale = lastFixMs == Long.MIN_VALUE || (t - lastFixMs) / 1000.0 > MAX_FIX_GAP_S;
        double dt = stale ? 0 : (t - lastFixMs) / 1000.0;
        if (stale) {
            fixes.clear();
            trendSeeded = false;
        }
        lastFixMs = t;
        fixes.add(new double[] {t, target, accuracyS});

        double window = Math.max(MIN_WINDOW_S, 2.0 * intervalS);
        while (!fixes.isEmpty() && (t - fixes.get(0)[0]) / 1000.0 > window + WINDOW_SLACK_S) fixes.remove(0);

        int n = fixes.size();
        if (n < 2) return trend && trendSeeded ? vTrend : 0;

        double meanT = 0, meanY = 0, maxAcc = 0, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] f : fixes) {
            meanT += (f[0] - t) / 1000.0;
            meanY += f[1];
            if (f[2] > maxAcc) maxAcc = f[2];
            minY = Math.min(minY, f[1]);
            maxY = Math.max(maxY, f[1]);
        }
        meanT /= n;
        meanY /= n;
        double sxy = 0, sxx = 0;
        for (double[] f : fixes) {
            double x = (f[0] - t) / 1000.0 - meanT;
            sxy += x * (f[1] - meanY);
            sxx += x * x;
        }
        double slope = sxx > 0 ? sxy / sxx : 0;

        // Dead band: two fixes each within its accuracy of a still device can
        // differ by up to twice the accuracy, so movement below that is noise.
        boolean dead = maxY - minY <= 2 * maxAcc;
        double observed = dead ? 0 : slope;
        if (!trend) return observed;

        if (!trendSeeded) {
            vTrend = observed;
            trendSeeded = true;
        } else {
            vTrend += (observed - vTrend) * Math.min(1, dt / TREND_W_S);
        }
        return dead ? 0 : vTrend;
    }

    // ---- display and guard (3.5) ----

    /** Unguarded displayed time D(t) = UTC + O, in ms. */
    public double displayedMs(long t) {
        return t + offset(t) * 1000.0;
    }

    /** Guarded displayed time: never earlier than the last one shown. Updates the floor. */
    public double shownMs(long t) {
        double d = displayedMs(t);
        holding = !Double.isNaN(lastShownMs) && d < lastShownMs;
        if (holding) return lastShownMs;
        lastShownMs = d;
        return d;
    }

    /** Speed of the shown display: zero while the guard holds it. */
    public double shownSpeed(long t) {
        return holding ? 0 : speed(t);
    }

    public Model copy() {
        Model m = new Model();
        m.hasAnchor = hasAnchor;
        m.tA = tA;
        m.targetA = targetA;
        m.vA = vA;
        m.gA = gA;
        m.tauA = tauA;
        m.xSeconds = xSeconds;
        m.instant = instant;
        m.trendAware = trendAware;
        m.lastShownMs = lastShownMs;
        m.intervalS = intervalS;
        for (double[] f : fixes) m.fixes.add(f.clone());
        m.lastFixMs = lastFixMs;
        m.trendSeeded = trendSeeded;
        m.vTrend = vTrend;
        m.correctionUntilMs = correctionUntilMs;
        return m;
    }
}
