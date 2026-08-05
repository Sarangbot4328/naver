package com.webtoonmap.mobile.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class AvseeLibraryDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "avsee_library.db";
    private static final int DB_VERSION = 1;

    public AvseeLibraryDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE videos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "file_path TEXT NOT NULL," +
                "thumbnail_path TEXT," +
                "page_url TEXT," +
                "tags TEXT," +
                "actors TEXT," +
                "description TEXT," +
                "created_at TEXT NOT NULL," +
                "size_bytes INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_avsee_created ON videos(created_at DESC)");
        db.execSQL("CREATE INDEX idx_avsee_page ON videos(page_url)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public long insert(String title, String filePath, String thumbnailPath, String pageUrl,
                       String tags, String actors, String description, String createdAt,
                       long sizeBytes) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("file_path", filePath);
        values.put("thumbnail_path", thumbnailPath);
        values.put("page_url", pageUrl);
        values.put("tags", tags);
        values.put("actors", actors);
        values.put("description", description);
        values.put("created_at", createdAt);
        values.put("size_bytes", sizeBytes);
        return getWritableDatabase().insertOrThrow("videos", null, values);
    }

    public boolean hasPage(String pageUrl) {
        if (pageUrl == null || pageUrl.trim().isEmpty()) return false;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM videos WHERE page_url=? LIMIT 1", new String[]{pageUrl})) {
            return cursor.moveToFirst();
        }
    }

    public List<AvseeVideo> listAll() {
        List<AvseeVideo> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,title,file_path,thumbnail_path,page_url,tags,actors," +
                        "description,created_at,size_bytes FROM videos ORDER BY id DESC", null)) {
            while (cursor.moveToNext()) {
                out.add(new AvseeVideo(
                        cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getString(5),
                        cursor.getString(6), cursor.getString(7), cursor.getString(8),
                        cursor.getLong(9)));
            }
        }
        return out;
    }

    public void delete(long id) {
        getWritableDatabase().delete("videos", "id=?", new String[]{String.valueOf(id)});
    }
}
