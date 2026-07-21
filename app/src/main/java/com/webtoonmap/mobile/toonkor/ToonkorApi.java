package com.webtoonmap.mobile.toonkor;

import android.net.Uri;
import android.text.Html;
import android.util.Base64;

import com.webtoonmap.mobile.joatoon.JoatoonApi;
import com.webtoonmap.mobile.network.NetworkRetry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToonkorApi {
    public static final String KEY_PREFIX = "toonkor_";

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

    private ToonkorApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[\\p{L}\\p{N}_.-]+");
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
                    "웹툰", "애니", "주소안내", "단행본", "망가", "포토툰",
                    "코사이트", "토토보증업체", "bbs", "skin", "viewer", "data",
                    "img", "images", "bann"
            };
            for (String value : reserved) if (value.equalsIgnoreCase(name)) return null;
            return path;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static SeriesInfo fetchSeriesInfo(String pageUrl, String cookie,
                                             ToonkorMetadataStore.Entry cached)
            throws Exception {
        String html = getText(pageUrl, origin(pageUrl) + "/", cookie);
        String title = stripHtml(firstGroup(html,
                "(?is)<td\\b[^>]*class=[\\\"'][^\\\"']*bt_title[^\\\"']*[\\\"'][^>]*>(.*?)</td>"));
        if (title.isEmpty()) title = stripHtml(findMeta(html, "title"));
        if (title.isEmpty()) title = stripHtml(tagText(html, "title"));
        if (title.isEmpty() && cached != null) title = cached.title;
        if (title.isEmpty()) title = "툰코 웹툰";

        String description = stripHtml(firstGroup(html,
                "(?is)<td\\b[^>]*class=[\\\"'][^\\\"']*bt_over[^\\\"']*[\\\"'][^>]*>(.*?)</td>"));
        if (description.isEmpty() && cached != null) description = cached.description;
        if (description.isEmpty()) description = stripHtml(findMeta(html, "description"));

        String thumbnail = null;
        String thumbBlock = firstGroup(html,
                "(?is)<td\\b[^>]*class=[\\\"'][^\\\"']*bt_thumb[^\\\"']*[\\\"'][^>]*>(.*?)</td>");
        String thumbTag = firstGroup(thumbBlock, "(?is)<img\\b[^>]*>");
        if (!thumbTag.isEmpty()) {
            thumbnail = absoluteUrl(pageUrl,
                    firstGroup(thumbTag, "(?is)src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"));
        }
        if (thumbnail == null && cached != null) {
            thumbnail = absoluteUrl(pageUrl, cached.thumbnailUrl);
        }

        String tags = stripHtml(firstGroup(html,
                "(?is)<div\\b[^>]*class=[\\\"'][^\\\"']*toon_gen[^\\\"']*[\\\"'][^>]*>(.*?)</div>"));
        if (tags.isEmpty() && cached != null) tags = cached.tags;
        tags = normalizeTags(tags);

        Map<Integer, EpisodeMeta> episodeMap = new TreeMap<>();
        Matcher cells = Pattern.compile("(?is)<td\\b([^>]*)>(.*?)</td>").matcher(html);
        while (cells.find()) {
            String attributes = cells.group(1);
            if (!Pattern.compile("(?is)name\\s*=\\s*[\\\"']view_list[\\\"']")
                    .matcher(attributes).find()) continue;
            String relative = firstGroup(attributes,
                    "(?is)data-role\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
                    .replace("&amp;", "&");
            if (relative.isEmpty()) continue;
            String url = absoluteUrl(pageUrl, relative);
            if (url == null) continue;
            int number = episodeNumber(Uri.decode(relative));
            if (number <= 0) continue;
            String episodeTitle = stripHtml(firstGroup(attributes,
                    "(?is)alt\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"));
            if (episodeTitle.isEmpty()) episodeTitle = stripHtml(cells.group(2));
            if (episodeTitle.isEmpty() || episodeTitle.matches("\\d{4}-\\d{2}-\\d{2}")) {
                episodeTitle = title + " " + number + "화";
            }
            if (episodeTitle.length() > 120) episodeTitle = episodeTitle.substring(0, 120);
            episodeMap.put(number, new EpisodeMeta(number, episodeTitle, url));
        }
        if (episodeMap.isEmpty()) throw new IOException("툰코 회차 목록을 찾지 못했습니다.");
        return new SeriesInfo(title, description, thumbnail, tags, pageUrl,
                new ArrayList<>(episodeMap.values()));
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie)
            throws Exception {
        String html = getText(episodeUrl, episodeUrl, cookie);
        String encoded = firstGroup(html,
                "(?is)var\\s+toon_img\\s*=\\s*[\\\"']([A-Za-z0-9+/=\\s]+)[\\\"']\\s*;");
        String imageHtml = "";
        if (!encoded.isEmpty()) {
            try {
                byte[] decoded = Base64.decode(encoded.replaceAll("\\s+", ""), Base64.DEFAULT);
                imageHtml = new String(decoded, StandardCharsets.UTF_8);
            } catch (Exception error) {
                throw new IOException("툰코 이미지 정보 해제 실패", error);
            }
        }
        if (imageHtml.isEmpty()) {
            imageHtml = firstGroup(html,
                    "(?is)<div\\b[^>]*id=[\\\"']toon_img[\\\"'][^>]*>(.*?)</div>");
        }
        if (imageHtml.isEmpty()) throw new IOException("툰코 이미지 정보를 찾지 못했습니다.");

        LinkedHashSet<String> images = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?is)<img\\b[^>]*>").matcher(imageHtml);
        while (matcher.find()) {
            String tag = matcher.group();
            String raw = firstGroup(tag,
                    "(?is)(?:data-original|data-src|src)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            if (raw.isEmpty() || raw.startsWith("data:")) continue;
            String url = absoluteUrl(episodeUrl, raw.replace("&amp;", "&"));
            if (url != null) images.add(url);
        }
        if (images.isEmpty()) throw new IOException("툰코 회차 이미지를 찾지 못했습니다.");
        return new ArrayList<>(images);
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> downloadOnceWithJpgFallback(url, referer, cookie));
    }

    private static byte[] downloadOnceWithJpgFallback(String url, String referer, String cookie)
            throws Exception {
        try {
            return downloadOnce(url, referer, cookie);
        } catch (IOException original) {
            String clean = url.replaceFirst("[?#].*$", "");
            if (clean.toLowerCase(Locale.US).endsWith(".jpg")) throw original;
            int dot = clean.lastIndexOf('.');
            if (dot <= clean.lastIndexOf('/')) throw original;
            String suffix = url.substring(clean.length());
            return downloadOnce(clean.substring(0, dot) + ".jpg" + suffix, referer, cookie);
        }
    }

    private static byte[] downloadOnce(String url, String referer, String cookie) throws Exception {
        HttpURLConnection conn = open(url, referer, cookie, "image/*,*/*;q=0.8");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("툰코 이미지 HTTP " + code);
            return readAll(conn.getInputStream(), "툰코 이미지 다운로드 중단");
        } finally {
            NetworkRetry.release(conn);
            conn.disconnect();
        }
    }

    private static String getText(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie,
                    "text/html,application/xhtml+xml,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("툰코 응답 오류 " + code);
                return new String(readAll(conn.getInputStream(), "툰코 요청 중단"),
                        StandardCharsets.UTF_8);
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        });
    }

    private static HttpURLConnection open(String url, String referer, String cookie, String accept)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(0);
        conn.setReadTimeout(0);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", JoatoonApi.USER_AGENT);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        if (referer != null) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return NetworkRetry.track(conn);
    }

    private static byte[] readAll(InputStream input, String interruptedMessage) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException(interruptedMessage);
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static int episodeNumber(String value) {
        Matcher matcher = Pattern.compile("(?:_|\\s)(\\d{1,5})화(?:\\.html)?",
                Pattern.CASE_INSENSITIVE).matcher(value == null ? "" : value);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String normalizeTags(String value) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String part : (value == null ? "" : value).split("[/,|]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && tag.length() <= 30) tags.add(tag);
        }
        return String.join(", ", tags);
    }

    private static String findMeta(String html, String key) {
        Matcher matcher = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            String name = firstGroup(tag,
                    "(?is)(?:name|property)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            if (!key.equalsIgnoreCase(name)) continue;
            return firstGroup(tag,
                    "(?is)content\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
        }
        return "";
    }

    private static String tagText(String html, String tag) {
        return firstGroup(html, "(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">");
    }

    private static String firstGroup(String input, String pattern) {
        if (input == null) return "";
        Matcher matcher = Pattern.compile(pattern).matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    @SuppressWarnings("deprecation")
    private static String stripHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return new URL(new URL(base), raw.trim()).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String origin(String url) {
        try {
            URL parsed = new URL(url);
            return parsed.getProtocol() + "://" + parsed.getAuthority();
        } catch (Exception ignored) {
            return url == null ? "" : url.replaceAll("/+$", "");
        }
    }
}
