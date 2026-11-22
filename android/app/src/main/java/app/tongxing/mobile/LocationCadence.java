package app.tongxing.mobile;

import android.location.Location;

final class LocationCadence {
    static final long PROVIDER_INTERVAL = 15_000;
    static final long MOVING_INTERVAL = 12_000;
    static final long STATIONARY_INTERVAL = 60_000;
    private static final long MOVEMENT_WINDOW = 120_000;
    private Location anchor;
    private long movingUntil;

    void step(long now) {
        movingUntil = now + MOVEMENT_WINDOW;
    }

    void fix(Location point, long now) {
        if (point.getAccuracy() > 60) return;
        if (anchor == null) anchor = point;
        boolean speed = point.hasSpeed() && point.hasSpeedAccuracy()
                && point.getSpeed() - point.getSpeedAccuracyMetersPerSecond() >= 0.6;
        double displacement = anchor.distanceTo(point);
        if (speed || displacement >= Math.max(18, (anchor.getAccuracy() + point.getAccuracy()) * 0.65)) {
            anchor = point;
            step(now);
        } else if (displacement < 10 && point.getAccuracy() < anchor.getAccuracy()) {
            anchor = point;
        }
    }

    long interval(long now) {
        return anchor == null || now < movingUntil ? MOVING_INTERVAL : STATIONARY_INTERVAL;
    }

    void reset() {
        anchor = null;
        movingUntil = 0;
    }
}
