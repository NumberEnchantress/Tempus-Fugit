package com.tempusfugit.solarclock;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Settings (section 5) and the About text with the data attribution. */
public class SettingsActivity extends Activity {
    private Engine engine;
    private Store store;
    private Settings s;

    private SeekBar x, ping;
    private TextView xLabel, pingLabel, pingWarning;
    private Switch trend, widgetSeconds, freeze;
    private EditText budget;
    private RadioGroup mode;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        engine = Engine.get(this);
        store = new Store(this);
        s = store.settings();

        mode = findViewById(R.id.mode);
        x = findViewById(R.id.x);
        xLabel = findViewById(R.id.x_label);
        trend = findViewById(R.id.trend);
        ping = findViewById(R.id.ping);
        pingLabel = findViewById(R.id.ping_label);
        pingWarning = findViewById(R.id.ping_warning);
        widgetSeconds = findViewById(R.id.widget_seconds);
        freeze = findViewById(R.id.freeze);
        budget = findViewById(R.id.budget);

        mode.check(s.instant ? R.id.mode_instant : R.id.mode_smooth);
        mode.setOnCheckedChangeListener((g, id) -> {
            s.instant = id == R.id.mode_instant;
            save();
            smoothOnly();
        });

        x.setProgress(s.xMinutes - Settings.MIN_X_MINUTES);
        x.setOnSeekBarChangeListener(new Slider(p -> {
            s.xMinutes = p + Settings.MIN_X_MINUTES;
            labels();
        }));

        trend.setChecked(s.trendAware);
        trend.setOnCheckedChangeListener((b, on) -> {
            s.trendAware = on;
            save();
        });

        findViewById(R.id.correct).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.correct_confirm)
                .setPositiveButton(R.string.correct_yes, (d, w) -> engine.correct())
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        radio(R.id.precision, s.precisionSeconds ? R.id.precision_seconds : R.id.precision_minutes,
                id -> s.precisionSeconds = id == R.id.precision_seconds);
        radio(R.id.face, s.analog ? R.id.face_analog : R.id.face_digital, id -> s.analog = id == R.id.face_analog);
        radio(R.id.widget_face, s.widgetAnalog ? R.id.widget_face_analog : R.id.widget_face_digital,
                id -> s.widgetAnalog = id == R.id.widget_face_analog);

        widgetSeconds.setChecked(s.widgetSeconds);
        widgetSeconds.setOnCheckedChangeListener(onWidgetSeconds);

        freeze.setChecked(s.freeze);
        freeze.setOnCheckedChangeListener((b, on) -> {
            s.freeze = on;
            engine.setFreeze(on);
        });

        ping.setProgress(stepIndex(s.pingFloorS));
        ping.setOnSeekBarChangeListener(new Slider(p -> {
            s.pingFloorS = Settings.PING_STEPS_S[p];
            labels();
        }));

        budget.setText(String.valueOf(s.distanceBudgetM));
        budget.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) {
                int m;
                try {
                    m = Integer.parseInt(e.toString());
                } catch (NumberFormatException ex) {
                    m = -1;
                }
                if (!Settings.validBudget(m)) {
                    // Rejected with an inline error and not saved.
                    budget.setError(getString(R.string.budget_error));
                    return;
                }
                budget.setError(null);
                s.distanceBudgetM = m;
                save();
            }
        });

        labels();
        smoothOnly();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the exact-alarm screen: seconds start only once access is granted.
        if (store.widgetSecondsPending()) {
            store.setWidgetSecondsPending(false);
            boolean granted = getSystemService(AlarmManager.class).canScheduleExactAlarms();
            s = store.settings();
            s.widgetSeconds = granted;
            store.saveSettings(s);
            setWidgetSecondsSwitch(granted);
            ClockWidget.schedule(this);
        }
    }

    private final CompoundButton.OnCheckedChangeListener onWidgetSeconds = (b, on) -> {
        if (!on) {
            s.widgetSeconds = false;
            save();
            ClockWidget.schedule(this);
            return;
        }
        setWidgetSecondsSwitch(false);
        new AlertDialog.Builder(this)
                .setTitle(R.string.widget_seconds_title)
                .setMessage(R.string.widget_seconds_body)
                .setPositiveButton(R.string.turn_on, (d, w) -> turnOnWidgetSeconds())
                .setNegativeButton(R.string.keep_minutes, null)
                .show();
    };

    private void turnOnWidgetSeconds() {
        AlarmManager alarms = getSystemService(AlarmManager.class);
        if (alarms.canScheduleExactAlarms()) {
            s.widgetSeconds = true;
            save();
            setWidgetSecondsSwitch(true);
            ClockWidget.schedule(this);
            return;
        }
        // Keep minutes until the user grants exact alarms in system settings.
        store.setWidgetSecondsPending(true);
        startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
    }

    private void setWidgetSecondsSwitch(boolean on) {
        widgetSeconds.setOnCheckedChangeListener(null);
        widgetSeconds.setChecked(on);
        widgetSeconds.setOnCheckedChangeListener(onWidgetSeconds);
    }

    private void labels() {
        xLabel.setText(getString(R.string.x_label, s.xMinutes));
        pingLabel.setText(getString(R.string.ping_label, s.pingFloorS));
        pingWarning.setVisibility(Settings.batteryWarning(s.pingFloorS) ? View.VISIBLE : View.GONE);
    }

    /** X and trend-aware apply to smooth mode only. */
    private void smoothOnly() {
        boolean smooth = !s.instant;
        x.setEnabled(smooth);
        xLabel.setEnabled(smooth);
        trend.setEnabled(smooth);
    }

    private void save() {
        // Settings saved while freeze or location changed elsewhere must not undo those.
        Settings current = store.settings();
        s.freeze = current.freeze;
        s.locationOn = current.locationOn;
        store.saveSettings(s);
        engine.settingsChanged();
    }

    private interface IntSetter {
        void set(int value);
    }

    private void radio(int group, int checked, IntSetter setter) {
        RadioGroup g = findViewById(group);
        g.check(checked);
        g.setOnCheckedChangeListener((rg, id) -> {
            setter.set(id);
            save();
        });
    }

    private static int stepIndex(int seconds) {
        for (int i = 0; i < Settings.PING_STEPS_S.length; i++) if (Settings.PING_STEPS_S[i] == seconds) return i;
        return 3;
    }

    /** A SeekBar listener that saves each change the user makes. */
    private final class Slider implements SeekBar.OnSeekBarChangeListener {
        private final IntSetter onChange;

        Slider(IntSetter onChange) {
            this.onChange = onChange;
        }

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            onChange.set(progress);
            if (fromUser) save();
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {}

        @Override
        public void onStopTrackingTouch(SeekBar bar) {}
    }
}
