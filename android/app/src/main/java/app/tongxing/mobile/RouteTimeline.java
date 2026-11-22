package app.tongxing.mobile;

import android.location.Location;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class RouteTimeline {
    enum Kind { MOVING, STOPPED, GAP, UNCERTAIN }
    record Part(Kind kind, long from, long to, double meters, int first, int last, long observed) {
        Part(Kind kind, long from, long to, double meters, int first, int last) {
            this(kind, from, to, meters, first, last, to - from);
        }
        long duration() { return to - from; }
        boolean activity() { return kind == Kind.MOVING || kind == Kind.STOPPED; }
    }

    record Summary(List<Part> activities, List<Part> missing, long moving, long stopped, long uncovered) {}
    static final double AREA_RADIUS_METERS = 90;
    static final long MAX_MOVING_GAP = 180_000;
    // Historical tracks were collected at intervals of up to 150 seconds when stationary.
    private static final long MAX_QUIET_GAP = 210_000;

    private RouteTimeline() {}

    static List<Part> build(JSONArray fixes) {
        List<Part> parts = new ArrayList<>();
        float[] distance = new float[1];
        JSONObject anchor = fixes.optJSONObject(0);
        for (int i = 1; i < fixes.length(); i++) {
            JSONObject first = fixes.optJSONObject(i - 1);
            JSONObject second = fixes.optJSONObject(i);
            if (first == null || second == null) { anchor = null; continue; }
            long from = first.optLong("recordedAt");
            long to = second.optLong("recordedAt");
            if (to <= from) continue;
            long elapsed = to - from;
            Kind kind;
            double meters = 0;
            if (elapsed > MAX_QUIET_GAP || (elapsed > MAX_MOVING_GAP && !sameQuietArea(anchor, first, second, distance))) {
                kind = Kind.GAP;
                anchor = second;
            } else if (first.optDouble("accuracy", 999) > 100 || second.optDouble("accuracy", 999) > 100) {
                kind = Kind.UNCERTAIN;
                anchor = second;
            } else {
                if (anchor == null || anchor.optDouble("accuracy", 999) > 100) anchor = first;
                Location.distanceBetween(anchor.optDouble("latitude"), anchor.optDouble("longitude"),
                        second.optDouble("latitude"), second.optDouble("longitude"), distance);
                double margin = Math.max(AREA_RADIUS_METERS, anchor.optDouble("accuracy") + second.optDouble("accuracy"));
                if (distance[0] > margin) {
                    Location.distanceBetween(first.optDouble("latitude"), first.optDouble("longitude"),
                            second.optDouble("latitude"), second.optDouble("longitude"), distance);
                    meters = distance[0];
                    kind = Kind.MOVING;
                    anchor = second;
                } else {
                    kind = Kind.STOPPED;
                }
            }
            append(parts, new Part(kind, from, to, meters, i - 1, i));
        }
        List<Part> merged = new ArrayList<>();
        for (Part part : parts) {
            append(merged, part.kind() == Kind.STOPPED && part.duration() < 300_000
                    ? new Part(Kind.UNCERTAIN, part.from(), part.to(), 0, part.first(), part.last()) : part);
        }
        return merged;
    }

    static Summary summarize(List<Part> parts) { return summarize(parts, null); }

    static Summary summarize(List<Part> parts, JSONArray fixes) {
        List<Part> activities = new ArrayList<>();
        List<Part> missing = new ArrayList<>();
        long moving = 0, stopped = 0, uncovered = 0;
        for (Part part : parts) {
            if (part.activity()) {
                addActivity(activities, part, fixes);
                if (part.kind() == Kind.MOVING) moving += part.duration();
                else stopped += part.duration();
            } else {
                missing.add(part);
                uncovered += part.duration();
            }
        }
        java.util.Collections.reverse(activities);
        java.util.Collections.reverse(missing);
        return new Summary(activities, missing, moving, stopped, uncovered);
    }

    private static void addActivity(List<Part> activities, Part part, JSONArray fixes) {
        Part previous = activities.isEmpty() ? null : activities.get(activities.size() - 1);
        if (fixes != null && previous != null && previous.kind() == Kind.STOPPED && part.kind() == Kind.STOPPED
                && staysNear(fixes, previous, part)) {
            activities.set(activities.size() - 1, new Part(Kind.STOPPED, previous.from(), part.to(), 0,
                    previous.first(), part.last(), previous.observed() + part.observed()));
        } else {
            activities.add(part);
        }
    }

    private static boolean staysNear(JSONArray fixes, Part previous, Part next) {
        JSONObject anchor = fixes.optJSONObject(previous.first());
        float[] meters = new float[1];
        for (int i = previous.last(); i <= next.last(); i++) {
            JSONObject fix = fixes.optJSONObject(i);
            if (fix == null || fix.optDouble("accuracy", 999) > 100) return false;
            Location.distanceBetween(anchor.optDouble("latitude"), anchor.optDouble("longitude"),
                    fix.optDouble("latitude"), fix.optDouble("longitude"), meters);
            if (meters[0] > AREA_RADIUS_METERS) return false;
        }
        return true;
    }

    private static boolean sameQuietArea(JSONObject anchor, JSONObject first, JSONObject second, float[] distance) {
        // Allow one minute of quiet-mode timing variance only when all readings support the same stay.
        if (anchor == null || anchor.optDouble("accuracy", 999) > 35 || first.optDouble("accuracy", 999) > 35
                || second.optDouble("accuracy", 999) > 35) return false;
        Location.distanceBetween(anchor.optDouble("latitude"), anchor.optDouble("longitude"),
                second.optDouble("latitude"), second.optDouble("longitude"), distance);
        return distance[0] <= 35;
    }

    private static void append(List<Part> parts, Part part) {
        Part previous = parts.isEmpty() ? null : parts.get(parts.size() - 1);
        if (previous != null && previous.kind() == part.kind() && previous.last() == part.first()
                && previous.to() == part.from()) {
            parts.set(parts.size() - 1, new Part(part.kind(), previous.from(), part.to(),
                    previous.meters() + part.meters(), previous.first(), part.last()));
        } else parts.add(part);
    }
}
