package com.tempusfugit.solarclock;

/**
 * Plain geometry of the analog face (plan 6.1), shared by the app and the
 * widget. Angles are degrees clockwise from 12 o'clock. No Android imports.
 */
public final class FaceGeometry {
    public static final float HOUR_LENGTH = 0.50f, MINUTE_LENGTH = 0.74f, SECOND_LENGTH = 0.84f;
    public static final float TICK_INNER = 0.86f, TICK_OUTER = 0.95f, ARC_RADIUS = 0.995f;
    /** Indicator lines under the hub, as fractions of the radius below the centre. */
    public static final float[] LINE_OFFSETS = {0.30f, 0.43f, 0.56f};

    private FaceGeometry() {}

    /**
     * {hour, minute, second} hand angles for an absolute time in ms, floored to
     * the precision drawn (whole seconds, or whole minutes).
     */
    public static double[] handAngles(double ms, boolean seconds) {
        long tod = SolarMath.timeOfDayMs((long) Math.floor(ms));
        long unit = seconds ? 1000 : 60_000;
        double s = (tod - tod % unit) / 1000.0;
        return new double[] {s / 3600 % 12 * 30, s / 60 % 60 * 6, s % 60 * 6};
    }

    /** Sweep of the speed arc: proportional to the speed difference, a full circle at ±100%. */
    public static float arcSweep(double arcFraction) {
        return (float) (Math.max(-1, Math.min(1, arcFraction)) * 360);
    }

    public static float x(float cx, float length, double angle) {
        return cx + length * (float) Math.sin(Math.toRadians(angle));
    }

    public static float y(float cy, float length, double angle) {
        return cy - length * (float) Math.cos(Math.toRadians(angle));
    }
}
