package com.webtoonmap.mobile.ililtoon;

import android.text.Html;

import com.webtoonmap.mobile.joatoon.JoatoonApi;

import org.json.JSONArray;

import com.webtoonmap.mobile.network.NetworkRetry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IliltoonApi {
    public static final String KEY_PREFIX = "ililtoon_";

    public static final class SeriesInfo {
        public final String title, description, thumbnailUrl, tags, pageUrl;
        public final List<EpisodeMeta> episodes;

        SeriesInfo(String title, String description, String thumbnailUrl, String tags,
                   String pageUrl, List<EpisodeMeta> episodes) {
            this.title = title;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.tags = tags;
            this.pageUrl = pageUrl;
            this.episodes = episodes;
        }
    }

    public static final class EpisodeMeta {
        public final int number;
        public final String title, url;

        EpisodeMeta(int number, String title, String url) {
            this.number = number;
            this.title = title;
            this.url = url;
        }
    }

    private static final class RawEpisode {
        final String title, url;
        final Integer number;
        RawEpisode(String title, String url, Integer number) {
            this.title = title; this.url = url; this.number = number;
        }
    }

    private IliltoonApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[0-9a-f]+");
    }

    public static SeriesInfo fetchSeriesInfo(String pageUrl, String cookie) throws Exception {
        String html = getText(pageUrl, origin(pageUrl), cookie);
        String title = stripHtml(tagText(html, "title"))
                .replaceFirst("(?i)\\s*-\\s*일일툰.*$", "").trim();
        if (title.isEmpty()) title = "일일툰 만화";
        String description = stripHtml(findMeta(html, "og:description"));

        // The site metadata image is sometimes extension-less and returns HTTP 522.
        // Follow the desktop extension and use the first episode banner first.
        String thumbnail = findElementImage(html, pageUrl,
                "episode-banner|comic-thumb|banner");
        if (thumbnail == null) {
            thumbnail = absoluteUrl(pageUrl, findMeta(html, "og:image"));
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher genre = Pattern.compile(
                "(?is)<[^>]*class=[\\\"'][^\\\"']*genre[^\\\"']*[\\\"'][^>]*>(.*?)</[^>]+>")
                .matcher(html);
        while (genre.find()) {
            String value = stripHtml(genre.group(1)).replaceFirst("^장르\\s*:\\s*", "");
            for (String part : value.split("[,/]") ) {
                if (!part.trim().isEmpty() && part.trim().length() <= 30) tags.add(part.trim());
            }
        }

        Pattern buttons = Pattern.compile(
                "(?is)<button\\b(?=[^>]*class=[\\\"'][^\\\"']*episode[^\\\"']*[\\\"'])[^>]*>.*?</button>");
        Matcher button = buttons.matcher(html);
        Map<String, RawEpisode> unique = new LinkedHashMap<>();
        while (button.find()) {
            String tag = button.group();
            Matcher location = Pattern.compile(
                    "(?is)location\\.href\\s*=\\s*['\\\"]([^'\\\"]+)").matcher(tag);
            if (!location.find()) continue;
            String url = absoluteUrl(pageUrl, location.group(1).replace("&amp;", "&"));
            if (url == null) continue;
            String text = stripHtml(tag.substring(tag.indexOf('>') + 1).replaceFirst("(?is)</button>.*$", ""));
            unique.putIfAbsent(url, new RawEpisode(text, url, episodeNumber(text)));
        }

        List<RawEpisode> raw = new ArrayList<>(unique.values());
        Collections.reverse(raw);
        Map<Integer, EpisodeMeta> numbered = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            RawEpisode episode = raw.get(i);
            int number = episode.number == null ? i + 1 : episode.number;
            String episodeTitle = episode.title.length() > 120 ? episode.title.substring(0, 120) : episode.title;
            numbered.putIfAbsent(number, new EpisodeMeta(number, episodeTitle, episode.url));
        }
        List<EpisodeMeta> episodes = new ArrayList<>(numbered.values());
        episodes.sort((a, b) -> Integer.compare(a.number, b.number));
        return new SeriesInfo(title, description, thumbnail, String.join(", ", tags),
                pageUrl, episodes);
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie) throws Exception {
        String html = getText(episodeUrl, episodeUrl, cookie);
        LinkedHashSet<String> images = parseImageArray(html, "img_list", episodeUrl);
        if (images.isEmpty()) images = parseImageArray(html, "img_list_2", episodeUrl);
        if (images.isEmpty()) {
            Matcher attr = Pattern.compile(
                    "(?is)(?:data-src|data-original|data-lazy-src|src)\\s*=\\s*[\\\"']([^\\\"']+\\.(?:jpg|jpeg|png|webp)(?:[?#][^\\\"']*)?)[\\\"']")
                    .matcher(html);
            while (attr.find()) {
                String url = absoluteUrl(episodeUrl, attr.group(1).replace("&amp;", "&"));
                if (url != null && !url.toLowerCase().matches(".*(?:logo|icon|banner|ad[s]?)[^/]*$")) images.add(url);
            }
        }
        if (images.isEmpty()) throw new IOException("일일툰 회차 이미지를 찾지 못했습니다.");
        return new ArrayList<>(images);
    }

    private static LinkedHashSet<String> parseImageArray(String html, String name, String baseUrl) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?is)var\\s+" + Pattern.quote(name) +
                "\\s*=\\s*(\\[[\\s\\S]*?\\])\\s*;").matcher(html);
        if (!matcher.find()) return result;
        try {
            JSONArray array = new JSONArray(matcher.group(1));
            for (int i = 0; i < array.length(); i++) {
                String url = absoluteUrl(baseUrl, array.optString(i).replace("&amp;", "&"));
                if (url != null) result.add(url);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "image/*,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("일일툰 이미지 HTTP " + code);
                return readAll(conn.getInputStream(), "일일툰 이미지 다운로드 중단");
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        
        });
    }

    private static String getText(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "text/html,application/xhtml+xml,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("일일툰 응답 오류 " + code);
                return new String(readAll(conn.getInputStream(), "일일툰 요청 중단"), StandardCharsets.UTF_8);
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        
        });
    }

    private static byte[] readAll(InputStream input, String interruptedMessage) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(interruptedMessage);
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static HttpURLConnection open(String url, String referer, String cookie, String accept)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(0);
        conn.setReadTimeout(0);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", JoatoonApi.USER_AGENT);
        conn.setRequestProperty("Accept", accept);
        if (referer != null) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return NetworkRetry.track(conn);
    }

    private static Integer episodeNumber(String text) {
        Matcher matcher = Pattern.compile("(\\d{1,4})\\s*(?:화|회)").matcher(text);
        Integer result = null;
        while (matcher.find()) result = Integer.parseInt(matcher.group(1));
        return result != null && result > 0 && result <= 5000 ? result : null;
    }

    private static String findMeta(String html, String key) {
        Matcher matcher = Pattern.compile("(?is)<meta\\b[^>]*(?:property|name)=[\\\"']" +
                Pattern.quote(key) + "[\\\"'][^>]*content=[\\\"']([^\\\"']*)[\\\"']")
                .matcher(html);
        if (matcher.find()) return matcher.group(1);
        matcher = Pattern.compile("(?is)<meta\\b[^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*" +
                "(?:property|name)=[\\\"']" + Pattern.quote(key) + "[\\\"']").matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String tagText(String html, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String findElementImage(String html, String pageUrl, String classNames) {
        String markup = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "");
        String[] allowed = classNames.split("\\|");
        Matcher element = Pattern.compile(
                "(?is)<(?:div|p|img)\\b[^>]*>")
                .matcher(markup);
        while (element.find()) {
            String tag = element.group();
            Matcher classAttribute = Pattern.compile(
                    "(?is)\\bclass\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']")
                    .matcher(tag);
            if (!classAttribute.find()) continue;
            boolean matches = false;
            for (String token : classAttribute.group(1).trim().split("\\s+")) {
                for (String wanted : allowed) {
                    if (token.equalsIgnoreCase(wanted) &&
                            (!wanted.equalsIgnoreCase("banner") || tag.matches("(?is)<img\\b.*"))) {
                        matches = true;
                        break;
                    }
                }
                if (matches) break;
            }
            if (!matches) continue;
            Matcher background = Pattern.compile(
                    "(?is)background(?:-image)?\\s*:\\s*url\\(\\s*[\\\"']?([^\\\"')\\s]+)")
                    .matcher(tag);
            if (background.find()) {
                String url = absoluteUrl(pageUrl, background.group(1));
                if (url != null) return url;
            }
            Matcher source = Pattern.compile(
                    "(?is)(?:src|data-src)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
                    .matcher(tag);
            if (source.find()) {
                String url = absoluteUrl(pageUrl, source.group(1));
                if (url != null) return url;
            }
        }
        return null;
    }

    private static String stripHtml(String value) {
        if (value == null) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ").trim();
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.startsWith("data:")) return null;
        try {
            URL resolved = new URL(new URL(base), raw.trim().replace(" ", "%20"));
            return resolved.toURI().toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String origin(String url) {
        try {
            URL parsed = new URL(url);
            return parsed.getProtocol() + "://" + parsed.getAuthority();
        } catch (Exception ignored) { return url; }
    }
}






