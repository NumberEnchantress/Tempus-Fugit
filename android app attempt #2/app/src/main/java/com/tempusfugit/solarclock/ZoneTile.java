package com.tempusfugit.solarclock;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** One parsed time-zone tile (TFZT v1): pieces of zone polygons clipped to a 10x10 degree cell. */
public final class ZoneTile {
    private static final double M_DEG = 6371008.8 * Math.PI / 180, E5 = 1e5;

    private final String[] zones;
    private final int[] pieceZone;   // zone index per piece
    private final int[] box;         // minLon, minLat, maxLon, maxLat per piece (E5)
    private final int[] pieceRing;   // first ring of piece p; pieceRing[n] = ring count
    private final int[] ringStart;   // first vertex of ring r; ringStart[n] = vertex count
    private final int[] xy;          // lon, lat pairs (E5)

    private ZoneTile(String[] zones, int[] pieceZone, int[] box, int[] pieceRing, int[] ringStart, int[] xy) {
        this.zones = zones;
        this.pieceZone = pieceZone;
        this.box = box;
        this.pieceRing = pieceRing;
        this.ringStart = ringStart;
        this.xy = xy;
    }

    /**
     * Reads a tile, gzipped or plain (the Android build stores .gz assets
     * uncompressed inside the already-deflated APK); does not close the stream.
     */
    public static ZoneTile read(InputStream raw) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(raw, 16384);
        buffered.mark(2);
        boolean gzip = buffered.read() == 0x1f && buffered.read() == 0x8b;
        buffered.reset();
        DataInputStream in = new DataInputStream(gzip
                ? new BufferedInputStream(new GZIPInputStream(buffered, 8192), 16384) : buffered);
        if (in.readInt() != 0x54465A54) throw new IOException("not a TFZT file");
        int version = in.readUnsignedByte();
        if (version != 1) throw new IOException("unsupported TFZT version " + version);
        String[] zones = new String[in.readUnsignedShort()];
        for (int i = 0; i < zones.length; i++) {
            byte[] b = new byte[in.readUnsignedByte()];
            in.readFully(b);
            zones[i] = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
        int n = in.readInt();
        if (n < 0) throw new IOException("bad piece count");
        int[] pieceZone = new int[n], box = new int[4 * n], pieceRing = new int[n + 1];
        int[] ringStart = new int[Math.max(16, n * 2)];
        int[] xy = new int[1 << 12];
        int rings = 0, verts = 0;
        for (int p = 0; p < n; p++) {
            pieceZone[p] = in.readUnsignedShort();
            if (pieceZone[p] >= zones.length) throw new IOException("bad zone index");
            for (int k = 0; k < 4; k++) box[4 * p + k] = in.readInt();
            pieceRing[p] = rings;
            int rc = in.readUnsignedShort();
            for (int r = 0; r < rc; r++) {
                int vc = in.readInt();
                if (vc < 0 || vc > (1 << 26)) throw new IOException("bad vertex count");
                if (rings + 2 > ringStart.length) ringStart = Arrays.copyOf(ringStart, ringStart.length * 2);
                ringStart[rings++] = verts;
                if (2 * (verts + vc) > xy.length) xy = Arrays.copyOf(xy, Math.max(xy.length * 2, 2 * (verts + vc)));
                int x = 0, y = 0;
                for (int v = 0; v < vc; v++) {
                    x += unzig(varint(in));
                    y += unzig(varint(in));
                    xy[2 * verts] = x;
                    xy[2 * verts + 1] = y;
                    verts++;
                }
            }
        }
        pieceRing[n] = rings;
        ringStart[rings] = verts;
        return new ZoneTile(zones, pieceZone, box, pieceRing,
                Arrays.copyOf(ringStart, rings + 1), Arrays.copyOf(xy, 2 * verts));
    }

