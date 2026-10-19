package com.tempusfugit.solarclock;

import java.io.IOException;

/** Decides when a zone lookup is due: only after moving at least the last result's edge distance. */
public final class ZoneCache {
    private static final double R = 6371008.8;

    private final ZoneLookup lookup;
    private boolean have;
    private String zoneId;
    private double edgeM, lookupLat, lookupLon;
    private int lookups;

    public ZoneCache(ZoneLookup lookup) {
        this.lookup = lookup;
    }

    /** Returns the zone id (or null for no polygon). Looks up only when no lookup has been done yet or the point is at least edgeDistanceM (haversine metres) from the last lookup point. */
    public String update(double lat, double lon) throws IOException {
        if (!have || haversineM(lookupLat, lookupLon, lat, lon) >= edgeM) {
            ZoneLookup.Result r = lookup.lookup(lat, lon);
            lookups++;
            have = true;
            zoneId = r.zoneId;
            edgeM = r.edgeDistanceM;
            lookupLat = lat;
            lookupLon = lon;
        }
        return zoneId;
    }

    public int lookups() {
        return lookups;
    }

    public String zoneId() {
        return zoneId;
    }

    public double edgeM() {
        return edgeM;
    }

    public double lookupLat() {
        return lookupLat;
    }

    public double lookupLon() {
        return lookupLon;
    }

    static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
