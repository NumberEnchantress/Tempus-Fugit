# Tempus Fugit, attempt 2: build report

Built from `tempus-fugit-build-plan.md` (revision 8). Android app in this folder; the Firefox extension (stretch) in `../tempus-fugit-firefox/`.

## Build and run

```
./gradlew assembleDebug assembleRelease        # JAVA_HOME = %LOCALAPPDATA%\Android\jdk21
```

| Output | Size |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 1.92 MB |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 1.73 MB (R8, **unsigned**) |
| Zone dataset (`app/src/main/assets/tiles/`, 471 tiles) | 1.61 MB gzipped |

Plain-JVM checks (no test library):

```
javac -encoding UTF-8 -d out app/src/main/java/com/tempusfugit/solarclock/{SolarMath,Settings,Model,PingPolicy,ClockFrame,FaceGeometry,ZoneTile,ZoneLookup,ZoneCache}.java app/src/test/java/com/tempusfugit/solarclock/*.java
java -cp out com.tempusfugit.solarclock.ModelCheck                      # 51 passed, 0 failed
java -cp out com.tempusfugit.solarclock.ZoneCheck app/src/main/assets/tiles tools/all.bin.gz   # 46 passed, 0 failed
```

`tools/all.bin.gz` is the untiled reference file written by `tools/build_zone_tiles.py`. It is not packaged in the app.

Toolchain: Temurin JDK 21, Gradle 9.8.1, AGP 9.4.1, compile/target SDK 36, minSdk 30. Emulator: API 36 Google APIs image (`solar36`).

## Files

| File | Role |
|---|---|
| `SolarMath` | Offsets, normalise, formatting, equation line |
| `Settings` | Section 5 values, ranges, battery-warning rule |
| `Model` | Section 3: anchors, rate estimate (plain, trend-aware, dead band), closing law, renewal, guard, freeze, correction. No Android imports |
| `PingPolicy` | Section 7.1: smoothed ground speed, dead band, interval, hysteresis, GPS/network choice |
| `ClockFrame` | One drawing's worth of state: shown and target time, indicators, the three readouts |
| `FaceGeometry`, `FaceRenderer` | The one analog drawing function (pure geometry + Canvas), used by app and widget |
| `ZoneTile`, `ZoneLookup`, `ZoneCache` | Section 4.2: tiled point-in-polygon, one tile in memory, edge-distance cache |
| `Store` | SharedPreferences: settings and the stored model (6.4). No coordinates |
| `LocationSource` | `LocationManager` only, interval from `PingPolicy`, min distance 0, provider reselect, one-shot fix for the correction |
| `Engine` | App-wide state; runs only between the main screen's onResume and onPause |
| `MainActivity`, `FaceView`, `SettingsActivity` | Screens (8.2) |
| `ClockWidget` | Widget (8.3), minutes and seconds cadence |
| `tools/build_zone_tiles.py` | Builds the 100 m / 10° tiles from `timezones-now.geojson.zip` (stdlib + numpy) |

## Acceptance criteria

