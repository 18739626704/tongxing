package app.tongxing.mobile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class AppUsageAccumulator {
    private final long start;
    private final long end;
    private final Map<String, Set<String>> active = new HashMap<>();
    private final Map<String, Long> totals = new HashMap<>();
    private long previous;
    private boolean screenOn = true;

    AppUsageAccumulator(long start, long end) {
        this.start = start;
        this.end = end;
        previous = start;
    }

    void resume(String app, String activity, long time) {
        advance(time);
        active.computeIfAbsent(app, ignored -> new HashSet<>()).add(activity);
    }

    void pause(String app, String activity, long time) {
        advance(time);
        Set<String> windows = active.get(app);
        if (windows == null) return;
        windows.remove(activity);
        if (windows.isEmpty()) active.remove(app);
    }

    void screen(boolean on, long time) {
        advance(time);
        screenOn = on;
    }

    void reset(long time) {
        advance(time);
        active.clear();
        screenOn = false;
    }

    Map<String, Long> finish() {
        advance(end);
        return new HashMap<>(totals);
    }

    private void advance(long time) {
        long cutoff = Math.max(previous, Math.max(start, Math.min(end, time)));
        long duration = cutoff - previous;
        if (screenOn && duration > 0) {
            for (String app : active.keySet()) totals.merge(app, duration, Long::sum);
        }
        previous = cutoff;
    }
}
