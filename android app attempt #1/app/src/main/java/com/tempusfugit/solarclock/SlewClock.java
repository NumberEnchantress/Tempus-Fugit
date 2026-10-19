package com.tempusfugit.solarclock;

/**
 * The displayed offset D chasing the target offset T, on a monotonic time base.
 *
 * Callers pass in the clock readings (System.currentTimeMillis and
 * SystemClock.elapsedRealtimeNanos) so this class has no Android imports and can
 * be driven by a virtual clock in SolarMathCheck.
 *
 * Slew rule (brief 3.2):
 *   err = T − D
 *   |err| > 60 s  → D = T                       (snap)
 *   otherwise     → dD/dt = clamp(err / 60 s, ±0.5)
 *
 * The rule is integrated exactly rather than with a 1 s Euler step, so a long
 * gap between ticks (screen off, slow frame) cannot overshoot T.
 */
public final class SlewClock {
    public static final double SNAP_MS = 60_000.0;
    public static final double TAU_MS = 60_000.0;
    public static final double MAX_RATE = 0.5;

    private long anchorWallMs;
    private long anchorElapsedNs;
    private boolean anchored;

    private long lastTickNs;
    private boolean ticked;

    private boolean hasTarget;
    private long targetMs;
    private double displayedMs;

    /** Anchor UTC to the monotonic clock. Called on every resume. */
    public void anchor(long wallMs, long elapsedNs) {
        anchorWallMs = wallMs;
        anchorElapsedNs = elapsedNs;
        anchored = true;
    }

    /** U: UTC milliseconds derived from the monotonic clock. */
    public double utcMs(long elapsedNs) {
        if (!anchored) throw new IllegalStateException("anchor() first");
        return anchorWallMs + (elapsedNs - anchorElapsedNs) / 1e6;
    }

    /** Set T. With snap, D jumps to T at once; otherwise D slews on later ticks. */
    public void setTarget(long ms, boolean snap) {
        targetMs = ms;
        if (snap || !hasTarget) displayedMs = ms;
        hasTarget = true;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public long targetMs() {
        return targetMs;
    }

    /** D, in milliseconds (fractional while slewing). */
    public double displayedMs() {
        return displayedMs;
    }

    /** Advance D to the given monotonic time. */
    public void tick(long elapsedNs) {
        if (ticked && hasTarget) {
            double dt = (elapsedNs - lastTickNs) / 1e6;
            if (dt > 0) displayedMs = advance(displayedMs, targetMs, dt);
        }
        lastTickNs = elapsedNs;
        ticked = true;
    }

    /** A = U + D, floored to whole milliseconds. Non-decreasing between snaps. */
    public long adjustedMs(long elapsedNs) {
        return (long) Math.floor(utcMs(elapsedNs) + displayedMs);
    }

    /** Current dD/dt. The displayed clock runs at 1 + rate(). */
    public double rate() {
        if (!hasTarget) return 0;
        return rateFor(targetMs - displayedMs);
    }

    /** Real milliseconds until the displayed time reaches its next whole second. */
    public long msUntilNextSecond(long elapsedNs) {
        double a = utcMs(elapsedNs) + displayedMs;
        double remaining = 1000.0 - (a - 1000.0 * Math.floor(a / 1000.0));
        return (long) Math.ceil(remaining / (1.0 + rate()));
    }

    /** Real milliseconds until UTC (and so the device's civil time) reaches its next whole second. */
    public long msUntilNextUtcSecond(long elapsedNs) {
        double u = utcMs(elapsedNs);
        return (long) Math.ceil(1000.0 - (u - 1000.0 * Math.floor(u / 1000.0)));
    }

    static double rateFor(double err) {
        if (Math.abs(err) > SNAP_MS) return 0;
        return Math.max(-MAX_RATE, Math.min(MAX_RATE, err / TAU_MS));
    }

    /** D after dt milliseconds of the slew rule, starting from d. */
    static double advance(double d, long target, double dtMs) {
        double err = target - d;
        if (Math.abs(err) > SNAP_MS) return target;
        double sign = Math.signum(err);
        // Linear phase: the rate is clamped at ±0.5 until |err| falls to 0.5 × τ.
        double linearLimit = MAX_RATE * TAU_MS;
        if (Math.abs(err) > linearLimit) {
            double linearTime = (Math.abs(err) - linearLimit) / MAX_RATE;
            if (dtMs <= linearTime) return d + sign * MAX_RATE * dtMs;
            err = sign * linearLimit;
            dtMs -= linearTime;
        }
        // Exponential phase: err decays with time constant τ.
        return target - err * Math.exp(-dtMs / TAU_MS);
    }
}
