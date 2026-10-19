import * as M from "../shared/model.js";
import { getPosition, permissionState } from "../shared/clock.js";

const $ = (id) => document.getElementById(id);
await browser.storage.local.set({ setup_seen: true });

async function showState() {
  $("state").textContent = await permissionState();
}

function showPosition(p) {
  const acc = p.coords.accuracy;
  $("accuracy").textContent = acc > 5000
    ? `Accurate to about ${Math.round(acc / 1000)} km. Desktop positions come from Wi-Fi or IP lookups and can be far off.`
    : `Accurate to about ${Math.round(acc)} m.`;
}

// The button press is the user gesture that lets Firefox show its prompt.
$("allow").addEventListener("click", async () => {
  $("result").textContent = "Waiting for Firefox…";
  $("windows").hidden = true;
  try {
    const p = await getPosition();
    $("result").textContent = "Location works.";
    showPosition(p);
  } catch (e) {
    if (e.code === "timeout") {
      $("result").textContent = "No answer within 10 s: no fix yet. If you dismissed the prompt, press the button again.";
    } else if (e.code === 1) {
      $("result").textContent = "Permission denied. Allow location for this extension in Firefox’s permissions, then try again.";
    } else {
      $("result").textContent = "Firefox could not get a position.";
      if (navigator.userAgent.includes("Windows")) $("windows").hidden = false;
    }
  }
  showState();
});

const { settings: stored } = await browser.storage.local.get("settings");
const settings = { ...M.defaultSettings(), ...(stored || {}) };
$("location").checked = settings.locationOn;
$("location").addEventListener("change", async () => {
  settings.locationOn = $("location").checked;
  await browser.storage.local.set({ settings });
});

try {
  (await navigator.permissions.query({ name: "geolocation" })).onchange = showState;
} catch {
  // The state still shows on open and after each button press.
}
showState();
