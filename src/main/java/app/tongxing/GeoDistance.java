package app.tongxing;

final class GeoDistance {
    private GeoDistance() {}

    static double distanceMeters(double firstLat, double firstLon, double lat, double lon) {
        double a = Math.pow(Math.sin(Math.toRadians(lat - firstLat) / 2), 2)
                + Math.cos(Math.toRadians(firstLat)) * Math.cos(Math.toRadians(lat))
                * Math.pow(Math.sin(Math.toRadians(lon - firstLon) / 2), 2);
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, a)));
    }
}
