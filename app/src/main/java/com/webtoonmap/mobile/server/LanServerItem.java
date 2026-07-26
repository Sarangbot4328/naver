package com.webtoonmap.mobile.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LanServerItem {
    public final String id;
    public final String title;
    public final List<String> titles;
    public final int webtoonCount;
    public final int episodeCount;
    public final long size;
    public final String uploadedAt;
    public final String originalName;

    public LanServerItem(String id, String title, List<String> titles, int webtoonCount,
                         int episodeCount, long size, String uploadedAt, String originalName) {
        this.id = id;
        this.title = title;
        this.titles = titles;
        this.webtoonCount = webtoonCount;
        this.episodeCount = episodeCount;
        this.size = size;
        this.uploadedAt = uploadedAt;
        this.originalName = originalName;
    }

    public static LanServerItem fromJson(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "").trim();
        if (id.isEmpty()) return null;
        List<String> titles = new ArrayList<>();
        JSONArray array = json.optJSONArray("titles");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) titles.add(value);
            }
        }
        String title = json.optString("title", "").trim();
        if (title.isEmpty() && !titles.isEmpty()) title = titles.get(0);
        if (title.isEmpty()) title = "웹툰 패키지";
        return new LanServerItem(
                id,
                title,
                titles,
                json.optInt("webtoon_count", titles.isEmpty() ? 1 : titles.size()),
                json.optInt("episode_count", 0),
                json.optLong("size", 0L),
                json.optString("uploaded_at", ""),
                json.optString("original_name", "")
        );
    }

    public String detailLine() {
        String sizeText = formatBytes(size);
        StringBuilder builder = new StringBuilder();
        builder.append("작품 ").append(webtoonCount).append("개");
        if (episodeCount > 0) builder.append(" · 회차 ").append(episodeCount).append("개");
        builder.append(" · ").append(sizeText);
        if (titles != null && titles.size() > 1) {
            builder.append('\n').append(String.join(", ", titles));
        }
        return builder.toString();
    }

    public static String formatBytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", value / 1024.0);
        }
        if (value < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0));
    }
}
