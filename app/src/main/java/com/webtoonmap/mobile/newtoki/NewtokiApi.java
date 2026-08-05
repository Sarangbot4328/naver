package com.webtoonmap.mobile.newtoki;

import android.net.Uri;
import android.util.Base64;

import com.webtoonmap.mobile.network.ConnectionCompatibility;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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

public final class NewtokiApi {
    public static final String KEY_PREFIX = "newtoki_";
    private static final int MAX_SERIES_PAGES = 200;

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
            this.title = title;
            this.url = url;
            this.number = number;
        }
    }

    private NewtokiApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[0-9a-f]+");
    }

    public static boolean isSeriesTable(String table) {
        return table != null && table.matches(
                "(?i)^(?:fafa|fafa19|fafabl|fafaend|fafaend19|fafaendbl)$");
    }

    public static SeriesInfo fetchSeriesInfo(String pageUrl, String cookie) throws Exception {
        String firstPageUrl = seriesPageUrl(pageUrl, 1);
        Document first = fetchDocument(firstPageUrl, origin(firstPageUrl), cookie);
        if (first.select(".serial-list a.item-subject[href]").isEmpty()) {
            throw new IOException("뉴토끼 작품의 연재 목록을 찾지 못했습니다. 작품 소개 페이지에서 다시 시도해 주세요.");
        }

        String title = meta(first, "og:title");
        if (title.isEmpty()) title = first.title();
        title = title.replaceFirst("(?i)\\s*[-|]\\s*(?:뉴토끼|NEWTOKI).*$", "").trim();
        if (title.isEmpty()) title = "뉴토끼 웹툰";
        String description = meta(first, "og:description");
        Element cover = first.selectFirst(
                ".view-content1 img[itemprop=image]," +
                ".view-content1 img[src*='/cover/']," +
                "a.view_image img[src*='/cover/'],img.img-tag[src*='/cover/']");
        String coverValue = cover == null ? null : firstNonEmpty(
                cover.attr("content"), cover.attr("data-src"),
                cover.attr("data-original"), cover.attr("src"));
        String thumbnail = absoluteUrl(firstPageUrl, coverValue);
        if (thumbnail == null) thumbnail = absoluteUrl(firstPageUrl, meta(first, "og:image"));

        LinkedHashSet<String> tagSet = new LinkedHashSet<>();
        String keywords = meta(first, "keywords");
        for (String part : keywords.split("[,/]") ) {
            String value = part.trim();
            if (!value.isEmpty() && value.length() <= 30) tagSet.add(value);
        }
        String table = queryParameter(firstPageUrl, "bo_table");
        String normalizedTable = table == null ? "" : table.toLowerCase();
        tagSet.add(normalizedTable.startsWith("fafaend") ? "완결웹툰" : "연재웹툰");
        if (normalizedTable.endsWith("19")) tagSet.add("성인");
        if (normalizedTable.endsWith("bl")) tagSet.add("BL/GL");

        Map<String, RawEpisode> unique = new LinkedHashMap<>();
        collectEpisodes(first, unique);
        int pageCount = pageCount(first, firstPageUrl);
        for (int page = 2; page <= pageCount && page <= MAX_SERIES_PAGES; page++) {
            checkCancelled("뉴토끼 회차 목록 수집 중단");
            String nextUrl = seriesPageUrl(firstPageUrl, page);
            Document next = fetchDocument(nextUrl, firstPageUrl, cookie);
            pageCount = Math.max(pageCount, pageCount(next, nextUrl));
            collectEpisodes(next, unique);
        }

        List<RawEpisode> raw = new ArrayList<>(unique.values());
        Collections.reverse(raw);
        LinkedHashSet<Integer> used = new LinkedHashSet<>();
        for (RawEpisode episode : raw) {
            if (episode.number != null) used.add(episode.number);
        }
        int fallback = 1;
        List<EpisodeMeta> episodes = new ArrayList<>();
        for (RawEpisode episode : raw) {
            int number;
            if (episode.number != null) {
                number = episode.number;
            } else {
                while (used.contains(fallback)) fallback++;
                number = fallback++;
                used.add(number);
            }
            String episodeTitle = episode.title.length() > 120
                    ? episode.title.substring(0, 120) : episode.title;
            episodes.add(new EpisodeMeta(number, episodeTitle, episode.url));
        }
        episodes.sort((a, b) -> Integer.compare(a.number, b.number));
        return new SeriesInfo(title, description, thumbnail, String.join(", ", tagSet),
                firstPageUrl, episodes);
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie)
            throws Exception {
        Document document = fetchDocument(episodeUrl, episodeUrl, cookie);
        LinkedHashSet<String> images = new LinkedHashSet<>();
        collectImages(document, "#mana_img", episodeUrl, images);
        if (images.isEmpty()) collectEncodedManaImages(document, episodeUrl, images);
        if (images.isEmpty()) {
            for (Element anchor : document.select("#mana_img a.view_image[href*='img=']")) {
                String url = queryParameter(anchor.attr("abs:href"), "img");
                url = absoluteUrl(episodeUrl, url);
                if (isImageUrl(url)) images.add(url);
            }
        }
        if (images.isEmpty()) throw new IOException("뉴토끼 회차 이미지를 찾지 못했습니다.");
        return new ArrayList<>(images);
    }

    private static void collectEncodedManaImages(Document document, String episodeUrl,
                                                  LinkedHashSet<String> images) {
        Pattern pattern = Pattern.compile(
                "var\\s+mana_img\\s*=\\s*(['\\\"])([A-Za-z0-9+/=\\r\\n]+)\\1");
        for (Element script : document.select("script:not([src])")) {
            Matcher matcher = pattern.matcher(script.data());
            if (!matcher.find()) {
                matcher = pattern.matcher(script.html());
                if (!matcher.find()) continue;
            }
            try {
                byte[] decoded = Base64.decode(matcher.group(2), Base64.DEFAULT);
                Document fragment = Jsoup.parseBodyFragment(
                        new String(decoded, StandardCharsets.UTF_8), episodeUrl);
                collectImages(fragment, "", episodeUrl, images);
                if (!images.isEmpty()) return;
            } catch (Exception ignored) { }
        }
    }

    private static void collectImages(Document document, String rootSelector, String episodeUrl,
                                      LinkedHashSet<String> images) {
        String imageSelector = rootSelector.isEmpty() ? "img" : rootSelector + " img";
        for (Element image : document.select(imageSelector)) {
            String raw = firstNonEmpty(image.attr("data-src"), image.attr("data-original"),
                    image.attr("content"), image.attr("src"));
            String url = absoluteUrl(episodeUrl, raw);
            if (isImageUrl(url)) images.add(url);
        }
    }

    public static byte[] downloadBytes(String url, String referer, String cookie)
            throws Exception {
        HttpURLConnection connection = open(url, referer, cookie, "image/*,*/*;q=0.8");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("뉴토끼 이미지 HTTP " + code);
            }
            return readAll(connection.getInputStream(), "뉴토끼 이미지 다운로드 중단");
        } finally {
            connection.disconnect();
        }
    }

    public static byte[] downloadThumbnailBytes(String url, String pageUrl, String cookie)
            throws Exception {
        String originReferer = origin(pageUrl);
        if (!originReferer.endsWith("/")) originReferer += "/";
        String[] referers = {originReferer, pageUrl, null};
        boolean sameHost = sameHost(url, pageUrl);
        String[] cookies = sameHost
                ? new String[]{cookie, null} : new String[]{null, cookie};
        Exception lastError = null;
        for (String referer : referers) {
            for (String requestCookie : cookies) {
                HttpURLConnection connection = null;
                try {
                    connection = open(url, referer, requestCookie,
                            "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                    connection.setRequestProperty("Cache-Control", "no-cache");
                    connection.setRequestProperty("Pragma", "no-cache");
                    connection.setRequestProperty("Sec-Fetch-Dest", "image");
                    connection.setRequestProperty("Sec-Fetch-Mode", "no-cors");
                    connection.setRequestProperty("Sec-Fetch-Site",
                            sameHost ? "same-origin" : "cross-site");
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new IOException("뉴토끼 표지 HTTP " + code);
                    }
                    byte[] bytes = readAll(connection.getInputStream(),
                            "뉴토끼 표지 다운로드 중단");
                    if (!isImageBytes(bytes)) {
                        throw new IOException(
                                "뉴토끼 표지 CDN이 이미지가 아닌 응답을 반환했습니다.");
                    }
                    return bytes;
                } catch (Exception error) {
                    lastError = error;
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }
        if (lastError != null) throw lastError;
        throw new IOException("뉴토끼 표지를 다운로드하지 못했습니다.");
    }

    private static Document fetchDocument(String url, String referer, String cookie)
            throws Exception {
        HttpURLConnection connection = open(url, referer, cookie,
                "text/html,application/xhtml+xml,*/*;q=0.8");
        try {
            int code = connection.getResponseCode();
            if (code == 403 || code == 503) {
                throw new IOException("뉴토끼 접속 인증이 만료되었습니다. 뉴토끼 채널에서 작품 페이지를 다시 연 뒤 다운로드해 주세요.");
            }
            if (code < 200 || code >= 300) throw new IOException("뉴토끼 응답 오류 " + code);
            String html = new String(readAll(connection.getInputStream(), "뉴토끼 요청 중단"),
                    StandardCharsets.UTF_8);
            return Jsoup.parse(html, url);
        } finally {
            connection.disconnect();
        }
    }

    private static void collectEpisodes(Document document, Map<String, RawEpisode> unique) {
        for (Element anchor : document.select(".serial-list a.item-subject[href]")) {
            String url = anchor.attr("abs:href");
            String title = anchor.text().replaceAll("\\s+", " ").trim();
            if (url.isEmpty() || title.isEmpty()) continue;
            unique.putIfAbsent(url, new RawEpisode(title, url, episodeNumber(title)));
        }
    }

    private static int pageCount(Document document, String pageUrl) {
        int maximum = pageNumber(pageUrl);
        for (Element anchor : document.select("a[href]")) {
            maximum = Math.max(maximum, pageNumber(anchor.attr("abs:href")));
        }
        return Math.max(1, Math.min(maximum, MAX_SERIES_PAGES));
    }

    private static int pageNumber(String url) {
        String value = queryParameter(url, "spage");
        if (value == null || !value.matches("\\d+")) return 1;
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String seriesPageUrl(String pageUrl, int page) {
        String cleaned = pageUrl
                .replaceAll("([?&])(?:spage|device)=[^&#]*&", "$1")
                .replaceAll("([?&])(?:spage|device)=[^&#]*$", "")
                .replaceAll("[?&]$", "");
        if (page <= 1) return cleaned;
        return cleaned + (cleaned.contains("?") ? "&" : "?") + "spage=" + page;
    }

    private static Integer episodeNumber(String title) {
        Matcher matcher = Pattern.compile("(\\d{1,5})\\s*(?:화|회)").matcher(title);
        Integer result = null;
        while (matcher.find()) result = Integer.parseInt(matcher.group(1));
        return result != null && result > 0 ? result : null;
    }

    private static String meta(Document document, String key) {
        Element element = document.selectFirst("meta[property='" + key + "'],meta[name='" + key + "']");
        return element == null ? "" : element.attr("content").replaceAll("\\s+", " ").trim();
    }

    private static boolean isImageUrl(String url) {
        return url != null && url.matches("(?i)^https?://.+\\.(?:jpe?g|png|webp)(?:[?#].*)?$");
    }

    private static boolean isImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        boolean jpeg = (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 &&
                (bytes[2] & 0xff) == 0xff;
        boolean png = (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 &&
                bytes[2] == 0x4e && bytes[3] == 0x47;
        boolean gif = bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
        boolean webp = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' &&
                bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' &&
                bytes[10] == 'B' && bytes[11] == 'P';
        return jpeg || png || gif || webp;
    }

    private static boolean sameHost(String first, String second) {
        try {
            String firstHost = new URL(first).getHost();
            String secondHost = new URL(second).getHost();
            return firstHost != null && firstHost.equalsIgnoreCase(secondHost);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private static String queryParameter(String url, String name) {
        if (url == null) return null;
        try {
            return Uri.parse(url.replace("&amp;", "&")).getQueryParameter(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.startsWith("data:")) return null;
        try {
            return new URL(new URL(base), raw.trim().replace(" ", "%20"))
                    .toURI().toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static HttpURLConnection open(String url, String referer, String cookie,
                                           String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", ConnectionCompatibility.requestUserAgent());
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");
        if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    private static byte[] readAll(InputStream input, String interruptedMessage) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                checkCancelled(interruptedMessage);
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void checkCancelled(String message) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(message);
    }

    private static String origin(String url) {
        try {
            URL parsed = new URL(url);
            return parsed.getProtocol() + "://" + parsed.getAuthority();
        } catch (Exception ignored) {
            return url;
        }
    }
}
