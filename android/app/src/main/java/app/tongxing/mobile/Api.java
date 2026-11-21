package app.tongxing.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Api {
    static final class Failure extends IOException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static Api instance;
    static synchronized Api get(Context context) {
        if (instance == null) instance = new Api(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences preferences;
    private String base;
    private String cookie;
    private String username;

    private Api(Context context) {
        preferences = context.getSharedPreferences("tongxing", Context.MODE_PRIVATE);
        base = preferences.getString("base", "");
        cookie = preferences.getString("session", "");
        username = preferences.getString("username", "");
    }

    String savedBase() { return preferences.getString("base", ""); }

    boolean hasSession() {
        return !preferences.getString("username", "").isEmpty()
                && !preferences.getString("session", "").isEmpty();
    }

    boolean streetMapAllowed() { return preferences.getBoolean("street-map-allowed", true); }

    void allowStreetMap(boolean enabled) { preferences.edit().putBoolean("street-map-allowed", enabled).apply(); }

    boolean amapMapChoiceMade() { return preferences.contains("amap-map-allowed"); }

    boolean amapMapAllowed() { return preferences.getBoolean("amap-map-allowed", false); }

    void allowAmapMap(boolean allowed) {
        preferences.edit().putBoolean("amap-map-allowed", allowed).apply();
    }

    void migrateAmapConsent() {
        if (!preferences.contains("amap-map-key")) return;
        boolean allowed = preferences.getString("amap-map-key", "").matches("[0-9a-fA-F]{32}");
        preferences.edit().remove("amap-map-key").putBoolean("amap-map-allowed", allowed).apply();
    }

    boolean amapPlaceNames() { return accountPreference("amap-place-names:"); }

    void allowAmapPlaceNames(boolean allowed) { setAccountPreference("amap-place-names:", allowed); }

    String emergencyContact() {
        return preferences.getString("emergency:" + accountKey(), "");
    }

    void saveEmergencyContact(String phone) {
        preferences.edit().putString("emergency:" + accountKey(), phone).apply();
    }

    boolean usageEnabled() {
        return preferences.getBoolean("usage:" + accountKey(), false);
    }

    void cacheUsage(boolean enabled) {
        preferences.edit().putBoolean("usage:" + accountKey(), enabled).apply();
    }

    boolean liveScreenEnabled() { return accountPreference("live-screen:"); }
    void cacheLiveScreen(boolean enabled) { setAccountPreference("live-screen:", enabled); }

    boolean appUsageEnabled() { return accountPreference("app-usage:"); }
    boolean deviceStatusEnabled() { return accountPreference("device-status:"); }

    void cacheAppUsage(boolean enabled) { setAccountPreference("app-usage:", enabled); }
    void cacheDeviceStatus(boolean enabled) { setAccountPreference("device-status:", enabled); }

    private boolean accountPreference(String key) {
        return preferences.getBoolean(key + accountKey(), false);
    }

    private void setAccountPreference(String key, boolean enabled) {
        preferences.edit().putBoolean(key + accountKey(), enabled).apply();
    }

    boolean sharingSetupAccepted(String pair) {
        return !pair.isEmpty() && pair.equals(preferences.getString("sharing-setup:" + accountKey(), ""));
    }

    boolean unifiedConsentAccepted(String pair) {
        return sharingSetupAccepted(pair) && pair.equals(preferences.getString("unified-consent:" + accountKey(), ""));
    }

    void acceptUnifiedConsent(String pair) {
        String account = accountKey();
        preferences.edit().putString("sharing-setup:" + account, pair)
                .putString("unified-consent:" + account, pair)
                .remove("review-sharing:" + account).remove("legacy-review:" + account).apply();
    }

    boolean restoreLocationOnBoot() { return accountPreference("restore-location:"); }

    void rememberLocationRunning() { setAccountPreference("restore-location:", true); }

    void migrateLegacyPause() {
        String account = accountKey();
        String legacy = "sharing-paused:" + account;
        if (!preferences.getBoolean(legacy, false)) return;
        preferences.edit().remove(legacy).remove("sharing-setup:" + account).remove("unified-consent:" + account)
                .putBoolean("review-sharing:" + account, true).putBoolean("legacy-review:" + account, true).commit();
    }

    boolean consumeLegacyReview() {
        String key = "legacy-review:" + accountKey();
        if (!preferences.getBoolean(key, false)) return false;
        preferences.edit().remove(key).apply();
        return true;
    }

    boolean sharingNeedsReview() { return accountPreference("review-sharing:"); }

    void withdrawSharing() {
        String account = accountKey();
        preferences.edit().remove("sharing-setup:" + account).remove("unified-consent:" + account)
                .putBoolean("review-sharing:" + account, true).remove("legacy-review:" + account)
                .putBoolean(pauseKey(base, username), true).commit();
    }

    boolean pausePending() {
        return preferences.getBoolean(pauseKey(preferences.getString("base", ""),
                preferences.getString("username", "")), false);
    }

    void requestSuspension() {
        preferences.edit().putBoolean(pauseKey(base, username), true).commit();
    }

    synchronized void stopSharing() throws Exception {
        if (!pausePending()) return;
        mutate("PUT", "/sharing", json("enabled", false));
        preferences.edit().remove(pauseKey(base, username)).apply();
    }

    synchronized void authenticate(String address, String user, String password, String displayName, String enrollment)
            throws Exception {
        base = validateBase(address);
        clearSession();
        preferences.edit().putString("base", base).apply();
        String normalized = user.trim().toLowerCase(Locale.ROOT);
        try {
            if (displayName != null) {
                mutate("POST", "/register", json("username", normalized, "password", password,
                        "displayName", displayName.trim(), "enrollmentKey", enrollment.trim()));
            }
            JSONObject csrf = getObject("/csrf");
            send("POST", "/login", "username=" + encode(normalized) + "&password=" + encode(password),
                    "application/x-www-form-urlencoded", csrf);
            username = normalized;
            preferences.edit().putString("username", username).apply();
        } catch (Exception exception) {
            clearSession();
            throw exception;
        }
    }

    synchronized void logout() throws Exception {
        stopSharing();
        mutate("POST", "/logout", null);
        clearSession();
    }

    synchronized void clearSession() {
        cookie = "";
        username = "";
        preferences.edit().remove("session").remove("username").apply();
    }

    synchronized JSONObject getObject(String path) throws Exception {
        return new JSONObject(send("GET", path, null, null, null));
    }

    synchronized JSONArray getArray(String path) throws Exception {
        return new JSONArray(send("GET", path, null, null, null));
    }

    String accountKey() {
        return preferences.getString("base", "") + "#" + preferences.getString("username", "");
    }

    synchronized JSONArray route(boolean partner, long from, long to) throws Exception {
        return new JSONArray(send("GET", "/positions?partner=" + partner + "&from=" + from + "&to=" + to,
                null, null, null));
    }

    synchronized JSONObject mutate(String method, String path, JSONObject payload) throws Exception {
        JSONObject csrf = getObject("/csrf");
        String response = send(method, path, payload == null ? null : payload.toString(), "application/json", csrf);
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private String send(String method, String path, String body, String contentType, JSONObject csrf)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(base + "/api" + path).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(7000);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            if (csrf != null) connection.setRequestProperty(csrf.getString("header"), csrf.getString("token"));
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType + "; charset=UTF-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(data);
                }
            }
            int status = connection.getResponseCode();
            if (status < 300 || status >= 400) saveCookie(connection.getHeaderFields());
            if (status >= 200 && status < 300) return read(connection.getInputStream());
            String error = read(connection.getErrorStream());
            String message = switch (status) {
                case 401 -> "登录已失效或账号密码有误";
                case 403 -> "请求未获授权，请重新登录后再试";
                case 409 -> "当前状态已变化，请刷新后重试";
                default -> "请求失败（HTTP " + status + "）";
            };
            try {
                message = new JSONObject(error).optString("message", message);
            } catch (JSONException ignored) {
                // Framework errors can have empty bodies.
            }
            throw new Failure(status, message);
        } finally {
            connection.disconnect();
        }
    }

    private void saveCookie(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
            for (String value : header.getValue()) {
                String candidate = value.split(";", 2)[0].trim();
                if (!candidate.startsWith("JSESSIONID=")) continue;
                cookie = candidate.equals("JSESSIONID=") ? "" : candidate;
                preferences.edit().putString("session", cookie).apply();
            }
        }
    }

    private static String read(InputStream source) throws IOException {
        if (source == null) return "";
        try (InputStream stream = source; ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (body.size() + count > 2_000_000) throw new IOException("响应过大");
                body.write(buffer, 0, count);
            }
            return body.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String validateBase(String address) throws IOException {
        try {
            URI uri = URI.create(address.trim());
            String host = uri.getHost();
            boolean localDebug = BuildConfig.DEBUG && "http".equalsIgnoreCase(uri.getScheme())
                    && ("10.0.2.2".equals(host) || "127.0.0.1".equals(host) || "localhost".equals(host));
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !localDebug)) {
                throw new IllegalArgumentException();
            }
            return uri.normalize().toASCIIString().replaceAll("/+$", "");
        } catch (IllegalArgumentException error) {
            throw new IOException("请输入服务器 HTTPS 地址，例如 https://share.example.com");
        }
    }

    private static String pauseKey(String base, String username) {
        return "pause:" + base + "#" + username;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    static JSONObject json(Object... values) {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2) object.put((String) values[i], values[i + 1]);
            return object;
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
    }
}
