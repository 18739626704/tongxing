package app.tongxing.mobile;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public final class CompanionPush extends Service {
    static void sync(Context context) {}
    static void unregister(Context context, Api api) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
