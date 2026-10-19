// Tempus Fugit model (plan sections 3, 6.4 and 7.1), a direct port of the
// Android classes Model, PingPolicy, ClockFrame, FaceGeometry and SolarMath.
// Plain functions and classes with no browser APIs, so Node can test it.

export const EPS = 0.5;
export const K = 9.4;
export const TREND_W_S = 3600;
export const MAX_FIX_GAP_S = 180;
export const MIN_WINDOW_S = 30;
export const MAX_RENEWAL_MS = 120000;
export const CORRECTION_FIX_MS = 30000;
const WINDOW_SLACK_S = 1.5;

export const DAY_S = 86400;
export const DAY_MS = 86400000;
export const SECONDS_PER_DEGREE = 240;
export const METERS_PER_DEGREE = 111320;
const MINUS = "−";

export const PING_STEPS_S = [1, 5, 10, 15, 30, 45, 60];

export function defaultSettings() {
  return {
    instant: false,
    xMinutes: 5,
    trendAware: false,
    precisionSeconds: true,
    analog: true,
    widgetAnalog: true,
    freeze: false,
    pingFloorS: 15,
    distanceBudgetM: 100,
    locationOn: true,
  };
}

export const validX = (m) => Number.isInteger(m) && m >= 1 && m <= 60;
export const validBudget = (m) => Number.isInteger(m) && m >= 10 && m <= 10000;
export const validPing = (s) => PING_STEPS_S.includes(s);
export const batteryWarning = (s) => s <= 10;

// ---- SolarMath ----

export function normalise(lon) {
  return (((lon + 180) % 360) + 360) % 360 - 180;
}

function inRange(lon) {
  return lon >= -180 && lon <= 180 ? lon : normalise(lon);
}

export function targetSeconds(lon) {
  return inRange(lon) * SECONDS_PER_DEGREE;
}

export function longitudeOf(offsetSeconds) {
  const lon = offsetSeconds / SECONDS_PER_DEGREE;
  return lon >= -180 && lon <= 180 ? lon : normalise(lon);
}

export function secondsPerMeter(lat) {
  const c = Math.max(0.01, Math.cos((lat * Math.PI) / 180));
  return SECONDS_PER_DEGREE / (METERS_PER_DEGREE * c);
}

export function distanceMeters(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180;
  const p1 = lat1 * r, p2 = lat2 * r, dp = p2 - p1, dl = (lon2 - lon1) * r;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(a)));
}

export function timeOfDayMs(ms) {
  return ((ms % DAY_MS) + DAY_MS) % DAY_MS;
}

const two = (n) => String(n).padStart(2, "0");

export function formatClock(ms, seconds) {
  const s = Math.floor(timeOfDayMs(Math.floor(ms)) / 1000);
  const hm = two(Math.floor(s / 3600)) + ":" + two(Math.floor(s / 60) % 60);
  return seconds ? hm + ":" + two(s % 60) : hm;
}

export function placeholder(seconds) {
  return seconds ? "--:--:--" : "--:--";
}

function signed(value, decimals, plus) {
  const digits = Math.abs(value).toFixed(decimals);
  const zero = digits.replace(/0/g, "") === ".";
  if (value < 0 && !zero) return MINUS + digits;
  return plus && !zero ? "+" + digits : digits;
}

export function equation(targetS) {
  const lon = longitudeOf(targetS);
  return "adjusted = UTC + λ ÷ 15 · λ = " + signed(lon, 2, false) + "° → " + signed(lon / 15, 4, true) + " h";
}

export function formatDuration(seconds) {
  const s = Math.round(Math.abs(seconds));
  if (s < 60) return s + " s";
  if (s < 3600) return s % 60 === 0 ? s / 60 + " min" : Math.floor(s / 60) + " min " + (s % 60) + " s";
  const m = Math.round(s / 60);
  return m % 60 === 0 ? m / 60 + " h" : Math.floor(m / 60) + " h " + (m % 60) + " min";
}

// ---- settle law (3.3) ----

export function settleTime(gap, x) {
  const u = Math.abs(gap) - EPS;
  if (u <= 0) return 0;
  return (x * u * u) / (u * u + K * K);
}

export function tau(gap, x) {
  if (Math.abs(gap) <= EPS) return 0;
  return settleTime(gap, x) / Math.log(Math.abs(gap) / EPS);
}

// ---- Model ----

