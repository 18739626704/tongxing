package app.tongxing.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import org.json.JSONException;
import org.json.JSONObject;

final class DeviceStatus {
    private DeviceStatus() {}

    static void append(Context context, JSONObject sample) throws JSONException {
        var connections = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities net = connections.getNetworkCapabilities(connections.getActiveNetwork());
        String type = net == null ? null
                : net.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                : net.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "MOBILE" : null;
        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        sample.put("deviceModel", model.substring(0, Math.min(80, model.length())));
        sample.put("networkType", type);
    }

    static String motionLabel(String value) {
        return switch (value) {
            case "STILL" -> "停留中";
            case "MOVING" -> "移动中";
            case "FAST" -> "较快移动";
            default -> "";
        };
    }

    static String networkLabel(String value) {
        return switch (value) {
            case "WIFI" -> "Wi-Fi";
            case "MOBILE" -> "移动网络";
            default -> "";
        };
    }
}
