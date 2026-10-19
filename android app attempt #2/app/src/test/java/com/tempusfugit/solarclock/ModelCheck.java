package com.tempusfugit.solarclock;

import java.time.ZoneId;
import java.util.Random;

/**
 * Acceptance criteria 1–21 (model and ping policy) as a plain main(), no test
 * library. See REPORT.md for the command line.
 */
public final class ModelCheck {
    private static int failures;
    private static int passes;

    static final long T0 = 1_784_000_000_000L; // 2026-07-14, an arbitrary UTC instant
    static final double LAT = 42.36;           // Boston
    /** Metres per degree of latitude on the sphere SolarMath.distanceMeters uses. */
    static final double METERS_PER_DEGREE_LAT = 6_371_008.8 * Math.PI / 180;

    public static void main(String[] args) {
        c1();
        c2();
        c3();
        c4();
        c5to8();
        c9();
        c10();
        c11();
        c12();
        c13();
        c14();
        c15();
        c16();
        c17to20();
        c21();
        faceGeometry();
        renewalBudget();
        frameText();
        System.out.println();
        System.out.println(passes + " passed, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // ---- helpers ----

    static Settings settings(boolean instant) {
        Settings s = new Settings();
        s.instant = instant;
        return s;
    }

    /** Degrees of longitude per second for a ground speed due west (negative). */
    static double lonRate(double metersPerSecond, double lat) {
        return -metersPerSecond / (SolarMath.METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
    }

    static double accS(double meters, double lat) {
        return meters * SolarMath.secondsPerMeter(lat);
    }

    /** A model with one anchor at T0 whose display sits `gap` seconds behind a target of λ. */
    static Model withGap(double lon, double gap, boolean instant) {
        Model m = new Model();
        Settings s = settings(instant);
        m.onFix(T0, lon - gap / 240.0, 0, s);   // display starts here
        m.onFix(T0 + 1000, lon, 0, s);          // the target moves by `gap`
        return m;
    }

    // ---- criteria ----

    static void c1() {
        section("1. offsets");
        check("T(−70) == −16800", SolarMath.targetSeconds(-70) == -16800);
        check("T(180) == 43200", SolarMath.targetSeconds(180) == 43200);
        check("normalise(190) == −170", SolarMath.normalise(190) == -170);
    }

    static void c2() {
        section("2. antimeridian");
        long e = SolarMath.timeOfDayMs(T0 + Math.round(SolarMath.targetSeconds(179.9999) * 1000));
        long w = SolarMath.timeOfDayMs(T0 + Math.round(SolarMath.targetSeconds(-179.9999) * 1000));
        long d = SolarMath.timeOfDayDistance(e, w);
        check("time of day at ±179.9999 differs by " + d + " ms (< 1000)", d < 1000);
        // Crossing eastward over the line keeps the display continuous: no 24 h gap.
        Model m = new Model();
        Settings s = settings(false);
        m.onFix(T0, 179.999, 0, s);
        m.onFix(T0 + 15_000, -179.999, 0, s);
        check(String.format("crossing 180° leaves a gap of %.2f s, not a day", m.gA), Math.abs(m.gA) < 1);
    }

    static void c3() {
        section("3. stationary, noiseless");
        Model m = new Model();
        Settings s = settings(false);
        boolean ok = true;
        for (int i = 0; i < 30; i++) {
            long t = T0 + i * 60_000L;
            m.onFix(t, -71.06, 0, s);
            ok &= m.speed(t + 30_000) == 1.0 && m.gap(t + 30_000) == 0.0 && m.vA == 0.0;
        }
        check("speed exactly 1, gap exactly 0, v exactly 0 over 30 fixes", ok);
    }

    /** Drive due west at 100 km/h with fixes on the ping policy's schedule. */
    static void c4() {
        section("4. constant motion, 100 km/h west");
        for (boolean instant : new boolean[] {false, true}) {
            Model m = new Model();
            PingPolicy p = new PingPolicy();
            Settings s = settings(instant);
            double v = 100 / 3.6, rate = lonRate(v, LAT), lon0 = -71.06;
            long t = T0;
            int interval = p.requestIntervalS(s, false);
            m.intervalS = interval;
            double worst = 0;
            while (t <= T0 + 600_000) {
                double lon = lon0 + rate * (t - T0) / 1000.0;
                m.onFix(t, lon, accS(5, LAT), s);
                if (p.onFix(t, LAT, lon, 5, Double.NaN, s, false)) {
                    interval = p.requestIntervalS(s, false);
                    m.intervalS = interval;
                }
                for (long u = t; u < t + interval * 1000L; u += 250) {
                    if (u < T0 + 300_000) continue;
                    double trueTarget = SolarMath.targetSeconds(lon0 + rate * (u - T0) / 1000.0);
                    worst = Math.max(worst, Math.abs(m.offset(u) - trueTarget));
                }
                t += interval * 1000L;
            }
            check(String.format("%s: |display − true target| ≤ %.3f s from 5 to 10 min (< 1 s), interval %d s",
                    instant ? "instant" : "smooth", worst, interval), worst < 1);
        }
    }

    static void c5to8() {
        section("5–8. settle law, X = 5 min");
        double x = 300;
        double s30 = Model.settleTime(30, x);
        check(String.format("gap 30 s settles in %.1f s (about 272)", s30), Math.abs(s30 - 272) < 1);

        Model m = withGap(-71.06, 30, false);
        double atSettle = Math.abs(m.gap(m.tA + Math.round(s30 * 1000)));
        check(String.format("gap at the settle time is %.6f s (≤ 0.5, to the ms the time is rounded to)", atSettle), atSettle <= 0.5 + 1e-4);
        boolean monotone = true;
        double prev = Math.abs(m.gap(m.tA));
        for (long dt = 0; dt <= 600_000; dt += 100) {
            double g = Math.abs(m.gap(m.tA + dt));
            if (g > prev + 1e-12) monotone = false;
            prev = g;
        }
        check("gap never increases", monotone);

        // RK4 integration of dO/dt = v + (T − O)/τ against the closed form, with motion.
        m.vA = -0.0806;
        double o = m.offset(m.tA), h = 0.01, worst = 0;
        for (int i = 1; i <= 60_000; i++) {
            double t0 = (i - 1) * h;
            double k1 = f(m, t0, o), k2 = f(m, t0 + h / 2, o + h / 2 * k1);
            double k3 = f(m, t0 + h / 2, o + h / 2 * k2), k4 = f(m, t0 + h, o + h * k3);
            o += h / 6 * (k1 + 2 * k2 + 2 * k3 + k4);
            if (i % 100 == 0) worst = Math.max(worst, Math.abs(o - m.offset(m.tA + Math.round(i * h * 1000))));
        }
        check(String.format("closed form matches numeric integration within %.2e s (< 0.01)", worst), worst < 0.01);

        double s3 = Model.settleTime(3, x);
        check(String.format("6. gap 3 s settles in %.1f s (about 20)", s3), Math.abs(s3 - 20) < 1);

        double s1h = Model.settleTime(3600, x);
        boolean under = true;
        for (double g = 0.5; g < 1e7; g *= 1.01) under &= Model.settleTime(g, x) < x;
        check(String.format("7. gap 1 h settles in %.3f s: approaches 300, never exceeds it", s1h),
                s1h > 299.9 && s1h < 300 && under);

        double sEdge = Model.settleTime(0.5001, x);
        check(String.format("8. gap 0.5001 s settles in %.2e s: no minimum", sEdge), sEdge < 1e-3);

        System.out.println("  worked table:");
        for (double g : new double[] {3, 10, 30, 3600}) {
            double st = Model.settleTime(g, x), tau = Model.tau(g, x);
            System.out.printf("    gap %6.0f s  settle %6.1f s  τ %5.1f s  start speed change %+.0f%%%n",
                    g, st, tau, 100 * g / tau);
        }
    }

    static double f(Model m, double tauPrime, double o) {
        double target = m.targetA + m.vA * tauPrime;
        return m.vA + (target - o) / m.tauA;
    }

    static void c9() {
        section("9. instant mode");
        Model m = new Model();
        Settings s = settings(true);
        m.onFix(T0, -71.06, 0, s);
        double before = m.shownMs(T0 + 999);
        m.onFix(T0 + 1000, -71.06 + 30 / 240.0, 0, s);
        double after = m.shownMs(T0 + 1000);
        check(String.format("display jumps %.3f s at the fix (30 s + 1 ms of real time)", (after - before) / 1000),
                Math.abs((after - before) / 1000 - 30.001) < 1e-6);
        m.vA = -0.08;
        double d1 = m.shownMs(T0 + 11_000);
        check(String.format("then runs at 1 + v: %.4f s per s", (d1 - after) / 10_000), Math.abs((d1 - after) / 10_000 - 0.92) < 1e-9);
        Model back = new Model();
        back.onFix(T0, -71.06, 0, s);
        double b0 = back.shownMs(T0 + 500);
        back.onFix(T0 + 1000, -71.06 - 30 / 240.0, 0, s);
        check("instant jumps may go backward", back.shownMs(T0 + 1000) < b0);
    }

    /** Westward faster than 15°/h with the display ahead: the display holds, never decreases. */
    static void c10() {
        section("10. monotonic guard");
        Model m = new Model();
        Settings s = settings(false);
        double lon = 0;
        double lastShown = -Double.MAX_VALUE;
        boolean nonDecreasing = true, held = false, resumed = false;
        long heldSince = -1;
        // 20°/h west for 15 minutes, fixes every 15 s, then stop.
        double degPerS = -20.0 / 3600;
        for (long t = T0; t <= T0 + 3_600_000L; t += 1000) {
            if ((t - T0) % 15_000 == 0) {
                if (t <= T0 + 900_000) lon = degPerS * (t - T0) / 1000.0;
                m.onFix(t, lon, 0, s);
            }
            double shown = m.shownMs(t);
            if (shown < lastShown) nonDecreasing = false;
            if (m.holding) {
                held = true;
                if (heldSince < 0) heldSince = t;
            } else if (held && shown > lastShown) {
                resumed = true;
            }
            lastShown = shown;
        }
        check("display never decreases", nonDecreasing);
        check("display holds still while the target runs backward", held);
        check("display resumes once the target catches up", resumed && !m.holding);
    }

    static void c11() {
        section("11. stop with fixes stopped: renewal");
        Model m = new Model();
        Settings s = settings(false);
        m.intervalS = 60;
        double rate = lonRate(100 / 3.6, LAT);
        // Drive with 15 s fixes, then park; the last moving fix leaves a non-zero v.
        long t = T0;
        for (; t <= T0 + 120_000; t += 15_000) m.onFix(t, -71.06 + rate * (t - T0) / 1000.0, accS(5, LAT), s);
        long last = t - 15_000;
        double v = m.vA;
        long renewalAt = last + m.renewalMs();
        m.renew(renewalAt - 1);
        boolean before = m.tA == last;
        m.renew(renewalAt);
        check(String.format("no renewal before S = %d s; renewal at S with v = %.4f → %.1f", m.renewalMs() / 1000, v, m.vA),
                before && m.tA == renewalAt && m.vA == 0 && v != 0);
        double g = m.gA;
        long closeBy = renewalAt + Math.round(m.xSeconds * 1000);
        m.renew(closeBy);
        check(String.format("gap %.2f s at the renewal is ≤ 0.5 s within X (%.4f s)", g, Math.abs(m.gap(closeBy))),
                Math.abs(m.gap(closeBy)) <= 0.5);
    }

    static void c12() {
        section("12. rate estimate");
        // Plain: west at 100 km/h, then a turn to due north at the same speed.
        Model m = new Model();
        Settings s = settings(false);
        double rate = lonRate(100 / 3.6, LAT), vTrue = rate * 240;
        long t = T0;
        double lon = -71.06;
        for (; t <= T0 + 600_000; t += 15_000) m.onFix(t, lon + rate * (t - T0) / 1000.0, accS(5, LAT), s);
        double turnLon = lon + rate * (t - 15_000 - T0) / 1000.0;
        long turn = t - 15_000;
        double vBefore = m.vA;
        long followed = -1;
        for (; t <= turn + 120_000; t += 15_000) {
            m.onFix(t, turnLon, accS(5, LAT), s);
            if (followed < 0 && Math.abs(m.vA) < 0.1 * Math.abs(vTrue)) followed = t - turn;
        }
        check(String.format("plain: v %.4f before (true %.4f); after the turn v is within 10%% by %d s (≤ 30 s)",
                vBefore, vTrue, followed / 1000), Math.abs(vBefore - vTrue) < 1e-3 && followed > 0 && followed <= 30_000);

        // Trend-aware, from the start of steady motion.
        Model tr = new Model();
        Settings ts = settings(false);
        ts.trendAware = true;
        Random rnd = new Random(7);
        for (t = T0; t <= T0 + 3_600_000; t += 15_000) {
            double noise = (rnd.nextDouble() - 0.5) * 2 * 20; // ±20 m east–west jitter
            double fixLon = lon + rate * (t - T0) / 1000.0 + noise / (SolarMath.METERS_PER_DEGREE * Math.cos(Math.toRadians(LAT)));
            tr.onFix(t, fixLon, accS(20, LAT), ts);
        }
        double err = Math.abs(tr.vA - vTrue) / Math.abs(vTrue);
        check(String.format("trend-aware: after 1 h of steady motion v = %.4f, %.1f%% from true (< 5%%)",
                tr.vA, 100 * err), err < 0.05);

        // Trend-aware after a turn from due west to south-west: the westward rate drops to cos 45°.
        long turnT = t;
        double tLon = lon + rate * (t - 15_000 - T0) / 1000.0;
        double vStart = tr.vA, vEnd = vTrue * Math.cos(Math.PI / 4);
        double at1h = Double.NaN;
        for (; t <= turnT + 3 * 3_600_000L; t += 15_000) {
            tr.onFix(t, tLon + rate * Math.cos(Math.PI / 4) * (t - turnT) / 1000.0, accS(20, LAT), ts);
            if (Double.isNaN(at1h) && t - turnT >= 3_600_000) at1h = tr.vA;
        }
        double followedFrac = (at1h - vStart) / (vEnd - vStart);
        check(String.format("trend-aware after a turn: %.0f%% of the change followed after 1 h (1 − 1/e = 63%%), %.0f%% after 3 h",
                100 * followedFrac, 100 * (tr.vA - vStart) / (vEnd - vStart)), followedFrac > 0.6 && followedFrac < 0.7);

        // Trend-aware from a standstill: the EWMA with W = 60 min needs ~3 h to reach 5%.
        Model still = new Model();
        still.intervalS = 60;
        for (t = T0; t <= T0 + 600_000; t += 60_000) still.onFix(t, lon, accS(5, LAT), ts);
        still.intervalS = 15;
        long start = t;
        double at1 = Double.NaN, at3 = Double.NaN;
        for (; t <= start + 3 * 3_600_000L; t += 15_000) {
            still.onFix(t, lon + rate * (t - start) / 1000.0, accS(5, LAT), ts);
            if (Double.isNaN(at1) && t - start >= 3_600_000) at1 = still.vA;
            if (Double.isNaN(at3) && t - start >= 3 * 3_600_000L - 15_000) at3 = still.vA;
        }
        System.out.printf("  note: trend-aware starting from a standstill reaches %.0f%% of the true rate after 1 h, %.0f%% after 3 h%n",
                100 * at1 / vTrue, 100 * at3 / vTrue);
    }

    static void c13() {
        section("13. dead band, 500 m network noise");
        for (boolean trend : new boolean[] {false, true}) {
            Model m = new Model();
            PingPolicy p = new PingPolicy();
            Settings s = settings(false);
            s.trendAware = trend;
            Random rnd = new Random(42);
            boolean zero = true;
            long t = T0;
            m.intervalS = p.requestIntervalS(s, false);
            while (t <= T0 + 600_000) {
                // Uniform in a 500 m disc around a fixed point; accuracy reported as 500 m.
                double r = 500 * Math.sqrt(rnd.nextDouble()), a = rnd.nextDouble() * 2 * Math.PI;
                double lat = LAT + r * Math.sin(a) / 110_574.0;
                double lon = -71.06 + r * Math.cos(a) / (SolarMath.METERS_PER_DEGREE * Math.cos(Math.toRadians(LAT)));
                m.onFix(t, lon, accS(500, LAT), s);
                zero &= m.vA == 0.0;
                if (p.onFix(t, lat, lon, 500, Double.NaN, s, false)) m.intervalS = p.requestIntervalS(s, false);
                t += m.intervalS * 1000L;
            }
            check((trend ? "trend-aware" : "plain") + ": rate exactly 0 at every fix for 10 min", zero);
        }
    }

    static void c14() {
        section("14. forced correction");
        Model m = withGap(-71.06, -20, false); // display 20 s ahead
        long press = m.tA + 5_000;
        double before = m.shownMs(press - 1);
        m.correct(press);
        double after = m.shownMs(press);
        double target = press + m.target(press) * 1000;
        check(String.format("display equals the target at the press (%.3f ms)", after - target), Math.abs(after - target) < 1e-6);
        check(String.format("display went backward by %.1f s", (before - after) / 1000), after < before);
        // The fresh fix requested at the press is applied with no gap.
        m.onFix(press + 3_000, -71.06 + 0.001, 0, settings(false));
        check("the fresh fix after the press is applied with no smoothing", m.gA == 0 && m.correctionUntilMs == Long.MIN_VALUE);
    }

    static void c15() {
        section("15. freeze");
        Model m = new Model();
        Settings s = settings(false);
        double rate = lonRate(100 / 3.6, LAT);
        for (long t = T0; t <= T0 + 60_000; t += 15_000) m.onFix(t, -71.06 + rate * (t - T0) / 1000.0, 0, s);
        long freezeAt = T0 + 65_000;
        double target = m.target(freezeAt);
        m.hold(freezeAt);
        long later = freezeAt + 3_600_000;
        m.renew(later);
        double d0 = m.shownMs(later), d1 = m.shownMs(later + 10_000);
        check(String.format("target holds (%.4f → %.4f s), v = 0", target, m.target(later)),
                m.target(later) == target && m.vA == 0);
        check(String.format("clock keeps running at the last offset: %.4f s per s", (d1 - d0) / 10_000),
                Math.abs((d1 - d0) / 10_000 - 1) < 1e-12);
    }

    static void c16() {
        section("16. X slider applies at the next fix");
        Model m = withGap(-71.06, 30, false);
        Settings s = settings(false);
        s.xMinutes = 10;
        double before = m.xSeconds;
        m.onFix(m.tA + 15_000, -71.06 + 60 / 240.0, 0, s);
        check(String.format("X %.0f s before the fix, %.0f s after; settle %.1f s", before, m.xSeconds, m.settleA()),
                before == 300 && m.xSeconds == 600 && m.settleA() > 300);
    }

    static void c17to20() {
        section("17–20. ping policy");
        Settings s = new Settings();
        double[] speeds = {0, 1.4, 5, 14, 28};
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        int[] expect = {60, 60, 20, 15, 15};
        for (int i = 0; i < speeds.length; i++) {
            int iv = steadyInterval(s, speeds[i], 5);
            got.append(iv).append(i < speeds.length - 1 ? ", " : "");
            ok &= iv == expect[i];
        }
        check("17. default 15 s at 0, 1.4, 5, 14, 28 m/s → " + got + " s (60, 60, 20, 15, 15)", ok);

        Settings fast = new Settings();
        fast.pingFloorS = 10;
        int iv = steadyInterval(fast, 28, 5);
        check("18. slider 10 s at 28 m/s → " + iv + " s, " + iv * 28 + " m between fixes (≤ 280)", iv == 10 && iv * 28 <= 280);

        PingPolicy p = new PingPolicy();
        long t = T0;
        int interval = p.requestIntervalS(s, false);
        while (t <= T0 + 360_000) {
            if (p.onFix(t, LAT, -71.06, 5, Double.NaN, s, false)) interval = p.requestIntervalS(s, false);
            t += interval * 1000L;
        }
        check("19. still for 5 min: provider " + (p.useNetwork() ? "network" : "gps") + ", interval " + interval + " s",
                p.useNetwork() && interval == 60);

        // 20. Speed wandering across the 20 s edge (5 m/s); platform speed, so no smoothing lag hides it.
        PingPolicy osc = new PingPolicy();
        Settings os = new Settings();
        Random rnd = new Random(3);
        t = T0;
        interval = osc.requestIntervalS(os, false);
        double lat = LAT, lon = -71.06;
        int changes = 0, fixes = 0, lastChange = -10;
        boolean spaced = true;
        for (int i = 0; i < 200; i++) {
            double sp = rnd.nextBoolean() ? 4.9 : 5.1;
            lat += sp * interval / METERS_PER_DEGREE_LAT;
            boolean ch = osc.onFix(t, lat, lon, 5, sp, os, false);
            fixes++;
            if (ch) {
                if (fixes - lastChange < 2) spaced = false;
                lastChange = fixes;
                changes++;
                interval = osc.requestIntervalS(os, false);
            }
            t += interval * 1000L;
        }
        check("20. speed wandering across an edge: " + changes + " interval changes in " + fixes
                + " fixes, never on consecutive fixes", spaced && changes > 0);
    }

    /** Feed fixes at a constant ground speed until the requested interval settles. */
    static int steadyInterval(Settings s, double speed, double accuracyM) {
        PingPolicy p = new PingPolicy();
        long t = T0;
        int interval = p.requestIntervalS(s, false);
        double lat = LAT;
        for (int i = 0; i < 20; i++) {
            if (p.onFix(t, lat, -71.06, accuracyM, Double.NaN, s, false)) interval = p.requestIntervalS(s, false);
            lat += speed * interval / METERS_PER_DEGREE_LAT;
            t += interval * 1000L;
        }
        return interval;
    }

    static void c21() {
        section("21. battery warning");
        boolean ok = true;
        for (int st : Settings.PING_STEPS_S) ok &= Settings.batteryWarning(st) == (st <= 10);
        check("warning at 1, 5, 10 s; none at 15, 30, 45, 60 s", ok);
    }

    /** Not a criterion: how renewals and frequent fixes restart the closing budget for large gaps. */
    static void renewalBudget() {
        section("note: closing with re-anchoring");
        for (double gap : new double[] {30, 200, 3600}) {
            Model m = withGap(-71.06, gap, false);
            m.intervalS = 60;
            long start = m.tA;
            long t = start;
            while (Math.abs(m.gap(t)) > Model.EPS && t < start + 3_600_000) {
                t += 1000;
                m.renew(t);
            }
            Model f = withGap(-71.06, gap, false);
            f.intervalS = 60;
            long u = f.tA;
            while (Math.abs(f.gap(u)) > Model.EPS && u < f.tA + 3_600_000) {
                u += 1000;
                if ((u - start) % 60_000 == 0) f.onFix(u, -71.06, 0, settings(false));
            }
            Model q = withGap(-71.06, gap, false);
            long w = q.tA;
            while (Math.abs(q.gap(w)) > Model.EPS && w < q.tA + 3_600_000) {
                w += 1000;
                if ((w - start) % 15_000 == 0) q.onFix(w, -71.06, 0, settings(false));
            }
            System.out.printf("  gap %5.0f s: one anchor %.0f s; renewals every 120 s %d s; fixes every 60 s %d s; every 15 s %d s%n",
                    gap, Model.settleTime(gap, 300), (t - start) / 1000, (u - start) / 1000, (w - start) / 1000);
        }
    }

    static void faceGeometry() {
        section("22. analog face geometry");
        long noon = 12 * 3_600_000L + 30 * 60_000L + 45_000L; // 12:30:45
        double[] a = FaceGeometry.handAngles(noon, true);
        check(String.format("12:30:45 → hour %.3f°, minute %.1f°, second %.0f°", a[0], a[1], a[2]),
                Math.abs(a[0] - 15.375) < 1e-9 && Math.abs(a[1] - 184.5) < 1e-9 && a[2] == 270);
        double[] m = FaceGeometry.handAngles(noon, false);
        check("minutes precision floors to the minute", m[1] == 180 && m[2] == 0);
        Model model = withGap(-71.06, 30, false);
        ClockFrame f = ClockFrame.compute(model, model.tA, true, null, ZoneId.of("UTC"));
        double[] real = FaceGeometry.handAngles(f.shownMs, true), ghost = FaceGeometry.handAngles(f.targetMs, true);
        double diff = ((ghost[1] - real[1]) % 360 + 360) % 360;
        check(String.format("real hands at the displayed time, ghost at the target: minute hands %.2f° apart for a 30 s gap", diff),
                Math.abs(f.targetMs - f.shownMs - 30_000) < 1 && Math.abs(diff - 3) < 0.11);
    }

    static void frameText() {
        section("indicator text");
        expect(ClockFrame.speedText(0.92), "running 8% slow");
        expect(ClockFrame.speedText(1.03), "running 3% fast");
        expect(ClockFrame.speedText(1.004), "on time");
        expect(ClockFrame.gapText(12), "display 12 s behind target");
        expect(ClockFrame.gapText(-12), "display 12 s ahead of target");
        expect(ClockFrame.settleText(180), "settles in about 3 min");
        expect(SolarMath.equation(-16800), "adjusted = UTC + λ ÷ 15 · λ = −70.00° → −4.6667 h");
        Model m = new Model();
        ClockFrame f = ClockFrame.compute(m, T0, true, null, ZoneId.of("America/New_York"));
        expect(f.tempus, "tempus fugit - --:--:--");
        m.onFix(T0, -70, 0, settings(false));
        f = ClockFrame.compute(m, T0, true, "America/New_York", ZoneId.of("UTC"));
        System.out.println("  " + f.tempus + " | " + f.local + " | " + f.system);
        check("readout labels", f.tempus.startsWith("tempus fugit - ") && f.local.startsWith("local time - ")
                && f.system.startsWith("system time - "));
    }

    private static void section(String name) {
        System.out.println(name);
    }

    private static void expect(String actual, String expected) {
        check("\"" + actual + "\"", actual.equals(expected));
        if (!actual.equals(expected)) System.out.println("      expected \"" + expected + "\"");
    }

    private static void check(String what, boolean ok) {
        System.out.println("  " + (ok ? "PASS " : "FAIL ") + what);
        if (ok) passes++;
        else failures++;
    }
}
