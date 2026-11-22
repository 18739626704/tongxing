package app.tongxing.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONException;
import org.json.JSONObject;

/** Local delivery queue. Sample IDs and capture times survive network loss and service recreation. */
final class PendingPositions extends SQLiteOpenHelper {
    private static final long MAX_AGE = 86_400_000;

    PendingPositions(Context context) {
        super(context, "pending-positions.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE position_queue (id TEXT PRIMARY KEY, account TEXT NOT NULL, "
                + "generation TEXT NOT NULL, captured INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX position_order ON position_queue(account, generation, captured)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int previous, int next) {}

    void prepare(String account, String generation) {
        getWritableDatabase().delete("position_queue", "account <> ? OR generation <> ? OR captured < ?",
                new String[]{account, generation, Long.toString(System.currentTimeMillis() - MAX_AGE)});
    }

    void add(String account, String generation, JSONObject point) {
        ContentValues row = new ContentValues();
        row.put("id", point.optString("id"));
        row.put("account", account);
        row.put("generation", generation);
        row.put("captured", point.optLong("recordedAt"));
        row.put("payload", point.toString());
        getWritableDatabase().insert("position_queue", null, row);
    }

    JSONObject next(String account, String generation) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("position_queue", "captured < ?", new String[]{Long.toString(System.currentTimeMillis() - MAX_AGE)});
        String id;
        try (var cursor = db.query("position_queue", new String[]{"id", "payload"}, "account = ? AND generation = ?",
                new String[]{account, generation}, null, null, "captured ASC, rowid ASC", "1")) {
            if (!cursor.moveToFirst()) return null;
            id = cursor.getString(0);
            try {
                return new JSONObject(cursor.getString(1));
            } catch (JSONException invalid) {
                // Remove a damaged entry so it cannot block newer points.
            }
        }
        db.delete("position_queue", "id = ?", new String[]{id});
        return null;
    }

    void remove(JSONObject sample) {
        getWritableDatabase().delete("position_queue", "id = ?", new String[]{sample.optString("id")});
    }

    void clear() { getWritableDatabase().delete("position_queue", null, null); }
}
