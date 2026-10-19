// DEBUG ONLY. Left out of release builds (see README): web-ext build --ignore-files shared/debug.js.
// Open a clock page with ?lon=-70&lat=42 to inject a position; add &west=28 to move west at 28 m/s.
export function injectedPosition() {
  const q = new URLSearchParams(location.search);
  if (!q.has("lon")) return null;
  const lat = Number(q.get("lat") ?? 42.36), lon0 = Number(q.get("lon")), west = Number(q.get("west") ?? 0);
  const start = Date.now();
  return () => {
    const t = Date.now();
    const lon = lon0 - (west * (t - start)) / 1000 / (111320 * Math.cos((lat * Math.PI) / 180));
    return { timestamp: t, coords: { latitude: lat, longitude: lon, accuracy: 10 } };
  };
}
