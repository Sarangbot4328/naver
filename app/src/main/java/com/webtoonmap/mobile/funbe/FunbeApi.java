package com.webtoonmap.mobile.funbe;

import android.net.Uri;

import com.webtoonmap.mobile.toonkor.ToonkorApi;
import com.webtoonmap.mobile.toonkor.ToonkorMetadataStore;

import java.util.List;
import java.util.Locale;

public final class FunbeApi {
    public static final String KEY_PREFIX = "funbe_";
    private static final String SITE_NAME = "\uD380\uBE44";

    private FunbeApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[0-9a-f]+");
    }

    public static String seriesPath(String url) {
        if (url == null) return null;
        try {
            String path = Uri.parse(url).getPath();
            if (path == null) return null;
            path = path.trim().replaceAll("/+$", "");
            if (!path.matches("^/[^/]+$")) return null;
            String name = path.substring(1);
            String lower = name.toLowerCase(Locale.US);
            if (lower.endsWith(".html") || lower.endsWith(".php")) return null;
            String[] reserved = {
                    "\uC6F9\uD230", "\uC644\uACB0", "\uC778\uAE30", "\uC7A5\uB974", "\uC81C\uBAA9",
                    "\uC8FC\uC18C\uC54C\uB9BC", "\uCF54\uC0AC\uC774\uD2B8", "\uACE0\uAC1D\uC13C\uD130",
                    "\uACF5\uC9C0\uC0AC\uD56D", "\uD1A0\uD1A0\uBCF4\uC99D\uC5C5\uCCB41BET1",
                    "bbs", "skin", "viewer", "data", "img", "images", "bann",
                    "notice"
            };
            for (String value : reserved) if (value.equalsIgnoreCase(name)) return null;
            return path;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ToonkorApi.SeriesInfo fetchSeriesInfo(
            String pageUrl, String cookie, ToonkorMetadataStore.Entry cached) throws Exception {
        return ToonkorApi.fetchSeriesInfo(pageUrl, cookie, cached, SITE_NAME);
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie)
            throws Exception {
        try {
            return ToonkorApi.fetchEpisodeImages(episodeUrl, cookie, SITE_NAME);
        } catch (Exception firstError) {
            if (cookie == null || cookie.isEmpty()) throw firstError;
            return ToonkorApi.fetchEpisodeImages(episodeUrl, null, SITE_NAME);
        }
    }

    public static byte[] downloadBytes(String url, String referer, String cookie)
            throws Exception {
        try {
            return ToonkorApi.downloadBytes(url, referer, cookie, SITE_NAME);
        } catch (Exception firstError) {
            if (cookie == null || cookie.isEmpty()) throw firstError;
            return ToonkorApi.downloadBytes(url, referer, null, SITE_NAME);
        }
    }
}
