// The clock page shared by the popup and the new tab: stored model, location
// while the page is open, zone lookup, and the redraw loop. Nothing runs once
// the page closes; there is no background script.

import * as M from "./model.js";
import { Face } from "./face.js";
import { ZoneCache, ZoneLookup, fetchTile } from "./zones.js";

export const TIMEOUT_MS = 10000;
export const ACCURACY_WARNING_M = 5000;

export async function loadState() {
  const o = await browser.storage.local.get(["settings", "model", "zone_id"]);
  return {
    settings: { ...M.defaultSettings(), ...(o.settings || {}) },
    model: M.Model.fromJSON(o.model),
    zoneId: o.zone_id ?? null,
  };
}

export async function permissionState() {
  try {
    return (await navigator.permissions.query({ name: "geolocation" })).state;
  } catch {
    return "unknown";
  }
}

/**
 * One position with a hard 10 s timeout. A dismissed prompt never calls back,
 * so the timer is what resolves it.
 */
export function getPosition(timeoutMs = TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: "timeout" }), timeoutMs);
    navigator.geolocation.getCurrentPosition(
      (p) => { clearTimeout(timer); resolve(p); },
      (e) => { clearTimeout(timer); reject(e); },
      { enableHighAccuracy: false, timeout: timeoutMs, maximumAge: 0 },
    );
  });
}

/** Debug-only longitude injection; shared/debug.js is left out of release builds. */
async function debugSource() {
  try {
    const d = await import("./debug.js");
    return d.injectedPosition();
  } catch {
    return null;
  }
}

export async function startClock(el) {
  let { settings, model, zoneId } = await loadState();
  const face = el.face ? new Face(el.face) : null;
  const zones = new ZoneCache(new ZoneLookup((name) => fetchTile(browser.runtime.getURL("data/tiles/" + name))));
  const speedRule = new M.SpeedRule();
  let reason = null;
  let accuracyNote = null;
  let lastFix = null;
  let watchId = null;
  let watchdog = null;
  let written = "";
  let tickTimer = null;

  const saveModel = async () => {
    const json = model.toJSON();
    written = JSON.stringify(json);
    await browser.storage.local.set({ model: json });
  };

  const apply = async (pos) => {
    const now = Date.now();
    lastFix = now;
    const { latitude: lat, longitude: lon, accuracy } = pos.coords;
    const acc = Number.isFinite(accuracy) ? accuracy : 100;
    accuracyNote = acc > ACCURACY_WARNING_M ? `position accurate to ${Math.round(acc / 1000)} km only` : null;
    if (!settings.locationOn || settings.freeze) return;
    const t = Math.min(now, pos.timestamp || now);
    if (!speedRule.accept(t, lat, lon, acc, settings)) return;
    model.onFix(t, lon, acc * M.secondsPerMeter(lat), settings);
    await saveModel();
    const id = await zones.update(lat, lon);
    if (id !== zoneId) {
      zoneId = id;
      await browser.storage.local.set({ zone_id: id });
    }
    render();
  };

  const stopLocation = () => {
    if (watchId !== null) navigator.geolocation.clearWatch(watchId);
    watchId = null;
    clearTimeout(watchdog);
  };

  const startLocation = async () => {
    stopLocation();
    reason = null;
    if (!settings.locationOn) return void (reason = "location off");
    if (settings.freeze) return void (reason = "freeze on");
    const injected = await debugSource();
    if (injected) {
      const feed = () => apply(injected());
      feed();
      watchdog = setInterval(feed, 5000);
      return;
    }
    const state = await permissionState();
    if (state === "denied") return void (reason = "permission revoked");
    if (state !== "granted") return void (reason = "no fix yet"); // a prompt would close the popup
    reason = "no fix yet";
    try {
      await apply(await getPosition());
      reason = null;
    } catch (e) {
      reason = e.code === 1 ? "permission revoked" : "no fix yet";
      render();
    }
    // Further fixes at the browser's cadence; the speed rule decides which to apply.
    watchId = navigator.geolocation.watchPosition(
      (p) => { reason = null; apply(p); },
      (e) => { if (e.code === 1) reason = "permission revoked"; },
      { enableHighAccuracy: false, maximumAge: 0 },
    );
  };

  const warning = () => {
    if (reason === "no fix yet" && lastFix && Date.now() - lastFix < 180000) return null;
    return reason;
  };

  const render = () => {
    const now = Date.now();
    const f = M.computeFrame(model, now, settings.precisionSeconds, zoneId);
    el.tempus.textContent = f.tempus;
    el.local.textContent = f.local;
    el.system.textContent = f.system;
    const w = warning();
    el.warning.hidden = !w;
    if (w) el.warning.textContent = w + " · offset may be out of date";
    if (el.setupLink) el.setupLink.hidden = !(w === "permission revoked" || (w === "no fix yet" && !lastFix));
    if (el.accuracy) {
      el.accuracy.hidden = !accuracyNote;
      el.accuracy.textContent = accuracyNote || "";
    }
    if (el.equation) el.equation.textContent = f.hasFix ? M.equation(model.target(now)) : "waiting for location";
    if (face) {
      const analog = settings[el.faceSetting || "analog"];
      el.face.hidden = !analog;
      document.body.classList.toggle("digital", !analog);
      if (analog) face.draw(f, settings.precisionSeconds);
    }
    return f;
  };

  const tick = () => {
    const now = Date.now();
    const f = render();
    const unit = f.seconds ? 1000 : 60000;
    let delay = unit - (now % unit);
    if (f.hasFix) {
      const remaining = unit - (f.shownMs - unit * Math.floor(f.shownMs / unit));
      delay = f.speed > 0.05 ? Math.min(delay, Math.ceil(remaining / f.speed)) : Math.min(delay, 1000);
    }
    tickTimer = setTimeout(tick, Math.max(1, delay) + 3);
  };

  if (el.locationSwitch) {
    el.locationSwitch.checked = settings.locationOn;
    el.locationSwitch.addEventListener("change", async () => {
      settings.locationOn = el.locationSwitch.checked;
      if (!settings.locationOn) {
        model.hold(Date.now());
        await saveModel();
      }
      await browser.storage.local.set({ settings });
    });
  }

  browser.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") return;
    if (changes.settings) {
      const before = settings;
      settings = { ...M.defaultSettings(), ...(changes.settings.newValue || {}) };
      if (el.locationSwitch) el.locationSwitch.checked = settings.locationOn;
      if (before.locationOn !== settings.locationOn || before.freeze !== settings.freeze) startLocation();
    }
    if (changes.model && JSON.stringify(changes.model.newValue) !== written) {
      model = M.Model.fromJSON(changes.model.newValue); // a correction from the settings page
    }
    clearTimeout(tickTimer);
    tick();
  });

  window.addEventListener("pagehide", () => {
    stopLocation();
    model.renew(Date.now());
    saveModel();
  });

  tick();
  startLocation().then(() => { clearTimeout(tickTimer); tick(); });
}
