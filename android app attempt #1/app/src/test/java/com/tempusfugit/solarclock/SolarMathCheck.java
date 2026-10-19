package com.tempusfugit.solarclock;

/**
 * Acceptance checks 1–5 (and formatting) as a plain main(), no test library.
 * Run with: javac -d out SolarMath.java SlewClock.java SolarMathCheck.java
 *           java -cp out com.tempusfugit.solarclock.SolarMathCheck
 */
public final class SolarMathCheck {
    private static int failures;
    private static int passes;

    public static void main(String[] args) {
        math();
        monotonic();
        convergence();
        snap();
        antimeridian();
        sampling();
        formatting();
        System.out.println();
        System.out.println(passes + " passed, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // 1. Math
    static void math() {
        section("1. math");
        check("offsetSeconds(-70.0) == -16800", SolarMath.offsetSeconds(-70.0) == -16800);
        check("offsetSeconds(180) == 43200", SolarMath.offsetSeconds(180) == 43200);
        check("offsetSeconds(-180) == -43200", SolarMath.offsetSeconds(-180) == -43200);
        check("normalise(190) == -170", SolarMath.normalise(190) == -170);
        check("normalise(-190) == 170", SolarMath.normalise(-190) == 170);
        check("normalise(180) == -180 (half-open range)", SolarMath.normalise(180) == -180);
        check("Boston -71.06 → -4 h 44 m 14 s",
                SolarMath.offsetSeconds(-71.06) == -(4 * 3600 + 44 * 60 + 14));
        check("targetMs(-70) == -16 800 000", SolarMath.targetMs(-70) == -16_800_000L);
    }

    // 2. Monotonic: a westward move of 3 s, sampled at 1 Hz on a virtual clock.
    static void monotonic() {
        section("2. monotonic");
        SlewClock c = new SlewClock();
        long ns = 5_000_000_000L;
        c.anchor(1_760_000_000_000L, ns);
        c.setTarget(-16_800_000L, true);
        c.tick(ns);

        // Includes the 3 s westward drop and a 47 s one that runs into the ±0.5 clamp.
        long[] targets = {-16_800_000L, -16_803_000L, -16_803_000L, -16_801_500L, -16_806_000L, -16_853_000L};
        double prevExact = Double.NaN;
        long prevShown = Long.MIN_VALUE;
        boolean ordered = true;
        boolean ratesOk = true;
        double minRate = Double.MAX_VALUE, maxRate = -Double.MAX_VALUE;
        int samples = 0;
        for (long target : targets) {
            c.setTarget(target, false);
            for (int s = 0; s < 120; s++) {
                ns += 1_000_000_000L;
                c.tick(ns);
                double exact = c.utcMs(ns) + c.displayedMs();
                long shown = c.adjustedMs(ns);
                if (!Double.isNaN(prevExact)) {
                    double rate = (exact - prevExact) / 1000.0;
                    minRate = Math.min(minRate, rate);
                    maxRate = Math.max(maxRate, rate);
                    if (rate < 0.5 - 1e-9 || rate > 1.5 + 1e-9) ratesOk = false;
                    if (shown < prevShown) ordered = false;
                }
                prevExact = exact;
                prevShown = shown;
                samples++;
            }
        }
        check("A non-decreasing over " + samples + " samples", ordered);
        check(String.format("per-second rate in [0.5, 1.5] (saw %.4f … %.4f)", minRate, maxRate), ratesOk);
        check("rate dipped below 1 on the westward move", minRate < 0.999);

        // The literal 1 s Euler step from the brief gives the same answer to within a few ms.
        double euler = -16_800_000.0;
        double exact = -16_800_000.0;
        for (int s = 0; s < 300; s++) {
            double err = -16_803_000.0 - euler;
            euler += Math.max(-0.5, Math.min(0.5, err / 60_000.0)) * 1000.0;
            exact = SlewClock.advance(exact, -16_803_000L, 1000);
        }
        check(String.format("exact slew matches 1 s Euler step within 50 ms (Δ %.1f ms)", exact - euler),
                Math.abs(exact - euler) < 50);
    }

    // 3. Convergence: |D − T| < 500 ms within 5 simulated minutes.
    static void convergence() {
        section("3. convergence");
        for (long step : new long[] {3_000, -3_000, 20_000, -45_000, 54_000}) {
            SlewClock c = new SlewClock();
            long ns = 0;
            c.anchor(0, ns);
            c.setTarget(0, true);
            c.tick(ns);
            c.setTarget(step, false);
            double worst = Double.NaN;
            int seconds = -1;
            for (int s = 1; s <= 300; s++) {
                ns += 1_000_000_000L;
                c.tick(ns);
                worst = Math.abs(c.displayedMs() - step);
                if (worst < 500 && seconds < 0) seconds = s;
            }
            check(String.format("step %+d ms: under 500 ms after %d s (%.1f ms left at 5 min)",
                    step, seconds, worst), seconds > 0 && worst < 500);
        }
        // Edge: with the clamp at ±0.5, an error of exactly 60 s needs ~306 s.
        SlewClock c = new SlewClock();
        c.anchor(0, 0);
        c.setTarget(0, true);
        c.tick(0);
        c.setTarget(60_000, false);
        c.tick(300_000_000_000L);
        System.out.printf("  note: a 60 s step leaves %.0f ms after 5 min (clamp limits the first 60 s)%n",
                Math.abs(c.displayedMs() - 60_000));
    }

    // 4. Snap: |T − D| > 60 s sets D = T in one step.
    static void snap() {
        section("4. snap");
        SlewClock c = new SlewClock();
        c.anchor(0, 0);
        c.setTarget(0, true);
        c.tick(0);
        c.setTarget(60_001, false);
        c.tick(1_000_000_000L);
        check("+60.001 s snaps in one tick", c.displayedMs() == 60_001);
        c.setTarget(-3_600_000, false);
        c.tick(2_000_000_000L);
        check("−1 h snaps in one tick", c.displayedMs() == -3_600_000);
        c.setTarget(-3_600_000 + 60_000, false);
        c.tick(3_000_000_000L);
        check("exactly 60 s slews rather than snaps", c.displayedMs() != -3_540_000 && c.displayedMs() > -3_600_000);

        SlewClock first = new SlewClock();
        first.anchor(0, 0);
        first.setTarget(12_345, false);
        check("first target snaps even without snap flag", first.displayedMs() == 12_345);
    }

    // 5. Antimeridian
    static void antimeridian() {
        section("5. antimeridian");
        long utc = 1_760_000_000_000L;
        long east = SolarMath.timeOfDayMs(utc + SolarMath.targetMs(179.9999));
        long west = SolarMath.timeOfDayMs(utc + SolarMath.targetMs(-179.9999));
        long d = SolarMath.timeOfDayDistance(east, west);
        check("time of day at ±179.9999 differs by " + d + " ms (< 1000)", d < 1000);
        check("±180 give the same time of day",
                SolarMath.timeOfDayMs(utc + SolarMath.targetMs(180)) == SolarMath.timeOfDayMs(utc + SolarMath.targetMs(-180)));
        check("formatClock(-1 ms) == 23:59:59", SolarMath.formatClock(-1).equals("23:59:59"));
    }

    // 6 (pure part). The 1 s rule for applying new fixes.
    static void sampling() {
        section("6. sampling rule (pure part)");
        check("999 ms change ignored", !SolarMath.isSignificantChange(-16_800_000, -16_800_999));
        check("1000 ms change applied", SolarMath.isSignificantChange(-16_800_000, -16_801_000));
        // 100 m east-west at 42° latitude moves the clock by about 0.29 s.
        double seconds = 240 * 0.1 / (111.32 * Math.cos(Math.toRadians(42)));
        check(String.format("100 m at 42° ≈ %.2f s of clock", seconds), seconds > 0.28 && seconds < 0.30);
    }

    static void formatting() {
        section("formatting");
        expect(SolarMath.equation(-16_800_000), "adjusted = UTC + λ ÷ 15      λ = −70.00° → −4.6667 h");
        expect(SolarMath.equation(0), "adjusted = UTC + λ ÷ 15      λ = 0.00° → 0.0000 h");
        expect(SolarMath.gmtZoneId(-16_800), "GMT-04:40");
        expect(SolarMath.gmtZoneId(-17_054), "GMT-04:44");
        expect(SolarMath.gmtZoneId(43_200), "GMT+12:00");
        expect(SolarMath.gmtLabel(-18_000), "GMT−05:00");
        expect(SolarMath.formatSignedDuration(1_200_000), "+0:20:00");
        expect(SolarMath.formatSignedDuration(-2_400_000), "−0:40:00");
        expect(SolarMath.formatSignedDuration(-17_054_400 + 18_000_000), "+0:15:46");
        expect(SolarMath.formatClock(-16_800_000), "19:20:00");
        expect(SolarMath.formatClockMinutes(45_296_000), "12:34");
        check("zone id parses as a Java custom zone",
                java.util.TimeZone.getTimeZone(SolarMath.gmtZoneId(-16_800)).getRawOffset() == -16_800_000);
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
