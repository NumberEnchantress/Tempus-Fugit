import { startClock } from "../shared/clock.js";

const $ = (id) => document.getElementById(id);
const openSetup = (e) => {
  e.preventDefault();
  browser.tabs.create({ url: browser.runtime.getURL("setup/setup.html") });
  window.close();
};
$("settings").addEventListener("click", openSetup);
$("setup-link").addEventListener("click", openSetup);

startClock({
  face: $("face"), tempus: $("tempus"), local: $("local"), system: $("system"), warning: $("warning"),
  accuracy: $("accuracy"), equation: $("equation"), locationSwitch: $("location"), setupLink: $("setup-link"),
  faceSetting: "widgetAnalog", // the popup is the extension's widget
});
