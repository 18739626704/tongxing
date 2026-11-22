package app.tongxing.mobile;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MainActivityTest {
    @Test
    public void serverAddressDefaultsToHttpsForDomainsAndIps() throws Exception {
        assertEquals("https://share.example.com", Api.validateBase(" share.example.com/ "));
        assertEquals("https://192.0.2.10:8443", Api.validateBase("192.0.2.10:8443"));
        try {
            Api.validateBase("http://share.example.com");
            fail("Remote HTTP should be refused");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void registrationExplainsRulesAndMarksInvalidInputs() {
        Api.get(RuntimeEnvironment.getApplication()).clearSession();
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            text(root, "创建账号").performClick();
            assertNotNull(text(root, "3–32 位，可用英文字母、数字和下划线，字母会转为小写。"));
            List<EditText> inputs = views(root, EditText.class);
            inputs.get(0).setText("http://share.example.com");
            inputs.get(1).setText("a!");
            inputs.get(2).setText("123");
            text(root, "注册并登录").performClick();
            assertTrue(inputs.get(0).getError().toString().contains("HTTPS"));
            assertTrue(inputs.get(1).getError().toString().contains("3–32"));
            assertTrue(inputs.get(2).getError().toString().contains("10–64"));
        }
    }

    @Test
    public void offlineLogoutClearsTheSessionWithoutWaitingForARequest() throws Exception {
        var api = Api.get(RuntimeEnvironment.getApplication());
        try (MockWebServer server = new MockWebServer()) {
            AtomicBoolean refreshStarted = new AtomicBoolean();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return switch (request.getRequestUrl().encodedPath()) {
                        case "/api/csrf" -> reply(200, "{\"header\":\"X-CSRF-TOKEN\",\"token\":\"test-csrf\"}");
                        case "/api/login" -> reply(204, "").setHeader("Set-Cookie", "JSESSIONID=test; Path=/; HttpOnly");
                        case "/api/overview" -> {
                            refreshStarted.set(true);
                            yield new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE);
                        }
                        default -> reply(503, "");
                    };
                }
            });
            server.start();
            api.authenticate("http://127.0.0.1:" + server.getPort(), "offline", "TestPassword123", false);
            try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                View root = controller.get().findViewById(android.R.id.content);
                await(refreshStarted::get);
                text(root, "设置").performClick();
                assertNotNull(text(root, "登录名 · offline"));
                text(root, "退出登录").performClick();
                assertNotNull(text(root, "登录"));
                assertFalse(api.hasSession());
                assertEquals("", api.savedUsername());
            }
        }
    }

    @Test
    public void walkingWakesDenseReportingWithoutTheStepSensor() {
        LocationCadence cadence = new LocationCadence();
        Location first = new Location(LocationManager.GPS_PROVIDER);
        first.setAccuracy(8);
        cadence.fix(first, 1_000);
        assertEquals(LocationCadence.STATIONARY_INTERVAL, cadence.interval(1_000));

        Location walking = new Location(first);
        walking.setLatitude(.00019);
        cadence.fix(walking, 16_000);
        assertEquals(LocationCadence.MOVING_INTERVAL, cadence.interval(16_000));
        assertEquals(LocationCadence.STATIONARY_INTERVAL, cadence.interval(137_000));
    }

    @Test
    public void offlineSamplesSurviveReopeningAndAreScopedToTheSharingSession() {
        var app = RuntimeEnvironment.getApplication();
        String account = "https://share.example.com#alice";
        JSONObject sample = Api.json("id", "one", "recordedAt", System.currentTimeMillis());
        try (var queue = new PendingPositions(app)) {
            queue.prepare(account, "generation-a");
            queue.add(account, "generation-a", sample);
        }
        try (var queue = new PendingPositions(app)) {
            assertNull(queue.next(account, "generation-b"));
            assertEquals("one", queue.next(account, "generation-a").optString("id"));
            queue.prepare(account, "generation-b");
            assertNull(queue.next(account, "generation-a"));
        }
    }

    @Test
    public void scannerOpensWithPermissionPendingAndCanReturnToManualInput() {
        var app = RuntimeEnvironment.getApplication();
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        Intent intent = new com.journeyapps.barcodescanner.ScanOptions()
                .setCaptureActivity(PairScanActivity.class)
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setOrientationLocked(false).setBeepEnabled(false).createScanIntent(app);
        try (var controller = Robolectric.buildActivity(PairScanActivity.class, intent).setup()) {
            var activity = controller.get();
            View root = activity.findViewById(android.R.id.content);
            assertNotNull(text(root, "将同行绑定二维码放入取景框"));
            assertFalse(activity.isFinishing());
            text(root, "手动输入").performClick();
            assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).getResultCode());
            assertTrue(shadowOf(activity).getResultIntent().getBooleanExtra(PairScanActivity.MANUAL, false));
            assertTrue(activity.isFinishing());
        }
    }

    @Test
    public void scannerAcceptsOnlyValidTongxingPairCodes() {
        assertEquals("0123456789ABCDEF", PairQr.scan("tongxing:pair:0123456789abcdef"));
        assertEquals("0123456789ABCDEF", PairQr.code(" 0123456789abcdef "));
        assertEquals("", PairQr.scan("https://share.example.com/0123456789abcdef"));
        assertEquals("", PairQr.scan("tongxing:pair:0123456789abcdeg"));
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    public void signsInUpdatesPartnerStatusAndShowsDailyTrack() throws Exception {
        Api.get(RuntimeEnvironment.getApplication()).clearSession();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean history = new AtomicBoolean();
        AtomicBoolean viewedSos = new AtomicBoolean();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getRequestUrl().encodedPath();
                    if (path.equals("/api/csrf")) {
                        var csrf = reply(200, "{\"header\":\"X-CSRF-TOKEN\",\"token\":\"test-csrf\"}");
                        if (request.getHeader("Cookie") == null) csrf.setHeader("Set-Cookie", "JSESSIONID=initial; Path=/; HttpOnly");
                        return csrf;
                    }
                    if (path.equals("/api/login")) {
                        boolean valid = "test-csrf".equals(request.getHeader("X-CSRF-TOKEN"))
                                && "JSESSIONID=initial".equals(request.getHeader("Cookie"))
                                && "username=alice&password=TestPassword123".equals(request.getBody().readUtf8());
                        if (!valid) failure.set("Login request did not contain CSRF, session, and encoded credentials");
                        return reply(valid ? 204 : 403, "")
                                .setHeader("Set-Cookie", "JSESSIONID=signed-in; Path=/; HttpOnly");
                    }
                    if (!"JSESSIONID=signed-in".equals(request.getHeader("Cookie"))) {
                        failure.set("Authenticated request did not use the rotated session cookie");
                        return reply(401, "");
                    }
                    long now = System.currentTimeMillis();
                    String location = "{\"latitude\":34.75,\"longitude\":113.65,\"accuracy\":8,"
                            + "\"battery\":81,\"charging\":true,\"recordedAt\":" + now + "}";
                    if (path.equals("/api/overview")) {
                        return reply(200, "{\"self\":{\"displayName\":\"Alice\",\"sharing\":false},"
                                + "\"partner\":{\"displayName\":\"Bob\",\"sharing\":true,\"lastLocation\":" + location
                                + "},\"serverTime\":" + now + "}");
                    }
                    if (path.equals("/api/feed")) {
                        var sos = Api.json("id", 999, "kind", "SOS", "text", "需要帮助", "sender", "Bob",
                                "incoming", true, "hasLocation", true, "createdAt", now);
                        return reply(200, Api.json("events", new org.json.JSONArray().put(sos)).toString());
                    }
                    if (path.equals("/api/sos/999")) return reply(200, location);
                    if (path.equals("/api/sos/999/receipt")) {
                        viewedSos.set(request.getBody().readUtf8().contains("\"viewed\":true"));
                        return reply(204, "");
                    }
                    if (path.equals("/api/positions")) {
                        if (!history.get()) return reply(200, "[" + location + "]");
                        var fixes = new org.json.JSONArray();
                        for (long offset : new long[]{30_000_000, 29_850_000, 29_655_000, 60_000, 0}) {
                            fixes.put(Api.json("latitude", 34.75, "longitude", 113.65, "accuracy", 8,
                                    "recordedAt", now - offset));
                        }
                        return reply(200, fixes.toString());
                    }
                    if (path.equals("/api/app-usage")) {
                        return reply(200, "{\"date\":\"2026-11-21\",\"apps\":[{\"name\":\"地图应用\",\"seconds\":120}]}");
                    }
                    return reply(404, "");
                }
            });
            server.start();
            try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                View root = activity.findViewById(android.R.id.content);
                List<EditText> fields = views(root, EditText.class);
                assertEquals(3, fields.size());
                fields.get(0).setText("http://127.0.0.1:" + server.getPort());
                fields.get(1).setText("Alice");
                fields.get(2).setText("TestPassword123");
                text(root, "登录").performClick();
                await(() -> views(root, TextView.class).stream()
                        .anyMatch(view -> view.getText().toString().contains("位置 刚刚更新 · 电量 81%")));
                assertTrue(Api.get(RuntimeEnvironment.getApplication()).hasSession());
                var web = views(root, android.webkit.WebView.class).get(0);
                assertEquals("https://appassets.androidplatform.net/assets/map.html", shadowOf(web).getLastLoadedUrl());
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.getLayoutParams().width);
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.getLayoutParams().height);
                assertTrue(web.getParent() instanceof android.widget.FrameLayout);
                assertFalse(text(root, "应用时长").isShown());
                text(root, "Bob").performClick();
                var focused = org.robolectric.util.ReflectionHelpers.<JSONObject>getField(activity, "mapData");
                assertEquals("partner", focused.optString("mode"));
                assertEquals(1, focused.getJSONArray("points").length());
                assertTrue(focused.getJSONArray("points").getJSONObject(0).getBoolean("partner"));
                text(root, "看双方").performClick();
                assertEquals("live", org.robolectric.util.ReflectionHelpers.<JSONObject>getField(activity, "mapData").optString("mode"));
                text(root, "展开").performClick();
                text(root, "应用时长").performClick();
                await(() -> org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog() != null);
                var usage = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                assertNotNull(text(usage.getWindow().getDecorView(), "地图应用"));
                usage.dismiss();
                assertEquals(1, text(root, "看双方").getMaxLines());
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, text(root, "看双方").getLayoutParams().width);
                assertEquals(1, text(root, "收起").getMaxLines());
                text(root, "收起").performClick();
                assertNotNull(text(root, "展开"));

                views(root, TextView.class).stream().filter(view -> view.getText().toString().equals("足迹")
                        && view.isClickable()).findFirst().orElseThrow().performClick();
                await(() -> text(root, "当天共 1 个有效定位点") != null);
                text(root, "今天").performClick();
                var date = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                assertTrue(date instanceof android.app.DatePickerDialog);
                date.dismiss();
                history.set(true);
                text(root, "前一天").performClick();
                await(() -> text(root, "查看时间轴").isEnabled());
                text(root, "查看时间轴").performClick();
                var timeline = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                View timelineView = timeline.getWindow().getDecorView();
                assertNotNull(text(timelineView, "足迹时间轴"));
                assertTrue(views(timelineView, TextView.class).stream()
                        .anyMatch(view -> view.getText().toString().startsWith("停留区域")));
                var missing = views(timelineView, TextView.class).stream()
                        .filter(view -> view.getText().toString().startsWith("期间无有效定位点")).findFirst().orElseThrow();
                assertFalse(missing.isShown());
                views(timelineView, TextView.class).stream()
                        .filter(view -> view.getText().toString().startsWith("展开记录缺口")).findFirst().orElseThrow().performClick();
                assertTrue(missing.isShown());
                timeline.dismiss();
                assertNull(failure.get());

                text(root, "设置").performClick();
                assertNotNull(text(root, "权限与共享"));
                assertNotNull(text(root, "修改密码"));
                assertNull(text(root, "查看我的应用使用明细"));
                assertNull(text(root, "饮水和就寝提醒"));
                assertNull(text(root, "清除我的轨迹"));
                text(root, "动态").performClick();
                await(() -> text(root, "查看求助位置") != null);
                text(root, "查看求助位置").performClick();
                await(() -> text(root, "SOS 求助位置") != null);
                assertNotNull(text(root, "返回实时地图"));
                assertTrue(viewedSos.get());
                assertNull(shadowOf(activity).getNextStartedActivity());
                assertTrue(CompanionNotifications.enabled(activity));
                var scheduler = activity.getSystemService(android.app.job.JobScheduler.class);
                assertTrue(scheduler.getAllPendingJobs().stream().anyMatch(job ->
                        job.getService().getClassName().equals(CompanionNotifications.class.getName())));
            }
        }
    }

    @Test
    public void locationServiceRetriesTheSamePointAndStopsOnUserRequest() throws Exception {
        String key = "13a7397b-7c39-4e86-9309-11d530f921ac";
        ConcurrentLinkedQueue<String> uploads = new ConcurrentLinkedQueue<>();
        AtomicBoolean paused = new AtomicBoolean();
        AtomicBoolean checkedFeed = new AtomicBoolean();
        AtomicBoolean incomingMessage = new AtomicBoolean();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return switch (request.getRequestUrl().encodedPath()) {
                        case "/api/csrf" -> reply(200, "{\"header\":\"X-CSRF-TOKEN\",\"token\":\"test-csrf\"}");
                        case "/api/login" -> reply(204, "").setHeader("Set-Cookie", "JSESSIONID=active; Path=/; HttpOnly");
                        case "/api/feed" -> {
                            checkedFeed.set(true);
                            String events = incomingMessage.get() ? "[{\"id\":1,\"incoming\":true,\"kind\":\"MESSAGE\","
                                    + "\"sender\":\"Bob\",\"text\":\"后台消息\",\"createdAt\":" + System.currentTimeMillis() + "}]" : "[]";
                            yield reply(200, "{\"pairId\":\"paired\",\"events\":" + events + "}");
                        }
                        case "/api/positions" -> {
                            uploads.add(request.getBody().readUtf8());
                            yield reply(uploads.size() == 1 ? 503 : 204, "");
                        }
                        case "/api/sharing" -> {
                            paused.set(request.getBody().readUtf8().contains("\"enabled\":false"));
                            yield reply(200, "{}");
                        }
                        default -> reply(404, "");
                    };
                }
            });
            server.start();
            Application app = RuntimeEnvironment.getApplication();
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
            Api api = Api.get(app);
            api.authenticate("http://127.0.0.1:" + server.getPort(), "alice", "TestPassword123", false);
            var controller = Robolectric.buildService(LocationSharing.class).create();
            try {
                LocationSharing service = controller.get();
                LocationManager manager = service.getSystemService(LocationManager.class);
                shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
                service.onStartCommand(new Intent().putExtra(LocationSharing.KEY, key), 0, 1);
                assertTrue(LocationSharing.running());
                assertNotNull(shadowOf(service).getLastForegroundNotification());
                await(checkedFeed::get);
                incomingMessage.set(true);
                var alarms = service.getSystemService(android.app.NotificationManager.class);
                await(() -> {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
                    return java.util.Arrays.stream(alarms.getActiveNotifications()).anyMatch(item -> item.getId() == 72);
                });
                var alert = java.util.Arrays.stream(alarms.getActiveNotifications())
                        .filter(item -> item.getId() == 72).findFirst().orElseThrow().getNotification();
                assertEquals("后台消息", alert.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString());
                assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH,
                        alarms.getNotificationChannel(alert.getChannelId()).getImportance());
                assertTrue(uploads.isEmpty());

                Location fix = new Location(LocationManager.GPS_PROVIDER);
                fix.setLatitude(34.75);
                fix.setLongitude(113.65);
                fix.setAccuracy(8);
                fix.setTime(System.currentTimeMillis());
                fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                service.onLocationChanged(fix);
                await(() -> uploads.size() == 1);
                long expires = System.nanoTime() + Duration.ofSeconds(6).toNanos();
                while (uploads.size() < 2 && System.nanoTime() < expires) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
                    Thread.sleep(30);
                }
                assertEquals(2, uploads.size());
                String first = uploads.poll();
                assertEquals(first, uploads.poll());
                assertEquals(key, new JSONObject(first).getString("shareKey"));

                service.onStartCommand(new Intent(LocationSharing.STOP), 0, 2);
                await(() -> paused.get() && !api.pausePending());
                assertTrue(shadowOf(service).isStoppedBySelf());
                assertTrue(api.sharingNeedsReview());
            } finally {
                controller.destroy();
            }
        }
    }

    @Test
    public void reopeningRestartsActiveLocationWithoutEnablingItAgain() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        ConcurrentLinkedQueue<String> sharingMethods = new ConcurrentLinkedQueue<>();
        String key = "13a7397b-7c39-4e86-9309-11d530f921ac";
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return switch (request.getRequestUrl().encodedPath()) {
                        case "/api/csrf" -> reply(200, "{\"header\":\"X-CSRF-TOKEN\",\"token\":\"test-csrf\"}");
                        case "/api/login" -> reply(204, "").setHeader("Set-Cookie", "JSESSIONID=active; Path=/; HttpOnly");
                        case "/api/overview" -> reply(200, "{\"self\":{\"sharing\":" + active.get()
                                + "},\"partner\":{\"displayName\":\"Bob\",\"sharing\":false},"
                                + "\"pairId\":\"27d5709f-eacb-4236-ab46-84d246764669\"}");
                        case "/api/sharing" -> {
                            sharingMethods.add(request.getMethod());
                            yield reply(200, "{\"shareKey\":\"" + key + "\"}");
                        }
                        default -> reply(404, "");
                    };
                }
            });
            server.start();
            Application app = RuntimeEnvironment.getApplication();
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
            var manager = app.getSystemService(LocationManager.class);
            shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
            Api.get(app).authenticate("http://127.0.0.1:" + server.getPort(), "alice", "TestPassword123", false);
            try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                var started = new AtomicReference<Intent>();
                await(() -> {
                    Intent service = shadowOf(app).getNextStartedService();
                    if (service == null || service.getComponent() == null
                            || !LocationSharing.class.getName().equals(service.getComponent().getClassName())) return false;
                    started.set(service);
                    return true;
                });
                assertEquals(key, started.get().getStringExtra(LocationSharing.KEY));
                assertEquals(List.of("GET"), new ArrayList<>(sharingMethods));
            }
            active.set(false);
            Api.get(app).withdrawSharing();
            Api.get(app).stopSharing();
            sharingMethods.clear();
            try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                View root = controller.get().findViewById(android.R.id.content);
                await(() -> text(root, "已暂停共享 · 点击恢复") != null);
                assertTrue(sharingMethods.isEmpty());
                assertNull(shadowOf(app).getNextStartedService());
            }
        }
    }

    @Test
    public void startsAutomaticallyWhenPairedAndLocationIsGranted() throws Exception {
        String pair = "27d5709f-eacb-4236-ab46-84d246764669";
        String key = "13a7397b-7c39-4e86-9309-11d530f921ac";
        AtomicReference<String> startBody = new AtomicReference<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return switch (request.getRequestUrl().encodedPath()) {
                        case "/api/csrf" -> reply(200, "{\"header\":\"X-CSRF-TOKEN\",\"token\":\"test-csrf\"}");
                        case "/api/login" -> reply(204, "").setHeader("Set-Cookie", "JSESSIONID=active; Path=/; HttpOnly");
                        case "/api/overview" -> reply(200, "{\"self\":{\"sharing\":false},"
                                + "\"partner\":{\"displayName\":\"Bob\"},\"pairId\":\"" + pair + "\"}");
                        case "/api/sharing" -> {
                            startBody.set(request.getBody().readUtf8());
                            yield reply(200, "{\"shareKey\":\"" + key + "\"}");
                        }
                        default -> reply(404, "");
                    };
                }
            });
            server.start();
            Application app = RuntimeEnvironment.getApplication();
            var location = app.getSystemService(LocationManager.class);
            shadowOf(location).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
            Api api = Api.get(app);
            api.authenticate("http://127.0.0.1:" + server.getPort(), "alice", "TestPassword123", false);
            try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                View root = activity.findViewById(android.R.id.content);
                await(() -> text(root, "位置权限未就绪，点右上角「权限」") != null);
                assertNull(startBody.get());
                shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
                activity.onRequestPermissionsResult(11, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
                await(() -> startBody.get() != null);
                assertEquals(pair, new JSONObject(startBody.get()).getString("pairId"));
                await(() -> {
                    Intent started = shadowOf(app).getNextStartedService();
                    return started != null && key.equals(started.getStringExtra(LocationSharing.KEY));
                });
                assertNull(text(root, "开始共享"));
            }
        }
    }

    @Test
    public void pairedAccountsEnableJourneyAndScreenWithoutExtraConsent() throws Exception {
        String pair = "27d5709f-eacb-4236-ab46-84d246764669";
        var journey = new AtomicBoolean();
        var screen = new AtomicBoolean();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return switch (request.getRequestUrl().encodedPath()) {
                        case "/api/csrf" -> reply(200, "{\"header\":\"X-CSRF-TOKEN\",\"token\":\"test-csrf\"}");
                        case "/api/login" -> reply(204, "").setHeader("Set-Cookie", "JSESSIONID=active; Path=/; HttpOnly");
                        case "/api/overview" -> reply(200, "{\"self\":{\"sharing\":true,\"usageEnabled\":false,"
                                + "\"deviceStatusEnabled\":true,\"journeyEnabled\":" + journey.get()
                                + ",\"liveScreenEnabled\":" + screen.get()
                                + "},\"partner\":{\"displayName\":\"Bob\"},\"pairId\":\"" + pair + "\"}");
                        case "/api/usage-sharing", "/api/app-usage-sharing", "/api/device-status-sharing" -> reply(204, "");
                        case "/api/sharing" -> reply(200, "{\"shareKey\":\"13a7397b-7c39-4e86-9309-11d530f921ac\"}");
                        case "/api/journey-sharing" -> { journey.set(true); yield reply(204, ""); }
                        case "/api/live-screen-sharing" -> { screen.set(true); yield reply(204, ""); }
                        default -> reply(404, "");
                    };
                }
            });
            server.start();
            Application app = RuntimeEnvironment.getApplication();
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
            shadowOf(app.getSystemService(LocationManager.class)).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
            var api = Api.get(app);
            api.authenticate("http://127.0.0.1:" + server.getPort(), "alice", "TestPassword123", false);

            try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                await(() -> journey.get() && screen.get());
                assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
            }
        }
    }

    private static MockResponse reply(int status, String data) {
        return new MockResponse().setResponseCode(status).setBody(data)
                .setHeader("Content-Type", "application/json; charset=UTF-8");
    }

    private static void await(BooleanSupplier check) throws InterruptedException {
        long end = System.nanoTime() + 8_000_000_000L;
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (check.getAsBoolean()) return;
            Thread.sleep(30);
        } while (System.nanoTime() < end);
        fail("UI did not reach the expected state");
    }

    private static TextView text(View root, String expected) {
        return views(root, TextView.class).stream().filter(view -> view.getText().toString().equals(expected))
                .findFirst().orElse(null);
    }

    private static <T extends View> List<T> views(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(views(group.getChildAt(i), type));
        }
        return result;
    }
}
