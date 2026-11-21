package app.tongxing.mobile;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.widget.RemoteViews;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

public final class LiveWidget extends AppWidgetProvider {
    static boolean present(Context context) { return ids(context).length > 0; }

    static void update(Context context, JSONObject overview) {
        int[] ids = ids(context);
        if (ids.length == 0) return;
        JSONObject partner = overview == null ? null : overview.optJSONObject("partner");
        String title = partner == null ? "同行" : partner.optString("displayName");
        String status = partner == null ? "打开同行查看伴侣状态"
                : partner.optBoolean("sharing") ? "等待位置更新" : "暂未同步";
        String time = "";
        JSONObject point = partner == null ? null : partner.optJSONObject("lastLocation");
        if (point != null) {
            int battery = point.optInt("battery", -1);
            status = battery >= 0 ? "电量 " + battery + "%" : "位置已收到";
            JSONObject self = overview.optJSONObject("self");
            JSONObject mine = self == null ? null : self.optJSONObject("lastLocation");
            long updated = point.optLong("recordedAt");
            long now = System.currentTimeMillis();
            if (mine != null && now - updated <= 120_000 && now - mine.optLong("recordedAt") <= 120_000) {
                float[] meters = new float[1];
                Location.distanceBetween(mine.optDouble("latitude"), mine.optDouble("longitude"),
                        point.optDouble("latitude"), point.optDouble("longitude"), meters);
                status += meters[0] < 1000 ? " · " + Math.round(meters[0]) + " 米"
                        : String.format(Locale.CHINA, " · %.1f 公里", meters[0] / 1000);
            }
            time = "位置更新于 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(updated));
        }
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views(context, title, status, time));
    }

    private static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, LiveWidget.class));
    }

    private static RemoteViews views(Context context, String title, String status, String time) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.live_widget);
        view.setTextViewText(R.id.widget_title, title);
        view.setTextViewText(R.id.widget_status, status);
        view.setTextViewText(R.id.widget_time, time);
        PendingIntent launch = PendingIntent.getActivity(context, 10, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        view.setOnClickPendingIntent(R.id.widget_root, launch);
        return view;
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        manager.updateAppWidget(appWidgetIds, views(context, "同行", "打开同行查看伴侣状态", ""));
    }
}
