package com.webtoonmap.mobile.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public final class LanServerSettings {
    public static final int DEFAULT_PORT = 17891;
    private static final String PREFS = "lan_server_settings";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_DISPLAY_NAME = "display_name";

    private LanServerSettings() { }

    public static String getHost(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HOST, "");
    }

    public static int getPort(Context context) {
        int port = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PORT, DEFAULT_PORT);
        if (port < 1 || port > 65535) return DEFAULT_PORT;
        return port;
    }

    public static String getBaseUrl(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BASE_URL, "");
        if (!TextUtils.isEmpty(saved)) return saved;
        String host = getHost(context);
        if (TextUtils.isEmpty(host)) return null;
        return "http://" + host + ":" + getPort(context);
    }

    public static String getDisplayName(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_NAME, "");
    }

    public static void saveEndpoint(Context context, String host, int port, String baseUrl,
                                    String displayName) {
        if (host == null) host = "";
        if (port < 1 || port > 65535) port = DEFAULT_PORT;
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = host.isEmpty() ? "" : ("http://" + host + ":" + port);
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HOST, host.trim())
                .putInt(KEY_PORT, port)
                .putString(KEY_BASE_URL, baseUrl.trim());
        if (displayName != null) {
            editor.putString(KEY_DISPLAY_NAME, displayName.trim());
        }
        editor.apply();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String normalizeManualAddress(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        value = value.replace(" ", "");
        if (value.startsWith("http://")) value = value.substring(7);
        if (value.startsWith("https://")) value = value.substring(8);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return null;
        return value;
    }

    public static ParsedEndpoint parseManual(String raw) {
        String value = normalizeManualAddress(raw);
        if (value == null) return null;
        String host;
        int port = DEFAULT_PORT;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(']') < 0) {
            String maybePort = value.substring(colon + 1);
            try {
                port = Integer.parseInt(maybePort);
                if (port < 1 || port > 65535) return null;
                host = value.substring(0, colon);
            } catch (NumberFormatException ignored) {
                host = value;
                port = DEFAULT_PORT;
            }
        } else {
            host = value;
        }
        if (host.isEmpty()) return null;
        return new ParsedEndpoint(host, port, "http://" + host + ":" + port);
    }

    public static final class ParsedEndpoint {
        public final String host;
        public final int port;
        public final String baseUrl;

        public ParsedEndpoint(String host, int port, String baseUrl) {
            this.host = host;
            this.port = port;
            this.baseUrl = baseUrl;
        }
    }
}
