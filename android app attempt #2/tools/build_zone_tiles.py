#!/usr/bin/env python3
"""Build the offline time-zone tiles for Tempus Fugit.

Input: timezone-boundary-builder timezones-now GeoJSON (.json or the release .zip).
Output: gzipped 10x10 degree tiles (TFZT v1, see ZoneTile.java), an untiled
reference file with every simplified polygon, the ODbL notice and a stats file.

usage: python -I build_zone_tiles.py INPUT(.zip|.json) TILES_DIR ALL_FILE STATS_FILE [DATA_LICENSE]
stdlib + numpy only.
"""
import gzip
import io
import json
import math
import os
import struct
import sys
import zipfile

import numpy as np

TOL_M = 100.0
MX = 111320.0          # metres per degree of longitude at the equator
MY = 110574.0          # metres per degree of latitude
E5 = 100000
RELEASE = "2026e"


# ---------------------------------------------------------------- simplification

def dp_chain(lon, lat, keep, i0, i1):
    """Douglas-Peucker on lon/lat[i0..i1] (inclusive), marking kept indices."""
    keep[i0] = keep[i1] = True
    stack = [(i0, i1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        sl = slice(a, b + 1)
        k = math.cos(math.radians(float(lat[sl].mean()))) * MX
        x = (lon[sl] - lon[a]) * k
        y = (lat[sl] - lat[a]) * MY
        bx, by = x[-1], y[-1]
        ll = bx * bx + by * by
        if ll > 0:
            t = np.clip((x * bx + y * by) / ll, 0.0, 1.0)
            d = np.hypot(x - t * bx, y - t * by)
        else:
            d = np.hypot(x, y)
        d[0] = d[-1] = 0.0
        m = int(d.argmax())
        if d[m] > TOL_M:
            keep[a + m] = True
            stack.append((a, a + m))
            stack.append((a + m, b))


def unwrap(lon):
    d = np.diff(lon)
    d = d - 360.0 * np.round(d / 360.0) * (np.abs(d) > 180.0)
    return np.concatenate(([lon[0]], lon[0] + np.cumsum(d)))


def ring_area_km2(lon, lat):
    k = math.cos(math.radians(float(lat.mean()))) * MX
    x, y = lon * k, lat * MY
    return abs(float(np.dot(x, np.roll(y, -1)) - np.dot(np.roll(x, -1), y))) / 2e6


def simplify_ring(coords):
    """coords: closed GeoJSON ring. Returns (planar int E5 ring as list of (lon,lat), kind)
    or (None, kind) if it collapses. kind: 'plain', 'wrap' (edges |dlon|>180) or 'polar'."""
    a = np.asarray(coords, dtype=np.float64)
    if len(a) > 1 and a[0, 0] == a[-1, 0] and a[0, 1] == a[-1, 1]:
        a = a[:-1]
    lon0, lat = a[:, 0], a[:, 1]
    n = len(a)
    closing = lon0[0] - lon0[-1]
    jumps = np.abs(np.diff(np.concatenate((lon0, lon0[:1])))) > 180.0
    kind = 'plain'
    lon = lon0
    if jumps.any():
        lon = unwrap(lon0)
        dc = lon0[0] - lon0[-1]
        if abs(dc) > 180.0:
            dc -= 360.0 * round(dc / 360.0)
        winding = (lon[-1] - lon[0]) + dc
        kind = 'polar' if abs(winding) > 180.0 else 'wrap'
    if kind == 'polar':
        # ring encircles a pole: simplify the open chain v0..v0', then close via the pole
        lonc = np.concatenate((lon, [lon[0] + round((lon[-1] + dc - lon[0]) / 360.0) * 360.0]))
        latc = np.concatenate((lat, [lat[0]]))
        keep = np.zeros(n + 1, dtype=bool)
        dp_chain(lonc, latc, keep, 0, n)
        pole = -90.0 if lat.mean() < 0 else 90.0
        pts = list(zip(lonc[keep], latc[keep]))
        pts += [(lonc[-1], pole), (lonc[0], pole)]
    else:
        if n < 3:
            return None, kind
        k = math.cos(math.radians(float(lat.mean()))) * MX
        far = int(np.hypot((lon - lon[0]) * k, (lat - lat[0]) * MY).argmax())
        lonc = np.concatenate((lon, lon[:1]))
        latc = np.concatenate((lat, lat[:1]))
        keep = np.zeros(n + 1, dtype=bool)
        if far > 0:
            dp_chain(lonc, latc, keep, 0, far)
            dp_chain(lonc, latc, keep, far, n)
        keep[n] = False
        pts = list(zip(lonc[keep], latc[keep]))
    ring = dedupe([(int(round(x * E5)), int(round(y * E5))) for x, y in pts])
    if len(set(ring)) < 3:
        return None, kind
    return ring, kind


def dedupe(r):
    out = []
    for p in r:
        if not out or out[-1] != p:
            out.append(p)
    while len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return out


def area2(r):
    s = 0
    n = len(r)
    for i in range(n):
        x1, y1 = r[i]
        x2, y2 = r[(i + 1) % n]
        s += x1 * y2 - x2 * y1
    return s


# ---------------------------------------------------------------- clipping

def clip_axis(pts, axis, v, keep_ge):
    """Sutherland-Hodgman against one axis-aligned half-plane (doubles)."""
    out = []
    n = len(pts)
    if n == 0:
        return out
    prev = pts[-1]
    pin = (prev[axis] >= v) if keep_ge else (prev[axis] <= v)
    for cur in pts:
        cin = (cur[axis] >= v) if keep_ge else (cur[axis] <= v)
        if cin != pin:
            t = (v - prev[axis]) / (cur[axis] - prev[axis])
            o = 1 - axis
            q = [0.0, 0.0]
            q[axis] = v
            q[o] = prev[o] + t * (cur[o] - prev[o])
            out.append((q[0], q[1]))
        if cin:
            out.append(cur)
        prev, pin = cur, cin
    return out


class Grid:
    def __init__(self, x0, w, nx, y0, h, ny):
        self.x0, self.w, self.nx, self.y0, self.h, self.ny = x0, w, nx, y0, h, ny

    def span(self, lo, hi, o, s):
        return (lo - o) // s, -((o - hi) // s) - 1   # floor, ceil-1

    def clip_ring(self, ring):
        """Yields ((ix, iy), int ring) for every cell the (possibly extended-lon) ring touches."""
        xs = [p[0] for p in ring]
        ys = [p[1] for p in ring]
        kx0, kx1 = self.span(min(xs), max(xs), self.x0, self.w)
        ky0, ky1 = self.span(min(ys), max(ys), self.y0, self.h)
        ky0, ky1 = max(ky0, 0), min(ky1, self.ny - 1)
        fp = [(float(x), float(y)) for x, y in ring]
        for kx in range(kx0, kx1 + 1):
            xa = self.x0 + kx * self.w
            xb = xa + self.w
            strip = fp
            if min(xs) < xa:
                strip = clip_axis(strip, 0, xa, True)
            if max(xs) > xb:
                strip = clip_axis(strip, 0, xb, False)
            if len(strip) < 3:
                continue
            wrapped = kx % self.nx
            shift = (wrapped - kx) * self.w
            sy = [p[1] for p in strip]
            for ky in range(ky0, ky1 + 1):
                ya = self.y0 + ky * self.h
                yb = ya + self.h
                if max(sy) <= ya or min(sy) >= yb:
                    continue
                cell = strip
                if min(sy) < ya:
                    cell = clip_axis(cell, 1, ya, True)
                if max(sy) > yb:
                    cell = clip_axis(cell, 1, yb, False)
                r = dedupe([(int(round(x)) + shift, int(round(y))) for x, y in cell])
                if len(r) < 3 or area2(r) == 0:
                    continue
                yield (wrapped, ky), r


TILES = Grid(-180 * E5, 10 * E5, 36, -90 * E5, 10 * E5, 18)
WHOLE = Grid(-180 * E5, 360 * E5, 1, -90 * E5, 180 * E5, 1)


# ---------------------------------------------------------------- binary format

def varint(out, z):
    while True:
        b = z & 0x7F
        z >>= 7
        if z:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zz(n):
    return ((n << 1) ^ (n >> 31)) & 0xFFFFFFFF


def encode(pieces, zone_names):
    """pieces: list of (zone name, [rings]) in source order."""
    zones = []
    zidx = {}
    for z, _ in pieces:
        if z not in zidx:
            zidx[z] = len(zones)
            zones.append(z)
    if zone_names is not None:          # fixed zone table (all-file)
        zones = list(zone_names)
        zidx = {z: i for i, z in enumerate(zones)}
    out = bytearray(b"TFZT")
    out += struct.pack(">BH", 1, len(zones))
    for z in zones:
        b = z.encode("utf-8")
        out += struct.pack(">B", len(b)) + b
    out += struct.pack(">I", len(pieces))
    for z, rings in pieces:
        xs = [p[0] for r in rings for p in r]
        ys = [p[1] for r in rings for p in r]
        out += struct.pack(">HiiiiH", zidx[z], min(xs), min(ys), max(xs), max(ys), len(rings))
        for r in rings:
            out += struct.pack(">I", len(r))
            px = py = 0
            for x, y in r:
                varint(out, zz(x - px))
                varint(out, zz(y - py))
                px, py = x, y
    return bytes(out)


def gz(data):
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as f:
        f.write(data)
    return buf.getvalue()


# ---------------------------------------------------------------- main

def load(path):
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            name = next(n for n in z.namelist() if n.endswith(".json") or n.endswith(".geojson"))
            return json.loads(z.read(name))
    with open(path, "rb") as f:
        return json.load(f)


LICENSE_HEAD = """Time-zone boundary data
=======================

The files in this folder (*.bin.gz) are derived from the timezone-boundary-builder
project, release {rel} ("timezones-now" GeoJSON),
https://github.com/evansiroky/timezone-boundary-builder/releases/tag/{rel}

Underlying data (c) OpenStreetMap contributors, https://www.openstreetmap.org/copyright
The timezone-boundary-builder data is made available under the Open Database
License (ODbL) v1.0: https://opendatacommons.org/licenses/odbl/1-0/

This tiled, simplified dataset (polygons simplified to about 100 m, coordinates
rounded to 1e-5 degrees, clipped to 10 x 10 degree tiles) is a Derivative
Database of that data and is likewise licensed under the ODbL v1.0. It was
produced with tools/build_zone_tiles.py in the Tempus Fugit source tree.
"""


def main(argv):
    if len(argv) < 5:
        print(__doc__)
        return 2
    src, tiles_dir, all_path, stats_path = argv[1:5]
    lic_path = argv[5] if len(argv) > 5 else None
    data = load(src)
    feats = data["features"]

    zone_names = []
    polys = []                       # (zone, [rings]) simplified, source order
    in_vertices = in_rings = 0
    kept_vertices = 0
    lost_polys = lost_rings = lost_holes = 0
    lost_area = lost_hole_area = 0.0
    specials = []
    for f in feats:
        tz = f["properties"]["tzid"]
        if tz not in zone_names:
            zone_names.append(tz)
        g = f["geometry"]
        plist = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
        for pi, p in enumerate(plist):
            rings = []
            outer_lost = False
            for ri, rc in enumerate(p):
                in_rings += 1
                in_vertices += len(rc)
                ring, kind = simplify_ring(rc)
                if kind != 'plain':
                    lons = [c[0] for c in rc]
                    specials.append((tz, pi, ri, kind, len(rc), min(lons), max(lons)))
                if ring is None:
                    lost_rings += 1
                    a = np.asarray(rc, dtype=np.float64)
                    ar = ring_area_km2(a[:, 0], a[:, 1])
                    if ri == 0:
                        outer_lost = True
                        lost_area += ar
                    else:
                        lost_holes += 1
                        lost_hole_area += ar
                    continue
                if ri == 0 or not outer_lost:
                    rings.append(ring)
            if outer_lost:
                lost_polys += 1
                continue
            kept_vertices += sum(len(r) for r in rings)
            polys.append((tz, rings))

    # all-file: one piece per simplified polygon, split at +-180 only if it wraps
    all_pieces = []
    for tz, rings in polys:
        rr = [r for ring in rings for _, r in WHOLE.clip_ring(ring)]
        if rr:
            all_pieces.append((tz, rr))
    all_bytes = gz(encode(all_pieces, zone_names))
    os.makedirs(os.path.dirname(os.path.abspath(all_path)), exist_ok=True)
    with open(all_path, "wb") as f:
        f.write(all_bytes)

    # tiles
    tiles = {}                       # (c, r) -> {poly index: [rings]}
    for idx, (tz, rings) in enumerate(polys):
        for ring in rings:
            for (kx, ky), r in TILES.clip_ring(ring):
                tiles.setdefault((kx - 18, ky - 9), {}).setdefault(idx, []).append(r)
    os.makedirs(tiles_dir, exist_ok=True)
    for name in os.listdir(tiles_dir):
        if name.endswith(".bin.gz"):
            os.remove(os.path.join(tiles_dir, name))
    sizes = {}
    tile_vertices = tile_pieces = 0
    for (c, r), d in sorted(tiles.items()):
        pieces = [(polys[i][0], d[i]) for i in sorted(d)]
        tile_pieces += len(pieces)
        tile_vertices += sum(len(x) for _, rr in pieces for x in rr)
        b = gz(encode(pieces, None))
        name = "%d_%d.bin.gz" % (c, r)
        with open(os.path.join(tiles_dir, name), "wb") as f:
            f.write(b)
        sizes[name] = len(b)

    lic = LICENSE_HEAD.format(rel=RELEASE)
    if lic_path:
        with open(lic_path, encoding="utf-8") as f:
            lic += "\n\nFull licence text (DATA_LICENSE from timezone-boundary-builder)\n" \
                   "================================================================\n\n" + f.read()
    with open(os.path.join(tiles_dir, "LICENSE-ODbL.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(lic)

    sv = sorted(sizes.values())
    distinct_in = in_vertices - in_rings
    biggest = max(sizes, key=sizes.get)
    lines = [
        "source: timezone-boundary-builder %s timezones-now (%s)" % (RELEASE, os.path.basename(src)),
        "features (zones): %d, distinct tzids: %d" % (len(feats), len(zone_names)),
        "polygons: %d, rings: %d" % (sum(len([f["geometry"]["coordinates"]] if f["geometry"]["type"] == "Polygon" else f["geometry"]["coordinates"]) for f in feats), in_rings),
        "input vertices: %d (%d without ring-closing duplicates)" % (in_vertices, distinct_in),
        "kept vertices: %d (%.2f%% of %d), tolerance %.0f m" % (kept_vertices, 100.0 * kept_vertices / distinct_in, distinct_in, TOL_M),
        "lost polygons: %d (%.1f km2), lost rings: %d (of which holes: %d, %.2f km2)" % (lost_polys, lost_area, lost_rings, lost_holes, lost_hole_area),
        "kept polygons: %d" % len(polys),
        "non-plain rings: " + "; ".join("%s poly %d ring %d %s (%d vertices, lon %.3f..%.3f)" % s for s in specials),
        "tile files: %d, pieces: %d, tile vertices: %d" % (len(sizes), tile_pieces, tile_vertices),
        "total gzipped tile size: %d bytes" % sum(sv),
        "tile size median / p90 / max: %d / %d / %d bytes (max: %s)" % (
            sv[len(sv) // 2], sv[min(len(sv) - 1, int(math.ceil(0.9 * len(sv))) - 1)], sv[-1], biggest),
        "all.bin.gz: %d bytes (%d pieces)" % (len(all_bytes), len(all_pieces)),
    ]
    text = "\n".join(lines) + "\n"
    print(text, end="")
    with open(stats_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