    private static int varint(DataInputStream in) throws IOException {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if (b < 0x80) return v;
        }
        throw new IOException("bad varint");
    }

    private static int unzig(int z) {
        return (z >>> 1) ^ -(z & 1);
    }

    public int pieceCount() {
        return pieceZone.length;
    }

    public int vertexCount() {
        return xy.length / 2;
    }

    /** First piece containing the point (even-odd over all its rings), or -1. */
    public int pieceAt(double lat, double lon) {
        double px = lon * E5, py = lat * E5;
        for (int p = 0; p < pieceZone.length; p++) {
            int b = 4 * p;
            if (px < box[b] || py < box[b + 1] || px > box[b + 2] || py > box[b + 3]) continue;
            boolean inside = false;
            for (int r = pieceRing[p]; r < pieceRing[p + 1]; r++) {
                int s = ringStart[r], e = ringStart[r + 1];
                int j = e - 1;
                for (int i = s; i < e; j = i++) {
                    double yi = xy[2 * i + 1], yj = xy[2 * j + 1];
                    if ((yi > py) != (yj > py)) {
                        double xi = xy[2 * i], xj = xy[2 * j];
                        if (px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside;
                    }
                }
            }
            if (inside) return p;
        }
        return -1;
    }

    /** minLon, minLat, maxLon, maxLat over all vertices (E5); for checks. */
    int[] vertexBounds() {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < xy.length; i += 2) {
            b[0] = Math.min(b[0], xy[i]);
            b[1] = Math.min(b[1], xy[i + 1]);
            b[2] = Math.max(b[2], xy[i]);
            b[3] = Math.max(b[3], xy[i + 1]);
        }
        return b;
    }

    public String zoneOf(int piece) {
        return zones[pieceZone[piece]];
    }

    /**
     * Minimum distance in metres from the point to any edge of any piece. Equirectangular around the point,
     * with each edge's east-west scale taken at the most poleward latitude of the point and the edge, then
     * reduced by 0.2%, so it stays a lower bound on the great-circle distance for edges within ~10 degrees.
     */
    public double distanceToNearestEdgeMeters(double lat, double lon) {
        double qx = lon * E5, qy = lat * E5, aq = Math.abs(qy);
        int hi = (int) Math.min(9_000_000, aq);
        for (int b = 1; b < box.length; b += 2) hi = Math.max(hi, Math.abs(box[b]));
        double kLow = cosE5(hi), ky = 1;
        double best = Double.POSITIVE_INFINITY;
        for (int r = 0; r + 1 < ringStart.length; r++) {
            int s = ringStart[r], e = ringStart[r + 1];
            int j = e - 1;
            for (int i = s; i < e; j = i++) {
                double d = segDist2(xy[2 * j] - qx, xy[2 * j + 1] - qy, xy[2 * i] - qx, xy[2 * i + 1] - qy, kLow, ky);
                if (d >= best) continue;
                double m = Math.max(aq, Math.max(Math.abs(xy[2 * j + 1]), Math.abs(xy[2 * i + 1])));
                d = segDist2(xy[2 * j] - qx, xy[2 * j + 1] - qy, xy[2 * i] - qx, xy[2 * i + 1] - qy, cosE5(m), ky);
                if (d < best) best = d;
            }
        }
        return 0.998 * Math.sqrt(best) * M_DEG / E5;
    }

    private static double cosE5(double latE5) {
        return Math.cos(Math.toRadians(Math.min(9_000_000, latE5) / E5));
    }

    /** Squared distance from the origin to segment a-b after scaling x by kx and y by ky. */
    private static double segDist2(double ax, double ay, double bx, double by, double kx, double ky) {
        ax *= kx; bx *= kx; ay *= ky; by *= ky;
        double dx = bx - ax, dy = by - ay, ll = dx * dx + dy * dy;
        double t = ll > 0 ? -(ax * dx + ay * dy) / ll : 0;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double cx = ax + t * dx, cy = ay + t * dy;
        return cx * cx + cy * cy;
    }
}
