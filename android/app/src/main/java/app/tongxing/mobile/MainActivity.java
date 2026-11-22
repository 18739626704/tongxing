package app.tongxing.mobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends ComponentActivity {
    private static final int BACKGROUND = 0xfff5f8f4;
    private static final int INK = 0xff172e26;
    private static final int MUTED = 0xff50695f;
    private static final int ACCENT = 0xff17634b;
    private static final int SOFT = 0xffe0efe7;
    private static final int LINEN = 0xfff9f5ee;
    private static final int AMBER = 0xff79512c;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Api api;
    private LinearLayout root;
    private LinearLayout content;
    private NativeMap nativeMap;
    private JSONObject mapData;
    private TextView headline;
    private TextView partnerSyncLine;
    private TextView partnerScreenLine;
    private android.animation.ObjectAnimator screenPulse;
    private TextView partnerLine;
    private TextView hint;

    private Button inviteButton;
    private Button pairButton;
    private Button scanButton;
    private Button unpairButton;
    private TextView displayNameLine;
    private TextView profileAvatar;
    private JSONObject overview;
    private boolean authenticated;
    private boolean registering;
    private boolean visible;
    private boolean busy;
    private boolean refreshing;
    private int tab;
    private int generation;
    private int accountGeneration;
    private int daysBack;
    private boolean partnerRoute = true;
    private boolean collapsed = true;
    private boolean partnerFocus;
    private JSONObject routeHighlight;
    private JSONObject sosPoint;
    private String sosPair;
    private String sosDetail;
    private long sosEventAt;
    private boolean sosIncoming;
    private FrameLayout mapFrame;
    private LinearLayout feedRows;
    private JSONArray displayedRoute;
    private java.util.List<RouteTimeline.Part> displayedParts = java.util.List.of();
    private Button timelineButton;
    private EditText messageInput;
    private boolean permissionPromptShown;
    private TextView permissionsChip;
    private AlertDialog permissionDialog;
    private AlertDialog mapPrivacyDialog;
    private LinearLayout permissionRows;
    private TextView partnerAvatar;
    private TextView partnerDeviceLine;
    private boolean resumed;
    private long nextSharingAttempt;
    private boolean restoring;
    private JSONObject feedCache;
    private android.os.CancellationSignal sosFixRequest;
    private JSONArray knownPlaces = new JSONArray();
    private String knownPlacesPair = "";
    private LinearLayout mapStatus;
    private LinearLayout mapDock;
    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> pairScanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (!authenticated) return;
                Intent data = result.getOriginalIntent();
                if (data != null && data.getBooleanExtra(PairScanActivity.MANUAL, false)) {
                    pair();
                    return;
                }
                if (result.getContents() == null) return;
                String code = PairQr.scan(result.getContents());
                if (code.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle("无法识别邀请码")
                            .setMessage("请扫描另一部手机上由同行生成的绑定二维码。")
                            .setPositiveButton("重新扫码", (dialog, which) -> scanPairCode())
                            .setNegativeButton("手动输入", (dialog, which) -> pair())
                            .show();
                    return;
                }
                pair(code);
            });

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible || !authenticated) return;
            if (!busy && !refreshing) refresh(false);
            main.postDelayed(this, 10_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 35) getWindow().setDecorFitsSystemWindows(false);
        api = Api.get(this);
        api.migrateAmapConsent();
        authenticated = api.hasSession();
        if (authenticated) api.migrateLegacyPause();
        if (authenticated && CompanionNotifications.OPEN.equals(getIntent().getAction())) tab = 3;
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (authenticated && !busy && CompanionNotifications.OPEN.equals(intent.getAction())) {
            tab = 3;
            render();
            refresh(false);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        nextSharingAttempt = 0;
        if (authenticated) CompanionNotifications.schedule(this);
        updatePermissionChip();
        main.removeCallbacks(poll);
        poll.run();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (nativeMap != null) nativeMap.resume();
        updatePermissionChip();
        if (authenticated) {
            maybeResumeSharing();
            refresh(false);
        }
    }

    @Override protected void onPause() {
        if (nativeMap != null) nativeMap.pause();
        resumed = false;
        super.onPause();
    }

    @Override protected void onStop() {
        stopScreenPulse();
        visible = false;
        main.removeCallbacks(poll);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (sosFixRequest != null) sosFixRequest.cancel();
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        if (mapPrivacyDialog != null) mapPrivacyDialog.dismiss();
        if (permissionDialog != null) permissionDialog.dismiss();
        disposeMap();
        super.onDestroy();
    }

    private void render() {
        generation++;
        disposeMap();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        setContentView(root);
        root.post(() -> {
            if (Build.VERSION.SDK_INT >= 35) {
                var controller = getWindow().getInsetsController();
                if (controller != null) {
                    int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(lightBars, lightBars);
                }
            }
        });

        mapFrame = null;
        mapStatus = null;
        mapDock = null;
        permissionsChip = null;
        if (authenticated && (tab == 0 || tab == 1)) {
            mapFrame = new FrameLayout(this);
            root.addView(mapFrame, new LinearLayout.LayoutParams(-1, 0, 1));
            makeMap();
            content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(12), dp(4), dp(12), 0);
            mapFrame.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
            if (tab == 0) locationScreen();
            else routeScreen();
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(18), dp(24), dp(18), dp(20));
            scroll.addView(content);
            headline = label(content, authenticated ? (tab == 2 ? "设置" : "动态") : "同行", 33, INK);
            headline.setTypeface(null, Typeface.BOLD);
            if (!authenticated) {
                authScreen();
                return;
            }
            if (tab == 2) settingsScreen();
            else feedScreen();
        }
        LinearLayout nav = row(root);
        nav.setBackgroundColor(0xfffffdf9);
        nav.setElevation(dp(4));
        nav.setPadding(dp(14), dp(6), dp(14), dp(6));
        String[] names = {"地图", "足迹", "动态", "设置"};
        int[] pages = {0, 1, 3, 2};
        for (int index = 0; index < names.length; index++) {
            int page = pages[index];
            boolean active = page == tab;
            Button item = button(nav, names[index], false, () -> {
                if ((busy && page != 2) || page == tab) return;
                routeHighlight = null;
                sosPoint = null;
                tab = page;
                render();
                refresh(false);
            });
            item.setSelected(active);
            item.setTextColor(active ? ACCENT : MUTED);
            item.setTextSize(12);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(5), 0, dp(4));
            item.setCompoundDrawablePadding(dp(3));
            item.setCompoundDrawables(null, icon(page, active ? ACCENT : MUTED), null, null);
            item.setBackground(ripple(active ? SOFT : Color.TRANSPARENT, 19));
            var tabSize = new LinearLayout.LayoutParams(0, dp(56), 1);
            tabSize.setMargins(dp(3), 0, dp(3), 0);
            item.setLayoutParams(tabSize);
            item.setSingleLine(true);
            item.setMinWidth(0);
        }
        updateOverview(false);
    }

    private void authScreen() {
        label(content, registering ? "创建自托管账号" : "登录到自己的服务器", 16, MUTED);
        gap(content, 18);
        LinearLayout form = card(content);
        boolean register = registering;
        label(form, "服务器地址", 14, INK);
        EditText address = field(form, "服务器域名或 IP 地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(api.savedBase());
        label(form, "例如 share.example.com 或 192.0.2.10:8443，默认使用 HTTPS。", 13, MUTED);
        label(form, "用户名", 14, INK);
        EditText username = field(form, "用户名", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        if (register) label(form, "3–32 位，可用英文字母、数字和下划线，字母会转为小写。", 13, MUTED);
        label(form, "密码", 14, INK);
        EditText password = field(form, "密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setAutofillHints(register ? "newPassword" : View.AUTOFILL_HINT_PASSWORD);
        if (register) label(form, "10–64 个字符。使用中文或表情时，总长度最多为 72 字节。", 13, MUTED);
        CheckBox reveal = new CheckBox(this);
        reveal.setText("显示密码");
        reveal.setOnCheckedChangeListener((view, checked) -> {
            password.setTransformationMethod(checked ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            password.setSelection(password.length());
        });
        form.addView(reveal);
        hint = label(form, "", 14, MUTED);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(form, register ? "注册并登录" : "登录", true, () -> {
            if (busy) return;
            String host = address.getText().toString();
            String user = username.getText().toString().trim();
            String pass = password.getText().toString();
            hint.setText("");
            for (EditText input : new EditText[]{address, username, password}) input.setError(null);
            String addressError = null;
            try {
                Api.validateBase(host);
            } catch (java.io.IOException invalid) {
                addressError = invalid.getMessage();
            }
            String userError = user.isEmpty() ? "请输入用户名"
                    : register && !user.matches("[A-Za-z0-9_]{3,32}") ? "请使用 3–32 位英文字母、数字或下划线" : null;
            String passwordError = pass.isEmpty() ? "请输入密码" : null;
            if (register && passwordError == null) {
                if (pass.isBlank() || pass.length() < 10 || pass.length() > 64) passwordError = "请输入 10–64 个字符的密码";
                else if (pass.getBytes(StandardCharsets.UTF_8).length > 72) passwordError = "密码超过 72 字节，请减少多字节字符";
            }
            if (addressError != null || userError != null || passwordError != null) {
                if (addressError != null) address.setError(addressError);
                if (userError != null) username.setError(userError);
                if (passwordError != null) password.setError(passwordError);
                (addressError != null ? address : userError != null ? username : password).requestFocus();
                return;
            }
            run(() -> {
                api.authenticate(host, user, pass, register);
                return null;
            }, ignored -> {
                authenticated = true;
                api.migrateLegacyPause();
                CompanionNotifications.schedule(this);
                tab = 0;
                render();
                main.removeCallbacks(poll);
                poll.run();
            });
        });
        button(content, register ? "返回登录" : "创建账号", false, () -> {
            if (busy) return;
            registering = !register;
            render();
        });
    }

    private void locationScreen() {
        LinearLayout toolbar = row(content);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        permissionsChip = button(toolbar, "权限", false, this::showPermissions);
        permissionsChip.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(46)));
        permissionsChip.setBackground(ripple(0xfffcfefa, 22));
        permissionsChip.setElevation(dp(3));
        fitButton(permissionsChip, 13);
        updatePermissionChip();
        if (sosPoint != null) {
            LinearLayout emergency = card(content);
            emergency.setBackground(round(0xffffeced, 20));
            label(emergency, "SOS 求助位置", 18, 0xffa3264a).setTypeface(null, Typeface.BOLD);
            label(emergency, sosDetail, 13, INK);
            Button back = button(emergency, "返回实时地图", false, () -> {
                sosPoint = null;
                render();
                refresh(false);
            });
            fitButton(back, 14);
        }

        mapDock = new LinearLayout(this);
        mapDock.setOrientation(LinearLayout.VERTICAL);
        mapDock.setPadding(dp(12), 0, dp(12), dp(10));
        mapFrame.addView(mapDock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (sosPoint != null) mapDock.setVisibility(View.GONE);
        LinearLayout tools = row(mapDock);
        tools.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        floatingButton(tools, "看双方", 5, () -> {
            partnerFocus = false;
            if (overview != null) updateOverview(true);
            refresh(false);
        });

        LinearLayout sheet = card(mapDock);
        sheet.setElevation(dp(5));
        sheet.setBackground(round(0xfffffdf9, 25));
        LinearLayout overviewRow = row(sheet);
        overviewRow.setGravity(Gravity.CENTER_VERTICAL);
        partnerAvatar = label(overviewRow, "伴", 19, ACCENT);
        partnerAvatar.setGravity(Gravity.CENTER);
        partnerAvatar.setTypeface(null, Typeface.BOLD);
        var avatarBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xffe9f6ea, 0xffc5e3d7});
        avatarBackground.setCornerRadius(dp(18));
        partnerAvatar.setBackground(avatarBackground);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 390;
        int avatarEdge = narrow ? 38 : 46;
        var avatarSize = new LinearLayout.LayoutParams(dp(avatarEdge), dp(avatarEdge));
        avatarSize.rightMargin = dp(narrow ? 8 : 12);
        partnerAvatar.setLayoutParams(avatarSize);
        partnerAvatar.setOnClickListener(view -> focusPartner());
        partnerAvatar.setContentDescription("定位到伴侣");
        var identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        overviewRow.addView(identity);
        partnerLine = label(identity, "伴侣", 19, INK);
        partnerLine.setMaxLines(1);
        partnerLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        partnerSyncLine = label(identity, "", 12, MUTED);
        partnerSyncLine.setPadding(0, 0, dp(2), 0);
        partnerScreenLine = label(identity, "", 13, ACCENT);
        partnerScreenLine.setVisibility(View.GONE);
        partnerScreenLine.setCompoundDrawables(icon(6, ACCENT), null, null, null);
        partnerScreenLine.setCompoundDrawablePadding(dp(4));
        partnerLine.setTypeface(null, Typeface.BOLD);
        partnerLine.setOnClickListener(view -> focusPartner());
        partnerSyncLine.setOnClickListener(view -> focusPartner());

        Button expand = button(overviewRow, collapsed ? "展开" : "收起", false, () -> {});
        expand.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(44)));
        fitButton(expand, 13);
        expand.setBackground(ripple(LINEN, 15));
        expand.setTextColor(MUTED);
        mapStatus = new LinearLayout(this);
        mapStatus.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(mapStatus);
        mapStatus.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        expand.setOnClickListener(view -> {
            collapsed = !collapsed;
            mapStatus.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            expand.setText(collapsed ? "展开" : "收起");
        });
        ScrollView detailScroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = dp(Math.max(64, Math.min(164, getResources().getConfiguration().screenHeightDp / 4)));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        detailScroll.setFillViewport(false);
        mapStatus.addView(detailScroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        detailScroll.addView(details);
        partnerDeviceLine = label(details, "", 13, MUTED);
        partnerDeviceLine.setVisibility(View.GONE);
        partnerDeviceLine.setBackground(round(0xfff0f6f1, 14));
        partnerDeviceLine.setPadding(dp(10), dp(7), dp(10), dp(7));
        var detailSize = (LinearLayout.LayoutParams) partnerDeviceLine.getLayoutParams();
        detailSize.topMargin = dp(8);

        hint = label(details, "", 13, MUTED);
        LinearLayout actions = row(mapStatus);
        Button usage = button(actions, "应用时长", false, this::showPartnerUsage);
        usage.setCompoundDrawables(icon(6, ACCENT), null, null, null);
        usage.setCompoundDrawablePadding(dp(4));
        fitButton(usage, 14);
        Button sos = button(actions, "SOS", false, this::showEmergency);
        sos.setLayoutParams(new LinearLayout.LayoutParams(dp(77), dp(48)));
        fitButton(sos, 14);
        sos.setTextColor(0xff962e48);
        sos.setBackground(ripple(0xfff9e7eb, 16));
        mapDock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (mapDock == v && b - t != ob - ot && overview != null) updateOverview(false);
        });
    }

    private void floatingButton(LinearLayout tools, String title, int symbol, Runnable action) {
        Button button = button(tools, title, false, action);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(46));
        size.leftMargin = dp(8);
        button.setLayoutParams(size);
        button.setBackground(ripple(Color.WHITE, 21));
        button.setElevation(dp(3));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setCompoundDrawables(icon(symbol, ACCENT), null, null, null);
        button.setCompoundDrawablePadding(dp(3));
        fitButton(button, 13);
    }

    private void fitButton(TextView view, int max) {
        view.setSingleLine(true);
        view.setMinWidth(0);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setTextSize(max);
        if (view.getLayoutParams().width != -2) {
            view.setAutoSizeTextTypeUniformWithConfiguration(10, max, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }
    }

    private void routeScreen() {
        LinearLayout panel = card(content);
        panel.setElevation(dp(3));
        panel.setBackground(round(0xfffffdf9, 22));
        LinearLayout controls = row(panel);
        button(controls, "我的", !partnerRoute, () -> {
            if (!busy && partnerRoute) changeRoute(false, daysBack);
        });
        button(controls, "伴侣", partnerRoute, () -> {
            if (!busy && !partnerRoute) changeRoute(true, daysBack);
        });
        var selectedDay = java.time.LocalDate.now().minusDays(daysBack);
        String date = selectedDay.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 · E", Locale.CHINA));
        LinearLayout dates = row(panel);
        Button earlier = button(dates, "前一天", false, () -> {
            if (!busy && daysBack < 30) changeRoute(partnerRoute, daysBack + 1);
        });
        Button dateButton = button(dates, daysBack == 0 ? "今天" : date, false, () -> pickRouteDay(selectedDay));
        dateButton.setContentDescription("选择足迹日期，当前为" + date);
        Button later = button(dates, "后一天", false, () -> {
            if (!busy && daysBack > 0) changeRoute(partnerRoute, daysBack - 1);
        });
        earlier.setEnabled(daysBack < 30);
        later.setEnabled(daysBack > 0);
        for (Button day : new Button[]{earlier, dateButton, later}) {
            fitButton(day, 14);
            day.setBackground(ripple(day == dateButton ? LINEN : 0xfff4f6f3, 13));
            day.setTextColor(day.isEnabled() ? INK : 0xff68776f);
        }
        mapDock = new LinearLayout(this);
        mapDock.setOrientation(LinearLayout.VERTICAL);
        mapDock.setPadding(dp(12), 0, dp(12), dp(10));
        mapFrame.addView(mapDock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        LinearLayout summary = card(mapDock);
        summary.setElevation(dp(4));
        hint = label(summary, "正在读取轨迹", 14, INK);
        displayedRoute = null;
        var routeActions = row(summary);
        timelineButton = button(routeActions, "查看时间轴", false, this::showRouteTimeline);
        var refreshRoute = button(routeActions, "刷新", false, () -> refresh(true));
        refreshRoute.setContentDescription("刷新足迹");
        timelineButton.setCompoundDrawables(icon(7, ACCENT), null, null, null);
        timelineButton.setCompoundDrawablePadding(dp(7));
        timelineButton.setEnabled(false);
    }

    private void pickRouteDay(java.time.LocalDate selectedDay) {
        var today = java.time.LocalDate.now();
        var earliest = today.minusDays(30);
        var zone = java.time.ZoneId.systemDefault();
        var picker = new android.app.DatePickerDialog(this, (view, y, m, d) -> {
            var chosen = java.time.LocalDate.of(y, m + 1, d);
            int offset = (int) java.time.temporal.ChronoUnit.DAYS.between(chosen, today);
            if (!busy && offset >= 0 && offset <= 30 && offset != daysBack) changeRoute(partnerRoute, offset);
        }, selectedDay.getYear(), selectedDay.getMonthValue() - 1, selectedDay.getDayOfMonth());
        picker.getDatePicker().setMinDate(earliest.atStartOfDay(zone).toInstant().toEpochMilli());
        picker.getDatePicker().setMaxDate(today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1);
        picker.show();
    }

    private void changeRoute(boolean partner, int days) {
        routeHighlight = null;
        partnerRoute = partner;
        daysBack = days;
        render();
        refresh(false);
    }

    private void settingsScreen() {
        LinearLayout profile = card(content);
        LinearLayout identity = row(profile);
        identity.setPadding(0, dp(4), 0, dp(4));
        profileAvatar = label(identity, initials(api.savedUsername()), 23, ACCENT);
        profileAvatar.setGravity(Gravity.CENTER);
        profileAvatar.setTypeface(null, Typeface.BOLD);
        profileAvatar.setBackground(round(SOFT, 18));
        var avatar = new LinearLayout.LayoutParams(dp(56), dp(56));
        avatar.rightMargin = dp(14);
        profileAvatar.setLayoutParams(avatar);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        identity.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        displayNameLine = label(names, api.savedUsername(), 22, INK);
        displayNameLine.setTypeface(null, Typeface.BOLD);
        displayNameLine.setMaxLines(2);
        displayNameLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label(names, "登录名 · " + api.savedUsername(), 13, MUTED);
        settingsAction(profile, "编辑显示名称", false, this::editDisplayName);
        LinearLayout binding = card(content);
        label(binding, "伴侣", 18, INK).setTypeface(null, Typeface.BOLD);
        partnerLine = label(binding, "正在读取绑定状态", 14, MUTED);
        scanButton = button(binding, "扫一扫绑定", true, this::scanPairCode);
        scanButton.setCompoundDrawables(icon(10, Color.WHITE), null, null, null);
        scanButton.setCompoundDrawablePadding(dp(9));
        scanButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        scanButton.setPadding(dp(16), 0, dp(16), 0);
        scanButton.getLayoutParams().height = dp(52);
        LinearLayout bindingActions = row(binding);
        inviteButton = button(bindingActions, "出示二维码", false, this::invite);
        pairButton = button(bindingActions, "输入邀请码", false, this::pair);
        unpairButton = settingsAction(binding, "解除绑定", true, () ->
                confirm("解除绑定", "双方将立即停止互相访问，本次绑定的轨迹和动态将被清除。",
                        () -> destructive("/pair")));
        inviteButton.setVisibility(View.GONE);
        pairButton.setVisibility(View.GONE);
        scanButton.setVisibility(View.GONE);
        unpairButton.setVisibility(View.GONE);
        LinearLayout privacy = card(content);
        label(privacy, "使用设置", 18, INK).setTypeface(null, Typeface.BOLD);
        settingsAction(privacy, "权限与共享", false, this::showPermissions);
        if (amapConfigured()) {
            boolean amap = api.amapMapAllowed();
            settingsAction(privacy, amap ? "停用高德地图" : "启用高德地图", false, () -> {
                if (amap) setAmapAllowed(false);
                else showAmapConsent();
            });
        }
        settingsAction(privacy, "紧急联系人", false, this::editEmergencyContact);
        settingsAction(privacy, "常去地点与到达提醒", false, this::showSavedPlaces);
        LinearLayout account = card(content);
        label(account, "账号与数据", 18, INK).setTypeface(null, Typeface.BOLD);
        settingsAction(account, "修改密码", false, this::changePassword);
        settingsAction(account, "退出登录", false, this::logout);
        settingsAction(account, "注销账号", true, this::deleteAccount);
        hint = label(content, "", 14, MUTED);
    }

    private void logout() {
        Api previous = api.beginLogout();
        var app = getApplicationContext();
        signedOut("已退出登录");
        int signedOutGeneration = accountGeneration;
        execute(() -> {
            if (previous.hasSession()) {
                CompanionPush.unregister(app, previous);
                previous.closeRemoteSession();
                api.confirmRemoteLogout(previous);
            }
            return null;
        }, ignored -> {}, error -> {
            if (!authenticated && !registering && signedOutGeneration == accountGeneration) {
                hint.setText("已退出登录。服务器暂停共享未确认，下次登录此账号时会重试。");
            }
        });
    }

    private String amapAndroidKey() {
        JSONObject config = overview == null ? null : overview.optJSONObject("mapConfig");
        String key = config == null ? "" : config.optString("amapAndroidKey", "");
        return key.matches("[0-9a-fA-F]{32}") ? key : "";
    }

    private void showAmapPrivacy() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/")));
        } catch (android.content.ActivityNotFoundException ignored) {
            android.widget.Toast.makeText(this, "请在浏览器打开高德开放平台隐私权政策", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private boolean amapConfigured() {
        return AmapMap.available() && !amapAndroidKey().isEmpty();
    }

    private void showAmapConsent() {
        if (mapPrivacyDialog != null || !amapConfigured()) return;
        mapPrivacyDialog = new AlertDialog.Builder(this).setTitle("高德地图隐私确认")
                .setMessage("高德 SDK 用于显示地图，会按其隐私政策处理设备、网络和位置相关信息。"
                        + "同意后自动使用高德底图，可在设置中随时停用。此选择与向伴侣共享位置独立。")
                .setNeutralButton("高德隐私政策", null)
                .setPositiveButton("同意", (dialog, which) -> setAmapAllowed(true))
                .setNegativeButton("使用默认地图", (dialog, which) -> setAmapAllowed(false))
                .create();
        mapPrivacyDialog.setOnDismissListener(dialog -> mapPrivacyDialog = null);
        mapPrivacyDialog.show();
        mapPrivacyDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> showAmapPrivacy());
    }

    private void setAmapAllowed(boolean allowed) {
        api.allowAmapMap(allowed);
        if (mapFrame == null) {
            render();
            return;
        }
        JSONObject previousMapData = mapData;
        disposeMap();
        makeMap();
        mapData = previousMapData;
        sendMapData();
    }

    private record PermissionRow(String label, boolean allowed, Runnable open) {}

    private boolean allowed(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private Intent applicationSettings() {
        return new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException denied) {
            startActivity(applicationSettings());
        }
    }

    private java.util.List<PermissionRow> systemPermissions() {
        var rows = new java.util.ArrayList<PermissionRow>();
        rows.add(new PermissionRow("系统定位服务", systemLocationEnabled(),
                () -> openSettings(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))));
        rows.add(new PermissionRow("精确定位", allowed(Manifest.permission.ACCESS_FINE_LOCATION), () -> {
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    || !getPreferences(MODE_PRIVATE).getBoolean("location-requested", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("location-requested", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 11);
            } else openSettings(applicationSettings());
        }));
        if (Build.VERSION.SDK_INT >= 29) {
            rows.add(new PermissionRow("后台定位 · 始终允许", allowed(Manifest.permission.ACCESS_BACKGROUND_LOCATION), () -> {
                if (!hasLocationPermission()) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 11);
                    return;
                }
                if (Build.VERSION.SDK_INT == 29)
                    requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 13);
                else openSettings(applicationSettings());
            }));
        }
        var notifications = getSystemService(android.app.NotificationManager.class);
        rows.add(new PermissionRow("通知", notifications.areNotificationsEnabled(), () -> {
            if (Build.VERSION.SDK_INT >= 33 && !allowed(Manifest.permission.POST_NOTIFICATIONS)
                    && (!getPreferences(MODE_PRIVATE).getBoolean("notification-requested", false)
                        || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notification-requested", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12);
            } else {
                openSettings(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        }));
        if (Build.VERSION.SDK_INT >= 28) {
            rows.add(new PermissionRow("使用情况访问", ScreenTime.allowed(this), () ->
                    openSettings(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))));
        }
        var power = getSystemService(android.os.PowerManager.class);
        rows.add(new PermissionRow("电池优化 · 不限制", power.isIgnoringBatteryOptimizations(getPackageName()),
                () -> openSettings(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())))));
        if (motionHardwareAvailable() && Build.VERSION.SDK_INT >= 29) {
            rows.add(new PermissionRow("身体活动 · 步数辅助定位", allowed(Manifest.permission.ACTIVITY_RECOGNITION), () -> {
                if (shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)
                        || !getPreferences(MODE_PRIVATE).getBoolean("motion-requested", false)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("motion-requested", true).apply();
                    requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 24);
                } else openSettings(applicationSettings());
            }));
        }
        return rows;
    }

    private boolean motionHardwareAvailable() {
        return getSystemService(android.hardware.SensorManager.class)
                .getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR) != null;
    }

    private void updatePermissionChip() {
        if (permissionsChip != null) {
            long missing = systemPermissions().stream().filter(row -> !row.allowed()).count();
            permissionsChip.setVisibility(missing == 0 ? View.GONE : View.VISIBLE);
            permissionsChip.setText("待设置 " + missing + " 项");
        }
        if (permissionDialog != null && permissionDialog.isShowing()) renderPermissionCenter();
    }

    private void showPermissions() {
        permissionPromptShown = true;
        if (permissionDialog != null && permissionDialog.isShowing()) {
            renderPermissionCenter();
            return;
        }
        permissionRows = new LinearLayout(this);
        permissionRows.setOrientation(LinearLayout.VERTICAL);
        permissionRows.setPadding(dp(18), dp(9), dp(18), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(permissionRows);
        permissionDialog = new AlertDialog.Builder(this).setTitle("权限与共享")
                .setView(scroll).setNegativeButton("完成", null).create();
        permissionDialog.setOnDismissListener(dialog -> {
            permissionDialog = null;
            permissionRows = null;
        });
        renderPermissionCenter();
        permissionDialog.show();
    }

    private void renderPermissionCenter() {
        permissionRows.removeAllViews();
        String pair = currentPair();
        JSONObject self = overview == null ? null : overview.optJSONObject("self");
        var items = systemPermissions();
        label(permissionRows, pair.isEmpty() ? "绑定后会按系统权限自动向伴侣共享。"
                : "已绑定。手机权限就绪后自动共享。", 15, INK);
        PermissionRow next = items.stream().filter(row -> !row.allowed()).findFirst().orElse(null);
        if (next != null) button(permissionRows, "继续开通 · " + next.label(), true, next.open());
        label(permissionRows, "系统权限", 16, INK).setTypeface(null, Typeface.BOLD);
        for (PermissionRow item : items) {
            if (item.allowed()) label(permissionRows, item.label() + "  ·  已允许", 14, ACCENT);
            else settingsAction(permissionRows, item.label() + "  ·  去设置", false, item.open());
        }
        if (!motionHardwareAvailable()) {
            label(permissionRows, "步数辅助定位 · 本机不支持计步，仍会按位置调频", 14, MUTED);
        } else if (Build.VERSION.SDK_INT < 29) {
            label(permissionRows, "步数辅助定位 · 可用", 14, ACCENT);
        }
        settingsAction(permissionRows, "系统消息横幅设置", false, () -> openSettings(
                CompanionNotifications.channelSettings(this)));
        label(permissionRows, "同行功能", 16, INK).setTypeface(null, Typeface.BOLD);
        String pending = pair.isEmpty() ? "待绑定" : api.sharingNeedsReview() ? "已暂停共享"
                : !hasLocationPermission() || !systemLocationEnabled() ? "待定位权限"
                : "正在同步设置";
        label(permissionRows, "位置与足迹  ·  " + (serverSharing() && hasLocationPermission() && systemLocationEnabled() ? "已启用" : pending), 14, MUTED);
        label(permissionRows, "自动到离报备  ·  " + featureStatus(self, "journeyEnabled", pending), 14, MUTED);
        label(permissionRows, "伴侣屏幕状态  ·  " + featureStatus(self, "liveScreenEnabled", pending), 14, MUTED);
        label(permissionRows, "应用使用明细  ·  " + (!ScreenTime.allowed(this) ? "待使用情况访问"
                : featureStatus(self, "appUsageEnabled", pending)), 14, MUTED);
        if (!pair.isEmpty()) {
            settingsAction(permissionRows, api.sharingNeedsReview() ? "恢复共享" : "暂停共享",
                    !api.sharingNeedsReview(), this::toggleSharing);
        }
        label(permissionRows, "iQOO / vivo 如仍限制后台运行，在电池或 i 管家允许同行后台耗电和自启动。", 13, MUTED);
    }

    private String featureStatus(JSONObject self, String feature, String pending) {
        return self != null && self.optBoolean(feature) && serverSharing()
                && hasLocationPermission() && systemLocationEnabled() ? "已启用" : pending;
    }

    private void showPartnerUsage() {
        run(() -> {
            try { return api.getObject("/app-usage"); }
            catch (Api.Failure error) { if (error.status == 404) return null; throw error; }
        }, report -> {
            if (report == null) {
                usageDialog("伴侣的应用明细", "", new JSONArray(),
                        "请确认双方已绑定，对方已确认共享并在系统中允许使用情况访问。");
                return;
            }
            JSONArray rows = report.optJSONArray("apps");
            long updated = report.optLong("recordedAt");
            String time = updated == 0 ? "" : new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                    .format(new java.util.Date(updated));
            usageDialog("伴侣的应用明细", (report.isNull("date") ? "" : report.optString("date")) + (time.isEmpty() ? "" : " · 更新于 " + time),
                    rows == null ? new JSONArray() : rows, updated == 0 ? "等待对方首次上报" : null);
        });
    }

    private void usageDialog(String title, String subtitle, JSONArray apps, String empty) {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(20), dp(8), dp(20), dp(12));
        if (!subtitle.trim().isEmpty()) label(rows, subtitle, 13, MUTED);
        if (apps.length() == 0) label(rows, empty == null ? "这一天暂无应用使用记录" : empty, 15, MUTED);
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            LinearLayout line = row(rows);
            TextView name = label(line, app.optString("name"), 15, INK);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            TextView minutes = label(line, duration(app.optLong("seconds")), 14, MUTED);
            minutes.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            minutes.setPadding(dp(8), dp(11), 0, dp(11));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setPositiveButton("关闭", null);
        dialog.show();
    }

    private static String duration(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟";
        return minutes / 60 + " 小时" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分钟");
    }

    private Button settingsAction(LinearLayout card, String name, boolean destructive, Runnable action) {
        Button button = button(card, name, false, action);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextColor(destructive ? 0xff962e48 : INK);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(ripple(Color.WHITE, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        button.setLayoutParams(params);
        return button;
    }

    private void feedScreen() {
        LinearLayout compose = card(content);
        label(compose, "给伴侣留言", 17, INK).setTypeface(null, Typeface.BOLD);
        messageInput = field(compose, "给伴侣发条消息", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        messageInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});
        LinearLayout actions = row(compose);
        button(actions, "发送消息", true, () -> {
            String message = messageInput.getText().toString().trim();
            if (!message.isEmpty()) sendInteraction("/messages", Api.json("text", message));
        });
        button(actions, "快捷报备", false, this::showCheckIns);
        hint = label(compose, "", 14, MUTED);
        gap(content, 16);
        label(content, "最近动态", 18, INK).setTypeface(null, Typeface.BOLD);
        feedRows = new LinearLayout(this);
        feedRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(feedRows);
        drawFeed();
    }

    private void showCheckIns() {
        String[] labels = {"早上好", "晚安", "我到了", "我出发了"};
        String[] kinds = {"GOOD_MORNING", "GOOD_NIGHT", "ARRIVED", "HEADING_OUT"};
        new AlertDialog.Builder(this).setTitle("发送快捷报备")
                .setItems(labels, (dialog, index) -> sendInteraction("/check-ins", Api.json("kind", kinds[index])))
                .setNegativeButton("取消", null).show();
    }

    private void drawFeed() {
        feedRows.removeAllViews();
        JSONArray events = feedCache == null ? null : feedCache.optJSONArray("events");
        if (events == null || events.length() == 0) {
            label(feedRows, feedCache == null ? "正在读取动态" : feedCache.isNull("pairId")
                    ? "绑定伴侣后即可发送消息" : "还没有动态", 15, MUTED);
            return;
        }
        appendEvents(events);
    }

    private void appendEvents(JSONArray events) {
        SimpleDateFormat format = new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA);
        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.optJSONObject(index);
            if (event == null) continue;
            LinearLayout item = card(feedRows);
            String kind = event.optString("kind");
            boolean incoming = event.optBoolean("incoming");
            boolean urgent = "SOS".equals(kind);
            if ("MESSAGE".equals(kind) && !incoming) item.setBackground(round(SOFT, 22));
            if (urgent) item.setBackground(round(0xfff9e7eb, 22));
            LinearLayout header = row(item);
            String sender = incoming ? event.optString("sender") : "我";
            TextView name = label(header, sender + (urgent ? " · SOS 求助" : ""), 15, urgent ? 0xffa12636 : INK);
            name.setTypeface(null, Typeface.BOLD);
            int symbol = switch (kind) {
                case "MESSAGE" -> 3;
                case "ARRIVE", "LEAVE", "AUTO_ARRIVE", "AUTO_LEAVE", "PLACE_ARRIVAL" -> 0;
                case "SOS" -> 5;
                default -> 7;
            };
            name.setCompoundDrawables(icon(symbol, urgent ? 0xffa12636 : ACCENT), null, null, null);
            name.setCompoundDrawablePadding(dp(7));
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            TextView time = label(header, format.format(new java.util.Date(event.optLong("createdAt"))), 12, MUTED);
            time.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            label(item, CompanionNotifications.describe(event), 16, INK);
            if (urgent) {
                long id = event.optLong("id");
                if (!incoming) {
                    long viewed = event.optLong("viewedAt");
                    long received = event.optLong("receivedAt");
                    label(item, viewed > 0 ? "对方已查看 · " + clockTime(viewed)
                            : received > 0 ? "对方设备已收到 · " + clockTime(received)
                            : "服务器已收到 · 等待对方设备确认", 13, MUTED);
                }
                if (event.optBoolean("hasLocation")) {
                    button(item, "查看求助位置", false, () -> showSosLocation(id, event.optLong("createdAt"), incoming));
                } else if (incoming) {
                    button(item, "查看求助", false, () -> run(() -> {
                        reportSosViewed(id);
                        return null;
                    }, ignored -> new AlertDialog.Builder(this).setTitle("SOS 求助")
                            .setMessage(CompanionNotifications.describe(event) + "\n发出 " + clockTime(event.optLong("createdAt")))
                            .setPositiveButton("关闭", (dialog, which) -> refresh(false)).show()));
                }
            }
        }
        if (events.length() == 80) {
            JSONObject oldest = events.optJSONObject(events.length() - 1);
            Button more = button(feedRows, "更早的动态", false, () -> {});
            more.setOnClickListener(v -> run(() -> api.getObject("/feed?before=" + oldest.optLong("id")), older -> {
                feedRows.removeView(more);
                if (!older.optString("pairId").equals(feedCache.optString("pairId"))) { refresh(false); return; }
                JSONArray previous = older.optJSONArray("events");
                if (previous != null) appendEvents(previous);
            }));
        }
    }

    private void showSosLocation(long eventId, long eventAt, boolean incoming) {
        String pair = currentPair();
        run(() -> {
            JSONObject location = api.getObject("/sos/" + eventId);
            if (incoming) reportSosViewed(eventId);
            return location;
        }, location -> {
            double latitude = location.optDouble("latitude");
            double longitude = location.optDouble("longitude");
            if (!pair.equals(currentPair()) || !Double.isFinite(latitude) || !Double.isFinite(longitude)) return;
            sosPair = pair;
            sosIncoming = incoming;
            sosEventAt = eventAt;
            sosPoint = drawPoint(location, "SOS 求助位置", true, false);
            sosDetail = "发出 " + clockTime(eventAt) + "  ·  定位 " + clockTime(location.optLong("recordedAt"))
                    + "\n精度约 " + Math.round(location.optDouble("accuracy")) + " 米";
            tab = 0;
            render();
        });
    }

    private void reportSosViewed(long eventId) throws Exception {
        try {
            api.mutate("PUT", "/sos/" + eventId + "/receipt", Api.json("viewed", true));
        } catch (Api.Failure error) {
            if (error.status != 404) throw error;
        }
    }

    private boolean sosStillVisible() {
        JSONObject sender = overview.optJSONObject(sosIncoming ? "partner" : "self");
        return currentPair().equals(sosPair) && sender != null && sender.optBoolean("sharing")
                && overview.optLong("serverTime") - sosEventAt < 86_400_000;
    }

    private void sendInteraction(String path, JSONObject data) {
        run(() -> api.mutate("POST", path, data), response -> {
            if (tab == 3 && "/messages".equals(path)) messageInput.setText("");
            android.widget.Toast.makeText(this, "已发送", android.widget.Toast.LENGTH_SHORT).show();
            refresh(false);
        });
    }

    private void stopScreenPulse() {
        if (screenPulse != null) screenPulse.cancel();
        screenPulse = null;
        if (partnerScreenLine != null) partnerScreenLine.setAlpha(1f);
    }

    private void displayLiveScreen(JSONObject partner, long now) {
        JSONObject sample = partner == null ? null : partner.optJSONObject("liveScreen");
        long reportedAt = sample == null ? 0 : sample.optLong("updatedAt");
        boolean recent = reportedAt > 0 && now - reportedAt <= 120_000;
        String state = sample == null ? "" : sample.optString("state");
        String caption = switch (state) {
            case "UNLOCKED" -> "◉ 亮屏 · 已解锁";
            case "LOCKED" -> "亮屏 · 锁定";
            case "OFF" -> "屏幕已熄灭";
            default -> partner != null && partner.optBoolean("sharing") && partner.optBoolean("liveScreenEnabled")
                    ? "屏幕状态暂未更新" : "";
        };
        if (!recent && !state.isEmpty()) {
            caption = "上次屏幕状态 · " + switch (state) {
                case "UNLOCKED" -> "已解锁";
                case "LOCKED" -> "锁定";
                case "OFF" -> "熄屏";
                default -> "未知";
            } + " · " + clockTime(reportedAt);
        }
        partnerScreenLine.setText(caption);
        partnerScreenLine.setVisibility(caption.isEmpty() ? View.GONE : View.VISIBLE);
        if (recent && "UNLOCKED".equals(state) && resumed) {
            if (screenPulse == null) {
                screenPulse = android.animation.ObjectAnimator.ofFloat(partnerScreenLine, View.ALPHA, 1f, .62f, 1f);
                screenPulse.setDuration(1900);
                screenPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                screenPulse.start();
            }
        } else stopScreenPulse();
    }

    private static String clockTime(long at) {
        boolean today = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                .toLocalDate().equals(java.time.LocalDate.now());
        return new SimpleDateFormat(today ? "HH:mm" : "MM月dd日 HH:mm", Locale.CHINA).format(new java.util.Date(at));
    }

    private static String appendDetail(String lines, String next) {
        return lines.isEmpty() ? next : lines + "\n" + next;
    }

    private void editEmergencyContact() {
        EditText phone = new EditText(this);
        phone.setSingleLine();
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        phone.setText(api.emergencyContact());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("紧急联系人电话")
                .setMessage("号码保存在这台手机上。留空可移除号码。")
                .setView(phone).setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String number = phone.getText().toString().trim().replaceAll("[ ()-]", "");
            if (!number.isEmpty() && !number.matches("[+]?[0-9]{3,18}")) {
                phone.setError("请输入电话号码");
                return;
            }
            api.saveEmergencyContact(number);
            dialog.dismiss();
        });
    }

    private void showEmergency() {
        String phone = api.emergencyContact();
        var attach = new android.widget.CheckBox(this);
        attach.setText("附上共享位置，并尝试获取新定位");
        attach.setChecked(serverSharing() && hasLocationPermission());
        attach.setPadding(dp(20), dp(6), dp(20), dp(6));
        var dialog = new AlertDialog.Builder(this).setTitle("紧急求助")
                .setMessage("向已绑定的伴侣发出求助消息。没有可用定位时，仍会发送求助文字。")
                .setView(attach)
                .setPositiveButton("发送 SOS", (view, which) ->
                        sendSos(java.util.UUID.randomUUID().toString(), attach.isChecked(), currentPair()))
                .setNegativeButton("取消", null);
        if (!phone.isEmpty()) {
            dialog.setNeutralButton("拨号", (view, which) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)));
                } catch (android.content.ActivityNotFoundException error) {
                    android.widget.Toast.makeText(this, "这台设备无法打开拨号", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        dialog.show();
    }

    private void sendSos(String request, boolean attach, String pair) {
        if (busy || !authenticated || !pair.equals(currentPair())) return;
        busy = true;
        String account = api.accountKey();
        execute(() -> {
            synchronized (api) {
                if (!account.equals(api.accountKey())) return new JSONObject();
                return api.mutate("POST", "/sos/requests", Api.json("requestId", request, "includeLocation", attach));
            }
        },
                result -> {
                    busy = false;
                    if (!account.equals(api.accountKey()) || !pair.equals(currentPair())) return;
                    long event = result.optLong("id");
                    android.widget.Toast.makeText(this, "服务器已收到求助", android.widget.Toast.LENGTH_LONG).show();
                    tab = 3;
                    render();
                    refresh(false);
                    if (attach && event > 0) requestSosFix(event, account, pair);
                }, error -> {
                    busy = false;
                    if (!account.equals(api.accountKey()) || !pair.equals(currentPair())) return;
                    if (error instanceof Api.Failure failure && failure.status >= 400 && failure.status < 500) {
                        showError(error);
                        return;
                    }
                    new AlertDialog.Builder(this).setTitle("等待发送确认")
                            .setMessage("暂时无法确认服务器是否收到。重试会使用同一个求助编号。")
                            .setPositiveButton("重试", (dialog, which) -> sendSos(request, attach, pair))
                            .setNegativeButton("关闭", null).show();
                });
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void requestSosFix(long event, String account, String pair) {
        if (Build.VERSION.SDK_INT < 30 || !serverSharing() || !hasLocationPermission()) return;
        var manager = getSystemService(LocationManager.class);
        if (sosFixRequest != null) sosFixRequest.cancel();
        var cancel = new android.os.CancellationSignal();
        sosFixRequest = cancel;
        var timeout = (Runnable) cancel::cancel;
        main.postDelayed(timeout, 8_000);
        try {
            String provider = allowed(Manifest.permission.ACCESS_FINE_LOCATION)
                    && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            if (!manager.isProviderEnabled(provider)) {
                main.removeCallbacks(timeout);
                return;
            }
            manager.getCurrentLocation(provider, cancel, getMainExecutor(), point -> {
                main.removeCallbacks(timeout);
                if (point == null || !point.hasAccuracy() || point.getAccuracy() > 1000 || isDestroyed()
                        || !account.equals(api.accountKey()) || !pair.equals(currentPair())) return;
                execute(() -> {
                    synchronized (api) {
                        if (!account.equals(api.accountKey()) || api.pausePending() || !hasLocationPermission()
                                || api.sharingNeedsReview()) return null;
                        return api.mutate("PUT", "/sos/" + event + "/fix", Api.json("point",
                                Api.json("latitude", point.getLatitude(), "longitude", point.getLongitude()),
                                "accuracy", point.getAccuracy(), "recordedAt", point.getTime()));
                    }
                }, ignored -> refresh(false), ignored -> {});
            });
        } catch (IllegalArgumentException | SecurityException ignored) {
            main.removeCallbacks(timeout);
        }
    }

    private void showSavedPlaces() {
        if (currentPair().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("常去地点").setMessage("请先绑定伴侣。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        run(() -> api.getArray("/saved-places"), places -> {
            knownPlaces = places;
            knownPlacesPair = currentPair();
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(16), dp(10), dp(16), dp(12));
            label(body, "地点半径约 120 米。开启后，伴侣下次确认进入时提醒一次。", 13, MUTED);
            JSONObject self = overview == null ? null : overview.optJSONObject("self");
            JSONObject partner = overview == null ? null : overview.optJSONObject("partner");
            var point = self == null ? null : self.optJSONObject("lastLocation");
            var partnerPoint = partner == null ? null : partner.optJSONObject("lastLocation");
            var scroll = new ScrollView(this);
            scroll.addView(body);
            var dialog = new AlertDialog.Builder(this).setTitle("常去地点与到达提醒").setView(scroll)
                    .setPositiveButton("关闭", null).create();
            if (point != null) button(body, "保存我的最近位置", false, () -> {
                dialog.dismiss();
                namePlace(point, this::showSavedPlaces);
            });
            if (partnerPoint != null) button(body, "保存伴侣的最近位置", false, () -> {
                dialog.dismiss();
                namePlace(partnerPoint, this::showSavedPlaces);
            });
            if (places.length() == 0) label(body, "也可在足迹时间轴里保存一个停留地点。", 13, MUTED);
            for (int i = 0; i < places.length(); i++) {
                var place = places.optJSONObject(i);
                if (place == null) continue;
                String path = "/saved-places/" + place.optString("id");
                boolean watching = place.optBoolean("watching");
                boolean armed = place.optBoolean("armed");
                var section = card(body);
                label(section, place.optString("name"), 16, INK).setTypeface(null, Typeface.BOLD);
                if (watching) label(section, armed ? "等待下次到达" : "等待确认在范围外", 13, MUTED);
                button(section, watching ? "取消提醒" : "下次到达提醒我", false, () -> {
                    dialog.dismiss();
                    run(() -> api.mutate("PUT", path + "/watch", Api.json("enabled", !watching)), ignored -> showSavedPlaces());
                });
                button(section, "删除地点", false, () -> {
                    dialog.dismiss();
                    run(() -> api.mutate("DELETE", path, null), ignored -> showSavedPlaces());
                });
            }
            dialog.show();
        });
    }

    private void namePlace(JSONObject point, Runnable after) {
        if (point == null || currentPair().isEmpty()) return;
        var name = new EditText(this);
        name.setSingleLine();
        name.setHint("例如 家、超市");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        var dialog = new AlertDialog.Builder(this).setTitle("保存常去地点").setView(name)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.setError("请输入地点名称");
                return;
            }
            run(() -> api.mutate("POST", "/saved-places", Api.json("name", value, "point",
                    Api.json("latitude", point.optDouble("latitude"), "longitude", point.optDouble("longitude")))), result -> {
                knownPlaces.put(result);
                knownPlacesPair = currentPair();
                dialog.dismiss();
                after.run();
            });
        });
    }

    private String placeName(JSONObject point, String fallback) {
        if (point == null || !knownPlacesPair.equals(currentPair())) return fallback;
        float[] distance = new float[1];
        float closest = 120;
        String label = fallback;
        for (int i = 0; i < knownPlaces.length(); i++) {
            var place = knownPlaces.optJSONObject(i);
            if (place == null) continue;
            Location.distanceBetween(point.optDouble("latitude"), point.optDouble("longitude"),
                    place.optDouble("latitude"), place.optDouble("longitude"), distance);
            if (distance[0] <= closest) {
                closest = distance[0];
                label = place.optString("name", fallback);
            }
        }
        return label;
    }

    private static String initials(String name) {
        return name.isBlank() ? "我" : name.substring(0, name.offsetByCodePoints(0, 1));
    }

    private void editDisplayName() {
        if (busy) return;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(8), dp(22), dp(8));
        EditText name = field(body, "显示名称", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        JSONObject self = overview == null ? null : overview.optJSONObject("self");
        String previous = self == null ? api.savedUsername() : self.optString("displayName", api.savedUsername());
        name.setText(previous);
        name.setSelection(name.length());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("编辑显示名称")
                .setMessage("伴侣会看到这个名称，登录名保持不变。最多 32 个字符。")
                .setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setOnClickListener(view -> {
            String next = name.getText().toString().strip();
            if (next.isBlank()) {
                name.setError("请输入显示名称");
                return;
            }
            if (previous.equals(next)) {
                dialog.dismiss();
                return;
            }
            save.setEnabled(false);
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            execute(() -> api.mutate("PUT", "/account/profile", Api.json("displayName", next)), result -> {
                dialog.dismiss();
                if (overview != null && overview.optJSONObject("self") != null) {
                    try {
                        overview.optJSONObject("self").put("displayName", result.optString("displayName", next));
                    } catch (org.json.JSONException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                    updateOverview(false);
                }
                refresh(false);
            }, error -> {
                if (error instanceof Api.Failure failure && failure.status == 401) {
                    dialog.dismiss();
                    showError(failure);
                    return;
                }
                save.setEnabled(true);
                dialog.setCancelable(true);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                name.setError(error instanceof Api.Failure ? error.getMessage() : "保存失败，请稍后重试");
            });
        });
    }

    private void changePassword() {
        if (busy) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(12));
        int input = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        EditText current = field(form, "当前密码", input);
        current.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        EditText replacement = field(form, "新密码 · 10–64 个字符", input);
        replacement.setAutofillHints("newPassword");
        EditText confirmation = field(form, "再输入一次新密码", input);
        TextView error = label(form, "", 14, MUTED);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("修改密码")
                .setMessage("保存后，已登录的设备需要用新密码重新登录。")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setOnClickListener(view -> {
            String old = current.getText().toString();
            String next = replacement.getText().toString();
            error.setText("");
            if (old.isBlank()) {
                current.setError("请输入当前密码");
                return;
            }
            if (next.isBlank() || next.length() < 10 || next.length() > 64
                    || next.getBytes(StandardCharsets.UTF_8).length > 72 || old.equals(next)) {
                replacement.setError(old.equals(next) ? "新密码须与当前密码不同"
                        : "新密码须为 10–64 个字符，且不超过 72 字节");
                return;
            }
            if (!next.contentEquals(confirmation.getText())) {
                confirmation.setError("两次输入的新密码不一致");
                return;
            }
            save.setEnabled(false);
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            execute(() -> api.mutate("PUT", "/account/password", Api.json("currentPassword", old, "newPassword", next)), result -> {
                dialog.dismiss();
                api.clearSession();
                signedOut("密码已修改，请用新密码重新登录。");
            }, failure -> {
                save.setEnabled(true);
                dialog.setCancelable(true);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                if (failure instanceof Api.Failure denied && denied.status == 401) {
                    dialog.dismiss();
                    showError(failure);
                } else {
                    error.setText(failure instanceof Api.Failure ? failure.getMessage() : "无法连接服务器，请稍后重试。");
                }
            });
        });
    }

    private void deleteAccount() {
        EditText password = new EditText(this);
        password.setSingleLine();
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("当前密码");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("注销账号")
                .setMessage("输入密码确认。账号、地点和共享记录将删除，并解除当前绑定。")
                .setView(password).setNegativeButton("取消", null).setPositiveButton("永久注销", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> run(() ->
                api.mutate("DELETE", "/account", Api.json("password", password.getText().toString())), result -> {
            CompanionNotifications.cancel(this);
            api.saveEmergencyContact("");
            api.cacheUsage(false);
            api.cacheAppUsage(false);
            api.cacheDeviceStatus(false);
            api.clearSession();
            dialog.dismiss();
            signedOut("账号已注销");
        }));
    }

    private void invite() {
        run(() -> api.mutate("POST", "/invite", null), result -> {
            String code = PairQr.code(result.optString("code"));
            if (code.isEmpty()) {
                hint.setText("服务器未返回有效邀请码，请重试。");
                return;
            }
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setGravity(Gravity.CENTER_HORIZONTAL);
            body.setPadding(dp(20), dp(12), dp(20), 0);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(PairQr.image(code));
            qr.setContentDescription("同行绑定二维码");
            body.addView(qr, new LinearLayout.LayoutParams(dp(188), dp(188)));
            label(body, code, 20, INK).setGravity(Gravity.CENTER);
            String expiry = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                    .format(new java.util.Date(result.optLong("expiresAt")));
            label(body, "请让伴侣在同行中扫码或输入邀请码。仅限同一服务器，" + expiry + " 到期。", 14, MUTED);
            label(body, "绑定后会按手机权限自动共享位置、足迹和设备状态。开放使用情况访问时也会共享使用时长。", 13, MUTED);
            new AlertDialog.Builder(this).setTitle("绑定二维码").setView(body)
                    .setPositiveButton("复制邀请码", (dialog, which) -> getSystemService(ClipboardManager.class)
                            .setPrimaryClip(ClipData.newPlainText("同行邀请码", code)))
                    .setNegativeButton("关闭", null).show();
        });
    }

    private void scanPairCode() {
        if (busy) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            new AlertDialog.Builder(this).setMessage("没有可用的相机，请手动输入邀请码。")
                    .setPositiveButton("手动输入", (dialog, which) -> pair()).show();
            return;
        }
        pairScanner.launch(new ScanOptions().setCaptureActivity(PairScanActivity.class)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setOrientationLocked(false)
                .setBeepEnabled(false));
    }

    private void pair() { pair(""); }

    private void pair(String scannedCode) {
        EditText code = new EditText(this);
        code.setSingleLine();
        code.setHint("输入 16 位邀请码");
        code.setText(scannedCode);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("接受绑定").setView(code)
                .setMessage("请确认你和伴侣登录的是同一台服务器。绑定后，双方会按手机权限自动共享位置、足迹和设备状态。开放使用情况访问时也会共享使用时长。")
                .setPositiveButton("绑定", null).setNegativeButton("取消", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String accepted = PairQr.code(code.getText().toString());
            if (accepted.isEmpty()) {
                code.setError("请输入 16 位邀请码，只含数字 0–9 和字母 A–F");
                return;
            }
            dialog.dismiss();
            run(() -> api.mutate("POST", "/pair", Api.json("code", accepted)), ignored -> refresh(true));
        });
    }

    private void destructive(String path) {
        if (busy) return;
        stopLocalSharing();
        run(() -> {
            api.mutate("DELETE", path, null);
            return null;
        }, ignored -> refresh(true));
    }

    private void stopPositionCollection() {
        stopService(new Intent(this, LocationSharing.class));
        if (getDatabasePath("pending-positions.db").isFile()) {
            try (var queue = new PendingPositions(this)) { queue.clear(); }
        }
    }

    private void stopLocalSharing() {
        nextSharingAttempt = 0;
        stopPositionCollection();
        clearLocationViews();

    }

    private void clearLocationViews() {
        overview = null;
        sosPoint = null;
        routeHighlight = null;
        updateMap(new JSONArray(), false, "cleared", false);
        if (tab == 0 && partnerSyncLine != null) {
            partnerSyncLine.setText("正在读取位置");
            partnerLine.setText("");
            partnerDeviceLine.setVisibility(View.GONE);
        }
    }

    private void toggleSharing() {
        if (busy || currentPair().isEmpty()) return;
        if (api.sharingNeedsReview()) {
            api.resumeAutomaticSharing();
            nextSharingAttempt = 0;
            updatePermissionChip();
            maybeResumeSharing();
            refresh(false);
            return;
        }
        api.withdrawSharing();
        stopLocalSharing();
        run(() -> { api.stopSharing(); return null; }, ignored -> refresh(false));
    }

    private String currentPair() {
        return overview == null || overview.isNull("pairId") ? "" : overview.optString("pairId");
    }

    private boolean hasLocationPermission() {
        return allowed(Manifest.permission.ACCESS_FINE_LOCATION)
                || allowed(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 11 || request == 12 || request == 13 || request == 24) {
            nextSharingAttempt = 0;
            updatePermissionChip();
            maybeResumeSharing();
            refresh(false);
        }
    }

    private boolean serverSharing() {
        JSONObject self = overview == null ? null : overview.optJSONObject("self");
        return self != null && self.optBoolean("sharing");
    }

    private boolean systemLocationEnabled() {
        var manager = getSystemService(android.location.LocationManager.class);
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
    }

    private void maybeResumeSharing() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!resumed || restoring || now < nextSharingAttempt || LocationSharing.running()
                || api.pausePending() || api.sharingNeedsReview() || !hasLocationPermission() || !systemLocationEnabled()) return;
        boolean previouslySharing = serverSharing();
        String pair = currentPair();
        if (pair.isEmpty()) return;
        restoring = true;
        nextSharingAttempt = now + 10_000;
        String account = api.accountKey();
        execute(() -> {
            if (api.sharingNeedsReview() || api.pausePending() || !account.equals(api.accountKey())) return null;
            JSONObject state = previouslySharing ? api.getObject("/sharing")
                    : api.mutate("PUT", "/sharing", Api.json("enabled", true, "pairId", pair));
            return state.getString("shareKey");
        }, key -> {
            restoring = false;
            if (key != null && resumed && authenticated && !busy && account.equals(api.accountKey())
                    && pair.equals(currentPair()) && !api.pausePending() && !api.sharingNeedsReview() && !LocationSharing.running()) {
                startSharingService(key);
                if (!previouslySharing) refresh(false);
            } else if (!resumed) nextSharingAttempt = 0;
        }, error -> {
            restoring = false;
            if (!account.equals(api.accountKey()) || api.pausePending()) return;
            if (error instanceof Api.Failure failure) {
                if (failure.status == 401) { showError(error); return; }
                if (failure.status == 409) refresh(false);
            }
            if (tab == 0 && hint != null) hint.setText("同步暂未启动，正在重试");
        });
    }

    private void checkSetup() {
        if (!resumed || isFinishing() || !authenticated || overview == null) return;
        if (permissionPromptShown || currentPair().isEmpty() || api.sharingNeedsReview()) return;
        if (!hasLocationPermission() || !systemLocationEnabled()) showPermissions();
    }

    private void syncInsightPermissions(JSONObject state, JSONObject self) throws Exception {
        if (!self.optBoolean("sharing") || api.sharingNeedsReview() || api.pausePending() || state.isNull("pairId")) return;
        boolean usage = ScreenTime.allowed(this);
        String pair = state.optString("pairId");
        if (pair.isEmpty()) return;
        syncInsight(pair, "/usage-sharing", usage, api.usageEnabled(), api::cacheUsage);
        syncInsight(pair, "/app-usage-sharing", usage, api.appUsageEnabled(), api::cacheAppUsage);
        syncInsight(pair, "/device-status-sharing", true, api.deviceStatusEnabled(), api::cacheDeviceStatus);
        if (syncInsight(pair, "/journey-sharing", true, self.optBoolean("journeyEnabled"), ignored -> {})) {
            self.put("journeyEnabled", true);
        }
        if (syncInsight(pair, "/live-screen-sharing", true, self.optBoolean("liveScreenEnabled"), api::cacheLiveScreen)) {
            self.put("liveScreenEnabled", true);
        }
    }

    private boolean syncInsight(String pair, String path, boolean allowed, boolean current, Done<Boolean> cache) throws Exception {
        if (allowed == current || api.sharingNeedsReview() || api.pausePending()) return false;
        api.mutate("PUT", path, Api.json("enabled", allowed, "pairId", pair));
        cache.accept(allowed);
        return true;
    }

    private void startSharingService(String key) {
        try {
            startForegroundService(new Intent(this, LocationSharing.class).putExtra(LocationSharing.KEY, key));
            if (tab == 0 && hint != null) hint.setText("正在等候定位");
        } catch (RuntimeException error) {
            if (tab == 0 && hint != null) hint.setText("定位服务暂未启动，请检查系统位置权限");
        }
    }

    private void refresh(boolean recenter) {
        if (busy || refreshing || !authenticated) return;
        int screen = generation;
        int page = tab;
        boolean target = partnerRoute;
        String account = api.accountKey();
        int day = daysBack;
        boolean loadRoute = page == 1 && (day == 0 || displayedRoute == null || recenter);
        String priorPlacesPair = knownPlacesPair;
        refreshing = true;
        execute(() -> {
            if (api.pausePending()) api.stopSharing();
            JSONObject state = api.getObject("/overview");
            JSONObject self = state.optJSONObject("self");
            String pair = state.isNull("pairId") ? "" : state.optString("pairId", "");
            boolean locationUnavailable = !hasLocationPermission() || !systemLocationEnabled();
            if (self != null && self.optBoolean("sharing") && !pair.isEmpty() && !state.isNull("pairId")
                    && (api.sharingNeedsReview() || locationUnavailable)) {
                try {
                    api.mutate("PUT", "/sharing", Api.json("enabled", false, "pairId", pair,
                            "locationUnavailable", locationUnavailable && !api.sharingNeedsReview()));
                    self.put("sharing", false);
                    self.remove("lastLocation");
                } catch (java.io.IOException unavailable) {
                    // The foreground refresh retries against the current binding.
                }
            }
            if (self != null && self.has("usageEnabled") && account.equals(api.accountKey())) {
                api.cacheUsage(self.optBoolean("usageEnabled"));
                api.cacheAppUsage(self.optBoolean("appUsageEnabled"));
                api.cacheDeviceStatus(self.optBoolean("deviceStatusEnabled"));
                api.cacheLiveScreen(self.optBoolean("liveScreenEnabled"));
                try {
                    syncInsightPermissions(state, self);
                } catch (java.io.IOException unavailable) {
                    // Permission sync retries without hiding the downloaded location overview.
                }
            }
            JSONObject feed = null;
            if (page == 3 || CompanionNotifications.enabled(this) || page == 0) {
                CompanionPush.sync(this);
                try {
                    feed = api.getObject("/feed");
                    if (account.equals(api.accountKey())) CompanionNotifications.deliver(this, account, feed);
                } catch (Api.Failure error) {
                    if (page == 3) throw error;
                } catch (java.io.IOException error) {
                    if (page == 3) throw error;
                }
            }
            JSONArray route = null;
            if (loadRoute) {
                Calendar midnight = Calendar.getInstance();
                midnight.add(Calendar.DAY_OF_YEAR, -day);
                midnight.set(Calendar.HOUR_OF_DAY, 0);
                midnight.set(Calendar.MINUTE, 0);
                midnight.set(Calendar.SECOND, 0);
                midnight.set(Calendar.MILLISECOND, 0);
                long start = midnight.getTimeInMillis();
                midnight.add(Calendar.DAY_OF_YEAR, 1);
                route = api.route(target, start, midnight.getTimeInMillis());
            }
            JSONArray places = null;
            if (!pair.isEmpty() && !pair.equals(priorPlacesPair)) {
                try { places = api.getArray("/saved-places"); } catch (Exception ignored) {}
            }
            return new Snapshot(state, route, feed, places);
        }, state -> {
            refreshing = false;
            if (!authenticated) return;
            if (screen != generation || busy) {
                refresh(false);
                return;
            }
            String previousMapKey = amapAndroidKey();
            String previousPair = currentPair();
            overview = state.overview;
            if (!previousPair.equals(currentPair())) {
                partnerFocus = false;
                routeHighlight = null;
                displayedRoute = null;
            }
            if (state.places != null) {
                knownPlaces = state.places;
                knownPlacesPair = currentPair();
            }
            if (sosPoint != null && !sosStillVisible()) {
                sosPoint = null;
                render();
            }
            if (mapFrame != null && api.amapMapAllowed() && AmapMap.available()
                    && !previousMapKey.equals(amapAndroidKey())) {
                disposeMap();
                makeMap();
            }
            LiveWidget.update(this, overview);
            updateOverview(recenter);
            maybeResumeSharing();
            if (mapPrivacyDialog == null) checkSetup();
            updatePermissionChip();
            if (page == 1) {
                var partner = overview.optJSONObject("partner");
                if (!previousPair.equals(currentPair()) && state.route == null) {
                    hint.setOnClickListener(null);
                    updateRoute(new JSONArray(), false);
                    displayedRoute = null;
                } else if (partnerRoute && (partner == null || !partner.optBoolean("sharing"))) {
                    routeHighlight = null;
                    hint.setOnClickListener(null);
                    updateRoute(new JSONArray(), false);
                } else if (state.route != null) {
                    updateRoute(state.route, recenter);
                }
            }
            if (page == 3) {
                if (feedCache == null || !feedCache.toString().equals(state.feed.toString())) {
                    feedCache = state.feed;
                    drawFeed();
                }
            }
        }, error -> {
            refreshing = false;
            if (!authenticated || screen != generation || busy) {
                refresh(false);
                return;
            }
            if (tab == 0 && partnerScreenLine.getVisibility() == View.VISIBLE) {
                stopScreenPulse();
                displayLiveScreen(overview == null ? null : overview.optJSONObject("partner"), System.currentTimeMillis());
            }
            showError(error);
            if (tab == 0 && hint != null && error instanceof java.io.IOException && !(error instanceof Api.Failure)) {
                hint.setText("本机连接服务器失败 · 地图保留上次记录");
                hint.setVisibility(View.VISIBLE);
            }
        });
    }

    private record Snapshot(JSONObject overview, JSONArray route, JSONObject feed, JSONArray places) {}

    private void updateOverview(boolean recenter) {
        if (overview == null) return;
        JSONObject self = overview.optJSONObject("self");
        JSONObject partner = overview.optJSONObject("partner");
        if (tab == 2) {
            partnerLine.setText(partner == null ? "还没有绑定伴侣" : "已绑定 " + partner.optString("displayName"));
            inviteButton.setVisibility(partner == null ? View.VISIBLE : View.GONE);
            pairButton.setVisibility(partner == null ? View.VISIBLE : View.GONE);
            scanButton.setVisibility(partner == null ? View.VISIBLE : View.GONE);
            String displayName = self == null ? api.savedUsername() : self.optString("displayName", api.savedUsername());
            displayNameLine.setText(displayName);
            profileAvatar.setText(initials(displayName));
            unpairButton.setVisibility(partner != null ? View.VISIBLE : View.GONE);
        }
        if (tab != 0 || self == null) return;
        long now = overview.optLong("serverTime");
        if (sosPoint != null) {
            updateMap(new JSONArray(), false, "sos", recenter);
            return;
        }

        String screenTime = "";
        JSONObject device = partner == null ? null : partner.optJSONObject("device");
        if (device != null) {
            String model = device.optString("model", "");
            if (!model.isEmpty()) screenTime = appendDetail(screenTime, model);
            String network = DeviceStatus.networkLabel(device.optString("network"));
            if (!network.isEmpty()) screenTime = appendDetail(screenTime, "最近网络 · " + network);
            long unlock = device.optLong("unlockedAt");
            if (unlock > 0) screenTime = appendDetail(screenTime, "最近解锁 " + clockTime(unlock));
        }
        partnerLine.setText(partner == null ? "伴侣 · 尚未绑定" : partner.optString("displayName"));
        partnerSyncLine.setText(partner == null ? "" : statusLine(partner, now));
        displayLiveScreen(partner, now);
        var journey = partner == null ? null : partner.optJSONObject("journey");
        if (journey != null && now - journey.optLong("updatedAt") <= 240_000) {
            String status = "STOPPED".equals(journey.optString("state"))
                    ? "在当前区域停留约 " + Math.max(1, (now - journey.optLong("since")) / 60_000) + " 分钟"
                    : "行程中";
            screenTime = screenTime.isEmpty() ? status : status + "\n" + screenTime;
        }
        partnerDeviceLine.setText(screenTime);
        partnerDeviceLine.setVisibility(screenTime.isEmpty() ? View.GONE : View.VISIBLE);
        String partnerName = partner == null ? "" : partner.optString("displayName").trim();
        partnerAvatar.setText(partnerName.isEmpty() ? "伴" : initials(partnerName));

        hint.setOnClickListener(null);
        hint.setClickable(false);
        JSONObject first = self.optJSONObject("lastLocation");
        JSONObject second = partner == null ? null : partner.optJSONObject("lastLocation");
        if (partner == null) {
            hint.setText("绑定后会按权限自动同步");
        } else if (api.sharingNeedsReview()) {
            hint.setText("已暂停共享 · 点击恢复");
            hint.setOnClickListener(view -> toggleSharing());
        } else if (api.pausePending()) {
            hint.setText("正在同步暂停状态");
        } else if (!hasLocationPermission() || !systemLocationEnabled()) {
            hint.setText("位置权限未就绪，点右上角「权限」");
            hint.setOnClickListener(view -> showPermissions());
        } else if (!LocationSharing.running()) {
            hint.setText("正在自动连接位置共享");
        } else if (LocationSharing.lastUploadAt() == 0) {
            hint.setText("同步服务运行中，正在等待首次定位上报");
        } else if (!partner.optBoolean("sharing")) {
            hint.setText("伴侣尚未开启或已暂停共享");
        } else if (second == null) {
            hint.setText("正在等待伴侣首次上报");
        } else if (!fresh(second, now)) {
            hint.setText("伴侣暂未上报新定位");
        } else if (!fresh(first, now)) {
            hint.setText("本机暂未取得新定位");
        } else if (first != null) {
            float[] meters = new float[1];
            Location.distanceBetween(first.optDouble("latitude"), first.optDouble("longitude"),
                    second.optDouble("latitude"), second.optDouble("longitude"), meters);
            hint.setText(meters[0] <= 30 && meters[0] <= first.optDouble("accuracy", 0) + second.optDouble("accuracy", 0)
                    ? "两人的定位范围接近" : meters[0] < 1_000 ? "直线距离约 " + Math.round(meters[0]) + " 米"
                    : String.format(Locale.CHINA, "直线距离约 %.1f 公里", meters[0] / 1_000));
        } else {
            hint.setText("");
        }
        hint.setVisibility(hint.length() == 0 ? View.GONE : View.VISIBLE);
        JSONArray points = new JSONArray();
        if (first != null) points.put(drawPoint(first, "我", false, !fresh(first, now)));
        if (second != null) points.put(drawPoint(second, placeName(second, partner.optString("displayName")), true, !fresh(second, now)));
        updateMap(points, false, partnerFocus && second != null ? "partner" : "live", recenter);
    }

    private void focusPartner() {
        if (sosPoint != null) return;
        JSONObject partner = overview == null ? null : overview.optJSONObject("partner");
        if (partner == null || partner.optJSONObject("lastLocation") == null) {
            android.widget.Toast.makeText(this, partner == null ? "请先绑定伴侣" : "对方暂无可显示的位置",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        partnerFocus = true;
        updateOverview(true);
    }

    private void showRouteTimeline() {
        JSONArray fixes = displayedRoute;
        if (fixes == null || fixes.length() < 2) return;
        var summary = RouteTimeline.summarize(displayedParts, fixes);
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), dp(16));
        layout.setBackgroundColor(0xfffffdf9);
        var scroll = new ScrollView(this);
        scroll.addView(layout);
        var dialog = new AlertDialog.Builder(this).setTitle("足迹时间轴").setView(scroll)
                .setPositiveButton("关闭", null).create();
        var day = java.time.LocalDate.now().minusDays(daysBack);
        var dateFormat = java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA);
        label(layout, (partnerRoute ? "伴侣" : "我的") + " · " + day.format(dateFormat), 13, MUTED);
        LinearLayout totals = row(layout);
        if (summary.stopped() > 0) timelineTotal(totals, "停留", summary.stopped(), SOFT, ACCENT);
        if (summary.moving() > 0) timelineTotal(totals, "移动", summary.moving(), LINEN, AMBER);

        var clock = new SimpleDateFormat("HH:mm", Locale.CHINA);
        if (summary.activities().isEmpty()) {
            label(layout, "这段记录暂不足以判断停留或移动。", 14, MUTED);
        }
        for (var part : summary.activities()) {
            boolean stopped = part.kind() == RouteTimeline.Kind.STOPPED;
            String heading = stopped ? placeName(fixes.optJSONObject(part.first()), "停留区域") : "移动";
            if (!stopped && part.meters() >= 50) {
                heading += part.meters() < 1_000 ? " · 约 " + Math.round(part.meters()) + " 米"
                        : String.format(Locale.CHINA, " · 约 %.1f 公里", part.meters() / 1000);
            }
            String detail = clockRange(clock, part) + "  ·  " + (part.observed() < part.duration() ? "有效停留 " : "")
                    + duration(part.observed() / 1000);
            TextView item = timelineRow(layout, heading, detail,
                    stopped ? 0xffedf6ef : LINEN, stopped ? ACCENT : AMBER, stopped ? 0 : 1);
            item.setOnClickListener(view -> {
                dialog.dismiss();
                showSegment(fixes, part, stopped);
            });
        }
        if (!summary.missing().isEmpty()) {
            String detail = summary.missing().size() + " 段 · 共 " + duration(summary.uncovered() / 1000);
            Button toggle = button(layout, "展开记录缺口 · " + detail, false, () -> {});
            toggle.setTextSize(13);
            toggle.setBackground(ripple(LINEN, 14));
            var missing = new LinearLayout(this);
            missing.setOrientation(LinearLayout.VERTICAL);
            missing.setVisibility(View.GONE);
            layout.addView(missing);
            label(missing, "同一地点的停留可合并显示。记录缺口不计入有效停留。", 13, MUTED);
            for (var part : summary.missing()) {
                String reason = part.kind() == RouteTimeline.Kind.GAP ? "期间无有效定位点" : "定位精度或采样不足";
                timelineRow(missing, reason, clockRange(clock, part) + "  ·  " + duration(part.duration() / 1000),
                        0xfff4f5f1, MUTED, 7);
            }
            toggle.setOnClickListener(view -> {
                boolean open = missing.getVisibility() == View.GONE;
                missing.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText((open ? "收起记录缺口 · " : "展开记录缺口 · ") + detail);
            });
        }
        dialog.show();
    }

    private void showSegment(JSONArray fixes, RouteTimeline.Part part, boolean stopped) {
        JSONObject first = fixes.optJSONObject(part.first());
        JSONObject last = fixes.optJSONObject(part.last());
        String label = stopped ? placeName(first, "停留区域") : "移动详情";
        String details = stopped ? "记录范围 " + clockTime(part.from()) + " – " + clockTime(part.to())
                + "\n有效停留 " + duration(part.observed() / 1000)
                : "出发 " + clockTime(part.from()) + " · " + placeName(first, "起点")
                + "\n到达 " + clockTime(part.to()) + " · " + placeName(last, "终点")
                + "\n移动时长 " + duration(part.observed() / 1000) + " · 约 " + Math.round(part.meters()) + " 米";
        var dialog = new AlertDialog.Builder(this).setTitle(label).setMessage(details)
                .setNegativeButton("关闭", null)
                .setPositiveButton("地图高亮", (view, which) -> {
                    JSONArray selected = new JSONArray();
                    int end = stopped ? part.first() : part.last();
                    for (int index = part.first(); index <= end; index++) selected.put(fixes.optJSONObject(index));
                    routeHighlight = Api.json("id", part.kind().name() + part.from() + ":" + part.to(),
                            "label", stopped ? label : "选中移动路段", "points", selected);
                    renderRouteSelection(stopped ? "已选停留区域" : "已选移动路段");
                });
        if (stopped && !currentPair().isEmpty()) {
            dialog.setNeutralButton("保存地点", (view, which) -> namePlace(first, this::showRouteTimeline));
        }
        dialog.show();
    }

    private void renderRouteSelection(String caption) {
        hint.setText(caption + " · 点击清除高亮");
        hint.setOnClickListener(view -> {
            routeHighlight = null;
            hint.setOnClickListener(null);
            if (mapData != null) mapData.remove("highlight");
            sendMapData();
            refresh(false);
        });
        if (mapData != null) {
            try {
                mapData.put("highlight", routeHighlight);
                mapData.put("recenter", false);
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            sendMapData();
        }
    }

    private void timelineTotal(LinearLayout totals, String heading, long elapsed, int fill, int color) {
        var column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(11), dp(4), dp(11), dp(5));
        column.setBackground(round(fill, 14));
        var size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMargins(0, dp(5), dp(5), dp(6));
        totals.addView(column, size);
        label(column, heading, 12, color);
        label(column, duration(elapsed / 1000), 16, INK).setTypeface(null, Typeface.BOLD);
    }

    private TextView timelineRow(LinearLayout layout, String title, String detail, int fill, int color, int symbol) {
        TextView item = label(layout, title + "\n" + detail, 14, INK);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setLineSpacing(dp(3), 1);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setCompoundDrawables(icon(symbol, color), null, null, null);
        item.setCompoundDrawablePadding(dp(9));
        item.setBackground(ripple(fill, 15));
        ((LinearLayout.LayoutParams) item.getLayoutParams()).topMargin = dp(6);
        return item;
    }

    private static String clockRange(SimpleDateFormat clock, RouteTimeline.Part part) {
        return clock.format(new java.util.Date(part.from())) + "–" + clock.format(new java.util.Date(part.to()));
    }

    private void updateRoute(JSONArray raw, boolean recenter) {
        JSONArray route = RouteStabilizer.stabilize(raw);
        displayedRoute = route;
        timelineButton.setEnabled(route.length() > 1);
        JSONArray points = new JSONArray();
        var clock = new SimpleDateFormat("HH:mm", Locale.CHINA);
        var parts = RouteTimeline.build(route);
        displayedParts = parts;
        double distance = parts.stream().mapToDouble(RouteTimeline.Part::meters).sum();
        var activities = RouteTimeline.summarize(parts, route).activities();
        boolean singleStay = activities.size() == 1 && activities.get(0).kind() == RouteTimeline.Kind.STOPPED;
        for (int i = 0; i < route.length(); i++) {
            JSONObject sample = route.optJSONObject(i);
            if (i == 0 || i == route.length() - 1) {
                String caption = i == 0 ? "起点 " : "终点 ";
                points.put(drawPoint(sample, caption + clock.format(new java.util.Date(sample.optLong("recordedAt"))),
                        partnerRoute, false));
            } else {
                points.put(Api.json("latitude", sample.optDouble("latitude"), "longitude", sample.optDouble("longitude"),
                        "recordedAt", sample.optLong("recordedAt")));
            }
        }
        JSONObject partner = overview == null ? null : overview.optJSONObject("partner");
        String summary = "当天共 " + route.length() + " 个有效定位点";
        if (points.length() == 0) {
            summary = partnerRoute && partner == null ? "绑定伴侣后可查看对方的足迹"
                    : partnerRoute && !partner.optBoolean("sharing") ? "伴侣暂未共享位置，足迹目前不可查看"
                    : raw.length() == 0 ? "这一天没有记录到定位点" : "定位精度不足，暂无法绘制足迹";
        } else if (singleStay) {
            summary = "主要为停留 · 未识别到明显移动";
        } else if (distance >= 50) {
            summary += String.format(Locale.CHINA, " · 估算路程 %.1f 公里", distance / 1000);
        }
        if (routeHighlight == null) hint.setText(summary);
        updateMap(points, true, "route:" + partnerRoute + ":" + daysBack, recenter);
    }

    private static String statusLine(JSONObject status, long now) {
        JSONObject point = status.optJSONObject("lastLocation");
        if (!status.optBoolean("sharing")) return status.optBoolean("locationUnavailable")
                ? "定位权限或定位服务未就绪" : "对方暂未共享位置";
        if (point == null) return "等待首次位置更新";
        int battery = point.optInt("battery", -1);
        String energy = battery < 0 ? "" : " · 电量 " + battery + "%" + (point.optBoolean("charging") ? " 充电中" : "");
        long minutes = Math.max(0, (now - point.optLong("recordedAt")) / 60_000);
        String time = minutes < 1 ? "刚刚更新" : minutes < 60 ? minutes + " 分钟前"
                : minutes < 1440 ? minutes / 60 + " 小时前" : minutes / 1440 + " 天前";
        return "位置 " + time + (fresh(point, now) ? "" : " · 上次记录") + energy;
    }

    private static boolean fresh(JSONObject point, long now) {
        return point != null && now - point.optLong("recordedAt") <= 240_000;
    }

    private static JSONObject drawPoint(JSONObject point, String label, boolean partner, boolean past) {
        return Api.json("latitude", point.optDouble("latitude"), "longitude", point.optDouble("longitude"),
                "recordedAt", point.optLong("recordedAt"), "accuracy", point.optDouble("accuracy", 0),
                "label", label, "partner", partner, "past", past);
    }

    private void makeMap() {
        mapData = null;
        String key = amapAndroidKey();
        // The server chooses the available provider at runtime. A missing Key or declined
        // SDK consent leaves the OpenStreetMap renderer active without another build.
        if (AmapMap.available() && api.amapMapAllowed() && !key.isEmpty()) {
            try {
                nativeMap = AmapMap.create(this, key);
            } catch (RuntimeException | LinkageError unavailable) {
                disposeMap();
            }
        }
        if (nativeMap == null) nativeMap = new LeafletMap(this, SOFT);
        mapFrame.addView(nativeMap.view(), 0, new FrameLayout.LayoutParams(-1, -1));
        if (resumed) nativeMap.resume();
    }

    private void updateMap(JSONArray points, boolean route, String mode, boolean recenter) {
        if (nativeMap == null) return;
        int top = Math.round(content.getHeight() / getResources().getDisplayMetrics().density);
        var paths = new JSONArray();
        var stops = new JSONArray();
        if (route) {
            for (var part : displayedParts) {
                if (part.kind() == RouteTimeline.Kind.MOVING) paths.put(new JSONArray().put(part.first()).put(part.last()));
                if (part.kind() == RouteTimeline.Kind.STOPPED) stops.put(part.first());
            }
        }
        JSONObject highlight = sosPoint != null && "sos".equals(mode)
                ? Api.json("id", "sos:" + sosPoint.optLong("recordedAt"), "label", "SOS 求助位置",
                        "points", new JSONArray().put(sosPoint))
                : route ? routeHighlight : null;
        mapData = Api.json("points", points, "route", route, "mode", mode, "recenter", recenter, "highlight", highlight,
                "paths", paths, "stops", stops, "top", Math.max(8, top),
                "bottom", mapDock == null || mapDock.getVisibility() == View.GONE ? 28 : Math.max(110,
                        Math.round(mapDock.getHeight() / getResources().getDisplayMetrics().density)));
        sendMapData();
    }

    private void sendMapData() {
        if (nativeMap != null && mapData != null) nativeMap.update(mapData);
    }

    private void disposeMap() {
        stopScreenPulse();
        if (nativeMap == null) return;
        View view = nativeMap.view();
        if (view.getParent() instanceof android.view.ViewGroup parent) parent.removeView(view);
        if (resumed) nativeMap.pause();
        nativeMap.destroy();
        nativeMap = null;
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("确认", (dialog, which) -> action.run())
                .setNegativeButton("取消", null).show();
    }

    private void signedOut(String reason) {
        accountGeneration++;
        busy = false;
        if (sosFixRequest != null) sosFixRequest.cancel();
        if (permissionDialog != null) permissionDialog.dismiss();
        stopPositionCollection();
        authenticated = false;
        if (mapPrivacyDialog != null) mapPrivacyDialog.dismiss();
        LiveWidget.update(this, null);
        CompanionNotifications.cancel(this);
        feedCache = null;
        knownPlaces = new JSONArray();
        knownPlacesPair = "";
        partnerFocus = false;
        registering = false;
        overview = null;
        sosPoint = null;
        routeHighlight = null;
        main.removeCallbacks(poll);
        render();
        if (hint != null) {
            hint.setText(reason);
        }
    }

    private void showError(Exception error) {
        if (error instanceof Api.Failure failure && failure.status == 401 && authenticated) {
            api.clearSession();
            signedOut(failure.getMessage());
            return;
        }
        if (hint == null) return;
        hint.setVisibility(View.VISIBLE);
        if (api.pausePending() && authenticated) {
            hint.setText("本机已停止采集。服务器暂停尚未确认，联网后将重试。");
        } else {
            hint.setText(error instanceof Api.Failure ? error.getMessage()
                    : error instanceof java.net.SocketTimeoutException ? "连接超时，请检查网络后刷新"
                    : error instanceof java.io.IOException && error.getMessage() != null
                            && error.getMessage().startsWith("请输入") ? error.getMessage() : "连接失败，请检查网络和服务器地址");
        }
    }

    private interface Action<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T result); }

    private <T> void run(Action<T> action, Done<T> onDone) {
        if (busy) return;
        busy = true;
        int account = accountGeneration;
        if (hint != null) hint.setText("正在处理…");
        execute(action, value -> {
            if (account != accountGeneration) return;
            busy = false;
            onDone.accept(value);
        }, error -> {
            if (account != accountGeneration) return;
            busy = false;
            showError(error);
        });
    }

    private <T> void execute(Action<T> action, Done<T> onDone, Done<Exception> onError) {
        executor.execute(() -> {
            try {
                T value = action.run();
                main.post(() -> { if (!isDestroyed()) onDone.accept(value); });
            } catch (Exception error) {
                main.post(() -> { if (!isDestroyed()) onError.accept(error); });
            }
        });
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(15), dp(12), dp(15), dp(12));
        GradientDrawable background = round(Color.WHITE, 20);
        background.setStroke(dp(1), 0xffe3eae4);
        layout.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(layout, params);
        return layout;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private TextView label(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private EditText field(LinearLayout parent, String placeholder, int inputType) {
        EditText view = new EditText(this);
        view.setTextSize(16);
        view.setTextColor(INK);
        view.setHintTextColor(MUTED);
        view.setSingleLine();
        view.setHint(placeholder);
        view.setInputType(inputType);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(round(BACKGROUND, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(10);
        parent.addView(view, params);
        return view;
    }

    private Button button(LinearLayout parent, String caption, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(caption);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(ripple(primary ? ACCENT : SOFT, 16));
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> click.run());
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams params = horizontal ? new LinearLayout.LayoutParams(0, dp(48), 1)
                : new LinearLayout.LayoutParams(-1, dp(48));
        params.setMargins(horizontal ? dp(3) : 0, dp(7), horizontal ? dp(3) : 0, dp(3));
        parent.addView(button, params);
        return button;
    }

    private android.graphics.drawable.Drawable ripple(int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2217634b), round(color, radius), null);
    }

    private android.graphics.drawable.Drawable icon(int page, int color) {
        var icon = new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(android.graphics.Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                paint.setColor(color);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(1.7f);
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                var path = new android.graphics.Path();
                switch (page) {
                    case 0 -> {
                        path.moveTo(12, 21);
                        path.cubicTo(8, 17, 5, 13, 5, 9.5f);
                        path.cubicTo(5, 1, 19, 1, 19, 9.5f);
                        path.cubicTo(19, 13, 16, 17, 12, 21);
                        path.close();
                        canvas.drawPath(path, paint);
                        canvas.drawCircle(12, 9.5f, 2.2f, paint);
                    }
                    case 1 -> {
                        canvas.drawCircle(5.5f, 18.5f, 2.4f, paint);
                        canvas.drawCircle(18.5f, 5.5f, 2.4f, paint);
                        path.moveTo(8, 18.5f);
                        path.lineTo(17, 18.5f);
                        path.cubicTo(21, 18.5f, 20, 12, 16, 12);
                        path.lineTo(9, 12);
                        path.cubicTo(4, 12, 4, 5.5f, 9, 5.5f);
                        path.lineTo(16, 5.5f);
                        canvas.drawPath(path, paint);
                    }
                    case 3 -> {
                        path.moveTo(4, 18);
                        path.lineTo(4, 6);
                        path.quadTo(4, 4, 6, 4);
                        path.lineTo(18, 4);
                        path.quadTo(20, 4, 20, 6);
                        path.lineTo(20, 15);
                        path.quadTo(20, 17, 18, 17);
                        path.lineTo(10, 17);
                        path.lineTo(4, 21);
                        path.close();
                        canvas.drawPath(path, paint);
                        canvas.drawLine(8, 9, 16, 9, paint);
                        canvas.drawLine(8, 13, 13, 13, paint);
                    }
                    case 10 -> {
                        path.moveTo(3, 9); path.lineTo(3, 3); path.lineTo(9, 3);
                        path.moveTo(15, 3); path.lineTo(21, 3); path.lineTo(21, 9);
                        path.moveTo(21, 15); path.lineTo(21, 21); path.lineTo(15, 21);
                        path.moveTo(9, 21); path.lineTo(3, 21); path.lineTo(3, 15);
                        canvas.drawPath(path, paint);
                        canvas.drawRect(9, 9, 15, 15, paint);
                    }
                    case 4 -> {
                        canvas.drawCircle(8.1f, 9, 4.2f, paint);
                        canvas.drawCircle(15.9f, 9, 4.2f, paint);
                        path.moveTo(4.8f, 11.7f);
                        path.quadTo(7.5f, 16, 12, 19.5f);
                        path.quadTo(16.5f, 16, 19.2f, 11.7f);
                        canvas.drawPath(path, paint);
                    }
                    case 5 -> {
                        canvas.drawCircle(12, 12, 6, paint);
                        canvas.drawCircle(12, 12, 2.2f, paint);
                        canvas.drawLine(12, 2, 12, 6, paint);
                        canvas.drawLine(12, 18, 12, 22, paint);
                        canvas.drawLine(2, 12, 6, 12, paint);
                        canvas.drawLine(18, 12, 22, 12, paint);
                    }
                    case 6 -> {
                        canvas.drawRoundRect(7, 3, 17, 21, 2.4f, 2.4f, paint);
                        canvas.drawLine(10.5f, 17.5f, 13.5f, 17.5f, paint);
                    }
                    case 7 -> {
                        canvas.drawCircle(12, 12, 8.5f, paint);
                        path.moveTo(12, 7);
                        path.lineTo(12, 12);
                        path.lineTo(16, 14);
                        canvas.drawPath(path, paint);
                    }
                    default -> {
                        canvas.drawRoundRect(5, 3, 19, 21, 4, 4, paint);
                        canvas.drawLine(8, 9, 16, 9, paint);
                        canvas.drawLine(8, 15, 16, 15, paint);
                        canvas.drawCircle(10, 9, 1.4f, paint);
                        canvas.drawCircle(14, 15, 1.4f, paint);
                    }
                }
                canvas.restore();
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
        icon.setBounds(0, 0, dp(23), dp(23));
        return icon;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), -1, dp(height)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
