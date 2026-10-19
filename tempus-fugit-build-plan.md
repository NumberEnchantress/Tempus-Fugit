# Tempus Fugit: Build Plan (second attempt, revision 8)

This plan is for an agent that will build a second attempt at Tempus Fugit. Read all of it before writing code. Section 13 lists what changed.

Two clients share one model:

1. **Android app** with a main screen, a settings screen, and a home-screen widget.
2. **Firefox extension**, a stretch deliverable. It is shelved if location cannot be made to work straightforwardly (section 7.9).

---

## 0. The idea

Tempus Fugit shows **longitude time**. Every degree of longitude moves the clock 4 minutes, so 15° is exactly one hour:

```
target offset  T = λ × 240 seconds      λ in degrees, east positive, Greenwich = 0
target time    UTC + T
```

The target ignores daylight saving time. The clock always advances at 1 second per second. Only the offset changes, and it changes as you travel.

The display may lag the target while it catches up. That lag is bounded: the display is within a tolerance of the target by the maximum smoothing time X after the last anchor (section 3).

---

## 1. Three readouts

Both clients show these lines, in this order:

| Label | Value | Prominence |
|---|---|---|
| `tempus fugit - HH:MM:SS` | Displayed time | Large. The main feature |
| `local time - HH:MM:SS` | Civil time in the location's zone, with its DST rules | Small |
| `system time - HH:MM:SS` | Civil time in the device's zone | Small, dimmer than local time |

- Labels are lowercase, with a hyphen, exactly as written.
- Precision is set in settings (section 5). The default is seconds (`HH:MM:SS`). Minutes show `HH:MM`.
- Before the first fix, the tempus fugit line shows `--:--:--`. Do not show UTC as a real longitude reading.

---

## 2. Terms

- **Anchor**: a point where the model is reset. An anchor is created by an accepted fix, a forced correction, or a renewal (section 3.6). Each anchor stores `t_a`, `T_a`, `v_a`, `g_a`, and `τ_a`.
- **Target `T(t)`**: the offset the clock should show now, extrapolated from the anchor: `T(t) = T_a + v_a × (t − t_a)`.
- **Displayed offset `O(t)`**: the offset actually shown.
- **Gap `g(t)`**: `T(t) − O(t)`. Positive means the display is behind the target.
- **Rate `v`**: how fast the target offset changes, in seconds per second. Driving west at 100 km/h is about −0.08.
- **Ground speed `s`**: how fast the device moves over the ground, in metres per second. Used for the ping interval (section 7.1), not for the clock.
- **Displayed time**: `D(t) = UTC(t) + O(t)`.
- **Speed**: `1 + dO/dt`. How fast the display runs relative to real time.
- **Tolerance `ε`**: 0.5 s. A gap smaller than this counts as on target.
- **Maximum smoothing time `X`**: from settings. Default 5 minutes, range 1–60.
- **Shape `K`**: 9.4 s, a fixed constant of the model. It sets where the settle time bends (section 3.3).

---

## 3. The model

Two modes, chosen in settings: **smooth** (default) and **instant**. Both use the same anchors, target, and guard.

### 3.1 Anchors

**Fix anchor.** When a fix is accepted at UTC time `t_k` with longitude `λ_k`:

1. `T_k = λ_k × 240`. This is the new target.
2. Estimate the rate `v_k` (section 3.2).
3. Compute the gap against the display just before the fix: `g_k = T_k − O(t_k)`. The display is continuous at the fix; the gap carries the change.
4. Compute the settle time and time constant (section 3.3). If `|g_k| ≤ ε`, set `g_k = 0`.
5. Store the anchor `(t_k, T_k, v_k, g_k, τ_k)`.

Accept every fix the platform delivers.

**Instant mode** changes step 3: the anchor's gap is zero, so the display jumps to the target at the fix.

### 3.2 Rate estimate

Two options, chosen in settings (section 5):

- **Plain** (default): a least-squares slope of the target over the most recent window of accepted fixes. The window is the longer of 30 s and twice the current ping interval, and needs at least two fixes. Use `v_k = 0` if there is no earlier fix in the window, or if the gap between fixes is more than 3 × the longest ping interval (60 s).
- **Trend-aware** (off by default): an exponentially weighted average, with a time constant `W = 60 minutes`, of the plain estimate. On each fix, with `Δt = t_k − t_{k−1}` and `v_obs` the plain estimate for this fix:
  ```
  v_k = v_{k−1} + (v_obs − v_{k−1}) × min(1, Δt / W)
  ```
  This tracks the trend over roughly the last hour and ignores short wobbles.

**Dead band (both options).** If the target moved less over the window than the fixes' reported accuracy, set `v_k = 0`. The accuracy is converted to seconds of offset with 1 m ≈ 0.0029 s (at Boston's latitude; scale by `1 / cos(latitude)` elsewhere). Without this, network noise of a few hundred metres produces a rate of several hundredths of a second per second, which makes the clock wander while the device is still.

**When to use each.**
- **Plain:** steady GPS use, such as walking or driving routes with turns and speed changes. It reacts within half a minute.
- **Trend-aware:** noisy positions that the dead band cannot fully absorb, or long straight, steady travel. It ignores jitter, but lags for about an hour after a turn or a change of speed.

