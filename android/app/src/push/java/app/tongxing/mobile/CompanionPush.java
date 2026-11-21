package app.tongxing.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import org.json.JSONObject;

public final class CompanionPush extends FirebaseMessagingService {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("companion_push", MODE_PRIVATE);
    }

    static void sync(Context context) {
        Context app = context.getApplicationContext();
        if (!CompanionNotifications.enabled(app) || FirebaseApp.getApps(app).isEmpty()) return;
        Api api = Api.get(app);
        String account = api.accountKey();
        long now = System.currentTimeMillis();
        if (now - prefs(app).getLong("registered:" + account, 0) < 6 * 3_600_000L) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token ->
                new Thread(() -> register(app, account, token), "tongxing-push").start());
    }

    private static void register(Context context, String account, String token) {
        Api api = Api.get(context);
        synchronized (api) {
            if (!api.hasSession() || !account.equals(api.accountKey()) || !CompanionNotifications.enabled(context)) return;
            try {
                String before = prefs(context).getString("token:" + account, "");
                if (!before.isEmpty() && !before.equals(token)) {
                    api.mutate("DELETE", "/push-token", Api.json("token", before));
                }
                api.mutate("PUT", "/push-token", Api.json("token", token));
                prefs(context).edit().putString("token:" + account, token)
                        .putLong("registered:" + account, System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
                // The periodic job still checks for notifications.
            }
        }
    }

    static void unregister(Context context) {
        Api api = Api.get(context);
        synchronized (api) {
            String account = api.accountKey();
            SharedPreferences stored = prefs(context);
            String token = stored.getString("token:" + account, "");
            stored.edit().remove("registered:" + account).apply();
            if (token.isEmpty() || !api.hasSession()) return;
            try {
                api.mutate("DELETE", "/push-token", Api.json("token", token));
                stored.edit().remove("token:" + account).apply();
            } catch (Exception ignored) {}
        }
    }

    @Override public void onNewToken(String token) {
        register(this, Api.get(this).accountKey(), token);
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        if (!"feed".equals(message.getData().get("kind"))) return;
        try { CompanionNotifications.poll(this); } catch (Exception ignored) {}
    }
}