export class Model {
  constructor() {
    this.hasAnchor = false;
    this.tA = 0;
    this.targetA = 0;
    this.vA = 0;
    this.gA = 0;
    this.tauA = 0;
    this.xSeconds = 300;
    this.instant = false;
    this.trendAware = false;
    this.lastShownMs = NaN;
    this.intervalS = 15;
    this.fixes = []; // [t ms, T s, accuracy s]
    this.lastFixMs = null;
    this.trendSeeded = false;
    this.vTrend = 0;
    this.correctionUntilMs = null;
    this.holding = false;
  }

  /** The stored model: no coordinates, fixes as UTC ms and target seconds only. */
  toJSON() {
    return {
      hasAnchor: this.hasAnchor, tA: this.tA, T_a: this.targetA, v_a: this.vA, g_a: this.gA,
      tau_a: this.tauA, X_seconds: this.xSeconds, mode: this.instant ? "instant" : "smooth",
      trend_aware: this.trendAware, last_shown_ms: Number.isNaN(this.lastShownMs) ? null : this.lastShownMs,
      current_interval_s: this.intervalS, recent_fixes: this.fixes.map((f) => [f[0], f[1]]),
      last_fix_ms: this.lastFixMs, trend_seeded: this.trendSeeded, v_trend: this.vTrend,
      correction_until_ms: this.correctionUntilMs,
    };
  }

  static fromJSON(o) {
    const m = new Model();
    if (!o) return m;
    m.hasAnchor = !!o.hasAnchor;
    m.tA = o.tA ?? 0;
    m.targetA = o.T_a ?? 0;
    m.vA = o.v_a ?? 0;
    m.gA = o.g_a ?? 0;
    m.tauA = o.tau_a ?? 0;
    m.xSeconds = o.X_seconds ?? 300;
    m.instant = o.mode === "instant";
    m.trendAware = !!o.trend_aware;
    m.lastShownMs = o.last_shown_ms ?? NaN;
    m.intervalS = o.current_interval_s ?? 15;
    m.fixes = (o.recent_fixes || []).map((f) => [f[0], f[1], NaN]);
    m.lastFixMs = o.last_fix_ms ?? null;
    m.trendSeeded = !!o.trend_seeded;
    m.vTrend = o.v_trend ?? 0;
    m.correctionUntilMs = o.correction_until_ms ?? null;
    return m;
  }

  target(t) {
    return this.targetA + (this.vA * (t - this.tA)) / 1000;
  }

  gap(t) {
    if (this.gA === 0) return 0;
    return this.gA * Math.exp(-Math.max(0, (t - this.tA) / 1000) / this.tauA);
  }

  offset(t) {
    return this.target(t) - this.gap(t);
  }

  speed(t) {
    return 1 + this.vA + (this.gA === 0 ? 0 : this.gap(t) / this.tauA);
  }

  settleA() {
    return settleTime(this.gA, this.xSeconds);
  }

  settleRemaining(t) {
    if (this.gA === 0) return 0;
    return Math.max(0, this.settleA() - Math.max(0, (t - this.tA) / 1000));
  }

  setAnchor(t, target, v, g) {
    if (Math.abs(g) <= EPS) g = 0;
    this.hasAnchor = true;
    this.tA = t;
    this.targetA = target;
    this.vA = v;
    this.gA = g;
    this.tauA = tau(g, this.xSeconds);
  }

  renewalMs() {
    return Math.min(MAX_RENEWAL_MS, 2000 * this.intervalS);
  }

  /** Apply every renewal due by t (3.6). */
  renew(t) {
    if (!this.hasAnchor) return;
    const s = this.renewalMs();
    while (t - this.tA >= s) {
      if (this.vA === 0 && this.gA === 0) {
        this.tA += Math.floor((t - this.tA) / s) * s;
        return;
      }
      const at = this.tA + s;
      this.setAnchor(at, this.target(at), 0, this.gap(at));
    }
  }

  onFix(t, lon, accuracyS, settings) {
    if (this.hasAnchor) {
      this.renew(t);
      if (t < this.tA) t = this.tA;
    }
    let target = targetSeconds(lon);
    const before = this.hasAnchor ? this.offset(t) : target;
    target += DAY_S * Math.round((before - target) / DAY_S);
    const v = this.estimateRate(t, target, accuracyS, settings.trendAware);
    const correction = this.correctionUntilMs !== null && t <= this.correctionUntilMs;
    const jump = !this.hasAnchor || settings.instant || correction;
    if (jump) this.lastShownMs = NaN;
    if (correction) this.correctionUntilMs = null;
    this.instant = settings.instant;
    this.trendAware = settings.trendAware;
    this.xSeconds = settings.xMinutes * 60;
    this.setAnchor(t, target, v, jump ? 0 : target - before);
  }

