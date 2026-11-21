package app.tongxing.mobile;

import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;

final class RouteStabilizer {
    private static final double MAX_ACCURACY_METERS = 100;
    private static final double MAX_SPEED_METERS_PER_SECOND = 100;

    private RouteStabilizer() {}

    static JSONArray stabilize(JSONArray raw) {
        JSONArray points = new JSONArray();
        JSONObject anchor = null;
        long lastTime = Long.MIN_VALUE;
        float[] distance = new float[1];
        for (int i = 0; i < raw.length(); i++) {
            JSONObject fix = raw.optJSONObject(i);
            if (!usable(fix)) continue;
            long at = fix.optLong("recordedAt");
            if (at <= lastTime) continue;
            if (anchor == null || at - lastTime > RouteTimeline.MAX_MOVING_GAP) {
                anchor = fix;
            } else if (outside(anchor, fix, distance)) {
                double meters = distance[0];
                double seconds = (at - lastTime) / 1000.0;
                if (meters / seconds <= MAX_SPEED_METERS_PER_SECOND && confirms(raw, i, anchor, fix, distance)) {
                    anchor = fix;
                }
                // Unconfirmed displacement keeps the last supported position for display.
            }
            points.put(copyAt(anchor, fix, at));
            lastTime = at;
        }
        return points;
    }

    private static boolean confirms(JSONArray raw, int index, JSONObject anchor, JSONObject candidate, float[] distance) {
        long at = candidate.optLong("recordedAt");
        for (int i = index + 1; i < raw.length(); i++) {
            JSONObject next = raw.optJSONObject(i);
            if (next == null) continue;
            long elapsed = next.optLong("recordedAt") - at;
            if (elapsed > RouteTimeline.MAX_MOVING_GAP) return false;
            if (elapsed <= 0 || !usable(next)) continue;
            if (!outside(anchor, next, distance)) return false;
            double anchorToNext = distance[0];
            measure(candidate, next, distance);
            // A point across the anchor, or an implausibly fast jump, does not confirm this excursion.
            return distance[0] <= anchorToNext
                    && distance[0] / (elapsed / 1000.0) <= MAX_SPEED_METERS_PER_SECOND;
        }
        return false;
    }

    private static boolean usable(JSONObject fix) {
        if (fix == null || fix.optLong("recordedAt") <= 0) return false;
        double accuracy = fix.optDouble("accuracy");
        return accuracy >= 0 && accuracy <= MAX_ACCURACY_METERS
                && Math.abs(fix.optDouble("latitude")) <= 90 && Math.abs(fix.optDouble("longitude")) <= 180;
    }

    private static boolean outside(JSONObject first, JSONObject second, float[] distance) {
        measure(first, second, distance);
        double uncertainty = first.optDouble("accuracy") + second.optDouble("accuracy");
        return distance[0] > Math.max(RouteTimeline.AREA_RADIUS_METERS, uncertainty);
    }

    private static void measure(JSONObject a, JSONObject b, float[] distance) {
        Location.distanceBetween(a.optDouble("latitude"), a.optDouble("longitude"),
                b.optDouble("latitude"), b.optDouble("longitude"), distance);
    }

    private static JSONObject copyAt(JSONObject anchor, JSONObject observed, long at) {
        return Api.json("latitude", anchor.optDouble("latitude"), "longitude", anchor.optDouble("longitude"),
                "accuracy", Math.max(anchor.optDouble("accuracy"), observed.optDouble("accuracy")), "recordedAt", at);
    }
}