### 3.3 Smooth closing law

The gap closes exponentially, with speed proportional to the gap:

```
dO/dt = v + g(t) / τ        equivalently   g(t) = g_k × e^(−τ'/τ_k)
```

`τ'` is the time since the anchor. The gap is to reach the tolerance `ε` in a settle time that depends on the gap:

```
settle time   T(g) = X × u² / (u² + K²)        u = |g| − ε,  K = 9.4 s,  ε = 0.5 s
time constant τ_k  = T(g_k) / ln( |g_k| / ε )
```

- **No minimum.** A gap just above the tolerance settles in almost no time. The settle time is zero at the tolerance and grows smoothly from there.
- **Asymptote at X.** Large gaps approach X and never exceed it. There is no cap and no kink.
- **Shape K.** The settle time is half the maximum when the gap is about 10 s. Small gaps settle slowly, with gentle hands, and large gaps approach X quickly. A larger K gives slower settling overall. Approved at K = 9.4 s.
- **Settle time** `A_k = T(g_k)` is shown on the face (section 6.1).

Closed form, so the clock and the widget can compute any time directly:

```
τ' = t − t_k
T(t) = T_k + v_k × τ'
g(t) = g_k × e^(−τ'/τ_k)
O(t) = T(t) − g(t)
speed(t) = 1 + v_k + g(t) / τ_k
```

**Worked examples, X = 5 min, K = 9.4 s, ε = 0.5 s.**

| Gap at anchor | Settle time | τ | Starting speed change |
|---|---|---|---|
| 3 s | 20 s | 11 s | +27% |
| 10 s | 2.5 min | 51 s | +20% |
| 30 s | 4.5 min | 66 s | +45% |
| 1 h | 5 min | 34 s | about 107× real time |

Small gaps settle slowly, with gentle hands. A 30 s gap takes several minutes. A 1 h gap settles within 5 min, starting at about 107× real time.

### 3.4 Instant mode

- At each fix anchor, the gap is zero, so the display jumps to the target.
- Between fixes, `O(t) = T_k + v_k × τ'`. Motion still moves the display continuously.
- Jumps may go backward. That is intended: instant mode shows changes as quickly as possible.

### 3.5 Monotonic guard (both modes)

The display never shows an earlier time than it last showed, except after an instant jump or a forced correction:

```
shown(t) = max( shown(t_last), UTC(t) + O(t) )
```

- Normal motion gives positive speed, so the guard does nothing.
- It binds only when the target moves west faster than 15°/h while the display is ahead of it. The display then holds still until the target catches up.

### 3.6 Stopping: no fixes, and the renewal

When the device is still, the ping policy (section 7.1) still asks for a fix every 60 s, so the target is corrected every minute and the gap closes within X of each correction. The renewal below is the safety net for when fixes stop altogether, for example when location is unavailable.

**The renewal.** If `t − t_a` reaches `S = 2 × the current ping interval` (at most 120 s) with no new fix, create a renewal anchor at `t_a + S`:

- `v = 0`.
- `T` is the old trajectory's value at that time, and `g` is the gap at that time.
- `τ` is recomputed from section 3.3.

Renewals repeat every `S` while no fix arrives. Each one restarts the closing budget from its own time.

**Accepted limit.** If fixes stop for a long time, the error grows with the target's rate, with no limit, until fixes return. Once they do, the catch-up is bounded by X. No further guardrail is planned.

### 3.7 Freeze and location off

- **Freeze** (setting, off by default): no fix anchors are created. The target holds at its last value with `v = 0`. Smooth mode still closes any remaining gap. Instant mode no longer jumps.
- **Location off** (toggle, or permission denied): same as freeze. Show the warning (section 5.4).

### 3.8 Forced correction

A button in settings, for when the model goes wrong:

1. One confirmation: "Set the clock to the target now? It may jump, including backwards."
2. Request a fresh fix immediately.
3. Create an anchor at the current time with `T = T_now`, `g = 0`, `v = 0`.
4. Clear the guard floor. The display may move backward. That is the purpose.

There is no automatic correction. The closing law already bounds catch-up at X.

### 3.9 Indicators

- **Speed:** `speed(t)` from section 3.3, shown as `running 8% slow`, `running 3% fast`, or `on time` within ±0.5%.
- **Gap:** `g(t)` in seconds, shown as `display 12 s behind target` or `display 12 s ahead of target`.
- **Settle:** `A_k` in minutes, shown as `settles in about 3 min`, while the gap is outside the tolerance.

---

## 4. Civil zone and daylight saving time

### 4.1 Zone rules

The daylight-saving rules come from the IANA time zone database, which the platform already includes:

- Android: `java.time.ZoneId.of(id).getRules().getOffset(instant)` for local time. `TimeZone.getDefault()` for the system readout.
- Firefox: `Intl.DateTimeFormat` with `timeZone: id`.

Nothing to bundle. The rules say whether a zone observes DST on a given date. Some zones do not.

### 4.2 Location to zone

IANA gives rules, not places. Finding the zone for a coordinate needs a boundary dataset. The plan bundles one, so lookup works offline and sends nothing over a network.

**Source: `timezone-boundary-builder`, release 2026e, the current release. Decided.**

