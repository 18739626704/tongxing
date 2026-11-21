package app.tongxing.mobile;

import android.view.View;
import org.json.JSONObject;

interface NativeMap {
    View view();
    void update(JSONObject data);
    void resume();
    void pause();
    void destroy();
}