"Unit" = ModelCheck / ZoneCheck (and the extension's `tests/check.mjs`, which repeats the model and zone checks in JavaScript: 29 passed). "Device" = API 36 emulator.

| # | Result | Evidence |
|---|---|---|
| 1 | Pass | Unit |
| 2 | Pass | 48 ms apart; crossing 180° leaves no 24 h gap (targets are kept continuous with the display) |
| 3 | Pass | Unit: speed, gap and v exactly 1, 0, 0 |
| 4 | Pass | Unit, both modes: error 0.000 s from 5 to 10 min |
| 5 | Pass | 272.3 s; gap 0.500003 s at the ms-rounded settle time; monotone; closed form vs RK4 1.7e-10 s |
| 6 | Pass | 19.8 s |
| 7 | Pass | 299.998 s, never ≥ 300 for gaps up to 10⁷ s |
| 8 | Pass | 3.4e-8 s at 0.5001 s |
| 9 | Pass | Unit: jumps 30 s, then runs at 1 + v |
| 10 | Pass | Unit: 20°/h west, then stop: holds, never decreases, resumes. Device: display held "running 100% slow" while 4 min 49 s ahead |
| 11 | Pass | Unit (see finding 1 for large gaps) |
| 12 | Pass, with a caveat | Plain follows a turn within 30 s. Trend-aware is within 0.1% after 1 h when the motion starts with the fix stream, and follows 64% of a turn after 1 h (95% after 3 h). See finding 2 |
| 13 | Pass | 500 m noise, both options, rate exactly 0 at every fix for 10 min |
| 14 | Pass | Unit; device: confirmation text shown first, then the display jumped back 4 min 8 s to the target |
| 15 | Pass | Unit; device: warning `freeze on · offset may be out of date`, no location requests |
| 16 | Pass | Unit |
| 17 | Pass | 60, 60, 20, 15, 15 s (whole seconds, no step rounding, as you decided). Device at 28 m/s: 15 s, 14 callbacks in 200 s |
| 18 | Pass | 10 s, 280 m |
| 19 | Pass (unit); partly on device | Unit: network after 5 min still, 60 s. Device: the policy asked for network after 5 still minutes, but the emulator's network provider is disabled (`enabled=false`), so it correctly fell back to GPS |
| 20 | Pass | Unit: 25 changes in 200 fixes, never on consecutive fixes |
| 21 | Pass | Unit; extension options checked at 5 s |
| 22 | Pass | Unit: hand angles, ghost at the target (3° apart for a 30 s gap). Device: visible ghost hands |
| 23 | Pass | Device screenshot: readouts only |
| 24 | Pass (smooth on device, instant by unit) | Device: hands ran at 14× while closing a 4 min gap |
| 25 | Pass | Device: analog widget, no second hand, `HH:MM` |
| 26 | Partly | Device: seconds widget shows the second hand and `HH:MM:SS` and changes every second. Not compared side by side with the app |
| 27 | Pass | `dumpsys alarm`: `RTC` (non-waking) inexact repeating, 60 s, no exact alarm |
| 28 | Pass | Seconds: exact alarm `exactAllowReason=permission`; off: only the minute repeat remains |
| 29 | Pass | Without access: system "Alarms & reminders" screen opens, widget stays on minutes; Back without granting keeps minutes |
| 30 | Pass | Device: resized to one row, the widget draws the digital readouts |
| 31 | Not observed on device | Code: the interval is recomputed from current settings at each request (`PingPolicy.requestIntervalS`) |
| 32 | Pass | Device: `removeUpdates`, no callbacks during a 40 s drive, exact warning text |
| 33 | Pass | Device: "Don't allow" → toggle off, `permission denied`, no crash, no second prompt |
| 34 | Pass | `aapt2 dump permissions`: FINE, COARSE, SCHEDULE_EXACT_ALARM only |
| 35 | Pass | `releaseRuntimeClasspath`: No dependencies. Sizes above |
| 36 | Pass | Unit (and JS); device: New York → `America/New_York` |
| 37 | Pass | −4 h in July, −5 h in January; Phoenix −7 both |
| 38 | Pass | Unit; device: a 4 km drive with a 44 km edge distance did no lookup |
| 39 | Pass | Unit |
| 40 | Pass | 100 000 points, 0 disagreements (Java and JS) |
| 41 | Pass | `Pacific/Auckland` Ross Dependency polygon; 11 points either side of ±180 |
| 42 | Pass | `Store` writes no coordinates; About text carries the ODbL attribution |
| 43–47 | **Not verified** | See the extension README: the gate needs a real Firefox session |
| 48 | Pass | `web-ext lint`: 0 errors, 0 warnings, 0 notices |
| 49 | Pass | Only the two manifest keys mention Chrome |

## Findings

1. **The closing budget restarts at every anchor (intended).** Each fix (or renewal) re-anchors with the remaining gap, and the settle time of a large remaining gap is close to X again. So while fixes keep arriving, total catch-up can take longer than X. You confirmed this is intended, and the closing law stays as the plan writes it. Measured with X = 5 min and a still device:

   | Gap | One anchor | Renewals every 120 s | Fixes every 60 s | Fixes every 15 s |
   |---|---|---|---|---|
   | 30 s | 272 s | 175 s | 421 s | 290 s |
   | 200 s | 299 s | 258 s | 481 s | 363 s |
   | 1 h | 300 s | 363 s | 497 s | 437 s |

   The display is always within the tolerance by X after the last anchor.

2. **Criterion 12 and W = 60 min conflict.** An exponential average with a one-hour time constant reaches 63% of a new rate after one hour and 95% after three. The trend estimate is seeded with the first plain estimate, so it passes when the motion starts with the fixes. Starting from a standstill, it is at 63% after 1 h.

3. **The "now" dataset uses stand-in zone names.** Beijing returns `Asia/Manila`, Berlin `Europe/Paris`, Anadyr `Pacific/Fiji`. The offsets and DST are correct today, and the app never shows the name. Rules that later split apart would need a dataset refresh.

## Deviations from the plan

1. **Folder.** Built in `android app attempt #2` (a copy of the reusable parts) so attempt 1 stays intact, as you chose.
2. **Ping interval:** whole seconds between the floor and 60 s, not rounded to slider steps, as you chose (criterion 17 as written).
3. **Rate dead band** compares the spread of targets in the window with **twice** the largest reported accuracy: two fixes each within its accuracy of a still point can differ by up to twice that. The ground-speed dead band uses the same rule. Seconds per metre is `240 / (111 320 · cos lat)`, which is 0.0029 s at Boston.
4. **Platform speed** is used only when it agrees with the speed computed from successive positions (within their accuracy). The emulator reports a confident speed of 0 while moving.
5. **Recent fixes** are kept for the whole rate window (up to 120 s), not 60 s. Their accuracy is held in memory only, so after a restart those fixes count as having no accuracy.
6. **Forced correction:** anchors at once with g = 0, and the fresh fix it requests (if it arrives within 30 s) is also applied with no smoothing.
7. **Settle line** shows the time remaining (`A_k` minus time since the anchor), not the fixed `A_k`. Under a minute it shows seconds.
8. **Speed line** reads `running 14× real time` at 2× or more, instead of a percentage in the thousands.
9. **Fix time** is the fix's age-corrected time, clamped to the current anchor, so a 10-minute-old last-known fix is anchored when it was taken.
10. **Widget seconds:** AlarmManager refuses alarms less than 5 s ahead (`min_futurity=+5s` on the device). Each exact alarm therefore draws a 5 s burst of per-second frames (`goAsync`) and re-arms. Both cadences use the non-waking `RTC` clock, so the widget does not wake a sleeping phone. The plan's warning text is kept as written.
11. **Widget** is one bitmap drawn by `FaceRenderer`, with readouts drawn in the same bitmap. It also redraws on time and zone changes, and when exact-alarm access changes.
12. **Zone tiles** are stored as `.bin.gz` in source. AGP unpacks `.gz` assets at build time (the APK deflates them instead), so the reader accepts both.
13. **Indicator text** is drawn over the hands with a halo, so the hands never hide it.
14. **"location off"** is also shown when system location services are off. **"no fix yet"** shows when location is on but no fix has arrived in 3 minutes.
15. **Icon:** a generic clock (attempt 1 had a sun).

## Not verified on hardware

- Everything location-related ran on the emulator with `geo fix`; real GNSS noise, the network provider, and approximate-only permission are untested.
- Doze behaviour of both widget cadences; battery use.
- Launchers other than the Pixel launcher.
