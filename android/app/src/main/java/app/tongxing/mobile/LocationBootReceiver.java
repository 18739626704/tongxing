package app.tongxing.mobile;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class LocationBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Api api = Api.get(context);
        api.migrateLegacyPause();
        CompanionNotifications.schedule(context);
        if (!api.hasSession() || !api.restoreLocationOnBoot() || api.sharingNeedsReview() || api.pausePending()) return;
        if (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            context.startForegroundService(new Intent(context, LocationSharing.class).setAction(LocationSharing.RESTORE));
        } catch (IllegalStateException | SecurityException blocked) {
            android.util.Log.i("TongxingLocation", "System deferred location restore until next app launch");
        }
    }
}
