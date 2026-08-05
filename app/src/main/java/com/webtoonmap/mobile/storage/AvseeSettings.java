package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class AvseeSettings {
    private static final String PREFS = "avsee_settings";
    private static final String KEY_URL = "base_url";
    public static final String DEFAULT_URL = "https://url1.avsee.me";

    private AvseeSettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getBaseUrl(Context context) {
        String value = prefs(context).getString(KEY_URL, DEFAULT_URL);
        return normalize(value == null ? DEFAULT_URL : value);
    }

    public static boolean setBaseUrl(Context context, String value) {
        String normalized = normalize(value);
        try {
            Uri uri = Uri.parse(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getHost().trim().isEmpty()) return false;
        } catch (Exception ignored) {
            return false;
        }
        return prefs(context).edit().putString(KEY_URL, normalized).commit();
    }

    public static boolean isConfiguredHost(Context context, String url) {
        try {
            String allowed = Uri.parse(getBaseUrl(context)).getHost();
            String actual = Uri.parse(url).getHost();
            if (allowed == null || actual == null) return false;
            return actual.equalsIgnoreCase(allowed) ||
                    actual.toLowerCase().endsWith("." + allowed.toLowerCase());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? DEFAULT_URL : out;
    }
}
