package app.tongxing.mobile;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;

final class LeafletMap implements NativeMap {
    private static final String ASSET = "https://appassets.androidplatform.net/assets/map.html";
    private final WebView view;
    private JSONObject latest;
    private boolean ready;
    private boolean destroyed;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    LeafletMap(Context context, int background) {
        var assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context)).build();
        view = new WebView(context);
        view.setContentDescription("位置地图");
        view.setBackgroundColor(background);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString("Tongxing-Android/" + BuildConfig.VERSION_NAME
                + " (+https://github.com/18739626704/tongxing)");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                if (request.isForMainFrame()
                        && "https://www.openstreetmap.org/copyright".equals(request.getUrl().toString())) {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    } catch (ActivityNotFoundException ignored) {
                        // The attribution remains visible without a browser.
                    }
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                if (destroyed || !ASSET.equals(url)) return;
                ready = true;
                render();
            }
        });
        view.setOnTouchListener((map, event) -> {
            int action = event.getActionMasked();
            var parent = map.getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(
                        action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
            }
            return false;
        });
        view.loadUrl(ASSET);
    }

    @Override public View view() { return view; }

    @Override public void update(JSONObject data) {
        latest = data;
        render();
    }

    private void render() {
        if (ready && latest != null && !destroyed) {
            view.evaluateJavascript("window.updateMap(" + latest + ")", null);
        }
    }

    @Override public void resume() { view.onResume(); }
    @Override public void pause() { view.onPause(); }

    @Override public void destroy() {
        destroyed = true;
        latest = null;
        view.destroy();
    }
}
