package com.webtoonmap.mobile.toonkor;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

public final class ToonkorMetadataStore {
    private static final String PREFS = "toonkor_series_metadata";

    public static final class Entry {
        public final String title, description, thumbnailUrl, tags;

        Entry(String title, String description, String thumbnailUrl, String tags) {
            this.title = title;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.tags = tags;
        }
    }

    private ToonkorMetadataStore() { }

    public static synchronized void put(Context context, String path, String title,
                                        String description, String thumbnailUrl, String tags) {
        String key = normalizePath(path);
        if (key == null) return;
        Entry previous = get(context, key);
        String cleanTitle = clean(title);
        String cleanDescription = clean(description);
        String cleanThumbnail = clean(thumbnailUrl);
        String cleanTags = clean(tags);
        if (previous != null) {
            if (cleanTitle.isEmpty()) cleanTitle = previous.title;
            if (cleanDescription.isEmpty()) cleanDescription = previous.description;
            if (cleanThumbnail.isEmpty()) cleanThumbnail = previous.thumbnailUrl;
            if (cleanTags.isEmpty()) cleanTags = previous.tags;
        }
        try {
            JSONObject value = new JSONObject();
            value.put("title", cleanTitle);
            value.put("description", cleanDescription);
            value.put("thumbnail_url", cleanThumbnail);
            value.put("tags", cleanTags);
            preferences(context).edit().putString(key, value.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static Entry get(Context context, String path) {
        String key = normalizePath(path);
        if (key == null) return null;
        String raw = preferences(context).getString(key, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject value = new JSONObject(raw);
            return new Entry(value.optString("title"), value.optString("description"),
                    value.optString("thumbnail_url"), value.optString("tags"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalizePath(String value) {
        if (value == null) return null;
        String path = value.trim();
        if (path.isEmpty()) return null;
        try {
            if (path.matches("(?i)^https?://.*")) path = Uri.parse(path).getPath();
            else path = Uri.decode(path);
        } catch (Exception ignored) { }
        if (path == null || path.trim().isEmpty()) return null;
        path = path.trim().replaceAll("/+$", "");
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
