package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class SourceSettings {
    public static final String SOURCE_NAVER = "naver";
    public static final String SOURCE_JOATOON = "joatoon";
    public static final String DEFAULT_JOATOON_URL = "https://joa-new.com";
    private static final String PREFS = "source_settings";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_JOATOON_URL = "joatoon_url";

    private SourceSettings() { }

    public static String getSource(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE, SOURCE_NAVER);
    }

    public static boolean isJoatoon(Context context) {
        return SOURCE_JOATOON.equals(getSource(context));
    }

    public static void setSource(Context context, String source) {
        String value = SOURCE_JOATOON.equals(source) ? SOURCE_JOATOON : SOURCE_NAVER;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SOURCE, value).apply();
    }

    public static String getJoatoonUrl(Context context) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JOATOON_URL, DEFAULT_JOATOON_URL);
        String normalized = normalizeUrl(stored);
        return normalized == null ? DEFAULT_JOATOON_URL : normalized;
    }

    public static boolean setJoatoonUrl(Context context, String raw) {
        String normalized = normalizeUrl(raw);
        if (normalized == null) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_JOATOON_URL, normalized).apply();
        return true;
    }

    public static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.trim().isEmpty()) return null;
            if (!"https".equalsIgnoreCase(scheme)) return null;
            String authority = uri.getEncodedAuthority();
            return "https://" + authority;
        } catch (Exception ignored) {
            return null;
        }
    }
}
