package app.tongxing.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CompanionNotifications extends JobService {
    private static final String LEGACY = "companion_events";
    private static final String NORMAL = "companion_alerts";
    private static final String URGENT = "companion_sos";
    static final String OPEN = "app.tongxing.mobile.OPEN_FEED";
    private static final int JOB = 711;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("companion", MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return Api.get(context).hasSession();
    }

    private static String messageChannel(Context context) {
        var manager = context.getSystemService(NotificationManager.class);
        NotificationChannel legacy = manager.getNotificationChannel(LEGACY);
        if (legacy != null && (legacy.getImportance() < NotificationManager.IMPORTANCE_DEFAULT
                || (android.os.Build.VERSION.SDK_INT >= 29 && legacy.hasUserSetImportance()))) return LEGACY;
        manager.createNotificationChannel(new NotificationChannel(NORMAL, "伴侣消息与动态", NotificationManager.IMPORTANCE_HIGH));
        return NORMAL;
    }

    static Intent channelSettings(Context context) {
        return new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, messageChannel(context));
    }

    static boolean schedule(Context context) {
        if (!enabled(context)) return true;
        messageChannel(context);
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        try {
            if (jobs.getPendingJob(JOB) != null) return true;
            return jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(context, CompanionNotifications.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60_000L)
                    .setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS;
        } catch (SecurityException | IllegalArgumentException | IllegalStateException failure) {
            android.util.Log.w("CompanionNotifications", "Background scheduling unavailable", failure);
            return false;
        }
    }

    static void cancel(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(72);
        manager.cancel(73);
    }

    static void poll(Context context) throws Exception {
        Api api = Api.get(context);
        if (!api.hasSession()) return;
        CompanionPush.sync(context);
        String account = api.accountKey();
        JSONObject feed = api.getObject("/feed");
        if (account.equals(api.accountKey())) deliver(context, account, feed);
        if (LiveWidget.present(context)) {
            JSONObject overview = api.getObject("/overview");
            if (account.equals(api.accountKey())) LiveWidget.update(context, overview);
        }

    }

    static synchronized void deliver(Context context, String account, JSONObject feed) {
        if (feed == null || feed.isNull("pairId")) return;
        JSONArray events = feed.optJSONArray("events");
        if (events == null || events.length() == 0) return;
        if (!enabled(context)) {
            acknowledgeSos(context, account, events);
            return;
        }
        String key = "seen:" + account + ":" + feed.optString("pairId");
        SharedPreferences prefs = prefs(context);
        long latest = prefs.getLong(key, 0);
        long next = latest;
        JSONObject normal = null;
        JSONObject sos = null;
        int count = 0;
        long now = System.currentTimeMillis();
        for (int i = events.length() - 1; i >= 0; --i) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || event.optLong("id") <= latest) continue;
            next = Math.max(next, event.optLong("id"));
            if (!event.optBoolean("incoming") || (latest == 0 && now - event.optLong("createdAt") > 15 * 60_000)) continue;
            if ("SOS".equals(event.optString("kind"))) sos = event;
            else { normal = event; count++; }
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) {
            acknowledgeSos(context, account, events);
            return;
        }
        String messageChannel = messageChannel(context);
        manager.createNotificationChannel(new NotificationChannel(URGENT, "伴侣求助", NotificationManager.IMPORTANCE_HIGH));
        if (normal != null) notify(context, manager, messageChannel, 72, normal.optString("sender"),
                count > 1 ? count + " 条新动态 · " + describe(normal) : describe(normal));
        if (sos != null) notify(context, manager, URGENT, 73, sos.optString("sender") + " 发来 SOS", describe(sos));
        prefs.edit().putLong(key, next).apply();
        acknowledgeSos(context, account, events);
    }

    private static void acknowledgeSos(Context context, String account, JSONArray events) {
        var api = Api.get(context);
        // A receipt confirms that this app fetched the event, even if Android notifications are disabled.
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || !e.optBoolean("incoming") || !"SOS".equals(e.optString("kind"))
                    || !e.has("receivedAt") || !e.isNull("receivedAt")
                    || System.currentTimeMillis() - e.optLong("createdAt") >= 86_400_000) continue;
            try {
                synchronized (api) {
                    if (!account.equals(api.accountKey())) return;
                    api.mutate("PUT", "/sos/" + e.optLong("id") + "/receipt", Api.json("viewed", false));
                }
                e.put("receivedAt", System.currentTimeMillis());
            } catch (Exception ignored) {
                // The next successful fetch can retry the receipt.
            }
        }
    }

    static String describe(JSONObject event) {
        String text = event.optString("text");
        return switch (event.optString("kind")) {
            case "ARRIVE" -> "到达 " + text;
            case "LEAVE" -> "离开 " + text;
            case "MOTION" -> DeviceStatus.motionLabel(text);
            case "NETWORK" -> "切换到 " + DeviceStatus.networkLabel(text);
            case "REMINDER" -> "关怀提醒 · " + text;
            default -> text;
        };
    }

    private static void notify(Context context, NotificationManager manager, String channel, int id,
                               String title, String body) {
        Intent intent = new Intent(context, MainActivity.class).setAction(OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent target = PendingIntent.getActivity(context, 8, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(id, new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_position)
                .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(target).setAutoCancel(true).build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                poll(this);
            } catch (Api.Failure error) {
                retry = error.status >= 500;
            } catch (Exception error) {
                retry = true;
            }
            jobFinished(params, retry);
        }, "tongxing-notifications").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
