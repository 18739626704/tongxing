package app.tongxing.mobile;

import android.location.Location;

final class LocationCadence {
    static final long ACTIVE = 60_000;
    static final long MOVING = 25_000;
    static final long QUIET = 150_000;
    private static final long MOTION_WINDOW = 180_000;
    private Location anchor;
    private long motionUntil;
    private long quietSince;
    private int quietFixes;

    void step(long now) {
        motionUntil = now + MOTION_WINDOW;
        quietSince = 0;
        quietFixes = 0;
    }

    void fix(Location point, long now) {
        if (point.getAccuracy() > 55) return;
        if (anchor == null) anchor = point;
        double change = anchor.distanceTo(point);
        boolean speed = point.hasSpeed() && point.hasSpeedAccuracy()
                && point.getSpeed() - point.getSpeedAccuracyMetersPerSecond() >= 1.5;
        if (speed || change > Math.max(55, anchor.getAccuracy() + point.getAccuracy())) {
            anchor = point;
            step(now);
        } else {
            if (quietSince == 0) quietSince = now;
            quietFixes++;
        }
    }

    long interval(long now) {
        if (now < motionUntil) return MOVING;
        return quietFixes < 3 || now - quietSince < 120_000 ? ACTIVE : QUIET;
    }

    void reset() {
        anchor = null;
        motionUntil = 0;
        quietSince = 0;
        quietFixes = 0;
    }
}
