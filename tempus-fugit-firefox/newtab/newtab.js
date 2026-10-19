import { startClock } from "../shared/clock.js";

const $ = (id) => document.getElementById(id);

// No background script, so setup cannot open itself on install: the first
// clock page sends the user there once instead.
const { setup_seen: seen } = await browser.storage.local.get("setup_seen");
if (!seen) {
  location.replace(browser.runtime.getURL("setup/setup.html"));
} else {
  startClock({
    face: $("face"), tempus: $("tempus"), local: $("local"), system: $("system"), warning: $("warning"),
    accuracy: $("accuracy"), equation: $("equation"), setupLink: $("setup-link"),
  });
}