- Project: [evansiroky/timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder). Code is MIT. Data is ODbL 1.0, from the `DATA_LICENSE` file in the repo. The data is derived from OpenStreetMap.
- Each polygon carries a `tzid` in IANA format, for example `America/New_York`. The lookup returns that ID, and the platform's DST rules apply.
- Use the **"same since now"** variant, `timezones-now`, without oceans. The project describes it as suitable for "use cases that need to know the current or future time", which is our case.
- The full dataset, measured: 65 zones, 725 polygons, 3.67 million vertices. Zipped 24.1 MB; raw JSON 82 MB.

**Measured simplification.** Each row keeps every border within the tolerance of its original position, so the tolerance is the worst-case border error. Sizes are for `tzid` only, with coordinates rounded to about 1 m.

| Worst border error | Vertices kept | Raw JSON | Gzip | Polygons lost | Area of lost polygons | Border length kept | Sampled max error |
|---|---|---|---|---|---|---|---|
| 1 km | 2.3% | 1.72 MB | 0.66 MB | 43 | 8.4 km² | 95.4% | 994 m |
| 500 m | 3.9% | 2.97 MB | 1.10 MB | 36 | 2.1 km² | 96.9% | 500 m |
| 100 m | 12.5% | 9.38 MB | 3.21 MB | 25 | 0.2 km² | 99.1% | 100 m |

**Decided: 100 m border error, about 3.2 MB gzipped.**

- **Nothing is lost at a whole-zone level.** Lost polygons are small islands and slivers.
- **Caveats:** rings were simplified independently, so neighbouring zones may leave small gaps or overlaps along shared borders. A topology-preserving tool would keep borders consistent. The source's own accuracy limits the result below about 100 m.

**Tiled dataset.** Split the data into grid tiles and load only the tile the device is in. **Decided: 10° tiles.**

- **Grid:** 10° × 10° cells. The tile for a point is `floor(lon / 10)`, `floor(lat / 10)`, so finding the tile needs no index.
- **Clipping:** each polygon ring is clipped to each tile it touches, so every vertex lands in one tile. Each tile file holds the clipped pieces, with their `tzid`.
- **Files:** one file per non-empty tile, each compressed separately in the APK. Measured at 10° on the 100 m data: 479 files, 3.0 MB gzipped in total, median 1.2 KB, 90th percentile 18 KB, largest 125 KB. The total is about the same as the single file, so install size does not change.
- **Loaded tile:** keep only one tile in memory. A lookup reads a tile file only when the point is in a different tile from the one loaded, and only when a lookup is due. The largest read is about 125 KB gzipped: a few milliseconds on a desktop, and a small fraction of a second on a phone (estimate, not measured on a phone).
- **Zone cache (within a tile):** a zone can change only when the location leaves that zone's polygon, which requires crossing its boundary. So store, with each lookup, the distance from the lookup point to the nearest edge of its own piece. Skip the lookup until the device has moved that far. This is a lower bound, so it is exact. Edges created by clipping are not real borders, so they make the distance smaller, never larger. The result is a few extra lookups near tile edges, not a wrong zone.
- **Polygons wider than 180°.** One polygon in the file spans more than 180° of longitude, so it crosses the antimeridian. It must be split at ±180° before tiling. The builder handles this, and the test in section 9 covers it.
- **Lookup check:** a sample of 100,000 random points, tiled against the single-file lookup, must agree except within 1 m of a border.

