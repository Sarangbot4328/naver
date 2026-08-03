package com.webtoonmap.mobile.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LanServerItem {
    public final String id;
    public final String title;
    public final String description;
    public final List<String> tags;
    public final int episodeCount;
    public final long size;
    public final String uploadedAt;
    public final String originalName;
    public final boolean hasThumbnail;
    public final String thumbnailUrl;
    public final String source;

    public LanServerItem(String id, String title, String description, List<String> tags,
                         int episodeCount, long size, String uploadedAt, String originalName,
                         boolean hasThumbnail, String thumbnailUrl, String source) {
        this.id = id;
        this.title = title;
        this.description = description == null ? "" : description;
        this.tags = tags;
        this.episodeCount = episodeCount;
        this.size = size;
        this.uploadedAt = uploadedAt;
        this.originalName = originalName;
        this.hasThumbnail = hasThumbnail;
        this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        this.source = source == null ? "" : source;
    }

    public static LanServerItem fromJson(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "").trim();
        if (id.isEmpty()) return null;
        List<String> tags = new ArrayList<>();
        JSONArray array = json.optJSONArray("tags");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) tags.add(value);
            }
        }
        String title = json.optString("title", "").trim();
        if (title.isEmpty()) {
            JSONArray titles = json.optJSONArray("titles");
            if (titles != null && titles.length() > 0) {
                title = titles.optString(0, "").trim();
            }
        }
        if (title.isEmpty()) title = "웹툰";
        boolean hasThumbnail = json.optBoolean("has_thumbnail", false);
        String thumbnailUrl = json.optString("thumbnail_url", "").trim();
        return new LanServerItem(
                id,
                title,
                json.optString("description", ""),
                tags,
                json.optInt("episode_count", 0),
                json.optLong("size", 0L),
                json.optString("uploaded_at", ""),
                json.optString("original_name", ""),
                hasThumbnail,
                thumbnailUrl,
                json.optString("source", "")
        );
    }

    public String absoluteThumbnailUrl(String baseUrl) {
        if (!hasThumbnail) return null;
        if (thumbnailUrl != null && thumbnailUrl.startsWith("http")) return thumbnailUrl;
        if (baseUrl == null || baseUrl.isEmpty()) return null;
        String path = thumbnailUrl;
        if (path == null || path.isEmpty()) {
            path = "/api/webtoons/" + id + "/thumbnail";
        }
        if (!path.startsWith("/")) path = "/" + path;
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + path
                : baseUrl + path;
    }

    public String tagsLine() {
        if (tags == null || tags.isEmpty()) return "";
        return String.join(" · ", tags);
    }

    public String detailLine() {
        StringBuilder builder = new StringBuilder();
        if (episodeCount > 0) builder.append("회차 ").append(episodeCount).append("개 · ");
        builder.append(formatBytes(size));
        String tagsText = tagsLine();
        if (!tagsText.isEmpty()) builder.append("\n").append(tagsText);
        String desc = description == null ? "" : description.trim();
        if (!desc.isEmpty()) {
            if (desc.length() > 120) desc = desc.substring(0, 120) + "…";
            builder.append("\n").append(desc);
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
