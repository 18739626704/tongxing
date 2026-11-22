package app.tongxing.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.Size;
import java.util.List;

public final class PairScanActivity extends Activity {
    static final String CODE = "pair_code";
    static final String MANUAL = "pair_manual";
    private static final int CAMERA_PERMISSION = 1;
    private BarcodeView preview;
    private TextView status;
    private boolean torch;
    private boolean completed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff101b17);
        getWindow().setNavigationBarColor(0xff101b17);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) bars.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff101b17);
        setContentView(root);
        preview = new BarcodeView(this);
        int size = Math.min(dp(266), getResources().getDisplayMetrics().widthPixels - dp(56));
        size = Math.max(dp(120), size);
        preview.setFramingRectSize(new Size(size, size));
        preview.setDecoderFactory(new DefaultDecoderFactory(List.of(BarcodeFormat.QR_CODE)));
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        View frame = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                Rect area = preview.getFramingRect();
                if (area == null) return;
                paint.setColor(0xc0101b17);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(0, 0, getWidth(), area.top, paint);
                canvas.drawRect(0, area.bottom, getWidth(), getHeight(), paint);
                canvas.drawRect(0, area.top, area.left, area.bottom, paint);
                canvas.drawRect(area.right, area.top, getWidth(), area.bottom, paint);
                paint.setColor(0xff9af3cf);
                paint.setStrokeWidth(dp(4));
                paint.setStrokeCap(Paint.Cap.ROUND);
                for (int x : new int[]{area.left, area.right}) {
                    for (int y : new int[]{area.top, area.bottom}) {
                        canvas.drawLine(x, y, x + (x == area.left ? dp(23) : -dp(23)), y, paint);
                        canvas.drawLine(x, y, x, y + (y == area.top ? dp(23) : -dp(23)), paint);
                    }
                }
            }
        };
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        preview.addStateListener(new CameraPreview.StateListener() {
            @Override public void previewSized() { frame.invalidate(); }
            @Override public void previewStarted() { frame.invalidate(); }
            @Override public void previewStopped() {}
            @Override public void cameraClosed() {}
            @Override public void cameraError(Exception error) {
                status.setText("相机无法打开，可以手动输入邀请码");
            }
        });
        preview.decodeContinuous(result -> {
            if (completed) return;
            String code = PairQr.scan(result.getText());
            if (code.isEmpty()) {
                status.setText("请扫描同行生成的绑定二维码");
                return;
            }
            completed = true;
            setResult(RESULT_OK, new Intent().putExtra(CODE, code));
            finish();
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(14), 0);
        button(header, "返回", this::finish);
        TextView title = text("扫一扫绑定", 19);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(72), 1));
        root.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(18), dp(15), dp(18), dp(24));
        status = text("将同行绑定二维码放入取景框", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        footer.addView(actions);
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Button flash = button(actions, "开手电筒", null);
            flash.setOnClickListener(view -> {
                if (!preview.isPreviewActive()) return;
                torch = !torch;
                preview.setTorch(torch);
                flash.setText(torch ? "关手电筒" : "开手电筒");
            });
        }
        button(actions, "手动输入", () -> {
            setResult(RESULT_OK, new Intent().putExtra(MANUAL, true));
            finish();
        });
        root.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                header.setPadding(dp(14) + bars.left, dp(10) + bars.top, dp(14) + bars.right, 0);
                footer.setPadding(dp(18) + bars.left, dp(15), dp(18) + bars.right, dp(24) + bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }

        if (!cameraAllowed()) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    private boolean cameraAllowed() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onResume() {
        super.onResume();
        if (cameraAllowed()) startPreview();
    }

    @SuppressLint("MissingPermission")
    private void startPreview() {
        preview.resume();
        if (torch) preview.setTorch(true);
    }

    @Override protected void onPause() {
        preview.pause();
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != CAMERA_PERMISSION) return;
        if (cameraAllowed()) startPreview();
        else status.setText("未授权相机，可以手动输入邀请码");
    }

    private TextView text(String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setTextSize(size);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setMinWidth(dp(72));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(0xff28493c);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x42ffffff), background, null));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(48));
        layout.setMargins(dp(4), dp(8), dp(4), 0);
        parent.addView(button, layout);
        if (action != null) button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
