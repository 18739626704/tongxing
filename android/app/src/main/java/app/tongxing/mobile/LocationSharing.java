package app.tongxing.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class LocationSharing extends Service implements LocationListener {
    static final String KEY = "share_key";
    static final String RESTORE = "app.tongxing.mobile.RESTORE_LOCATION";
    static final String STOP = "app.tongxing.mobile.STOP";
    private static final String CHANNEL = "location_sharing";
    private final LocationCadence cadence = new LocationCadence();
    private long lastFixElapsed;
    private boolean listeningForSteps;
    private static volatile boolean running;
    private static volatile long lastUploadAt;
    private final Runnable retryProviders = this::subscribe;

    static boolean running() { return running; }
    static long lastUploadAt() { return lastUploadAt; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PendingPositions pending;
    private String sharingAccount;
    private String restoringAccount;
    private final Runnable retryRestore = this::restore;
    private final Runnable providerWatchdog = this::checkProviders;
    private long lastProviderFix;
    private long lastAccurateGps;
    private LocationManager locations;
    private volatile String shareKey;
    private long lastAccepted;
    private boolean uploading;
    private long lastUsageReport;
    private long lastAppUsageReport;
    private final ExecutorService notifications = Executors.newSingleThreadExecutor();
    private final Runnable pollFeed = new Runnable() {
        @Override public void run() {
            notifications.execute(() -> {
                reportScreen();
                try { CompanionNotifications.poll(LocationSharing.this); }
                catch (Exception ignored) { /* Retry independently. */ }
                main.post(() -> {
                    if (shareKey == null) return;
                    syncStepSensor();
                    main.postDelayed(this, 20_000);
                });
            });
        }
    };
    private final android.hardware.SensorEventListener stepListener = new android.hardware.SensorEventListener() {
        @Override public void onSensorChanged(android.hardware.SensorEvent event) {
            if (shareKey == null) return;
            cadence.step(SystemClock.elapsedRealtime());
        }

        @Override public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}
    };

    @SuppressLint("MissingPermission")
    private void syncStepSensor() {
        boolean allowed = android.os.Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        if (allowed == listeningForSteps) return;
        var manager = getSystemService(android.hardware.SensorManager.class);
        if (!allowed) {
            manager.unregisterListener(stepListener);
            listeningForSteps = false;
            return;
        }
        var sensor = manager.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR);
        if (sensor != null) {
            try {
                listeningForSteps = manager.registerListener(stepListener, sensor,
                        android.hardware.SensorManager.SENSOR_DELAY_NORMAL, 10_000_000, main);
            } catch (SecurityException denied) {
                listeningForSteps = false;
            }
        }
    }

    private boolean screenReceiverRegistered;
    private final android.content.BroadcastReceiver screenChanges = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            notifications.execute(LocationSharing.this::reportScreen);
        }
    };

    private void reportScreen() {
        try { syncScreen(); }
        catch (Exception ignored) { /* Retry at the next screen change or heartbeat. */ }
    }

    private String lastScreenKey;
    private String lastScreenState;
    private long lastScreenAt;

    private void syncScreen() throws Exception {
        Api api = Api.get(this);
        String key = shareKey;
        if (key == null || !api.liveScreenEnabled() || api.pausePending() || !hasLocationPermission()) {
            lastScreenState = null;
            return;
        }
        var power = getSystemService(android.os.PowerManager.class);
        var lock = getSystemService(android.app.KeyguardManager.class);
        String state = !power.isInteractive() ? "OFF" : lock.isKeyguardLocked() ? "LOCKED" : "UNLOCKED";
        long now = SystemClock.elapsedRealtime();
        if (key.equals(lastScreenKey) && state.equals(lastScreenState) && now - lastScreenAt < 55_000) return;
        if (!key.equals(shareKey) || !api.liveScreenEnabled() || api.pausePending()) return;
        api.mutate("PUT", "/live-screen", Api.json("shareKey", key, "state", state));
        lastScreenKey = key;
        lastScreenState = state;
        lastScreenAt = now;
    }

    private long unlockCheckedAt;
    private long unlockAt;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            pauseAndStop();
            return START_NOT_STICKY;
        }
        Api api = Api.get(this);
        if (!api.hasSession() || api.pausePending() || api.sharingNeedsReview()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || RESTORE.equals(intent.getAction())) {
            if (android.os.Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                startForeground(41, notification());
            } catch (RuntimeException denied) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (shareKey == null) {
                restoringAccount = api.accountKey();
                restore();
            }
            return START_STICKY;
        }
        String key = intent.getStringExtra(KEY);
        if (key == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!key.equals(shareKey)) startLocation(key);
        return START_STICKY;
    }

    private void restore() {
        String account = restoringAccount;
        Api api = Api.get(this);
        if (account == null || !account.equals(api.accountKey()) || !api.hasSession()
                || api.pausePending() || api.sharingNeedsReview()) {
            stopSelf();
            return;
        }
        worker.execute(() -> {
            try {
                String key = api.getObject("/sharing").getString("shareKey");
                main.post(() -> {
                    if (shareKey == null && account.equals(api.accountKey()) && !api.sharingNeedsReview()
                            && !api.pausePending()) startLocation(key);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (shareKey != null || !account.equals(restoringAccount)) return;
                    if (error instanceof Api.Failure failure && (failure.status == 401 || failure.status == 409)) {
                        discardPositions();
                        stopSelf();
                    } else {
                        main.removeCallbacks(retryRestore);
                        main.postDelayed(retryRestore, 20_000);
                    }
                });
            }
        });
    }

    private void checkProviders() {
        if (shareKey == null) return;
        if (SystemClock.elapsedRealtime() - lastProviderFix > 90_000) subscribe();
        main.removeCallbacks(providerWatchdog);
        main.postDelayed(providerWatchdog, 60_000);
    }

    private void discardPositions() {
        if (pending == null) pending = new PendingPositions(this);
        pending.clear();
    }

    private void startLocation(String key) {
        boolean wasRunning = shareKey != null;
        shareKey = key;
        restoringAccount = null;
        main.removeCallbacks(retryRestore);
        sharingAccount = Api.get(this).accountKey();
        if (pending == null) pending = new PendingPositions(this);
        pending.prepare(sharingAccount, key);
        lastAccepted = 0;
        lastAccurateGps = 0;
        lastProviderFix = SystemClock.elapsedRealtime();
        lastFixElapsed = 0;
        cadence.reset();
        lastUploadAt = 0;
        try {
            startForeground(41, notification());
            running = true;
            Api.get(this).rememberLocationRunning();
            if (!wasRunning) {
                registerScreenChanges();
                main.post(pollFeed);
            }
            subscribe();
            checkProviders();
            upload();
        } catch (RuntimeException error) {
            stopSelf();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerScreenChanges() {
        var filter = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenChanges, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenChanges, filter);
        screenReceiverRegistered = true;
    }

    @SuppressLint("MissingPermission")
    private void subscribe() {
        locations = getSystemService(LocationManager.class);
        locations.removeUpdates(this);
        main.removeCallbacks(retryProviders);
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        String[] providers = fine ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}
                : coarse ? new String[]{LocationManager.NETWORK_PROVIDER} : new String[0];
        boolean registered = false;
        Location best = null;
        for (String provider : providers) {
            try {
                if (!locations.isProviderEnabled(provider)) continue;
                long interval = fine && LocationManager.NETWORK_PROVIDER.equals(provider)
                        ? LocationCadence.STATIONARY_INTERVAL : LocationCadence.PROVIDER_INTERVAL;
                locations.requestLocationUpdates(provider, interval, 0, this);
                registered = true;
                Location fix = locations.getLastKnownLocation(provider);
                if (fix == null || !fix.hasAccuracy()
                        || SystemClock.elapsedRealtime() - fix.getElapsedRealtimeNanos() / 1_000_000 > 30_000) continue;
                if (best == null || fix.getAccuracy() < best.getAccuracy()) best = fix;
            } catch (IllegalArgumentException | SecurityException error) {
                android.util.Log.i("TongxingLocation", "Location provider unavailable: " + provider);
            }
        }
        if (!registered) main.postDelayed(retryProviders, 20_000);
        if (best != null) {
            Location recent = best;
            main.post(() -> onLocationChanged(recent));
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (shareKey == null) return;
        long now = SystemClock.elapsedRealtime();
        long fixAt = location.getElapsedRealtimeNanos() / 1_000_000;
        long age = now - fixAt;
        if (age < 0 || age > 60_000 || (lastFixElapsed != 0 && fixAt <= lastFixElapsed)
                || !location.hasAccuracy() || location.getAccuracy() > 10_000) return;
        lastProviderFix = now;
        if (LocationManager.GPS_PROVIDER.equals(location.getProvider()) && location.getAccuracy() <= 60) {
            lastAccurateGps = fixAt;
        } else if (lastAccurateGps > 0 && fixAt - lastAccurateGps < 45_000 && location.getAccuracy() > 60) {
            return;
        }
        lastFixElapsed = fixAt;
        cadence.fix(location, fixAt);
        if (lastAccepted != 0 && fixAt - lastAccepted < cadence.interval(fixAt)) return;
        lastAccepted = fixAt;
        BatteryManager battery = getSystemService(BatteryManager.class);
        int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        JSONObject sample = Api.json("id", UUID.randomUUID().toString(), "shareKey", shareKey,
                "latitude", location.getLatitude(), "longitude", location.getLongitude(),
                "accuracy", location.getAccuracy(), "battery", Math.max(-1, Math.min(100, level)),
                "charging", battery.isCharging(), "recordedAt", location.getTime());
        pending.add(sharingAccount, shareKey, sample);
        upload();
    }

    private void upload() {
        if (uploading || shareKey == null) return;
        JSONObject sample = pending.next(sharingAccount, shareKey);
        if (sample == null) return;
        String generation = shareKey;
        uploading = true;
        worker.execute(() -> {
            int result;
            try {
                result = 0;
                if (generation.equals(shareKey)) {
                    Api api = Api.get(this);
                    long now = SystemClock.elapsedRealtime();
                    if (api.usageEnabled() && (now - lastUsageReport >= 60_000 || lastUsageReport == 0)) {
                        try {
                            ScreenTime.append(this, sample);
                        } catch (Exception ignored) {
                            // Location delivery remains independent of optional daily statistics.
                        }
                        lastUsageReport = now;
                    }
                    if (generation.equals(shareKey) && !api.pausePending() && hasLocationPermission()) {
                        appendDeviceStatus(api, sample, now);
                        if (api.amapPlaceNames()) sample.put("amapPlaceNames", true);
                        else sample.remove("amapPlaceNames");
                        api.mutate("POST", "/positions", sample);
                        result = 204;
                        reportAppUsage(api, generation, now);
                    }

                }
            } catch (Api.Failure error) {
                result = error.status;
            } catch (Exception error) {
                result = 0;
            }
            int status = result;
            main.post(() -> {
                uploading = false;
                if (!generation.equals(shareKey)) {
                    upload();
                    return;
                }
                if (status == 401 || status == 409) {
                    discardPositions();
                    stopSelf();
                    return;
                }
                if (status == 204) lastUploadAt = System.currentTimeMillis();
                if (status == 204 || status == 400) {
                    pending.remove(sample);
                    upload();
                } else {
                    main.removeCallbacks(retry);
                    main.postDelayed(retry, 15_000);
                }
            });
        });
    }

    private void appendDeviceStatus(Api api, JSONObject sample, long now) {
        sample.remove("unlockedAt");
        sample.remove("deviceModel");
        sample.remove("networkType");
        if (!api.deviceStatusEnabled()) {
            unlockCheckedAt = 0;
            unlockAt = 0;
            return;
        }
        try {
            DeviceStatus.append(this, sample);
            if (unlockCheckedAt == 0 || now - unlockCheckedAt >= 60_000) {
                unlockAt = ScreenTime.latestUnlock(this, System.currentTimeMillis());
                unlockCheckedAt = now;
            }
            if (unlockAt > 0 && ScreenTime.allowed(this)) sample.put("unlockedAt", unlockAt);
        } catch (Exception ignored) {
            // Optional device statistics must not interrupt location sharing.
        }
    }

    private void reportAppUsage(Api api, String generation, long now) {
        if (!api.appUsageEnabled() || !ScreenTime.allowed(this)
                || (lastAppUsageReport != 0 && now - lastAppUsageReport < 60_000)) return;
        lastAppUsageReport = now;
        try {
            ScreenTime.AppUsage usage = ScreenTime.appUsage(this, java.time.LocalDate.now());
            if (usage != null && generation.equals(shareKey) && !api.pausePending()) {
                api.mutate("PUT", "/app-usage", Api.json("shareKey", generation,
                        "date", usage.day().toString(), "recordedAt", usage.recordedAt(), "apps", usage.toJson(200)));
            }
        } catch (Exception ignored) {
            // Application totals have their own retry interval and cannot block location delivery.
        }
    }

    private final Runnable retry = this::upload;

    private Notification notification() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "位置共享", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(app.tongxing.mobile.R.drawable.ic_position)
                .setContentTitle("同行正在共享位置").setContentText("轻触查看同步状态和共享授权")
                .setOngoing(true).setContentIntent(open).build();
    }

    private void pauseAndStop() {
        Api api = Api.get(this);
        api.withdrawSharing();
        shareKey = null;
        discardPositions();
        new Thread(() -> {
            try {
                api.stopSharing();
            } catch (Exception ignored) {
                // A later foreground refresh retries the persisted pause.
            }
        }, "tongxing-pause").start();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        lastUploadAt = 0;
        shareKey = null;
        if (pending != null) pending.close();
        main.removeCallbacksAndMessages(null);
        if (locations != null) locations.removeUpdates(this);
        worker.shutdownNow();
        if (listeningForSteps) getSystemService(android.hardware.SensorManager.class).unregisterListener(stepListener);
        if (screenReceiverRegistered) unregisterReceiver(screenChanges);
        notifications.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
