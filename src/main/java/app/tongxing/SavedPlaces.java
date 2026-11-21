package app.tongxing;

import app.tongxing.SharingController.Coordinates;
import app.tongxing.SharingController.Place;
import app.tongxing.store.PositionSample;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Called within the sharing service's guarded transactions. */
@Repository
class SavedPlaces {
    private static final int PLACE_RADIUS_METRES = 120;

    private final JdbcTemplate jdbc;

    SavedPlaces(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Place> findAll(String pair, String owner) {
        return jdbc.query("""
                SELECT id, name, latitude, longitude, watching, armed FROM companion_place
                WHERE pair_id = ? AND owner_id = ? ORDER BY name, id
                """, (row, index) -> new Place(row.getString("id"), row.getString("name"),
                row.getDouble("latitude"), row.getDouble("longitude"), row.getBoolean("watching"), row.getBoolean("armed")),
                pair, owner);
    }

    Place add(String id, String pair, String owner, String name, Coordinates point) {
        jdbc.update("""
                INSERT INTO companion_place(id, pair_id, owner_id, name, latitude, longitude) VALUES (?, ?, ?, ?, ?, ?)
                """, id, pair, owner, name, point.latitude(), point.longitude());
        return new Place(id, name, point.latitude(), point.longitude(), false, false);
    }

    void updateWatch(Place place, boolean enabled, PositionSample point, long now) {
        boolean armed = point != null && now - point.recordedAt <= 240_000 && point.accuracy <= 55
                && GeoDistance.distanceMeters(place.latitude(), place.longitude(), point.latitude, point.longitude)
                        > PLACE_RADIUS_METRES + point.accuracy;
        jdbc.update("""
                UPDATE companion_place SET watching = ?, armed = ?, inside_at = NULL, observed_at = ?
                WHERE id = ?
                """, enabled, armed, point == null ? now : point.recordedAt, place.id());
    }

    void delete(String id, String owner, String pair) {
        jdbc.update("DELETE FROM companion_place WHERE id = ? AND owner_id = ? AND pair_id = ?", id, owner, pair);
    }

    private record ArrivalPlace(String id, String name, double lat, double lng, boolean armed, Long insideAt) {}

    void recordArrivals(String pair, String sender, PositionSample sample, Consumer<String> onArrival) {
        if (sample.accuracy > 55) return;
        var places = jdbc.query("""
                SELECT id, name, latitude, longitude, armed, inside_at FROM companion_place
                WHERE pair_id = ? AND owner_id <> ? AND watching = TRUE AND observed_at < ?
                """, (row, index) -> new ArrivalPlace(row.getString("id"), row.getString("name"),
                row.getDouble("latitude"), row.getDouble("longitude"), row.getBoolean("armed"),
                row.getObject("inside_at", Long.class)), pair, sender, sample.recordedAt);
        for (var place : places) {
            double distance = GeoDistance.distanceMeters(place.lat(), place.lng(), sample.latitude, sample.longitude);
            boolean outside = distance > PLACE_RADIUS_METRES + sample.accuracy;
            boolean inside = distance + sample.accuracy <= PLACE_RADIUS_METRES;
            Long insideAt = inside && place.armed() ? place.insideAt() : null;
            if (inside && place.armed() && (insideAt == null || sample.recordedAt - insideAt > 240_000)) {
                insideAt = sample.recordedAt;
            }
            boolean arrived = insideAt != null && sample.recordedAt - insideAt >= 15_000;
            jdbc.update("""
                    UPDATE companion_place SET watching = ?, armed = ?, inside_at = ?, observed_at = ? WHERE id = ?
                    """, !arrived, !arrived && (outside || place.armed()), arrived ? null : insideAt, sample.recordedAt, place.id());
            if (arrived) onArrival.accept(place.name());
        }
    }
}