  hold(t) {
    if (!this.hasAnchor) return;
    this.renew(t);
    if (t < this.tA) t = this.tA;
    this.setAnchor(t, this.target(t), 0, this.gap(t));
  }

  correct(t) {
    this.correctionUntilMs = t + CORRECTION_FIX_MS;
    if (!this.hasAnchor) return;
    this.renew(t);
    if (t < this.tA) t = this.tA;
    this.lastShownMs = NaN;
    this.setAnchor(t, this.target(t), 0, 0);
  }

  estimateRate(t, target, accuracyS, trend) {
    const stale = this.lastFixMs === null || (t - this.lastFixMs) / 1000 > MAX_FIX_GAP_S;
    const dt = stale ? 0 : (t - this.lastFixMs) / 1000;
    if (stale) {
      this.fixes = [];
      this.trendSeeded = false;
    }
    this.lastFixMs = t;
    this.fixes.push([t, target, accuracyS]);
    const window = Math.max(MIN_WINDOW_S, 2 * this.intervalS);
    while (this.fixes.length && (t - this.fixes[0][0]) / 1000 > window + WINDOW_SLACK_S) this.fixes.shift();

    const n = this.fixes.length;
    if (n < 2) return trend && this.trendSeeded ? this.vTrend : 0;
    let meanT = 0, meanY = 0, maxAcc = 0, minY = Infinity, maxY = -Infinity;
    for (const f of this.fixes) {
      meanT += (f[0] - t) / 1000;
      meanY += f[1];
      if (f[2] > maxAcc) maxAcc = f[2];
      minY = Math.min(minY, f[1]);
      maxY = Math.max(maxY, f[1]);
    }
    meanT /= n;
    meanY /= n;
    let sxy = 0, sxx = 0;
    for (const f of this.fixes) {
      const x = (f[0] - t) / 1000 - meanT;
      sxy += x * (f[1] - meanY);
      sxx += x * x;
    }
    const slope = sxx > 0 ? sxy / sxx : 0;
    const dead = maxY - minY <= 2 * maxAcc;
    const observed = dead ? 0 : slope;
    if (!trend) return observed;
    if (!this.trendSeeded) {
      this.vTrend = observed;
      this.trendSeeded = true;
    } else {
      this.vTrend += (observed - this.vTrend) * Math.min(1, dt / TREND_W_S);
    }
    return dead ? 0 : this.vTrend;
  }

  displayedMs(t) {
    return t + this.offset(t) * 1000;
  }

  shownMs(t) {
    const d = this.displayedMs(t);
    this.holding = !Number.isNaN(this.lastShownMs) && d < this.lastShownMs;
    if (this.holding) return this.lastShownMs;
    this.lastShownMs = d;
    return d;
  }

  shownSpeed(t) {
    return this.holding ? 0 : this.speed(t);
  }
}

// ---- ping speed rule (7.1, 7.6) ----

/**
 * The browser sets the fix cadence, so the extension uses the speed estimate,
 * the dead band and the interval rule only to decide which fixes to apply:
 * a fix is applied once the interval for the current speed has passed.
 * Coordinates stay in this object's memory.
 */
export class SpeedRule {
  constructor() {
    this.fixes = []; // [t, lat, lon, acc]
    this.speed = 0;
    this.hasSpeed = false;
    this.lastAppliedMs = null;
  }

  static intervalFor(speed, floorS, budgetM) {
    const raw = speed > 0 ? budgetM / speed : 60;
    return Math.floor(Math.max(floorS, Math.min(60, raw)) + 1e-9);
  }

  floorS(settings, accuracyM) {
    return accuracyM > 50 ? Math.max(settings.pingFloorS, 30) : settings.pingFloorS;
  }

