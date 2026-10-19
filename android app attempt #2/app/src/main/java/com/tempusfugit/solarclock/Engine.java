package com.tempusfugit.solarclock;

import android.content.Context;
import android.content.res.AssetManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide state: the live model, settings, ping policy, location and the zone
 * cache. The main screen starts it on resume and stops it on pause; nothing
 * runs in the background. Every anchor is stored at once and pushed to the widget.
 */
final class Engine implements LocationSource.Listener {
    private static Engine instance;

    static Engine get(Context context) {
        if (instance == null) instance = new Engine(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService zoneThread = Executors.newSingleThreadExecutor();
    private final ZoneCache zones;
    private final LocationSource location;
    private final PingPolicy policy = new PingPolicy();
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    final Model model;
    Settings settings;
    private String zoneId;
    private boolean started;
    private boolean servicesOff;
    private long lastFixWallMs = Long.MIN_VALUE;
    /** "denied" or "revoked" when the permission is missing, else null. Not stored. */
    String permissionProblem;

    private Engine(Context context) {
        this.context = context;
        store = new Store(context);
        model = store.model();
        settings = store.settings();
        zoneId = store.zoneId();
        AssetManager assets = context.getAssets();
        zones = new ZoneCache(new ZoneLookup(name -> {
            try {
                // The build unpacks "x.bin.gz" assets to "x.bin"; the APK deflates them instead.
                return assets.open("tiles/" + name.replace(".bin.gz", ".bin"));
            } catch (FileNotFoundException e) {
                return null; // open water: no tile
            }
        }));
        location = new LocationSource(context, this);
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    String zoneId() {
        return zoneId;
    }

    boolean servicesOff() {
        return servicesOff;
    }

    boolean recentFix(long now) {
        return lastFixWallMs != Long.MIN_VALUE && now - lastFixWallMs < 3 * 60_000L;
    }

    LocationSource location() {
        return location;
    }

    // ---- lifecycle ----

    void start() {
        started = true;
        settings = store.settings();
        if (settings.locationOn && !location.hasPermission()) {
            // Revoked in system settings while we were away. Do not prompt from here.
            permissionProblem = "revoked";
            setLocationOn(false);
            return;
        }
        restartLocation();
    }

    void stop() {
        started = false;
        location.stop();
        model.renew(System.currentTimeMillis());
        store.saveModel(model);
    }

    private void restartLocation() {
        location.stop();
        if (!started || !settings.locationOn || settings.freeze || !location.hasPermission()) return;
        servicesOff = !location.servicesEnabled();
        int interval = policy.requestIntervalS(settings, location.approximateOnly());
        model.intervalS = interval;
        location.start(interval, policy.useNetwork());
        store.saveProvider(location.provider());
    }

    // ---- settings that act on the model ----

    void setLocationOn(boolean on) {
        settings = store.settings();
        boolean was = settings.locationOn;
        settings.locationOn = on;
        store.saveSettings(settings);
        if (was && !on) hold();
        restartLocation();
        changed();
    }

    void setFreeze(boolean freeze) {
        settings = store.settings();
        settings.freeze = freeze;
        store.saveSettings(settings);
        if (freeze) hold();
        restartLocation();
        changed();
    }

    /** Settings that apply at the next fix or the next location request. */
    void settingsChanged() {
        settings = store.settings();
        if (location.running()) {
            int interval = policy.requestIntervalS(settings, location.approximateOnly());
            model.intervalS = interval;
            location.update(interval, policy.useNetwork());
        }
        ClockWidget.refresh(context);
        changed();
    }

    /** Freeze or location off: the target holds at its value now. */
    private void hold() {
        model.hold(System.currentTimeMillis());
        save();
    }

    /** Forced correction (3.8): display = target now, then a fresh fix with no smoothing. */
    void correct() {
        model.correct(System.currentTimeMillis());
        save();
        settings = store.settings();
        if (settings.locationOn && !settings.freeze) location.requestFresh(this::onLocation);
        changed();
    }

    // ---- fixes ----

    @Override
    public void onLocation(Location l) {
        settings = store.settings();
        if (!settings.locationOn || settings.freeze) return;
        long now = System.currentTimeMillis();
        long t = now - Math.max(0, LocationSource.ageMs(l));
        double lat = l.getLatitude(), lon = l.getLongitude();
        double accM = l.hasAccuracy() ? l.getAccuracy() : 100;
        lastFixWallMs = now;
        servicesOff = false;

        model.onFix(t, lon, accM * SolarMath.secondsPerMeter(lat), settings);

        double platformSpeed = l.hasSpeed() && l.hasSpeedAccuracy()
                && l.getSpeedAccuracyMetersPerSecond() <= PingPolicy.TRUSTED_SPEED_ACCURACY
                ? l.getSpeed() : Double.NaN;
        boolean approx = location.approximateOnly();
        if (policy.onFix(t, lat, lon, accM, platformSpeed, settings, approx) && location.running()) {
            int interval = policy.requestIntervalS(settings, approx);
            model.intervalS = interval;
            location.update(interval, policy.useNetwork());
            store.saveProvider(location.provider());
        }
        save();
        lookUpZone(lat, lon);
        changed();
    }

    @Override
    public void onServicesChanged(boolean enabled) {
        servicesOff = !enabled;
        changed();
    }

    /** The zone cache decides whether a lookup is due; the tile read happens off the main thread. */
    private void lookUpZone(double lat, double lon) {
        zoneThread.execute(() -> {
            try {
                int before = zones.lookups();
                String id = zones.update(lat, lon);
                if (zones.lookups() == before) return;
                Log.d(LocationSource.TAG, "zone lookup " + zones.lookups() + ": " + id
                        + ", edge " + Math.round(zones.edgeM()) + " m");
                double edge = zones.edgeM();
                String tile = ZoneLookup.tileName(lat, lon);
                main.post(() -> {
                    store.saveZone(id, edge, tile);
                    if (id == null ? zoneId != null : !id.equals(zoneId)) {
                        zoneId = id;
                        ClockWidget.refresh(context);
                        changed();
                    }
                });
            } catch (IOException | RuntimeException e) {
                Log.w(LocationSource.TAG, "zone lookup failed", e);
            }
        });
    }

    private void save() {
        store.saveModel(model);
        ClockWidget.refresh(context);
    }
}
