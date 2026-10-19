package com.tempusfugit.solarclock;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Everything one drawing of the clock needs, computed from the model at one
 * instant: shown and target times, indicators (3.9) and the three readouts (1).
 * Shared by the app and the widget. No Android imports.
 */
public final class ClockFrame {
    public boolean hasFix;
    public boolean seconds;
    /** Shown (guarded) and target times, absolute ms; their time of day is what is drawn. */
    public double shownMs, targetMs;
    public double speed, gapS, settleS;
    public boolean holding;
    /** Speed arc as a signed fraction of a full turn, capped at ±1. */
    public double arc;
    public String tempus, local, system;
    public String speedLine, gapLine, settleLine;

    /**
     * Evaluate the model at UTC time now. Applies renewals and the guard to the
     * model passed in. zoneId is null when no zone is known (system fallback).
     */
    public static ClockFrame compute(Model m, long now, boolean seconds, String zoneId, ZoneId systemZone) {
        ClockFrame f = new ClockFrame();
        f.seconds = seconds;
        f.hasFix = m.hasAnchor;

        int systemOffset = systemZone.getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds();
        f.system = "system time - " + SolarMath.formatClock(now + systemOffset * 1000L, seconds);
        ZoneId zone = zone(zoneId);
        if (zone != null) {
            int off = zone.getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds();
            f.local = "local time - " + SolarMath.formatClock(now + off * 1000L, seconds);
        } else {
            f.local = "local time - " + SolarMath.formatClock(now + systemOffset * 1000L, seconds) + " (system)";
        }

        if (!m.hasAnchor) {
            f.tempus = "tempus fugit - " + SolarMath.placeholder(seconds);
            f.speed = 1;
            return f;
        }
        m.renew(now);
        f.shownMs = m.shownMs(now);
        f.holding = m.holding;
        double target = m.target(now);
        f.targetMs = now + target * 1000.0;
        f.speed = m.shownSpeed(now);
        f.gapS = target - (f.shownMs - now) / 1000.0;
        f.settleS = Math.abs(f.gapS) < Model.EPS ? 0 : m.settleRemaining(now);
        f.arc = Math.max(-1, Math.min(1, f.speed - 1));

        f.tempus = "tempus fugit - " + SolarMath.formatClock((long) Math.floor(f.shownMs), seconds);
        f.speedLine = speedText(f.speed);
        f.gapLine = gapText(f.gapS);
        f.settleLine = f.settleS > 0 ? settleText(f.settleS) : null;
        return f;
    }

    static ZoneId zone(String id) {
        if (id == null) return null;
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "running 8% slow", "running 3% fast", "on time" within ±0.5%. */
    public static String speedText(double speed) {
        double pct = (speed - 1) * 100;
        if (Math.abs(pct) < 0.5) return "on time";
        if (speed >= 2) return String.format(Locale.ROOT, "running %.0f× real time", speed);
        long p = Math.round(Math.abs(pct));
        return "running " + p + "% " + (pct < 0 ? "slow" : "fast");
    }

    /** "display 12 s behind target", "display 12 s ahead of target", "display on target". */
    public static String gapText(double gap) {
        if (Math.abs(gap) < Model.EPS) return "display on target";
        String d = SolarMath.formatDuration(gap);
        return gap > 0 ? "display " + d + " behind target" : "display " + d + " ahead of target";
    }

    /** "settles in about 3 min"; under a minute, in seconds. */
    public static String settleText(double seconds) {
        if (seconds < 59.5) return "settles in about " + Math.max(1, Math.round(seconds)) + " s";
        return "settles in about " + Math.round(seconds / 60) + " min";
    }
}
