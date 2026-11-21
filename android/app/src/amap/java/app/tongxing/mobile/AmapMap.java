package app.tongxing.mobile;

import android.content.Context;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.CoordinateConverter;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CircleOptions;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.PolylineOptions;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class AmapMap implements NativeMap {
    private final TextureMapView view;
    private final AMap map;
    private final CoordinateConverter converter;
    private JSONObject latest;
    private boolean ready;
    private boolean destroyed;
    private String mode = "";
    private String highlightId = "";

    static boolean available() { return true; }

    static NativeMap create(Context context, String key) {
        MapsInitializer.updatePrivacyShow(context, true, true);
        MapsInitializer.updatePrivacyAgree(context, true);
        MapsInitializer.setApiKey(key);
        MapsInitializer.setProtocol(MapsInitializer.HTTPS);
        return new AmapMap(context);
    }

    private AmapMap(Context context) {
        converter = new CoordinateConverter(context.getApplicationContext());
        view = new TextureMapView(context);
        view.setContentDescription("高德位置地图");
        view.onCreate(null);
        map = view.getMap();
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setTiltGesturesEnabled(false);
        map.getUiSettings().setRotateGesturesEnabled(false);
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinate(34.75, 113.63), 4));
        map.setOnMapLoadedListener(() -> {
            ready = true;
            if (latest != null) update(latest);
        });
    }

    @Override public TextureMapView view() { return view; }
    @Override public void resume() { view.onResume(); }
    @Override public void pause() { view.onPause(); }
    @Override public void destroy() { destroyed = true; view.onDestroy(); }

    @Override public void update(JSONObject data) {
        if (destroyed) return;
        latest = data;
        if (!ready) return;
        var raw = data.optJSONArray("points");
        var points = new ArrayList<LatLng>(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            var point = raw.optJSONObject(i);
            points.add(coordinate(point.optDouble("latitude"), point.optDouble("longitude")));
        }
        map.clear();
        boolean route = data.optBoolean("route");
        if (route) drawRoute(data, points);
        for (int i = 0; i < points.size(); i++) {
            if (route && i != 0 && i != points.size() - 1) continue;
            var point = raw.optJSONObject(i);
            boolean partner = point.optBoolean("partner");
            boolean past = point.optBoolean("past");
            int color = partner ? 0xffa34b50 : 0xff17634b;
            if (!route && point.optDouble("accuracy") > 0) {
                map.addCircle(new CircleOptions().center(points.get(i)).radius(point.optDouble("accuracy"))
                        .strokeColor(color & 0x66ffffff).strokeWidth(2).fillColor(color & (past ? 0x10ffffff : 0x22ffffff)));
            }
            map.addMarker(new MarkerOptions().position(points.get(i)).title(point.optString("label"))
                    .alpha(past ? .65f : 1f).icon(BitmapDescriptorFactory.defaultMarker(
                            partner ? BitmapDescriptorFactory.HUE_ROSE : BitmapDescriptorFactory.HUE_GREEN)));
        }
        var highlight = data.optJSONObject("highlight");
        String nextHighlight = highlight == null ? "" : highlight.optString("id");
        String nextMode = data.optString("mode");
        if (highlight != null) {
            List<LatLng> selected = drawHighlight(highlight);
            if (!selected.isEmpty() && (!nextHighlight.equals(highlightId) || data.optBoolean("recenter"))) {
                fit(selected, data.optInt("top"), data.optInt("bottom"));
            }
            mode = nextMode;
        } else if (!points.isEmpty() && (!nextMode.equals(mode) || data.optBoolean("recenter"))) {
            fit(points, data.optInt("top"), data.optInt("bottom"));
            mode = nextMode;
        } else if (points.isEmpty()) {
            mode = "";
        }
        highlightId = nextHighlight;
    }

    private List<LatLng> drawHighlight(JSONObject highlight) {
        var raw = highlight.optJSONArray("points");
        var selected = new ArrayList<LatLng>();
        if (raw == null) return selected;
        for (int i = 0; i < raw.length(); i++) {
            var point = raw.optJSONObject(i);
            if (point != null) selected.add(coordinate(point.optDouble("latitude"), point.optDouble("longitude")));
        }
        if (selected.isEmpty()) return selected;
        boolean sos = highlight.optString("id").startsWith("sos:");
        int color = sos ? 0xffbf2349 : 0xfffa7900;
        LatLng start = selected.get(0);
        if (selected.size() > 1) {
            map.addPolyline(new PolylineOptions().addAll(selected).width(18).color(0xffffffff).zIndex(20));
            map.addPolyline(new PolylineOptions().addAll(selected).width(12).color(color).zIndex(21));
            var end = selected.get(selected.size() - 1);
            map.addMarker(new MarkerOptions().position(end).title("选中路段终点").zIndex(22)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
        } else {
            double radius = sos ? Math.max(16, raw.optJSONObject(0).optDouble("accuracy", 0)) : RouteTimeline.AREA_RADIUS_METERS;
            map.addCircle(new CircleOptions().center(start).radius(radius).strokeWidth(5)
                    .strokeColor(color).fillColor(color & 0x28ffffff).zIndex(21));
        }
        var marker = map.addMarker(new MarkerOptions().position(start).title(highlight.optString("label")).zIndex(23)
                .icon(BitmapDescriptorFactory.defaultMarker(sos ? BitmapDescriptorFactory.HUE_RED : BitmapDescriptorFactory.HUE_ORANGE)));
        marker.showInfoWindow();
        return selected;
    }

    private void drawRoute(JSONObject data, List<LatLng> points) {
        var paths = data.optJSONArray("paths");
        for (int i = 0; i < paths.length(); i++) {
            var path = paths.optJSONArray(i);
            map.addPolyline(new PolylineOptions().addAll(points.subList(path.optInt(0), path.optInt(1) + 1))
                    .width(8).color(0xff17634b));
        }
        var stops = data.optJSONArray("stops");
        for (int i = 0; i < stops.length(); i++) {
            int index = stops.optInt(i);
            if (index <= 0 || index >= points.size() - 1) continue;
            map.addMarker(new MarkerOptions().position(points.get(index)).title("停留区域")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        }
    }

    private void fit(List<LatLng> points, int top, int bottom) {
        if (points.stream().allMatch(point -> point.equals(points.get(0)))) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.get(0), 16));
            return;
        }
        var bounds = LatLngBounds.builder();
        points.forEach(bounds::include);
        int maxVertical = Math.max(1, view.getHeight() / 3);
        float density = view.getResources().getDisplayMetrics().density;
        int sides = Math.round(24 * density);
        map.moveCamera(CameraUpdateFactory.newLatLngBoundsRect(bounds.build(), sides, sides,
                Math.min(maxVertical, Math.round(top * density)), Math.min(maxVertical, Math.round(bottom * density))));
    }

    private LatLng coordinate(double latitude, double longitude) {
        var original = new LatLng(latitude, longitude);
        var converted = converter.from(CoordinateConverter.CoordType.GPS).coord(original).convert();
        return converted == null ? original : converted;
    }
}
