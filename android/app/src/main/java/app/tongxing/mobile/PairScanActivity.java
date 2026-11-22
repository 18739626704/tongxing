package app.tongxing.mobile;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.Size;

public final class PairScanActivity extends CaptureActivity {
    static final String MANUAL = "pair_manual";

    @Override protected DecoratedBarcodeView initializeContent() {
        getWindow().getDecorView();
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
        var scanner = new DecoratedBarcodeView(this);
        var preview = scanner.getBarcodeView();
        int size = Math.max(dp(120), Math.min(dp(266), getResources().getDisplayMetrics().widthPixels - dp(56)));
        preview.setFramingRectSize(new Size(size, size));
        scanner.getViewFinder().setLaserVisibility(false);
        scanner.getViewFinder().setVisibility(View.GONE);
        if (scanner.getStatusView() != null) scanner.getStatusView().setVisibility(View.GONE);
        root.addView(scanner, new FrameLayout.LayoutParams(-1, -1));
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
            @Override public void cameraError(Exception error) {}
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
        TextView status = text("将同行绑定二维码放入取景框", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        footer.addView(actions);
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Button flash = button(actions, "开手电筒", null);
            scanner.setTorchListener(new DecoratedBarcodeView.TorchListener() {
                @Override public void onTorchOn() { flash.setText("关手电筒"); }
                @Override public void onTorchOff() { flash.setText("开手电筒"); }
            });
            flash.setOnClickListener(view -> {
                if (!preview.isPreviewActive()) return;
                if ("开手电筒".contentEquals(flash.getText())) scanner.setTorchOn();
                else scanner.setTorchOff();
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

        return scanner;
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
