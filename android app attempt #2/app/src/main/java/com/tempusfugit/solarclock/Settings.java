package com.tempusfugit.solarclock;

/** User settings (plan section 5). Plain values; Store reads and writes them. */
public final class Settings {
    public static final int[] PING_STEPS_S = {1, 5, 10, 15, 30, 45, 60};
    public static final int MIN_X_MINUTES = 1, MAX_X_MINUTES = 60;
    public static final int MIN_BUDGET_M = 10, MAX_BUDGET_M = 10_000;
    /** The battery warning shows at this ping interval or faster. */
    public static final int BATTERY_WARNING_S = 10;

    public boolean instant = false;
    public int xMinutes = 5;
    public boolean trendAware = false;
    public boolean precisionSeconds = true;
    public boolean analog = true;
    public boolean widgetAnalog = true;
    public boolean widgetSeconds = false;
    public boolean freeze = false;
    public int pingFloorS = 15;
    public int distanceBudgetM = 100;
    public boolean locationOn = true;

    public double xSeconds() {
        return xMinutes * 60.0;
    }

    public static boolean validX(int minutes) {
        return minutes >= MIN_X_MINUTES && minutes <= MAX_X_MINUTES;
    }

    public static boolean validBudget(int meters) {
        return meters >= MIN_BUDGET_M && meters <= MAX_BUDGET_M;
    }

    public static boolean validPing(int seconds) {
        for (int s : PING_STEPS_S) if (s == seconds) return true;
        return false;
    }

    public static boolean batteryWarning(int pingFloorS) {
        return pingFloorS <= BATTERY_WARNING_S;
    }
}
