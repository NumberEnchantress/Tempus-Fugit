# Solar Clock: build report

Built from `solar-clock-agent-brief.md`. Java, framework APIs only, `dependencies {}` empty.

## Build and run

```
./gradlew assembleDebug assembleRelease
```

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (109 KB)
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk` (23 KB, R8 minified, **unsigned**; sign with your own key before distributing)
- Math checks, no test library:
  ```
  javac -encoding UTF-8 -d out app/src/main/java/com/tempusfugit/solarclock/SolarMath.java app/src/main/java/com/tempusfugit/solarclock/SlewClock.java app/src/test/java/com/tempusfugit/solarclock/SolarMathCheck.java
  java -cp out com.tempusfugit.solarclock.SolarMathCheck
  ```

Toolchain used: Temurin JDK 21, Gradle 9.8.1 (wrapper), AGP 9.4.1, compileSdk/targetSdk 36, minSdk 30, build-tools 36.1.0. `local.properties` points at the SDK in `%LOCALAPPDATA%\Android\Sdk`.

## Files

| File | Role |
|---|---|
| `SolarMath.java` | Pure functions: offset, normalise, formatting, GMT zone ids, equation line |
| `SlewClock.java` | Monotonic anchor, D/T slew state machine. No Android imports, so it runs on a plain JVM |
| `LocationSource.java` | `LocationManager` wrapper: last-known (<10 min) snap, 60 s / 100 m updates, GPS then network, the 1 s rule |
| `Store.java` | SharedPreferences: `offset_seconds`, `fix_time_ms`, `location_on` only |
| `MainActivity.java` | Toggle, permission flow, tick loop, rendering |
| `SolarWidget.java` | `AppWidgetProvider`; `TextClock` set to `GMT±hh:mm` through `RemoteViews` |
| `SolarMathCheck.java` | Criteria 1–5 plus formatting, as a plain `main()` |

## Acceptance criteria

Emulator: Android 16 (API 36) Google APIs x86_64 image with WHPX acceleration. Mock location via `adb emu geo fix`. Device time zone was America/New_York (EDT).

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 1 | Math | **Pass** | `offsetSeconds(-70)=-16800`, `(180)=43200`, `(-180)=-43200`, `normalise(190)=-170` |
| 2 | Monotonic | **Pass** | 720 samples at 1 Hz across 3 s and 47 s westward drops: A non-decreasing, rate 0.500–1.024 |
| 3 | Convergence | **Pass**, with one edge case | Steps of 3, 20, 45 and 54 s fall under 500 ms in 108–294 s. See note 1 for a step of exactly 60 s |
| 4 | Snap | **Pass** | >60 s snaps in one tick; first target always snaps. On device: −70° → 0° snapped to UTC |
| 5 | Antimeridian | **Pass** | ±179.9999 differ by 48 ms. On device: 15:59:11 at +180°, then 16:02:15 three minutes later at −179.99° |
| 6 | Sampling | **Pass on emulator** | 1 callback, then none during ~4 min stationary; `removeUpdates after N callbacks` logged on every pause; GPS `ProviderRequest[OFF]` once backgrounded |
| 7 | Toggle off | **Pass** | `removeUpdates` logged, GPS request OFF, clock kept ticking at −179.99°, line reads `location off · last fix 00:02` |
| 8 | Permission denied | **Pass** | "Don't allow" → toggle back off, `location permission denied`, no crash, no second prompt |
| 9 | Manifest | **Pass** | `aapt2 dump permissions`: only `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (debug and release) |
| 10 | Dependencies | **Pass** | `releaseRuntimeClasspath`: no dependencies; release APK 23 KB; dex holds only app classes |
| 11 | Widget | **Pass** | Placed on the Pixel launcher: 23:15 at GMT−04:40 while the app showed 23:14–23:16; later 16:03 at GMT−12:00, same minute as the app |
| 12 | Resource use | **Pass** | Backgrounded: 0 alarms, no services, no jobs, no app wakelocks. The only wakelock entry was the system's 3 ms `*location*` delivery lock during a foreground callback |

Also checked on the emulator: a fresh last-known fix on resume snaps; state survives reinstall (toggle, offset, widget); dark mode follows the system; the 3 s westward move slewed (Δ −0:40:00 → −0:40:02 → −0:40:03) with no step.

### TextClock remotability (brief 3.6)

**Confirmed, so the preferred approach is used.** In AOSP `frameworks/base/core/java/android/widget/TextClock.java` the class is `@RemoteView` and `setTimeZone(String)` is `@RemotableViewMethod` on `android11-release` (API 30, the minSdk), `android12-release` and `main`. On the API 36 emulator the placed widget ticked in the custom `GMT-04:40` and `GMT-12:00` zones. `updatePeriodMillis` is `0`, so the widget never wakes the device. The fallback is not needed.

## Not verified on a physical device

- Criterion 6 on real GPS hardware. The emulator's GPS only produces a fix when `geo fix` is sent, so "no callbacks while stationary" is much stronger evidence on a phone that is sitting still with real GNSS noise. The brief asks for this; it still needs doing.
- Approximate-only permission (Android 12+ "Approximate" choice) and the Android 11 path, where the app uses the network provider because GPS needs fine access.
- Switching system location services off and on while the app is open (the provider reselect path).
- Third-party launchers other than the Pixel launcher.

## Deviations from the brief

1. **Slew integration.** The slew rule is integrated exactly, linear while clamped and then exponential, instead of a literal `D += rate × dt` Euler step. It is the same ODE: the two agree within 1 ms over 5 minutes at 1 Hz, and it cannot overshoot when ticks are far apart (screen off, slow frame). Snapping is unchanged.
2. **Convergence edge case (note 1).** With the ±0.5 clamp, a step of exactly 60 s spends 60 s in the linear phase and leaves 549 ms after 5 minutes. It drops under 500 ms at about 306 s. Steps up to about 54 s meet the 5-minute bound. That follows from the brief's own parameters; I did not change them.
3. **Tick timing.** The tick runs at the next second boundary of the adjusted time *or* the device time, whichever comes first. Following only the adjusted time made the dim device line lag by up to 1 s, which was visible on screen. In practice that is up to 2 redraws a second while visible, and none in the background.
4. **Theme.** `Theme.DeviceDefault.DayNight` (framework, API 29+) with the action bar removed. Plain `Theme.DeviceDefault` is always dark, which does not meet "follow the system light or dark setting".
5. **`fix_time_ms` updates on every fix,** including ones under the 1 s threshold that leave T unchanged. "last fix HH:MM" then means "position last confirmed", not "offset last changed". It is shown in device civil time.
6. **Before any fix,** the main time shows `--:--:--` rather than UTC, and the widget shows `--:--` / `no fix yet`. Showing UTC would look like a real longitude reading.
7. **Δ is the literal `adjustedOffset − civilOffset`,** so near the date line it can exceed ±12 h (+16:00:00 at 180° from New York, −7:59:58 just west of it). The displayed times are continuous; only Δ jumps. Wrapping Δ into ±12 h would be a one-line change if preferred.
8. **`android.builtInKotlin=false`** in `gradle.properties`. Without it, AGP 9 silently adds `kotlin-stdlib` to the runtime classpath. AGP 10 removes this option, so re-check `:app:dependencies` when upgrading.
9. **Extras that add no permissions:** tapping the widget opens the app; the app has a vector launcher icon and a static widget-picker preview; the location provider is reselected when GPS or network is switched on or off.
10. **targetSdk 36.** This is assumed to be the Play requirement as of Oct 2026 (API 36 for new apps and updates from 31 Aug 2026). API 37 platforms exist, so check the Play Console before uploading.
