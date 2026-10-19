package com.tempusfugit.solarclock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.function.Consumer;

/**
 * Framework LocationManager wrapper (no Play Services). Registers with the
 * interval from PingPolicy and a minimum distance of zero, since the interval
 * already enforces the distance budget. Foreground only: Engine starts it from
 * onResume and stops it from onPause.
 */
final class LocationSource implements LocationListener {
    static final String TAG = "TempusFugit";
    static final long MAX_LAST_KNOWN_AGE_MS = 10 * 60_000L;

    interface Listener {
        void onLocation(Location location);

        /** Location services or a provider were switched on or off. */
        void onServicesChanged(boolean enabled);
    }

    private final Context context;
    private final LocationManager manager;
    private final Listener listener;

    private boolean running;
    private String provider;
    private int intervalS;
    private boolean wantNetwork;
    private int callbacks;
    private CancellationSignal fresh;

    LocationSource(Context context, Listener listener) {
        this.context = context;
        this.manager = context.getSystemService(LocationManager.class);
        this.listener = listener;
    }

    boolean hasPermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    boolean approximateOnly() {
        return !granted(Manifest.permission.ACCESS_FINE_LOCATION) && hasPermission();
    }

    boolean servicesEnabled() {
        return manager.isLocationEnabled();
    }

    boolean running() {
        return running;
    }

    String provider() {
        return provider;
    }

    /** Start: a recent last-known fix first, then updates at the interval. */
    void start(int intervalS, boolean network) {
        stop();
        if (!hasPermission()) return;
        callbacks = 0;
        Location last = lastKnown();
        if (last != null && ageMs(last) < MAX_LAST_KNOWN_AGE_MS) listener.onLocation(last);
        this.intervalS = intervalS;
        this.wantNetwork = network;
        subscribe();
    }

    /** Re-register only if the interval or the provider changed. */
    void update(int intervalS, boolean network) {
        if (!running) return;
        if (intervalS == this.intervalS && network == wantNetwork) return;
        this.intervalS = intervalS;
        this.wantNetwork = network;
        manager.removeUpdates(this);
        running = false;
        subscribe();
    }

    void stop() {
        if (running) {
            manager.removeUpdates(this);
            Log.d(TAG, "removeUpdates after " + callbacks + " callbacks");
        }
        if (fresh != null) fresh.cancel();
        fresh = null;
        running = false;
        provider = null;
    }

    /** One fresh fix right away, for the forced correction. */
    void requestFresh(Consumer<Location> consumer) {
        if (!hasPermission()) return;
        String p = chooseProvider(false);
        if (p == null) return;
        if (fresh != null) fresh.cancel();
        fresh = new CancellationSignal();
        try {
            manager.getCurrentLocation(p, fresh, context.getMainExecutor(), l -> {
                fresh = null;
                if (l != null) consumer.accept(l);
            });
            Log.d(TAG, "getCurrentLocation " + p);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "getCurrentLocation failed", e);
        }
    }

    private void subscribe() {
        String chosen = chooseProvider(wantNetwork);
        if (chosen == null) return;
        try {
            manager.requestLocationUpdates(chosen, intervalS * 1000L, 0f, this, Looper.getMainLooper());
            provider = chosen;
            running = true;
            Log.d(TAG, "requestLocationUpdates " + chosen + " every " + intervalS + " s"
                    + (wantNetwork ? " (wanted network)" : ""));
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "requestLocationUpdates failed", e);
        }
    }

    /** GPS while moving, network when still; with approximate access only, network first. */
    private String chooseProvider(boolean network) {
        String[] order = network || approximateOnly()
                ? new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}
                : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        // Android 11 refuses GPS_PROVIDER without fine access; 12+ coarsens it.
        boolean gpsAllowed = !approximateOnly() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        for (String p : order) {
            if (usable(p, gpsAllowed) && manager.isProviderEnabled(p)) return p;
        }
        // Nothing enabled: register anyway, so onProviderEnabled fires when it comes back.
        for (String p : order) if (usable(p, gpsAllowed)) return p;
        return null;
    }

    private boolean usable(String p, boolean gpsAllowed) {
        return manager.getAllProviders().contains(p) && (gpsAllowed || !LocationManager.GPS_PROVIDER.equals(p));
    }

    private Location lastKnown() {
        Location best = null;
        for (String p : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!manager.getAllProviders().contains(p)) continue;
            try {
                Location l = manager.getLastKnownLocation(p);
                if (l != null && (best == null || ageMs(l) < ageMs(best))) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Not allowed for this provider with the access granted.
            }
        }
        return best;
    }

    @Override
    public void onLocationChanged(Location location) {
        callbacks++;
        Log.d(TAG, "location callback #" + callbacks + " from " + location.getProvider()
                + " acc " + (location.hasAccuracy() ? location.getAccuracy() : -1)
                + " speed " + (location.hasSpeed() ? location.getSpeed() : -1)
                + " ± " + (location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : -1));
        listener.onLocation(location);
    }

    @Override
    public void onLocationChanged(List<Location> locations) {
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

    private void reselect() {
        listener.onServicesChanged(servicesEnabled());
        if (!running) return;
        String best = chooseProvider(wantNetwork);
        if (best != null && !best.equals(provider)) {
            manager.removeUpdates(this);
            running = false;
            subscribe();
        }
    }

    private boolean granted(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static long ageMs(Location location) {
        return (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L;
    }
}
