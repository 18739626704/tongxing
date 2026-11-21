package app.tongxing.mobile;

import android.content.Context;

final class AmapMap {
    private AmapMap() {}
    static boolean available() { return false; }
    static NativeMap create(Context context, String key) { return null; }
}
