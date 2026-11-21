package app.tongxing.mobile;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import org.junit.Test;

public class AppUsageAccumulatorTest {
    @Test
    public void clipsCrossDayUsageAndCountsOverlappingActivitiesOnlyOnce() {
        var usage = new AppUsageAccumulator(1000, 9000);
        usage.resume("reader", "first", 500);
        usage.resume("reader", "second", 2000);
        usage.pause("reader", "first", 3000);
        usage.screen(false, 4000);
        usage.screen(true, 6000);
        usage.pause("reader", "second", 7000);
        usage.resume("maps", "map", 7500);
        usage.pause("maps", "map", 10000);
        assertEquals(Map.of("reader", 4000L, "maps", 1500L), usage.finish());
    }

    @Test
    public void shutdownClearsForegroundActivities() {
        var usage = new AppUsageAccumulator(1000, 9000);
        usage.resume("reader", "reader", 2000);
        usage.reset(4000);
        usage.screen(true, 6000);
        assertEquals(Map.of("reader", 2000L), usage.finish());
    }
}
