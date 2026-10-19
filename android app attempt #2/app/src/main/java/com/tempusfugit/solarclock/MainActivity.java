package com.tempusfugit.solarclock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.time.ZoneId;

/** Main screen (8.2): location switch, face, three readouts, warning, equation, settings. */
public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 1;
    /** Land just past a boundary rather than just before it. */
    private static final long TICK_SLACK_MS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;
    private final Runnable onEngine = this::restartTicking;

    private Engine engine;
    private Switch toggle;
    private FaceView face;
    private TextView tempus, local, system, warning, equation;
    private boolean resumed;

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
        face = findViewById(R.id.face);
        tempus = findViewById(R.id.tempus);
        local = findViewById(R.id.local);
        system = findViewById(R.id.system);
        warning = findViewById(R.id.warning);
        equation = findViewById(R.id.equation);
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Analog face: about 60% of the screen width.
        int width = getResources().getDisplayMetrics().widthPixels;
        face.getLayoutParams().width = Math.round(width * 0.6f);

        engine = Engine.get(this);
        toggle.setOnCheckedChangeListener(onToggle);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        engine.addListener(onEngine);
        engine.start();
        setToggle(engine.settings.locationOn);
        applyFace();
        ClockWidget.schedule(this);
        restartTicking();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(tick);
        engine.removeListener(onEngine);
        engine.stop();
    }

    private void onToggle(boolean on) {
        engine.permissionProblem = null;
        if (!on) {
            engine.setLocationOn(false);
        } else if (engine.location().hasPermission()) {
            engine.setLocationOn(true);
        } else {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
        restartTicking();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_LOCATION) return;
        boolean granted = false;
        for (int r : results) granted |= r == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            // Fine or only approximate: approximate raises the ping floor to 30 s.
            engine.setLocationOn(true);
        } else {
            engine.permissionProblem = "denied";
            engine.setLocationOn(false);
            setToggle(false);
        }
        restartTicking();
    }

    private void setToggle(boolean on) {
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(on);
        toggle.setOnCheckedChangeListener(onToggle);
    }

    private void applyFace() {
        Settings s = engine.settings;
        face.setVisibility(s.analog ? View.VISIBLE : View.GONE);
        // Digital: the readouts only, with the tempus fugit line the largest text on screen.
        tempus.setAutoSizeTextTypeUniformWithConfiguration(20, s.analog ? 40 : 64, 1, TypedValue.COMPLEX_UNIT_SP);
        tempus.getLayoutParams().height = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, s.analog ? 56 : 88, getResources().getDisplayMetrics()));
        tempus.requestLayout();
    }

    private void restartTicking() {
        if (!resumed) return;
        setToggle(engine.settings.locationOn);
        applyFace();
        handler.removeCallbacks(tick);
        onTick();
    }

    /**
     * Redraw when the displayed time reaches its next boundary at the chosen
     * precision, or when device time does, whichever comes first.
     */
    private void onTick() {
        long now = System.currentTimeMillis();
        ClockFrame f = render(now);
        long unit = f.seconds ? 1000 : 60_000;
        long delay = unit - Math.floorMod(now, unit);
        if (f.hasFix) {
            double remaining = unit - (f.shownMs - unit * Math.floor(f.shownMs / unit));
            delay = f.speed > 0.05 ? Math.min(delay, (long) Math.ceil(remaining / f.speed)) : Math.min(delay, 1000);
        }
        handler.postDelayed(tick, Math.max(1, delay) + TICK_SLACK_MS);
    }

    private ClockFrame render(long now) {
        Settings s = engine.settings;
        ClockFrame f = ClockFrame.compute(engine.model, now, s.precisionSeconds, engine.zoneId(), ZoneId.systemDefault());
        tempus.setText(f.tempus);
        local.setText(f.local);
        system.setText(f.system);
        if (s.analog) face.show(f, s.precisionSeconds);

        String reason = warningReason(now);
        warning.setVisibility(reason == null ? View.GONE : View.VISIBLE);
        if (reason != null) warning.setText(getString(R.string.warning, reason));
        equation.setText(f.hasFix ? SolarMath.equation(engine.model.target(now)) : getString(R.string.waiting));
        return f;
    }

    /** Section 5.4: why the offset may be out of date, or null. */
    private String warningReason(long now) {
        Settings s = engine.settings;
        if ("revoked".equals(engine.permissionProblem)) return "permission revoked";
        if ("denied".equals(engine.permissionProblem)) return "permission denied";
        if (!s.locationOn) return "location off";
        if (s.freeze) return "freeze on";
        if (engine.servicesOff()) return "location off";
        if (!engine.recentFix(now)) return "no fix yet";
        return null;
    }
}
