package app.tongxing.mobile;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Locale;

final class PairQr {
    private static final String PREFIX = "tongxing:pair:";
    private static final int SIZE = 360;

    private PairQr() {}

    static Bitmap image(String code) {
        try {
            var matrix = new QRCodeWriter().encode(PREFIX + code, BarcodeFormat.QR_CODE, SIZE, SIZE);
            int[] pixels = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    pixels[y * SIZE + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888);
        } catch (com.google.zxing.WriterException invalid) {
            throw new IllegalArgumentException("二维码生成失败", invalid);
        }
    }

    static String scan(String payload) {
        return payload.startsWith(PREFIX) ? code(payload.substring(PREFIX.length())) : "";
    }

    static String code(String input) {
        String code = input.trim().toUpperCase(Locale.ROOT);
        return code.matches("[0-9A-F]{16}") ? code : "";
    }
}
