import * as M from "../shared/model.js";

const $ = (id) => document.getElementById(id);
const stored = await browser.storage.local.get(["settings"]);
const s = { ...M.defaultSettings(), ...(stored.settings || {}) };
const save = () => browser.storage.local.set({ settings: s });

function labels() {
  $("x-label").textContent = `maximum smoothing time - ${s.xMinutes} min`;
  $("ping-label").textContent = `maximum ping frequency - one ping per ${s.pingFloorS} s`;
  $("ping-warning").hidden = !M.batteryWarning(s.pingFloorS);
  $("x").disabled = s.instant;
  $("trend").disabled = s.instant;
}

function radio(name, value, onChange) {
  for (const r of document.getElementsByName(name)) {
    r.checked = r.value === value;
    r.addEventListener("change", () => {
      onChange(r.value);
      save();
      labels();
    });
  }
}

/** Freeze and the forced correction act on the stored model, which the clock pages pick up. */
async function updateModel(change) {
  const { model } = await browser.storage.local.get("model");
  const m = M.Model.fromJSON(model);
  change(m);
  await browser.storage.local.set({ model: m.toJSON() });
}

radio("mode", s.instant ? "instant" : "smooth", (v) => (s.instant = v === "instant"));
radio("precision", s.precisionSeconds ? "seconds" : "minutes", (v) => (s.precisionSeconds = v === "seconds"));
radio("face", s.analog ? "analog" : "digital", (v) => (s.analog = v === "analog"));
radio("widget-face", s.widgetAnalog ? "analog" : "digital", (v) => (s.widgetAnalog = v === "analog"));

$("x").value = s.xMinutes;
$("x").addEventListener("input", () => {
  const v = Number($("x").value);
  if (M.validX(v)) {
    s.xMinutes = v;
    save();
  }
  labels();
});

$("trend").checked = s.trendAware;
$("trend").addEventListener("change", () => {
  s.trendAware = $("trend").checked;
  save();
});

$("freeze").checked = s.freeze;
$("freeze").addEventListener("change", async () => {
  s.freeze = $("freeze").checked;
  if (s.freeze) await updateModel((m) => m.hold(Date.now()));
  save();
});

$("ping").value = M.PING_STEPS_S.indexOf(s.pingFloorS);
$("ping").addEventListener("input", () => {
  s.pingFloorS = M.PING_STEPS_S[Number($("ping").value)];
  save();
  labels();
});

$("budget").value = s.distanceBudgetM;
$("budget").addEventListener("input", () => {
  const text = $("budget").value.trim();
  const v = Number(text);
  const ok = text !== "" && M.validBudget(v);
  $("budget-error").hidden = ok; // out of range: inline error, not saved
  if (ok) {
    s.distanceBudgetM = v;
    save();
  }
});

$("correct").addEventListener("click", async () => {
  if (!confirm("Set the clock to the target now? It may jump, including backwards.")) return;
  await updateModel((m) => m.correct(Date.now()));
});

labels();
