package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class SourceSettings {
    public static final String SOURCE_NAVER = "naver";
    public static final String SOURCE_JOATOON = "joatoon";
    public static final String SOURCE_MANHWABANG = "manhwabang";
    public static final String SOURCE_ILILTOON = "ililtoon";
    public static final String DEFAULT_JOATOON_URL = "https://joa-new.com";
    public static final String DEFAULT_MANHWABANG_URL = "https://manhwabang.net";
    public static final String DEFAULT_ILILTOON_URL = "https://11toon148.com";
    public static final String VIEW_MODE_SCROLL = "scroll";
    public static final String VIEW_MODE_PAGE = "page";
    private static final String PREFS = "source_settings";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_JOATOON_URL = "joatoon_url";
    private static final String KEY_MANHWABANG_URL = "manhwabang_url";
    private static final String KEY_ILILTOON_URL = "ililtoon_url";
    private static final String KEY_VIEW_MODE = "view_mode";

    private SourceSettings() { }

    public static String getViewMode(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VIEW_MODE, VIEW_MODE_SCROLL);
        return VIEW_MODE_PAGE.equals(value) ? VIEW_MODE_PAGE : VIEW_MODE_SCROLL;
    }

    public static boolean isPageMode(Context context) {
        return VIEW_MODE_PAGE.equals(getViewMode(context));
    }

    public static void setViewMode(Context context, String mode) {
        String value = VIEW_MODE_PAGE.equals(mode) ? VIEW_MODE_PAGE : VIEW_MODE_SCROLL;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_VIEW_MODE, value).apply();
    }

    public static String getSource(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE, SOURCE_NAVER);
    }

    public static boolean isJoatoon(Context context) {
        return SOURCE_JOATOON.equals(getSource(context));
    }

    public static void setSource(Context context, String source) {
        String value = isKnownSource(source) ? source : SOURCE_NAVER;
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
        return setUrl(context, KEY_JOATOON_URL, raw);
    }

    public static String getManhwabangUrl(Context context) {
        return getUrl(context, KEY_MANHWABANG_URL, DEFAULT_MANHWABANG_URL);
    }

    public static boolean setManhwabangUrl(Context context, String raw) {
        return setUrl(context, KEY_MANHWABANG_URL, raw);
    }

    public static String getIliltoonUrl(Context context) {
        return getUrl(context, KEY_ILILTOON_URL, DEFAULT_ILILTOON_URL);
    }

    public static boolean setIliltoonUrl(Context context, String raw) {
        return setUrl(context, KEY_ILILTOON_URL, raw);
    }

    public static String channelLabel(Context context) {
        String source = getSource(context);
        if (SOURCE_JOATOON.equals(source)) return "조아툰";
        if (SOURCE_MANHWABANG.equals(source)) return "만화방";
        if (SOURCE_ILILTOON.equals(source)) return "일일툰";
        return "네이버 웹툰";
    }

    public static String homeUrl(Context context) {
        String source = getSource(context);
        if (SOURCE_JOATOON.equals(source)) return getJoatoonUrl(context);
        if (SOURCE_MANHWABANG.equals(source)) return getManhwabangUrl(context) + "/webtoon/list?type=def";
        if (SOURCE_ILILTOON.equals(source)) return getIliltoonUrl(context);
        return "https://comic.naver.com/webtoon";
    }

    private static boolean setUrl(Context context, String key, String raw) {
        String normalized = normalizeUrl(raw);
        if (normalized == null) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, normalized).apply();
        return true;
    }

    private static String getUrl(Context context, String key, String fallback) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key, fallback);
        String normalized = normalizeUrl(stored);
        return normalized == null ? fallback : normalized;
    }

    private static boolean isKnownSource(String source) {
        return SOURCE_NAVER.equals(source) || SOURCE_JOATOON.equals(source) ||
                SOURCE_MANHWABANG.equals(source) || SOURCE_ILILTOON.equals(source);
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
