// Shared model checks (criteria 1–16, 22) and zone checks (36, 38–41) for the
// extension's JavaScript, mirroring the Android ModelCheck and ZoneCheck.
// Run: node tests/check.mjs [path/to/all.bin.gz]
import { readFileSync, existsSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import * as M from "../shared/model.js";
import { parseTile, pieceAt, nearestEdgeMeters, ZoneLookup, ZoneCache, tileName } from "../shared/zones.js";

let passes = 0, failures = 0;
const check = (what, ok) => {
  console.log("  " + (ok ? "PASS " : "FAIL ") + what);
  ok ? passes++ : failures++;
};
const section = (s) => console.log(s);
const bytes = (b) => b.buffer.slice(b.byteOffset, b.byteOffset + b.length);
const T0 = 1784000000000, LAT = 42.36;
const settings = (instant = false) => ({ ...M.defaultSettings(), instant });
const lonRate = (ms, lat) => -ms / (M.METERS_PER_DEGREE * Math.cos((lat * Math.PI) / 180));
const accS = (m, lat) => m * M.secondsPerMeter(lat);
const withGap = (lon, gap, instant) => {
  const m = new M.Model();
  m.onFix(T0, lon - gap / 240, 0, settings(instant));
  m.onFix(T0 + 1000, lon, 0, settings(instant));
  return m;
};

section("1–2. offsets and antimeridian");
check("T(−70) == −16800, T(180) == 43200, normalise(190) == −170",
  M.targetSeconds(-70) === -16800 && M.targetSeconds(180) === 43200 && M.normalise(190) === -170);
{
  const e = M.timeOfDayMs(T0 + Math.round(M.targetSeconds(179.9999) * 1000));
  const w = M.timeOfDayMs(T0 + Math.round(M.targetSeconds(-179.9999) * 1000));
  const d = Math.min(Math.abs(e - w), M.DAY_MS - Math.abs(e - w));
  check(`time of day at ±179.9999 differs by ${d} ms`, d < 1000);
  const m = new M.Model();
  m.onFix(T0, 179.999, 0, settings());
  m.onFix(T0 + 15000, -179.999, 0, settings());
  check("crossing 180° leaves no day-sized gap", Math.abs(m.gA) < 1);
}

section("3. stationary");
{
  const m = new M.Model();
  let ok = true;
  for (let i = 0; i < 30; i++) {
    const t = T0 + i * 60000;
    m.onFix(t, -71.06, 0, settings());
    ok &&= m.speed(t + 30000) === 1 && m.gap(t + 30000) === 0;
  }
  check("speed exactly 1, gap exactly 0", ok);
}

section("4. 100 km/h west, both modes");
for (const instant of [false, true]) {
  const m = new M.Model();
  m.intervalS = 15;
  const rate = lonRate(100 / 3.6, LAT);
  let worst = 0;
  for (let t = T0; t <= T0 + 600000; t += 15000) {
    m.onFix(t, -71.06 + (rate * (t - T0)) / 1000, accS(5, LAT), settings(instant));
    for (let u = t; u < t + 15000; u += 250) {
      if (u < T0 + 300000) continue;
      worst = Math.max(worst, Math.abs(m.offset(u) - M.targetSeconds(-71.06 + (rate * (u - T0)) / 1000)));
    }
  }
  check(`${instant ? "instant" : "smooth"}: within ${worst.toFixed(3)} s after 5 min`, worst < 1);
}

section("5–8. settle law");
{
  check(`gap 30 s → ${M.settleTime(30, 300).toFixed(1)} s`, Math.abs(M.settleTime(30, 300) - 272) < 1);
  const m = withGap(-71.06, 30, false);
  check("gap ≤ 0.5 s at the settle time", Math.abs(m.gap(m.tA + Math.round(M.settleTime(30, 300) * 1000))) <= 0.5 + 1e-4);
  check(`gap 3 s → ${M.settleTime(3, 300).toFixed(1)} s`, Math.abs(M.settleTime(3, 300) - 20) < 1);
  let under = true;
  for (let g = 0.5; g < 1e7; g *= 1.01) under &&= M.settleTime(g, 300) < 300;
  check("gap 1 h approaches 300 s and never exceeds it", M.settleTime(3600, 300) > 299.9 && under);
  check("gap 0.5001 s settles in almost no time", M.settleTime(0.5001, 300) < 1e-3);
}

section("9–10. instant mode, guard");
{
  const m = new M.Model();
  m.onFix(T0, -71.06, 0, settings(true));
  const before = m.shownMs(T0 + 999);
  m.onFix(T0 + 1000, -71.06 + 30 / 240, 0, settings(true));
  check("instant: jumps 30 s at the fix", Math.abs((m.shownMs(T0 + 1000) - before) / 1000 - 30.001) < 1e-6);

  const g = new M.Model();
  let last = -Infinity, nonDecreasing = true, held = false, lon = 0;
  for (let t = T0; t <= T0 + 3600000; t += 1000) {
    if ((t - T0) % 15000 === 0) {
      if (t <= T0 + 900000) lon = (-20 / 3600) * ((t - T0) / 1000);
      g.onFix(t, lon, 0, settings());
    }
    const s = g.shownMs(t);
    if (s < last) nonDecreasing = false;
    held ||= g.holding;
    last = s;
  }
  check("guard: never decreases, holds, and resumes", nonDecreasing && held && !g.holding);
}

section("11. renewal");
{
  const m = new M.Model();
  m.intervalS = 60;
  const rate = lonRate(100 / 3.6, LAT);
  let t = T0;
  for (; t <= T0 + 120000; t += 15000) m.onFix(t, -71.06 + (rate * (t - T0)) / 1000, accS(5, LAT), settings());
  const at = t - 15000 + m.renewalMs();
  m.renew(at);
  check("renewal at S with v = 0", m.tA === at && m.vA === 0);
}

section("12–13. rate");
{
  const m = new M.Model();
  const rate = lonRate(100 / 3.6, LAT), vTrue = rate * 240;
  for (let t = T0; t <= T0 + 600000; t += 15000) m.onFix(t, -71.06 + (rate * (t - T0)) / 1000, accS(5, LAT), settings());
  check(`plain rate ${m.vA.toFixed(4)} (true ${vTrue.toFixed(4)})`, Math.abs(m.vA - vTrue) < 1e-3);
  let seed = 42;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
  for (const trend of [false, true]) {
    const n = new M.Model();
    n.intervalS = 60;
    let zero = true;
    for (let t = T0; t <= T0 + 600000; t += 60000) {
      const r = 500 * Math.sqrt(rnd()), a = rnd() * 2 * Math.PI;
      const lon = -71.06 + (r * Math.cos(a)) / (M.METERS_PER_DEGREE * Math.cos((LAT * Math.PI) / 180));
      n.onFix(t, lon, accS(500, LAT), { ...settings(), trendAware: trend });
      zero &&= n.vA === 0;
    }
    check(`${trend ? "trend-aware" : "plain"}: 500 m noise keeps the rate at exactly 0`, zero);
  }
}

section("14–16. correction, freeze, X");
{
  const m = withGap(-71.06, -20, false);
  const press = m.tA + 5000, before = m.shownMs(press - 1);
  m.correct(press);
  const after = m.shownMs(press);
  check("correction: display = target, may go backward",
    Math.abs(after - (press + m.target(press) * 1000)) < 1e-6 && after < before);
  const f = new M.Model();
  f.onFix(T0, -71.06, 0, settings());
  f.onFix(T0 + 15000, -71.05, 0, settings());
  const target = f.target(T0 + 20000);
  f.hold(T0 + 20000);
  check("freeze: target holds, v = 0", f.target(T0 + 3600000) === target && f.vA === 0);
  const x = withGap(-71.06, 30, false);
  x.onFix(x.tA + 15000, -71.06 + 60 / 240, 0, { ...settings(), xMinutes: 10 });
  check("X applies at the next fix", x.xSeconds === 600);
  const restored = M.Model.fromJSON(JSON.parse(JSON.stringify(x.toJSON())));
  check("stored model round-trips and holds no coordinates",
    restored.offset(x.tA + 5000) === x.offset(x.tA + 5000) && !/lat|lon/i.test(JSON.stringify(x.toJSON())));
}

section("speed rule and face");
{
  check("interval for 0, 1.4, 5, 14, 28 m/s → 60, 60, 20, 15, 15",
    [0, 1.4, 5, 14, 28].map((s) => M.SpeedRule.intervalFor(s, 15, 100)).join() === "60,60,20,15,15");
  const a = M.handAngles(12 * 3600000 + 30 * 60000 + 45000, true);
  check("22. hands at 12:30:45 → 15.375°, 184.5°, 270°", Math.abs(a[0] - 15.375) < 1e-9 && Math.abs(a[1] - 184.5) < 1e-9 && a[2] === 270);
  const fr = M.computeFrame(new M.Model(), T0, true, null, "UTC");
  check("readout before the first fix", fr.tempus === "tempus fugit - --:--:--");
  const ny = new M.Model();
  ny.onFix(T0, -74.01, 0, settings());
  const july = M.computeFrame(ny, Date.UTC(2026, 6, 15, 12), true, "America/New_York", "UTC");
  const jan = M.computeFrame(ny, Date.UTC(2026, 0, 15, 12), true, "America/New_York", "UTC");
  check(`37. New York local time: ${july.local} in July, ${jan.local} in January`,
    july.local === "local time - 08:00:00" && jan.local === "local time - 07:00:00");
}

section("zones");
{
  const tiles = fileURLToPath(new URL("../data/tiles/", import.meta.url));
  const open = async (name) => (existsSync(tiles + name) ? parseTile(bytes(gunzipSync(readFileSync(tiles + name)))) : null);
  const lookup = new ZoneLookup(open);
  const known = [[40.71, -74.01, "America/New_York"], [33.45, -112.07, "America/Phoenix"],
    [22.57, 88.36, "Asia/Kolkata"], [0, -140, null], [30, -40, null]];
  let ok = true;
  for (const [lat, lon, want] of known) {
    const r = await lookup.lookup(lat, lon);
    ok &&= r.zoneId === want;
    if (r.zoneId !== want) console.log(`      ${lat}, ${lon} → ${r.zoneId}, expected ${want}`);
  }
  check("36. New York, Phoenix, Kolkata, and mid-ocean fallback", ok);

  const l2 = new ZoneLookup(open);
  await l2.lookup(40.71, -74.01);
  await l2.lookup(40.2, -74.5);
  const sameTile = l2.reads === 1;
  await l2.lookup(33.45, -112.07);
  check("39. a tile is read only when the point changes tile", sameTile && l2.reads === 2 && l2.loadedName === tileName(33.45, -112.07));

  const cache = new ZoneCache(new ZoneLookup(open));
  await cache.update(40.71, -74.01);
  const edge = cache.edgeM;
  await cache.update(40.71 + (0.5 * edge) / 111195, -74.01);
  const skipped = cache.lookups === 1;
  await cache.update(40.71 + (1.01 * edge) / 111195, -74.01);
  check(`38. zone cache: no lookup inside ${Math.round(edge)} m, a lookup past it`, skipped && cache.lookups === 2);

  const allPath = process.argv[2];
  if (allPath && existsSync(allPath)) {
    const all = parseTile(bytes(gunzipSync(readFileSync(allPath))));
    const tiled = new ZoneLookup(open);
    let s = 7, bad = 0, excused = 0, inside = 0;
    const rnd = () => ((s = (s * 1103515245 + 12345) % 2147483648) / 2147483648);
    const pts = [];
    for (let i = 0; i < 100000; i++) pts.push([(Math.asin(2 * rnd() - 1) * 180) / Math.PI, rnd() * 360 - 180]);
    pts.sort((a, b) => tileName(a[0], a[1]).localeCompare(tileName(b[0], b[1])));
    for (const [lat, lon] of pts) {
      const p = pieceAt(all, lat, lon);
      const want = p >= 0 ? all.zones[all.pieces[p].zone] : null;
      if (want) inside++;
      const got = (await tiled.lookup(lat, lon)).zoneId;
      if (got !== want) nearestEdgeMeters(all, lat, lon) <= 1 ? excused++ : bad++;
    }
    check(`40. tiled vs single file on 100000 points (${inside} inside): ${bad} disagreements, ${excused} excused`, bad === 0);
  } else {
    console.log("  (40 skipped: pass the path to all.bin.gz)");
  }
}

console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures ? 1 : 0);
