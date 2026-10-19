package com.tempusfugit.solarclock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Plain-main checks for the offline zone lookup. Args: assets/tiles folder, all.bin.gz. */
public final class ZoneCheck {
    private static int passed, failed;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: ZoneCheck <assets/tiles dir> <all.bin.gz>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        ZoneLookup.TileSource src = name -> {
            File f = new File(dir, name);
            return f.isFile() ? new FileInputStream(f) : null;
        };
        ZoneTile all;
        long t0 = System.nanoTime();
        try (InputStream in = new FileInputStream(args[1])) {
            all = ZoneTile.read(in);
        }
        System.out.printf("all-file: %d pieces, %d vertices, parsed in %.1f ms%n",
                all.pieceCount(), all.vertexCount(), (System.nanoTime() - t0) / 1e6);

        knownPoints(src);
        dst();
        antimeridian(src);
        tiles(src);
        cache(src);
        tileFiles(dir);
        edgeBound(src);
        agreement(src, all);
        largestTile(dir);

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
    }

    private static void expect(ZoneLookup lk, String label, double lat, double lon, String zone) throws IOException {
        ZoneLookup.Result r = lk.lookup(lat, lon);
        check(label + " " + lat + "," + lon + " -> " + zone, Objects.equals(r.zoneId, zone),
                "got " + r.zoneId + ", edge " + Math.round(r.edgeDistanceM) + " m, tile " + r.tile);
    }

    private static void knownPoints(ZoneLookup.TileSource src) throws IOException {
        ZoneLookup lk = new ZoneLookup(src);
        expect(lk, "New York", 40.71, -74.01, "America/New_York");
        expect(lk, "Phoenix", 33.45, -112.07, "America/Phoenix");
        expect(lk, "Kolkata", 22.57, 88.36, "Asia/Kolkata");
        expect(lk, "Pacific ocean", 0, -140, null);
        expect(lk, "Atlantic ocean", 30, -40, null);
        expect(lk, "London", 51.5074, -0.1278, "Europe/London");
        expect(lk, "Tokyo", 35.68, 139.69, "Asia/Tokyo");
        expect(lk, "Sydney", -33.87, 151.21, "Australia/Sydney");
        expect(lk, "Honolulu", 21.31, -157.86, "Pacific/Honolulu");
        expect(lk, "Anchorage", 61.22, -149.9, "America/Anchorage");
        // timezones-now merges zones with identical current rules: Anadyr (UTC+12, no DST) is filed under Pacific/Fiji
        expect(lk, "Anadyr", 64.73, 177.5, "Pacific/Fiji");
        expect(lk, "Chukotka east of 180", 66.0, -171.0, "Pacific/Fiji");
        expect(lk, "Fiji", -18.1, 178.4, "Pacific/Fiji");
        expect(lk, "Fiji Lau group east of 180", -18.2, -178.8, "Pacific/Fiji");
        expect(lk, "Chatham", -43.95, -176.55, "Pacific/Chatham");
        expect(lk, "Adak west of 180", 51.9, 179.5, "America/Adak");
        expect(lk, "lon 180 == -180", -78, 180.0, "Pacific/Auckland");
        expect(lk, "lon 540 normalised", 40.71, -74.01 + 720, "America/New_York");
    }

    private static void dst() {
        Instant jul = Instant.parse("2026-07-15T12:00:00Z"), jan = Instant.parse("2026-01-15T12:00:00Z");
        int[][] want = {{-4 * 3600, -5 * 3600}, {-7 * 3600, -7 * 3600}};
        String[] ids = {"America/New_York", "America/Phoenix"};
        for (int i = 0; i < 2; i++) {
            ZoneId z = ZoneId.of(ids[i]);
            int a = z.getRules().getOffset(jul).getTotalSeconds(), b = z.getRules().getOffset(jan).getTotalSeconds();
            check("DST " + ids[i] + " Jul " + a / 3600 + " h, Jan " + b / 3600 + " h",
                    a == want[i][0] && b == want[i][1], "");
        }
    }

    private static void antimeridian(ZoneLookup.TileSource src) throws IOException {
        ZoneLookup lk = new ZoneLookup(src);
        double[][] pts = {{-78, 179.5}, {-78, -179.5}, {-78, 179.999}, {-78, -179.999}, {-89.5, 0}, {-89.9, 90},
                {-87, -100}, {-80, -160}, {-76, -160}, {-69, 170}, {-85, 165}};
        for (double[] p : pts) expect(lk, "Antarctic Pacific/Auckland sector", p[0], p[1], "Pacific/Auckland");
        expect(lk, "outside the sector", -74, -160, null);
    }

    private static void tiles(ZoneLookup.TileSource src) throws IOException {
        ZoneLookup lk = new ZoneLookup(src);
        lk.lookup(40.71, -74.01);
        check("first lookup reads one tile", lk.tileReads() == 1 && "-8_4.bin.gz".equals(lk.loadedTile()),
                "reads " + lk.tileReads() + ", loaded " + lk.loadedTile());
        lk.lookup(42.36, -71.06);
        lk.lookup(40.0, -79.9);
        check("same-tile lookups read nothing", lk.tileReads() == 1, "reads " + lk.tileReads());
        lk.lookup(51.5074, -0.1278);
        check("other tile is read and replaces the loaded one", lk.tileReads() == 2 && "-1_5.bin.gz".equals(lk.loadedTile()),
                "reads " + lk.tileReads() + ", loaded " + lk.loadedTile());
        lk.lookup(40.71, -74.01);
        check("returning reads the first tile again (only one held)", lk.tileReads() == 3 && "-8_4.bin.gz".equals(lk.loadedTile()),
                "reads " + lk.tileReads());
        ZoneLookup.Result sea = lk.lookup(0, -140);
        check("missing tile: no read, null zone, edge = tile boundary",
                lk.tileReads() == 3 && sea.zoneId == null && Math.abs(sea.edgeDistanceM - 0) < 1 && "-14_0.bin.gz".equals(sea.tile),
                "reads " + lk.tileReads() + ", edge " + sea.edgeDistanceM);
        ZoneLookup.Result sea2 = lk.lookup(5, -145);
        check("missing tile edge distance ~ 5 deg", Math.abs(sea2.edgeDistanceM - 5 * 111195) < 3000,
                Math.round(sea2.edgeDistanceM) + " m");
        check("tileName", ZoneLookup.tileName(40.71, -74.01).equals("-8_4.bin.gz")
                && ZoneLookup.tileName(90, 180).equals("-18_8.bin.gz")
                && ZoneLookup.tileName(-90, 179.99).equals("17_-9.bin.gz"), "");
    }

    private static void cache(ZoneLookup.TileSource src) throws IOException {
        ZoneCache zc = new ZoneCache(new ZoneLookup(src));
        String z = zc.update(40.71, -74.01);
        double e = zc.edgeM();
        check("cache first update looks up", zc.lookups() == 1 && "America/New_York".equals(z) && e > 0,
                "edge " + Math.round(e) + " m");
        double[] near = dest(40.71, -74.01, 60, 0.5 * e);
        zc.update(near[0], near[1]);
        double[] near2 = dest(40.71, -74.01, 250, 0.99 * e);
        zc.update(near2[0], near2[1]);
        check("move shorter than edge distance does not look up", zc.lookups() == 1, "lookups " + zc.lookups());
        double[] far = dest(40.71, -74.01, 60, 1.01 * e);
        zc.update(far[0], far[1]);
        check("move past edge distance looks up", zc.lookups() == 2 && zc.lookupLat() == far[0] && zc.lookupLon() == far[1],
                "lookups " + zc.lookups());
        ZoneCache sea = new ZoneCache(new ZoneLookup(src));
        check("cache ocean -> null", sea.update(30, -40) == null && sea.lookups() == 1, "edge " + Math.round(sea.edgeM()));
    }

    /** Every tile parses and every vertex lies inside its own tile rectangle. */
    private static void tileFiles(File dir) throws IOException {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".bin.gz"));
        int bad = 0, pieces = 0;
        for (File f : files) {
            String[] cr = f.getName().replace(".bin.gz", "").split("_");
            int c = Integer.parseInt(cr[0]), r = Integer.parseInt(cr[1]);
            ZoneTile t;
            try (InputStream in = new FileInputStream(f)) {
                t = ZoneTile.read(in);
            }
            pieces += t.pieceCount();
            int[] b = t.vertexBounds();
            if (b[0] < c * 1_000_000 || b[2] > (c + 1) * 1_000_000 || b[1] < r * 1_000_000 || b[3] > (r + 1) * 1_000_000) bad++;
            if (!ZoneLookup.tileName(10 * r + 5, 10 * c + 5).equals(f.getName())) bad++;
        }
        check("all " + files.length + " tile files parse, vertices inside their tile (" + pieces + " pieces)", bad == 0, "bad " + bad);
    }

    /** Moving less than edgeDistanceM never changes the tiled result. */
    private static void edgeBound(ZoneLookup.TileSource src) throws IOException {
        Random rnd = new Random(7);
        ZoneLookup lk = new ZoneLookup(src);
        int bad = 0, n = 0;
        while (n < 3000) {
            double lat = Math.toDegrees(Math.asin(2 * rnd.nextDouble() - 1)), lon = 360 * rnd.nextDouble() - 180;
            if (Math.abs(lat) > 85) continue;
            ZoneLookup.Result r = lk.lookup(lat, lon);
            if (r.zoneId == null && n % 3 != 0) continue;   // favour land points
            n++;
            for (int k = 0; k < 8; k++) {
                double[] q = dest(lat, lon, 45 * k + 10, 0.999 * r.edgeDistanceM);
                ZoneLookup.Result s = lk.lookup(q[0], q[1]);
                if (!Objects.equals(s.zoneId, r.zoneId)) {
                    if (bad++ < 10) System.out.println("  moved " + lat + "," + lon + " (" + r.zoneId + ", edge " + r.edgeDistanceM
                            + " m, " + r.tile + ") -> " + q[0] + "," + q[1] + " (" + s.zoneId + ", " + s.tile + ")");
                }
            }
        }
        check("moves of 0.999 x edge distance keep the zone (" + n + " points x 8)", bad == 0, "changed " + bad);
    }

    private static void agreement(ZoneLookup.TileSource src, ZoneTile all) throws IOException {
        int n = 100_000;
        Random rnd = new Random(20261018L);
        double[] lat = new double[n], lon = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = Math.toDegrees(Math.asin(2 * rnd.nextDouble() - 1));
            lon[i] = 360 * rnd.nextDouble() - 180;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparing(i -> ZoneLookup.tileName(lat[i], lon[i])));
        ZoneLookup lk = new ZoneLookup(src);
        String[] tiled = new String[n];
        long t0 = System.nanoTime();
        for (int i : order) tiled[i] = lk.lookup(lat[i], lon[i]).zoneId;
        long t1 = System.nanoTime();
        String[] ref = new String[n];
        int land = 0;
        for (int i = 0; i < n; i++) {
            int p = all.pieceAt(lat[i], lon[i]);
            ref[i] = p < 0 ? null : all.zoneOf(p);
            if (ref[i] != null) land++;
        }
        long t2 = System.nanoTime();
        int dis = 0, excused = 0;
        List<String> worst = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (Objects.equals(tiled[i], ref[i])) continue;
            dis++;
            double d = all.distanceToNearestEdgeMeters(lat[i], lon[i]);
            if (d <= 1.0) excused++;
            else if (worst.size() < 10) worst.add(lat[i] + "," + lon[i] + " tiled=" + tiled[i] + " all=" + ref[i] + " d=" + d);
        }
        System.out.printf("agreement: %d points (%d in a polygon), %d disagreements, %d excused (<= 1 m from a border)%n",
                n, land, dis, excused);
        System.out.printf("agreement timing: tiled %.0f ms (%d tile reads, sorted by tile), all-file %.0f ms%n",
                (t1 - t0) / 1e6, lk.tileReads(), (t2 - t1) / 1e6);
        for (String s : worst) System.out.println("  " + s);
        check("tiled lookup agrees with all-file on 100000 random points", dis == excused,
                dis + " disagreements, " + excused + " excused");
    }

    private static void largestTile(File dir) throws IOException {
        File big = null;
        for (File f : dir.listFiles((d, n) -> n.endsWith(".bin.gz"))) if (big == null || f.length() > big.length()) big = f;
        double first = 0;
        double[] ms = new double[30];
        int pieces = 0, verts = 0;
        for (int k = 0; k < ms.length; k++) {
            long t0 = System.nanoTime();
            ZoneTile t;
            try (InputStream in = new FileInputStream(big)) {
                t = ZoneTile.read(in);
            }
            ms[k] = (System.nanoTime() - t0) / 1e6;
            pieces = t.pieceCount();
            verts = t.vertexCount();
        }
        first = ms[0];
        Arrays.sort(ms);
        System.out.printf("largest tile %s: %d bytes, %d pieces, %d vertices; read+parse first %.1f ms, median %.2f ms (of %d)%n",
                big.getName(), big.length(), pieces, verts, first, ms[ms.length / 2], ms.length);
    }

    /** Destination point along a great circle. */
    private static double[] dest(double lat, double lon, double bearingDeg, double m) {
        double d = m / 6371008.8, b = Math.toRadians(bearingDeg), p = Math.toRadians(lat), l = Math.toRadians(lon);
        double p2 = Math.asin(Math.sin(p) * Math.cos(d) + Math.cos(p) * Math.sin(d) * Math.cos(b));
        double l2 = l + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p), Math.cos(d) - Math.sin(p) * Math.sin(p2));
        double lo = Math.toDegrees(l2);
        lo = ((lo + 180) % 360 + 360) % 360 - 180;
        return new double[] {Math.toDegrees(p2), lo};
    }
}
