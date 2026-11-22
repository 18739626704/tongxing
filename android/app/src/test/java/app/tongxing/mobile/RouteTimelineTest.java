package app.tongxing.mobile;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RouteTimelineTest {
    @Test
    public void separatesStopsMovementGapsAndUnreliableLocation() {
        JSONArray points = new JSONArray();
        for (int i = 0; i <= 5; i++) points.put(point(i * 60_000L, 34.75, 8));
        points.put(point(360_000, 34.752, 8));
        points.put(point(600_000, 34.76, 8));
        points.put(point(660_000, 34.77, 500));
        var parts = RouteTimeline.build(points);
        assertEquals(4, parts.size());
        assertEquals(RouteTimeline.Kind.STOPPED, parts.get(0).kind());
        assertEquals(RouteTimeline.Kind.MOVING, parts.get(1).kind());
        assertTrue(parts.get(1).meters() > 100);
        assertEquals(RouteTimeline.Kind.GAP, parts.get(2).kind());
        assertEquals(RouteTimeline.Kind.UNCERTAIN, parts.get(3).kind());
    }

    @Test
    public void holdsStationaryAnchorAcrossIndoorDriftAndOneOffJump() {
        var fixes = new JSONArray();
        fixes.put(point(1000, 34.75, 33));
        fixes.put(point(61000, 34.75022, 38));
        fixes.put(point(121000, 34.753, 15));
        fixes.put(point(181000, 34.7498, 34));
        fixes.put(point(241000, 34.75018, 42));
        fixes.put(point(301000, 34.75, 25));
        var stable = RouteStabilizer.stabilize(fixes);
        assertEquals(6, stable.length());
        var parts = RouteTimeline.build(stable);
        assertEquals(1, parts.size());
        assertEquals(RouteTimeline.Kind.STOPPED, parts.get(0).kind());
        assertEquals(0, parts.get(0).meters(), 0);
    }

    @Test
    public void preservesConfirmedTravelAndDoesNotConnectAcrossLongGaps() {
        var fixes = new JSONArray();
        fixes.put(point(1000, 34.75, 8));
        fixes.put(point(61000, 34.751, 8));
        fixes.put(point(121000, 34.752, 8));
        fixes.put(point(181000, 34.753, 8));
        fixes.put(point(500000, 34.8, 8));
        var stable = RouteStabilizer.stabilize(fixes);
        var parts = RouteTimeline.build(stable);
        assertEquals(RouteTimeline.Kind.MOVING, parts.get(0).kind());
        assertTrue(parts.get(0).meters() > 200);
        assertEquals(RouteTimeline.Kind.GAP, parts.get(parts.size() - 1).kind());
    }

    @Test
    public void toleratesQuietSamplingVarianceButReportsLongOutageSeparately() {
        long afternoon = 8 * 60 * 60_000L;
        var fixes = new JSONArray();
        fixes.put(point(1_000, 34.75, 8));
        fixes.put(point(151_000, 34.75, 8));
        fixes.put(point(346_000, 34.75006, 8));
        fixes.put(point(afternoon, 34.75, 8));
        fixes.put(point(afternoon + 60_000, 34.75, 8));
        var summary = RouteTimeline.summarize(RouteTimeline.build(RouteStabilizer.stabilize(fixes)));
        assertEquals(1, summary.activities().size());
        assertEquals(345_000, summary.stopped());
        assertEquals(0, summary.moving());
        assertEquals(2, summary.missing().size());
        assertEquals(RouteTimeline.Kind.GAP, summary.missing().get(1).kind());
        assertEquals(afternoon - 346_000 + 60_000, summary.uncovered());
    }

    @Test
    public void quietToleranceDoesNotHideSignificantDisplacementOrWeakFixes() {
        var fixes = new JSONArray();
        fixes.put(point(1_000, 34.75, 8));
        fixes.put(point(196_000, 34.755, 8));
        fixes.put(point(391_000, 34.755, 80));
        var parts = RouteTimeline.build(RouteStabilizer.stabilize(fixes));
        assertEquals(1, parts.size());
        assertEquals(RouteTimeline.Kind.GAP, parts.get(0).kind());
        assertEquals(390_000, parts.get(0).duration());
    }

    @Test
    public void groupsSameAreaAcrossNearbyReturnsButDoesNotCountCoverageGaps() {
        var fixes = new JSONArray();
        for (long start : new long[]{1_000, 3_601_000, 7_201_000}) {
            double latitude = start == 7_201_000 ? 34.754 : 34.7504;
            for (int i = 0; i <= 5; i++) fixes.put(point(start + i * 60_000, latitude, 8));
        }
        var summary = RouteTimeline.summarize(RouteTimeline.build(fixes), fixes);
        assertEquals(2, summary.activities().size());
        assertEquals(7_201_000, summary.activities().get(0).from());
        assertEquals(1_000, summary.activities().get(1).from());
        assertEquals(600_000, summary.activities().get(1).observed());
        assertEquals(900_000, summary.stopped());
        assertEquals(6_600_000, summary.uncovered());
    }

    @Test
    public void quietCadenceResumesForStepsAndVehicleSpeedWithoutSteps() {
        var tracker = new LocationCadence();
        var position = new android.location.Location("gps");
        position.setLatitude(34.75);
        position.setLongitude(113.65);
        position.setAccuracy(8);
        tracker.fix(position, 1000);
        tracker.fix(position, 61000);
        tracker.fix(position, 121000);
        assertEquals(LocationCadence.STATIONARY_INTERVAL, tracker.interval(121000));
        tracker.step(123000);
        assertEquals(LocationCadence.MOVING_INTERVAL, tracker.interval(123000));
        tracker.fix(position, 301000);
        tracker.fix(position, 361000);
        tracker.fix(position, 421000);
        assertEquals(LocationCadence.STATIONARY_INTERVAL, tracker.interval(421000));
        position.setSpeed(8f);
        position.setSpeedAccuracyMetersPerSecond(.5f);
        tracker.fix(position, 500000);
        assertEquals(LocationCadence.MOVING_INTERVAL, tracker.interval(500000));
    }

    private static org.json.JSONObject point(long timestamp, double latitude, double accuracy) {
        return Api.json("recordedAt", timestamp, "latitude", latitude, "longitude", 113.65, "accuracy", accuracy);
    }
}