  /** Feed a fix; returns true when it should be applied to the model. */
  accept(t, lat, lon, accuracyM, settings) {
    const prev = this.fixes[this.fixes.length - 1];
    if (prev && t <= prev[0]) return false;
    if (prev) {
      const dt = (t - prev[0]) / 1000;
      const observed = distanceMeters(prev[1], prev[2], lat, lon) / dt;
      if (!this.hasSpeed) this.speed = observed;
      else this.speed += (observed - this.speed) * (1 - Math.exp(-dt / 30));
      this.hasSpeed = true;
    }
    this.fixes.push([t, lat, lon, accuracyM]);
    while (this.fixes.length > 2 && (t - this.fixes[1][0]) / 1000 > 60) this.fixes.shift();
    if (this.fixes.length >= 2) {
      let moved = 0, maxAcc = 0;
      for (const f of this.fixes) {
        moved = Math.max(moved, distanceMeters(f[1], f[2], lat, lon));
        maxAcc = Math.max(maxAcc, f[3]);
      }
      if (moved <= 2 * maxAcc) this.speed = 0;
    }
    const interval = SpeedRule.intervalFor(this.speed, this.floorS(settings, accuracyM), settings.distanceBudgetM);
    if (this.lastAppliedMs !== null && t - this.lastAppliedMs < interval * 1000 - 500) return false;
    this.lastAppliedMs = t;
    return true;
  }
}

// ---- frame (ClockFrame) ----

function zoneOffsetSeconds(zone, now) {
  // Intl gives the civil time in a zone; the difference from UTC is the offset.
  const f = new Intl.DateTimeFormat("en-US", {
    timeZone: zone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
  const p = {};
  for (const part of f.formatToParts(new Date(now))) p[part.type] = part.value;
  const local = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return Math.round((local - Math.floor(now / 1000) * 1000) / 1000);
}

export function systemZone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

export function computeFrame(m, now, seconds, zoneId, sysZone = systemZone()) {
  const f = { hasFix: m.hasAnchor, seconds };
  const sysOff = zoneOffsetSeconds(sysZone, now);
  f.system = "system time - " + formatClock(now + sysOff * 1000, seconds);
  let localOff = null;
  if (zoneId) {
    try {
      localOff = zoneOffsetSeconds(zoneId, now);
    } catch {
      localOff = null;
    }
  }
  f.local = localOff !== null
    ? "local time - " + formatClock(now + localOff * 1000, seconds)
    : "local time - " + formatClock(now + sysOff * 1000, seconds) + " (system)";
  if (!m.hasAnchor) {
    f.tempus = "tempus fugit - " + placeholder(seconds);
    f.speed = 1;
    return f;
  }
  m.renew(now);
  f.shownMs = m.shownMs(now);
  f.holding = m.holding;
  const target = m.target(now);
  f.targetMs = now + target * 1000;
  f.targetS = target;
  f.speed = m.shownSpeed(now);
  f.gapS = target - (f.shownMs - now) / 1000;
  f.settleS = Math.abs(f.gapS) < EPS ? 0 : m.settleRemaining(now);
  f.arc = Math.max(-1, Math.min(1, f.speed - 1));
  f.tempus = "tempus fugit - " + formatClock(Math.floor(f.shownMs), seconds);
  f.speedLine = speedText(f.speed);
  f.gapLine = gapText(f.gapS);
  f.settleLine = f.settleS > 0 ? settleText(f.settleS) : null;
  return f;
}

export function speedText(speed) {
  const pct = (speed - 1) * 100;
  if (Math.abs(pct) < 0.5) return "on time";
  if (speed >= 2) return "running " + Math.round(speed) + "× real time";
  return "running " + Math.round(Math.abs(pct)) + "% " + (pct < 0 ? "slow" : "fast");
}

export function gapText(gap) {
  if (Math.abs(gap) < EPS) return "display on target";
  const d = formatDuration(gap);
  return gap > 0 ? "display " + d + " behind target" : "display " + d + " ahead of target";
}

export function settleText(seconds) {
  if (seconds < 59.5) return "settles in about " + Math.max(1, Math.round(seconds)) + " s";
  return "settles in about " + Math.round(seconds / 60) + " min";
}

// ---- face geometry (FaceGeometry) ----

export const HOUR_LENGTH = 0.5, MINUTE_LENGTH = 0.74, SECOND_LENGTH = 0.84;
export const TICK_INNER = 0.86, TICK_OUTER = 0.95, ARC_RADIUS = 0.995;
export const LINE_OFFSETS = [0.3, 0.43, 0.56];

export function handAngles(ms, seconds) {
  const tod = timeOfDayMs(Math.floor(ms));
  const unit = seconds ? 1000 : 60000;
  const s = (tod - (tod % unit)) / 1000;
  return [((s / 3600) % 12) * 30, ((s / 60) % 60) * 6, (s % 60) * 6];
}

export function arcSweep(arc) {
  return Math.max(-1, Math.min(1, arc)) * 360;
}