**Fallback:** [Natural Earth time zones](https://www.naturalearthdata.com/downloads/10m-cultural-vectors/timezones/), public domain, 1.88 MB, version 4.0.0. Its boundaries derive from the 2012 CIA World Factbook map, so they are coarse. Its records include UTC offsets, but I could not confirm IANA zone IDs, which the DST rules need. Use it only if the ODbL data cannot be used.

- **Attribution and sharing (ODbL):** the About text credits OpenStreetMap contributors and the project. The bundled dataset is a derived database under ODbL. If the app is distributed, the dataset must stay available under ODbL. The app's own code can carry a different licence.
- Lookup: point-in-polygon, with a bounding-box check first. Write it directly. Do not add a lookup library.
- Store only the zone ID, never the coordinates.
- If no polygon matches (open water, bad data), use the system zone and label the local-time line `(system)`.
- Refresh the dataset when IANA publishes a new release. The project publishes only after IANA does.

---

## 5. Settings (both clients)

| Setting | Control | Default | Range or values | Android | Extension |
|---|---|---|---|---|---|
| Time change mode | `instant` or `smooth` | `smooth` | | Yes | Yes |
| Maximum smoothing time X | Slider, minutes. Smooth mode only | 5 | 1–60 | Yes | Yes |
| Trend-aware smoothing | On or off. Smooth mode only | off | | Yes | Yes |
| Correct clock now | Button, with one confirmation | | | Yes | Yes |
| Precision | `seconds` or `minutes` | `seconds` | | Yes | Yes |
| Face | `analog` or `digital` | `analog` | | Yes | Yes |
| Widget face | `analog` or `digital` | `analog` | | Yes | Yes |
| Widget seconds | Off or on, with warning | off | | Yes | n/a |
| Freeze offset | Switch | off | | Yes | Yes |
| Maximum ping frequency | Slider, one ping per interval | 15 s | 1, 5, 10, 15, 30, 45, 60 s | Yes | Yes |
| Distance budget | Number, metres | 100 | 10–10 000 | Yes | Yes |
| Location | Switch | on | | Main screen | Setup page and popup |

Out-of-range values are rejected with an inline error and not saved.

### 5.1 Time change mode
- **Smooth** (default): section 3.3. The hands run faster or slower as they close the gap.
- **Instant**: section 3.4. The display jumps at each fix, and so do the hands.

### 5.2 Maximum smoothing time slider
- 1–60 minutes, showing the current value. Default 5.
- Applies at the next fix. The stored model keeps the old X until then.

### 5.3 Trend-aware smoothing
- Off (default): plain rate estimate (section 3.2). Best for GPS use with turns and speed changes.
- On: trend-aware rate estimate. Best for noisy positions or long steady travel.

### 5.4 Warning

When location is off, permission is denied, freeze is on, or there is no fix, show this under the tempus fugit line:

```
location off · offset may be out of date
```

Use the reason in place of `location off` where it applies: `freeze on`, `no fix yet`, `permission denied`, `permission revoked`.

### 5.5 Maximum ping frequency: battery warning

The slider sets the shortest interval between location requests. Its steps are 1, 5, 10, 15, 30, 45, and 60 seconds.

When the slider is at 10 s or faster, show this under the slider:

> **Frequent location checks use more battery.** Pinging every 10 s or faster can noticeably increase battery use while moving. Still, the phone checks once a minute, whatever this setting.

### 5.6 Widget seconds: warning and confirmation

Turning on widget seconds shows this dialog before it takes effect:

> **Widget seconds uses more battery.** The widget redraws every second, which wakes the phone about 3,600 times an hour. Expect noticeably higher battery use while a seconds widget is on the home screen. Minutes is the default and is much lighter. Keep it on only if you need the second hand on the widget.

Buttons: `Turn on` and `Keep minutes`. On `Turn on`, check `AlarmManager.canScheduleExactAlarms()`. If it returns false, open the system's exact-alarm settings screen for this app, and keep the widget on minutes until it is granted.

### 5.7 Settings changes
- Changing the ping frequency or the distance budget takes effect at the next location request. No restart.
- Changing mode, X, or trend-aware smoothing takes effect at the next fix.

---

## 6. Clock faces (both clients, drawn from scratch)

No `TextClock`, no custom GMT zones, and no platform clock widgets. Faces are drawn with `android.graphics.Canvas` on Android and `<canvas>` in the extension. One shared drawing function, written as plain geometry, is used by the app and the widget.

### 6.1 Analog face (default)

- A circle with 12 hour ticks.
- Three hands: hour, minute, and second. The second hand shows only at seconds precision.
- Real hands at the displayed time `D(t)`. In smooth mode they move continuously and run faster or slower with the speed. In instant mode they jump.
- **Ghost hands** in a faint colour at the target time `T(t)`. The angle between a real hand and its ghost is the gap.
- **Speed line** under the hub (section 3.9).
- **Gap line** under the speed line.
- **Settle line**, shown while the gap is outside the tolerance.
- **Speed arc:** a thin arc on the rim from 12 o'clock, its length proportional to the speed difference, capped at a full circle.
- The three readouts (section 1) sit below the face.

### 6.2 Digital face (optional, plain)

- The three readouts only. No hands, no ghost, no indicators.
- The tempus fugit line is the largest text on the screen.
- The conservative choice. It is the lightest to draw and the easiest to read at a glance.

### 6.3 Drawing rules
- Redraw when the displayed time changes at the chosen precision: at each second in seconds mode, at each minute boundary in minutes mode.
- Keep the bitmap or canvas size fixed. No allocation per frame.
- The app and the widget use the same geometry function, so they match.

### 6.4 Stored model (Android and extension)

Stored when an anchor is created, a setting changes, or a correction runs:

```
mode, trend_aware, X_seconds, t_a (UTC ms), T_a (seconds), v_a (s/s),
g_a (seconds), tau_a (seconds), last_shown_ms, location_on, freeze,
precision, face, widget_face, widget_seconds,
ping_frequency_s, distance_budget_m, current_interval_s, provider,
recent_fixes (last 60 s, as UTC ms and target seconds only),
zone_id, zone_edge_m, zone_lookup_lat, zone_lookup_lon, loaded_tile
```

The displayed offset comes from the closed form in section 3.3 or 3.4. Apply any renewal due (section 3.6) before computing. The lookup coordinates are needed only for the zone cache (section 4.2). They are not used for the clock and are never sent anywhere. Keep them in memory only, and accept one zone lookup after each restart. The stored model holds no coordinates.

---

## 7. Location, permissions, and the Firefox setup page

### 7.1 Ping policy: adaptive interval

The device asks for fixes at an interval that depends on how fast it is moving:

```
interval = clamp( distance_budget / s , floor , 60 s )     then rounded down to a step
```

- `s` is ground speed in m/s (section 2), smoothed as below.
- `distance_budget` defaults to 100 m: the most ground the device should cover between fixes.
- `floor` is the maximum ping frequency setting (default 15 s). With network positions only (fixes worse than 50 m accuracy), the floor is at least 30 s.
- Steps are 1, 5, 10, 15, 30, 45, and 60 s. The interval is rounded down to the nearest step, so the distance budget is never exceeded.
- Still, the interval is 60 s whatever the setting, because the floor's upper limit is 60 s.

Worked values, with the defaults:

| Movement | Speed | Interval |
|---|---|---|
| Still | 0 | 60 s |
| Walking | 1.4 m/s | 60 s (the 71 s value is clamped to 60) |
| Cycling | 5 m/s | 20 s |
| Driving in town | 14 m/s | 15 s (the 7 s value is raised to the 15 s floor) |
| Motorway | 28 m/s | 15 s (the 3.6 s value is raised to the 15 s floor) |

**Keeping the interval steady.**
- **Speed estimate.** Ground speed is the distance between successive accepted fixes divided by the time between them, smoothed with a 30 s time constant. Use the platform's speed reading when it is available and trusted; otherwise compute it.
- **Dead band.** If the distance moved over the last 60 s is smaller than the fixes' reported accuracy, `s = 0`. This is the same rule as the rate dead band in section 3.2.
- **Hysteresis.** The requested interval changes only when the computed step has been the same for two consecutive fixes. Changing it re-registers the location request.

**Provider.** Use GPS while moving. Use the network provider when still for five minutes or more. Switch back to GPS as soon as the speed estimate rises above 1 m/s.

**Battery.** The heaviest case is the slider at 1 s or 5 s at motorway speed. The warning in section 5.5 covers it.

### 7.2 Android location

Use `android.location.LocationManager`. Do not use Play Services.

On resume, if location is on and freeze is off:
1. Read `getLastKnownLocation` for GPS, falling back to network. Use it if under 10 minutes old, as a fix anchor.
2. Register updates with the interval from section 7.1, `requestLocationUpdates(provider, intervalMs, 0, listener)`. Set the minimum distance to zero, because the interval already enforces the distance budget. Reselect the provider when GPS or network is switched on or off.
3. On each fix, create an anchor (section 3.1), update the speed estimate, choose the interval, and run the zone cache check (section 4.2).

On pause: `removeUpdates`. No background location.

Permissions: `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, requested together. If only approximate is granted, keep running. Approximate fixes are typically network-based, so the floor becomes 30 s.

### 7.3 Firefox setup page

The extension's setup page is part of the stretch deliverable (section 7.9). If built, it:

1. Shows the location permission state, read with `navigator.permissions.query({ name: "geolocation" })`: `granted`, `prompt`, or `denied`.
2. Has an `Allow location` button. Pressing it calls `getCurrentPosition` from the page, which shows Firefox's prompt. The button press is the user gesture.
3. In state `granted`, the popup and new-tab pages may call `getCurrentPosition`. In state `prompt`, they must not, because the prompt would close the popup. In state `denied`, they show `permission revoked` with a link to the setup page.
4. Shows the Windows warning (section 7.5) and the accuracy status.

### 7.4 Whether the permission lasts

Whether Firefox keeps a geolocation grant for an extension origin across restarts is **not confirmed**. A search found a proposed Firefox patch and a related bug, but neither is verified. The plan does not assume persistence. The setup page and each popup or new tab check the state on every open. The restart test in section 10 decides it.

### 7.5 Windows

Settings → Privacy & security → Location must be on, with "Let desktop apps access your location" on. If not, Firefox receives no position. The setup page detects the failure and shows the link.

### 7.6 Extension location details

- No background script. Location is requested only from open pages.
- The browser sets the fix cadence while a page is open. The extension uses the speed estimate and dead band from section 7.1 to decide which fixes to apply, and it does not change the browser's cadence.
- Every request has a 10 s timeout. A dismissed prompt gives no callback, so the timeout resolves it. On timeout, show `no fix yet` and keep the stored model.
- Read `coords.accuracy`. Warn above 5 km. Desktop positions come from Wi-Fi or IP lookups, not GPS, and can be far off. The dead band and trend-aware smoothing keep these jumps from reaching the clock.
- Store only the model. The distance budget does not apply to the extension's cadence. The extension applies the same speed rule to fixes it receives.
- Firefox may resolve position through a network service. Say so in the privacy text.

### 7.7 Firefox manifest (if built)
- `browser_specific_settings.gecko.id`: placeholder such as `tempus-fugit@example.org`. Confirm before submitting.
- `action.default_popup`: `popup/popup.html`.
- `chrome_url_overrides.newtab`: `newtab/newtab.html`.
- `chrome_settings_overrides.homepage`: `newtab/newtab.html`. Gives the new-window page. Set at install. Verify whether Firefox prompts on install.
- `options_ui`: `options/options.html` (settings, section 5). The setup page, `setup/setup.html`, opens on install and from the popup's settings link.
- `permissions`: `storage`, `geolocation`. Add nothing else.
- Do not request `<all_urls>`, `tabs`, `history`, `webRequest`, or `browserSettings`.

**On the `chrome_` names.** The keys `chrome_url_overrides` and `chrome_settings_overrides` come from the WebExtensions manifest format. Firefox reads them, and I know of no Firefox-specific alternative. They stay as written. Everything else in the project says Firefox.

### 7.8 Homepage and new tabs
- The new-tab override is active whenever the extension is enabled. It cannot be toggled at runtime.
- The homepage cannot be changed at runtime (`browserSettings.homepageOverride` is read-only), so it is set at install.
- Firefox's own settings can override the new-window behaviour, and the user can revert the homepage. The plan does not override those choices.

### 7.9 Extension folder layout and go / no-go gate

```
tempus-fugit-firefox/
  manifest.json
  setup/setup.html, setup.js, setup.css        # permission setup, Windows check, accuracy
  popup/popup.html, popup.js, popup.css
  newtab/newtab.html, newtab.js, newtab.css    # also the homepage
  options/options.html, options.js             # settings (section 5)
  shared/model.js                              # sections 3 and 6.4, plain functions
  shared/face.js                               # clock face geometry, section 6
  shared/zones.js                              # zone lookup and cache, section 4.2
  data/tiles/                                  # tiled zone dataset, ODbL per section 4.2
  icons/icon-48.png, icon-96.png               # generic clock icon
```

No background script. Nothing runs while no page is open.

The Firefox extension ships only if all of these hold, tested by the builder (section 10):
1. The popup or new-tab page reads a position through a permission grant made from the setup page.
2. That grant survives a Firefox restart, or the setup page can re-grant in one click each session without friction.
3. A dismissed prompt times out cleanly.

If any fails, the extension is shelved. The Android app is unaffected, and the shelved extension's folder can be removed. The shared model and zone data stay in the Android project.

---

## 8. Android app

### 8.1 Starting point

The previous attempt is in the project root (`app/`, `build.gradle`, `REPORT.md`). Useful parts:
- `SolarMath.java`: the constants and formatting.
- The Gradle config, with empty `dependencies {}` and `android.builtInKotlin=false`.
- The manifest with two location permissions.

Replace:
- `SlewClock.java` and its chasing filter. Replaced by section 3.
- `SolarWidget.java` with its `TextClock` and GMT zones. Replaced by a drawn face (section 6) and a widget that uses the stored model.
- The `Δ` line. The three readouts replace it.
- The fixed 60 s / 100 m request in `LocationSource`, which the previous attempt registered with a minimum distance. Replaced by the adaptive policy in section 7.1.

Build the second attempt in the same project. Remove dead code.

### 8.2 Screens

**Main screen.** Framework views only. Top to bottom:
1. A switch labelled `location`.
2. The face (section 6): analog or digital, square, about 60% of the screen width for analog.
3. The three readouts (section 1). The tempus fugit line is the largest text on the screen in digital mode.
4. The warning line (section 5.4), when it applies.
5. The equation line, small: `adjusted = UTC + λ ÷ 15 · λ = −70.00° → −4.6667 h`. Show `waiting for location` before the first fix.
6. A small `settings` button.

**Settings screen.** A second `Activity` with the settings in section 5, and an About text with the attribution in section 4.2.

Theme: `Theme.DeviceDefault.DayNight` with the action bar removed.

### 8.3 Widget: the same analog view, rearranged

The widget shows the same analog view as the app, with the same features, laid out for the widget's shape:

- **Real hands** at the displayed time.
- **Ghost hands** at the target time.
- **Speed line, gap line, settle line, and speed arc.**
- **The three readouts** (tempus fugit, local time, system time), in small text beside or below the face.

The drawing function is the app's (section 6). The widget only decides the layout. Use a landscape arrangement (face on the left, readouts on the right) for wide widgets and a stacked one for tall widgets. Set the minimum size in the widget info so the face is never drawn smaller than it can be read. Below that minimum, fall back to the digital face.

**Cadence.** The widget updates only when its bitmap changes, and a bitmap cannot animate, so cadence sets what the widget can show:

- **Minutes** (default): one update per minute, from `AlarmManager.setInexactRepeating`. No exact-alarm permission. The face is drawn at minute resolution, so **the second hand is not drawn**. Everything else in the analog view is drawn: hands, ghost hands, the speed and gap indicators, and the settle line. The readouts show `HH:MM`.
- **Seconds** (optional, section 5.6): one update per second, from `AlarmManager.setExactAndAllowWhileIdle`, re-armed at each update. It draws the second hand and shows `HH:MM:SS`. It needs `SCHEDULE_EXACT_ALARM`, which the user grants through the system settings. Doze may delay alarms while the screen is off. The widget is seldom visible then, so this is acceptable.

The widget reads the stored model and computes the displayed time with the closed form (section 3.3) and the renewal rule (section 3.6). It does not need the app running.

The alarm runs only while a widget is placed. It is cancelled when the last widget is removed. Changing widget seconds reschedules the alarm at once.

### 8.4 Permissions and build

Manifest permissions:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

- `SCHEDULE_EXACT_ALARM` is declared because widget seconds is built. The app checks `canScheduleExactAlarms()` before use (section 5.6). The minutes path needs no exact-alarm access.
- Must not appear: `INTERNET`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`.

- Package: `com.tempusfugit.solarclock`.
- Java only. `minSdk` 30. `targetSdk` 36 (Play requirement as of October 2026; check the Play Console before upload).
- `dependencies {}` is empty. No AndroidX, Material, AppCompat, or Kotlin stdlib.
- The zone dataset is an asset, not a dependency.

---

## 9. Acceptance criteria

All must pass before reporting done.

**Model (both clients, shared tests)**
1. `T(λ = −70) == −16800`, `T(180) == 43200`, `normalise(190) == −170`.
2. Time of day at `λ = 179.9999` and `λ = −179.9999` differ by less than 1 s.
3. Stationary, with noiseless fixes: speed exactly 1, gap zero.
4. Constant motion at 100 km/h: the display matches the target to within 1 s after 5 minutes, in both modes.
5. Smooth mode, gap 30 s at an anchor: settle time about 4.5 min (272 s). The gap is 0.5 s or less at the settle time, and never increases. The closed form matches a numeric integration of `dO/dt = v + g/τ` to within 0.01 s.
6. Smooth mode, gap 3 s: settle time about 20 s.
7. Smooth mode, gap 1 h: settle time approaches 5 min and never exceeds it.
8. Smooth mode, gap just above 0.5 s: settle time is near zero. There is no minimum.
9. Instant mode, gap 30 s: the display jumps 30 s at the fix, then moves at the target's rate.
10. Westward motion faster than 15°/h while the display is ahead: the display holds still, never decreases, and resumes when the target catches up.
11. Stopped device with fixes stopped: after `S` with no fix, a renewal anchor is created with `v = 0`. The gap then closes within X of the renewal.
12. Plain (default): the rate follows a 30 s window of fixes, so a turn is reflected within about half a minute. Trend-aware (on): after one hour of steady westward motion, the estimated rate is within 5% of the true rate, and after a turn it follows within about an hour. Both are tested.
13. Dead band: with 500 m network noise and a stationary device, the rate stays at exactly 0 for 10 minutes.
14. Forced correction: the display equals the target at the press, within one fix's accuracy. It may go backward. The confirmation appears first.
15. Freeze on: no fix anchors, the target holds, and the clock keeps running at the last offset.
16. Slider change: the next fix uses the new X, with no restart.

**Ping policy**
17. With the default 15 s setting, at ground speeds of 0, 1.4, 5, 14, and 28 m/s, the requested intervals are 60, 60, 20, 15, and 15 s.
18. With the slider at 10 s, at 28 m/s the interval is 10 s, and the distance between fixes is at most 280 m.
19. Still for five minutes: the provider is network, and the interval is 60 s.
20. Speed oscillating around a step edge changes the interval at most once every two fixes.
21. The battery warning appears when the slider is at 10 s or faster, and not at 15 s or slower.

**Faces and widget**
22. Analog face: real hands at the displayed time, ghost hands at the target time, second hand only at seconds precision.
23. Digital face: only the readouts are drawn.
24. Smooth mode: hands move continuously. Instant mode: they jump at fixes.
25. Widget (minutes): the analog view matches the app's view, except the second hand, which is absent. The readouts show `HH:MM`.
26. Widget (seconds): the widget's second hand and readouts match the app within 1 s while stationary.
27. Widget minutes cadence: one update per minute, no exact alarm used.
28. Widget seconds cadence: after turning on with the warning, updates arrive each second while the device is awake. Turning it off stops them and cancels the alarm.
29. If exact alarms are not granted, seconds cadence does not start, and the user is sent to the setting.
30. Below the minimum widget size, the widget falls back to the digital layout.

**Android**
31. Changes to the ping frequency or distance budget take effect at the next location request, with no restart.
32. Location off: no location callbacks. Warning line reads `location off · offset may be out of date`.
33. Permission denied: toggle returns to off, no crash, no prompt loop.
34. Manifest lists only the three permissions in section 8.4.
35. `dependencies {}` is empty. The zone dataset size and the APK size are reported.

**Zone lookup**
36. Known points return the expected IANA ID: New York (40.71, −74.01) → `America/New_York`, Phoenix (33.45, −112.07) → `America/Phoenix` (no DST), Kolkata (22.57, 88.36) → `Asia/Kolkata`, and a point in mid-ocean returns the system-zone fallback.
37. Local-time DST: a New York fix gives −4 in July and −5 in January, from the platform rules.
38. The zone cache: a move shorter than the stored edge distance does not look up the zone. A move past it does.
39. Tiles: a lookup reads a tile file only when the point is in a tile other than the loaded one. At most one tile is in memory at a time.
40. The tiled lookup agrees with the single-file lookup on 100,000 random points, except within 1 m of a border.
41. The polygon wider than 180° is split at the antimeridian, and points on either side resolve to the right zone.
42. The stored model holds no coordinates that leave the device, and the About text carries the attribution.

**Firefox (only if the extension is built, section 7.9)**
43. The setup page shows the permission state. `Allow location` produces Firefox's prompt and, on grant, the state reads `granted`.
44. The popup and new tab call `getCurrentPosition` only when the state is `granted`.
45. A dismissed prompt times out at 10 s and shows `no fix yet`.
46. Manifest permissions are exactly `storage` and `geolocation`. No background script.
47. New tab shows the clock. New window opens the clock. Disabling the extension restores the defaults.
48. `web-ext lint` reports no errors.
49. Nothing in the project's own files refers to Chrome, except the manifest key names in section 7.7.

---

## 10. Verification and open decisions

**Math and geometry.** Run the model checks (criteria 1–16) as a plain program. Android: plain `main()`.

**Android.**
1. `./gradlew assembleDebug`.
2. Emulator mock location: `adb emu geo fix <longitude> <latitude>`. Longitude first. Simulate a series of fixes at each speed in criterion 17, a turn, a stop, and a network-only period.
3. Check the manifest and the dependency list.
4. Widget: minutes and seconds cadence, stationary and moving. Confirm the seconds warning and the exact-alarm flow.
5. Zone cache: confirm the dataset is read only when a lookup is due.

**Firefox (if built).**
1. `npx web-ext lint` and `npx web-ext run`.
2. Load as a temporary add-on. Test the setup page grant from a button press.
3. Restart Firefox. Check whether the grant survives. Record the result, and apply the gate in section 7.9.
4. Test whether the new-tab page can prompt. Record the result.
5. Check whether the homepage override prompts on install. Record the result.
6. For testing without real location, add a debug flag that injects a longitude, kept out of release builds.

**Report:** what was built, which criteria passed, what was not verified on hardware, and every deviation from this plan.

### 10.1 Decisions still open for the user

None. Every decision is recorded in section 10.2.

### 10.2 Decided

| Decision | Choice | Reason |
|---|---|---|
| Model | Anchors from fixes, target extrapolated, display closes gap (section 3) | Continuous, follows motion, bounded lag |
| Default mode | Smooth | As requested |
| Smooth closing | Settle time `X · u² / (u² + K²)`, `u = gap − ε`, `K = 9.4 s`; time constant `T/ln(gap/ε)` | No minimum, asymptote at X, no kink, several minutes for 30 s |
| Maximum smoothing time | Slider 1–60 min, default 5, the asymptote of settling | As requested |
| Settle time | Shown on the face, never over X | As requested |
| Rate estimate | 30 s window slope, dead band for noise. Trend-aware optional | Responsive, and steady while still |
| Ping policy | Interval `clamp(budget / speed, slider, 60 s)`, rounded down to a step | More fixes at speed, one a minute when still |
| Ping frequency slider | 1, 5, 10, 15, 30, 45, 60 s, default 15 s, warning at 10 s or faster | Battery is the user's trade |
| Still provider | Network after five minutes still, GPS while moving | Battery |
| Stopping | Renewal anchor after `2 × interval` with no fix | Safety net if fixes stop altogether |
| Monotonic guard | Display never decreases, except instant jumps and forced correction | As requested |
| Forced correction | Button with one confirmation | As requested |
| Automatic correction | None. Catch-up is bounded by X | As requested |
| Location-loss error | Accepted. It grows until fixes return, then catch-up is bounded by X | As requested |
| Default face | Analog, with ghost hands and indicators | As requested |
| Digital face | Plain readouts, optional | As requested |
| Widget content | The same analog view as the app, rearranged for the widget | As requested |
| Widget cadence | Minutes, confirmed (no second hand). Seconds optional with warning | Battery |
| Widget face | Analog by default, its own setting | Widget can differ from the app |
| Precision | Seconds by default | As requested |
| Daylight saving | IANA rules from the platform, boundary data from timezone-boundary-builder 2026e `timezones-now` | Neutral rules, offline lookup, current-time variant |
| Data licence | ODbL, with Natural Earth as the fallback | As requested |
| Border error | 100 m, about 3.2 MB gzipped | As requested |
| Tile size | 10° tiles, one tile in memory | As requested |
| Zone lookup | Tiled grid, zone cached by distance to its polygon edge | Each read is a small file, and reads happen only on tile changes |
| Firefox extension | Stretch goal, gated on location working, shelved otherwise | As requested |

---

## 11. Naming

- Visible name: **Tempus Fugit**.
- Project folders, labels, and comments: Firefox or Android only.
- Manifest keys: `chrome_url_overrides` and `chrome_settings_overrides` are the format's names, and Firefox reads them.
- Icon: a generic clock.

---

## 12. Sources for the zone dataset

- Project and README: [evansiroky/timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder). Code MIT, data ODbL 1.0 (`DATA_LICENSE`).
- Release used for measurement: [2026e](https://github.com/evansiroky/timezone-boundary-builder/releases/tag/2026e). The `timezones-now.geojson.zip` asset was downloaded to the scratch folder and measured there.
- Fallback: [Natural Earth time zones](https://www.naturalearthdata.com/downloads/10m-cultural-vectors/timezones/), public domain, 1.88 MB, version 4.0.0.

---

## 13. What changed from revision 7

- **Closing law** uses a steeper S-curve, `X · u² / (u² + K²)`, with `K = 9.4 s`. It gives 20 s for a 3 s gap and about 4.5 min for a 30 s gap. It has no minimum, and it approaches X for large gaps.
- **Ping frequency** is a slider from 1 to 60 s, with steps 1, 5, 10, 15, 30, 45, and 60 s. The default is 15 s. A battery warning appears at 10 s or faster. The still interval is fixed at 60 s.
- **Automatic correction** is dropped. Catch-up is already bounded by X.
- **Location-loss error** is recorded as an accepted limit (section 3.6).
- **Decided:** ODbL with Natural Earth as the fallback, 100 m border error, and 10° tiles.
