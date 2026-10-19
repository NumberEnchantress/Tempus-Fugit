package com.tempusfugit.solarclock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * Framework LocationManager wrapper (no Play Services) with the sampling rules
 * from brief 3.3. Runs only between start() and stop(), which the activity calls
 * from onResume and onPause.
 */
final class LocationSource implements LocationListener {
    static final String TAG = "SolarClock";
    static final long MIN_TIME_MS = 60_000;
    static final float MIN_DISTANCE_M = 100;
    static final long MAX_LAST_KNOWN_AGE_NS = 10L * 60 * 1_000_000_000L;

    interface Listener {
        /** The target offset changed by at least 1 s, or this is the first fix. */
        void onTarget(long targetMs, boolean snap);

        /** Every fix, including ones too small to change the target. */
        void onFix(long fixTimeMs);

        /** Location services were switched on or off in system settings. */
        void onServicesChanged(boolean enabled);
    }

    private final Context context;
    private final LocationManager manager;
    private final Listener listener;

    private boolean running;
    private String provider;
    private boolean hasTarget;
    private long targetMs;
    private int callbacks;

    LocationSource(Context context, Listener listener) {
        this.context = context;
        this.manager = context.getSystemService(LocationManager.class);
        this.listener = listener;
    }

    boolean hasPermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    boolean servicesEnabled() {
        return manager.isLocationEnabled();
    }

    /**
     * Start sampling. currentTargetMs is the offset the clock already shows, if
     * any, so that fixes under 1 s away from it are ignored.
     */
    void start(boolean hasCurrentTarget, long currentTargetMs) {
        stop();
        if (!hasPermission()) return;
        hasTarget = hasCurrentTarget;
        targetMs = currentTargetMs;
        callbacks = 0;

        // 1. A recent last-known fix sets T at once, with no slew.
        Location last = lastKnown();
        if (last != null && ageNs(last) < MAX_LAST_KNOWN_AGE_NS) deliver(last, true);

        // 2. Then listen for movement: at most once a minute, and only after 100 m.
        subscribe();
    }

    void stop() {
        if (running) {
            manager.removeUpdates(this);
            Log.d(TAG, "removeUpdates after " + callbacks + " callbacks");
        }
        running = false;
        provider = null;
    }

    private void subscribe() {
        String chosen = chooseProvider();
        if (chosen == null) return;
        try {
            manager.requestLocationUpdates(chosen, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper());
            provider = chosen;
            running = true;
            Log.d(TAG, "requestLocationUpdates " + chosen);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "requestLocationUpdates failed", e);
        }
    }

    /** GPS if it is enabled, otherwise network. With approximate access only, network first. */
    private String chooseProvider() {
        String[] order = preferredProviders();
        for (String p : order) {
            if (exists(p) && manager.isProviderEnabled(p)) return p;
        }
        // Nothing is enabled: register on the best existing provider anyway, so
        // onProviderEnabled fires when the user turns location back on.
        for (String p : order) if (exists(p)) return p;
        return null;
    }

    private String[] preferredProviders() {
        boolean fine = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        if (fine) return new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        // Android 11 refuses GPS_PROVIDER without fine access; 12+ coarsens it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER};
        }
        return new String[] {LocationManager.NETWORK_PROVIDER};
    }

    private Location lastKnown() {
        for (String p : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!exists(p)) continue;
            try {
                Location l = manager.getLastKnownLocation(p);
                if (l != null) return l;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Not allowed for this provider with the access granted; try the next.
            }
        }
        return null;
    }

    private void deliver(Location location, boolean initial) {
        listener.onFix(location.getTime());
        long t = SolarMath.targetMs(location.getLongitude());
        if (initial || !hasTarget) {
            hasTarget = true;
            targetMs = t;
            listener.onTarget(t, true);
        } else if (SolarMath.isSignificantChange(targetMs, t)) {
            targetMs = t;
            listener.onTarget(t, false);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        callbacks++;
        Log.d(TAG, "location callback #" + callbacks);
        deliver(location, false);
    }

    @Override
    public void onLocationChanged(List<Location> locations) {
        // Batched delivery: only the newest fix matters.
        if (!locations.isEmpty()) onLocationChanged(locations.get(locations.size() - 1));
    }

    @Override
    public void onProviderEnabled(String p) {
        reselect();
    }

    @Override
    public void onProviderDisabled(String p) {
        reselect();
    }

    /** Move to the better provider when one is switched on or off, without re-reading last-known. */
    private void reselect() {
        listener.onServicesChanged(servicesEnabled());
        if (!running) return;
        String best = chooseProvider();
        if (best != null && !best.equals(provider)) {
            manager.removeUpdates(this);
            running = false;
            subscribe();
        }
    }

    private boolean exists(String p) {
        return manager.getAllProviders().contains(p);
    }

    private boolean granted(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static long ageNs(Location location) {
        return SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
    }
}
