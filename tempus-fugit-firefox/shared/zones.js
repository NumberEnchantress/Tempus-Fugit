// Offline zone lookup (plan 4.2): 10° tiles of the timezone-boundary-builder
// data (ODbL), one tile in memory, and a zone cache keyed on the distance to
// the nearest edge. A port of the Android ZoneTile, ZoneLookup and ZoneCache.

const R = 6371008.8;
const M_DEG = (R * Math.PI) / 180;
const E5 = 1e5;

/** Parse a TFZT v1 tile from its uncompressed bytes. */
export function parseTile(buffer) {
  const v = new DataView(buffer);
  let o = 0;
  const u8 = () => v.getUint8(o++);
  const u16 = () => { const x = v.getUint16(o); o += 2; return x; };
  const i32 = () => { const x = v.getInt32(o); o += 4; return x; };
  const varint = () => {
    let x = 0;
    for (let shift = 0; shift < 35; shift += 7) {
      const b = u8();
      x |= (b & 0x7f) << shift;
      if (b < 0x80) return x >>> 0;
    }
    throw new Error("bad varint");
  };
  const unzig = (z) => (z >>> 1) ^ -(z & 1);

  if (i32() !== 0x54465a54) throw new Error("not a TFZT file");
  if (u8() !== 1) throw new Error("unsupported TFZT version");
  const zones = [];
  const decoder = new TextDecoder();
  for (let n = u16(), i = 0; i < n; i++) {
    const len = u8();
    zones.push(decoder.decode(new Uint8Array(buffer, o, len)));
    o += len;
  }
  const pieces = [];
  for (let n = i32(), p = 0; p < n; p++) {
    const zone = u16();
    const box = [i32(), i32(), i32(), i32()];
    const rings = [];
    for (let rc = u16(), r = 0; r < rc; r++) {
      const vc = i32();
      const xy = new Int32Array(2 * vc);
      let x = 0, y = 0;
      for (let k = 0; k < vc; k++) {
        x += unzig(varint());
        y += unzig(varint());
        xy[2 * k] = x;
        xy[2 * k + 1] = y;
      }
      rings.push(xy);
    }
    pieces.push({ zone, box, rings });
  }
  return { zones, pieces };
}

export function pieceAt(tile, lat, lon) {
  const px = lon * E5, py = lat * E5;
  for (let p = 0; p < tile.pieces.length; p++) {
    const { box, rings } = tile.pieces[p];
    if (px < box[0] || py < box[1] || px > box[2] || py > box[3]) continue;
    let inside = false;
    for (const xy of rings) {
      const n = xy.length / 2;
      for (let i = 0, j = n - 1; i < n; j = i++) {
        const yi = xy[2 * i + 1], yj = xy[2 * j + 1];
        if ((yi > py) !== (yj > py)) {
          const xi = xy[2 * i], xj = xy[2 * j];
          if (px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) inside = !inside;
        }
      }
    }
    if (inside) return p;
  }
  return -1;
}

const cosE5 = (latE5) => Math.cos((Math.min(9000000, latE5) / E5) * (Math.PI / 180));

function segDist2(ax, ay, bx, by, kx) {
  ax *= kx;
  bx *= kx;
  const dx = bx - ax, dy = by - ay, ll = dx * dx + dy * dy;
  let t = ll > 0 ? -(ax * dx + ay * dy) / ll : 0;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx, cy = ay + t * dy;
  return cx * cx + cy * cy;
}

/** Lower bound, in metres, on the distance from the point to any edge in the tile. */
export function nearestEdgeMeters(tile, lat, lon) {
  const qx = lon * E5, qy = lat * E5, aq = Math.abs(qy);
  let best = Infinity;
  for (const { rings } of tile.pieces) {
    for (const xy of rings) {
      const n = xy.length / 2;
      for (let i = 0, j = n - 1; i < n; j = i++) {
        const m = Math.max(aq, Math.abs(xy[2 * j + 1]), Math.abs(xy[2 * i + 1]));
        const d = segDist2(xy[2 * j] - qx, xy[2 * j + 1] - qy, xy[2 * i] - qx, xy[2 * i + 1] - qy, cosE5(m));
        if (d < best) best = d;
      }
    }
  }
  return (0.998 * Math.sqrt(best) * M_DEG) / E5;
}

const normLon = (lon) => (lon >= 180 || lon < -180 ? lon - 360 * Math.floor((lon + 180) / 360) : lon);
const col = (lon) => Math.max(-18, Math.min(17, Math.floor(lon / 10)));
const row = (lat) => Math.max(-9, Math.min(8, Math.floor(lat / 10)));

export function tileName(lat, lon) {
  return col(normLon(lon)) + "_" + row(lat) + ".bin.gz";
}

function rectDistance(lat, lon, c, r) {
  const rad = Math.PI / 180;
  let d = Infinity;
  if (r > -9) d = Math.min(d, (lat - 10 * r) * rad * R);
  if (r < 8) d = Math.min(d, (10 * (r + 1) - lat) * rad * R);
  const cl = Math.cos(lat * rad);
  d = Math.min(d, Math.asin(Math.min(1, cl * Math.sin((lon - 10 * c) * rad))) * R);
  d = Math.min(d, Math.asin(Math.min(1, cl * Math.sin((10 * (c + 1) - lon) * rad))) * R);
  return Math.max(0, d);
}

/** Reads a gzipped tile through fetch; null when the tile has no file (open water). */
export async function fetchTile(url) {
  let res;
  try {
    res = await fetch(url);
  } catch {
    return null;
  }
  if (!res.ok) return null;
  const stream = res.body.pipeThrough(new DecompressionStream("gzip"));
  return parseTile(await new Response(stream).arrayBuffer());
}

export class ZoneLookup {
  /** open(name) resolves to a parsed tile or null. */
  constructor(open) {
    this.open = open;
    this.loadedName = null;
    this.loaded = null;
    this.reads = 0;
  }

  async lookup(lat, lon) {
    lon = normLon(lon);
    lat = Math.max(-90, Math.min(90, lat));
    const c = col(lon), r = row(lat);
    const name = c + "_" + r + ".bin.gz";
    if (name !== this.loadedName) {
      this.loaded = null;
      this.loadedName = null;
      const tile = await this.open(name);
      if (tile) this.reads++;
      this.loaded = tile;
      this.loadedName = name;
    }
    let edge = rectDistance(lat, lon, c, r);
    let zoneId = null;
    if (this.loaded) {
      const p = pieceAt(this.loaded, lat, lon);
      if (p >= 0) zoneId = this.loaded.zones[this.loaded.pieces[p].zone];
      edge = Math.min(edge, nearestEdgeMeters(this.loaded, lat, lon));
    }
    return { zoneId, edgeDistanceM: edge, tile: name };
  }
}

/** Looks up only when the point has moved at least the last result's edge distance. */
export class ZoneCache {
  constructor(lookup) {
    this.lookup = lookup;
    this.have = false;
    this.zoneId = null;
    this.edgeM = 0;
    this.lat = 0;
    this.lon = 0;
    this.lookups = 0;
  }

  async update(lat, lon) {
    if (!this.have || haversine(this.lat, this.lon, lat, lon) >= this.edgeM) {
      const r = await this.lookup.lookup(lat, lon);
      this.lookups++;
      this.have = true;
      this.zoneId = r.zoneId;
      this.edgeM = r.edgeDistanceM;
      this.lat = lat;
      this.lon = lon;
    }
    return this.zoneId;
  }
}

function haversine(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180;
  const p1 = lat1 * r, p2 = lat2 * r, dp = p2 - p1, dl = (lon2 - lon1) * r;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}
