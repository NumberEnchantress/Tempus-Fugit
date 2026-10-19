package com.tempusfugit.solarclock;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.util.TimeZone;

/** The one screen: location toggle, adjusted time, equation line, civil comparison. */
public class MainActivity extends Activity implements LocationSource.Listener {
    private static final int REQUEST_LOCATION = 1;
    /** Land just past the second boundary rather than just before it. */
    private static final long TICK_SLACK_MS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SlewClock clock = new SlewClock();
    private final Runnable tick = this::onTick;

    private Store store;
    private LocationSource location;

    private Switch toggle;
    private TextView timeView;
    private TextView equationView;
    private TextView civilView;

    private boolean resumed;
    private boolean permissionDenied;
    private boolean servicesOff;
    private String pushedZone;

    private final CompoundButton.OnCheckedChangeListener onToggle = (button, on) -> onToggle(on);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        toggle = findViewById(R.id.location_switch);
        timeView = findViewById(R.id.adjusted_time);
        equationView = findViewById(R.id.equation);
        civilView = findViewById(R.id.civil);

        store = new Store(this);
        location = new LocationSource(this, this);
        if (store.hasOffset()) clock.setTarget(store.offsetSeconds() * 1000, true);
        toggle.setOnCheckedChangeListener(onToggle);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        clock.anchor(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos());

        boolean on = store.locationOn();
        if (on && !location.hasPermission()) {
            // Revoked in system settings while we were away. Do not prompt from here.
            on = false;
            store.setLocationOn(false);
            permissionDenied = true;
        }
        setToggle(on);
        if (on) startLocation();

        pushWidget(true);
        handler.removeCallbacks(tick);
        onTick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(tick);
        location.stop();
    }

    private void onToggle(boolean on) {
        permissionDenied = false;
        if (!on) {
            store.setLocationOn(false);
            location.stop();
        } else if (location.hasPermission()) {
            store.setLocationOn(true);
            startLocation();
        } else {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
        render();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_LOCATION) return;
        boolean granted = false;
        for (int r : results) granted |= r == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            // Fine or only approximate: either is good enough for a few seconds of error.
            store.setLocationOn(true);
            if (resumed) startLocation();
        } else {
            permissionDenied = true;
            store.setLocationOn(false);
            setToggle(false);
        }
        render();
    }

    private void startLocation() {
        servicesOff = !location.servicesEnabled();
        location.start(clock.hasTarget(), clock.targetMs());
    }

    @Override
    public void onTarget(long targetMs, boolean snap) {
        clock.tick(SystemClock.elapsedRealtimeNanos());
        clock.setTarget(targetMs, snap);
        store.setOffsetSeconds(SolarMath.secondsOf(targetMs));
        pushWidget(false);
        if (resumed) restartTicking();
    }

    @Override
    public void onFix(long fixTimeMs) {
        store.setFixTimeMs(fixTimeMs);
    }

    @Override
    public void onServicesChanged(boolean enabled) {
        servicesOff = !enabled;
        render();
    }

    private void setToggle(boolean on) {
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(on);
        toggle.setOnCheckedChangeListener(onToggle);
    }

    /** Update the widget when its whole-minute zone changes (or always, when forced). */
    private void pushWidget(boolean force) {
        String zone = store.hasOffset() ? SolarMath.gmtZoneId(store.offsetSeconds()) : "";
        if (force || !zone.equals(pushedZone)) {
            SolarWidget.push(this);
            pushedZone = zone;
        }
    }

    private void restartTicking() {
        handler.removeCallbacks(tick);
        onTick();
    }

    /**
     * Redraw at the next second boundary of the adjusted time, or of the device
     * time if that comes first. The two are out of phase by D's fractional
     * second, so following only the adjusted clock lets the device line lag by
     * up to a second.
     */
    private void onTick() {
        long now = render();
        long delay = Math.min(clock.msUntilNextSecond(now), clock.msUntilNextUtcSecond(now));
        handler.postDelayed(tick, delay + TICK_SLACK_MS);
    }

    /** Draw everything for the current instant. Returns the elapsedRealtimeNanos used. */
    private long render() {
        long now = SystemClock.elapsedRealtimeNanos();
        clock.tick(now);
        long utc = (long) Math.floor(clock.utcMs(now));
        int civilOffsetMs = TimeZone.getDefault().getOffset(utc);
        String civil = getString(R.string.device_time, SolarMath.formatClock(utc + civilOffsetMs),
                SolarMath.gmtLabel(civilOffsetMs / 1000));

        if (clock.hasTarget()) {
            timeView.setText(SolarMath.formatClock(clock.adjustedMs(now)));
            long displayedOffset = Math.round(clock.displayedMs());
            civil += getString(R.string.difference, SolarMath.formatSignedDuration(displayedOffset - civilOffsetMs));
        } else {
            timeView.setText(R.string.no_time);
        }
        civilView.setText(civil);
        equationView.setText(equationLine());
        return now;
    }

    private String equationLine() {
        if (permissionDenied) return getString(R.string.permission_denied);
        if (!toggle.isChecked()) {
            String last = store.hasFixTime()
                    ? getString(R.string.last_fix, SolarMath.formatClockMinutes(
                            store.fixTimeMs() + TimeZone.getDefault().getOffset(store.fixTimeMs())))
                    : getString(R.string.no_fix_yet);
            return getString(R.string.location_off, last);
        }
        if (!clock.hasTarget()) {
            return servicesOff ? getString(R.string.waiting_services_off) : getString(R.string.waiting);
        }
        return SolarMath.equation(clock.targetMs());
    }
}
