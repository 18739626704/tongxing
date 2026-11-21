package app.tongxing.mobile;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import java.time.LocalDate;
import java.time.ZoneId;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import android.content.pm.PackageManager;

final class ScreenTime {
    private ScreenTime() {}

    static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT >= 28 && context.getSystemService(AppOpsManager.class).checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    record AppDuration(String label, long seconds) {}
    record AppUsage(LocalDate day, long recordedAt, List<AppDuration> rows) {
        JSONArray toJson(int limit) {
            JSONArray apps = new JSONArray();
            for (AppDuration row : rows.subList(0, Math.min(limit, rows.size()))) {
                apps.put(Api.json("name", row.label(), "seconds", row.seconds()));
            }
            return apps;
        }
    }

    static AppUsage appUsage(Context context, LocalDate day) {
        if (!allowed(context)) return null;
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = Math.min(now, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        UsageEvents events = context.getSystemService(UsageStatsManager.class).queryEvents(
                day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), end);
        if (events == null) return null;
        AppUsageAccumulator accumulator = new AppUsageAccumulator(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String app = event.getPackageName();
            String activity = event.getClassName();
            long time = event.getTimeStamp();
            switch (event.getEventType()) {
                // Before Android 10 these same codes are named MOVE_TO_FOREGROUND and MOVE_TO_BACKGROUND.
                case UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (app != null) accumulator.resume(app, activity, time);
                }
                case UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (app != null) accumulator.pause(app, activity, time);
                }
                case UsageEvents.Event.SCREEN_INTERACTIVE -> accumulator.screen(true, time);
                case UsageEvents.Event.SCREEN_NON_INTERACTIVE -> accumulator.screen(false, time);
                case UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> accumulator.reset(time);
                default -> {}
            }
        }
        PackageManager packages = context.getPackageManager();
        var rows = new ArrayList<AppDuration>();
        accumulator.finish().forEach((app, millis) -> {
            long seconds = millis / 1000;
            if (seconds > 0) rows.add(new AppDuration(label(packages, app), Math.min(90_000, seconds)));
        });
        rows.sort(Comparator.comparingLong(AppDuration::seconds).reversed().thenComparing(AppDuration::label));
        return new AppUsage(day, now, java.util.Collections.unmodifiableList(rows));
    }

    private static String label(PackageManager packages, String app) {
        String label = app;
        try {
            label = packages.getApplicationLabel(packages.getApplicationInfo(app, 0)).toString().trim();
        } catch (PackageManager.NameNotFoundException ignored) {}
        if (label.isEmpty()) label = app;
        return label.substring(0, label.offsetByCodePoints(0, Math.min(label.codePointCount(0, label.length()), 40)));
    }

    static long latestUnlock(Context context, long now) {
        if (!allowed(context)) return 0;
        UsageEvents events = context.getSystemService(UsageStatsManager.class).queryEvents(now - 86_400_000, now);
        if (events == null) return 0;
        UsageEvents.Event event = new UsageEvents.Event();
        long unlock = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.KEYGUARD_HIDDEN) unlock = event.getTimeStamp();
        }
        return unlock;
    }

    static void append(Context context, JSONObject point) throws org.json.JSONException {
        if (!allowed(context)) return;
        LocalDate day = LocalDate.now();
        long start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long previousDay = day.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long now = System.currentTimeMillis();
        UsageEvents events = context.getSystemService(UsageStatsManager.class).queryEvents(previousDay, now);
        if (events == null) return;
        UsageEvents.Event event = new UsageEvents.Event();
        long onSince = -1;
        long total = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            long time = Math.max(start, event.getTimeStamp());
            switch (event.getEventType()) {
                case UsageEvents.Event.SCREEN_INTERACTIVE:
                    if (onSince < 0) onSince = time;
                    break;
                case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                case UsageEvents.Event.DEVICE_SHUTDOWN:
                case UsageEvents.Event.DEVICE_STARTUP:
                    if (onSince >= 0) total += Math.max(0, time - onSince);
                    onSince = -1;
                    break;
            }
        }
        if (onSince >= 0) total += Math.max(0, now - onSince);
        point.put("screenMinutes", Math.min(1500, total / 60_000));
        point.put("screenDate", day.toString());
    }
}
