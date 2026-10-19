package com.tempusfugit.solarclock;

import java.io.IOException;
import java.io.InputStream;

/** Offline point-to-time-zone lookup over 10x10 degree tiles; holds one tile in memory. */
public final class ZoneLookup {
    private static final double R = 6371008.8;

    /** Opens tile files by name, e.g. from the app's assets/tiles folder. */
    public interface TileSource {
        /** null when the tile has no file. */
        InputStream open(String fileName) throws IOException;
    }

    /** Result of one lookup. */
    public static final class Result {
        /** null = no polygon (ocean); the caller falls back to the system zone. */
        public final String zoneId;
        /** Lower bound on how far the point can move before the result may change. */
        public final double edgeDistanceM;
        public final String tile;

        Result(String zoneId, double edgeDistanceM, String tile) {
            this.zoneId = zoneId;
            this.edgeDistanceM = edgeDistanceM;
            this.tile = tile;
        }
    }

    private final TileSource source;
    private String loadedName;
    private ZoneTile loaded;
    private int reads;

    public ZoneLookup(TileSource source) {
        this.source = source;
    }

    public static String tileName(double lat, double lon) {
        return col(normLon(lon)) + "_" + row(lat) + ".bin.gz";
    }

    public Result lookup(double lat, double lon) throws IOException {
        lon = normLon(lon);
        lat = Math.max(-90, Math.min(90, lat));
        int c = col(lon), r = row(lat);
        String name = c + "_" + r + ".bin.gz";
        if (!name.equals(loadedName)) {
            loaded = null;
            loadedName = null;
            InputStream in = source.open(name);
            if (in != null) {
                reads++;
                try (InputStream s = in) {
                    loaded = ZoneTile.read(s);
                }
            }
            loadedName = name;
        }
        double edge = rectDistance(lat, lon, c, r);
        String zone = null;
        if (loaded != null) {
            int p = loaded.pieceAt(lat, lon);
            if (p >= 0) zone = loaded.zoneOf(p);
            edge = Math.min(edge, loaded.distanceToNearestEdgeMeters(lat, lon));
        }
        return new Result(zone, edge, name);
    }

    public String loadedTile() {
        return loadedName;
    }

    public int tileReads() {
        return reads;
    }

    private static double normLon(double lon) {
        if (lon >= 180 || lon < -180) lon -= 360 * Math.floor((lon + 180) / 360);
        return lon;
    }

    private static int col(double lon) {
        return Math.max(-18, Math.min(17, (int) Math.floor(lon / 10)));
    }

    private static int row(double lat) {
        return Math.max(-9, Math.min(8, (int) Math.floor(lat / 10)));
    }

    /** Great-circle distance from the point to the boundary of tile (c, r). */
    private static double rectDistance(double lat, double lon, int c, int r) {
        double d = Double.POSITIVE_INFINITY;
        if (r > -9) d = Math.min(d, Math.toRadians(lat - 10 * r) * R);
        if (r < 8) d = Math.min(d, Math.toRadians(10 * (r + 1) - lat) * R);
        double cl = Math.cos(Math.toRadians(lat));
        d = Math.min(d, Math.asin(Math.min(1, cl * Math.sin(Math.toRadians(lon - 10 * c)))) * R);
        d = Math.min(d, Math.asin(Math.min(1, cl * Math.sin(Math.toRadians(10 * (c + 1) - lon)))) * R);
        return Math.max(0, d);
    }
}
