package com.webtoonmap.mobile.marumaru;

import com.webtoonmap.mobile.network.ConnectionCompatibility;
import com.webtoonmap.mobile.network.NetworkRetry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public work metadata and the reader API used by Marumaru's own web client. */
public final class MarumaruApi {
    public static final String KEY_PREFIX = "marumaru_";
    private static final int REQUEST_ATTEMPTS = 3;
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;

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

    private static final class HttpError extends IOException {
        final int status;
        HttpError(int status) {
            super("마루마루 요청 HTTP " + status +
                    (status == 401 || status == 403 ? " · 사이트에서 접속 상태를 확인한 뒤 이어받기를 눌러 주세요." : ""));
            this.status = status;
        }
    }

    private MarumaruApi() { }

    /** Use only for a page download failure, never for metadata/episode requests. */
    public static boolean isMissingImage(Exception error) {
        return error instanceof HttpError && ((HttpError) error).status == 404;
    }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[0-9]+");
    }

    public static String seriesKey(String url) {
        String path = seriesPath(url);
        return path == null ? null : KEY_PREFIX + path.substring("/work/".length());
    }

    public static String seriesPath(String url) {
        if (url == null) return null;
        try {
            String path = new URI(url).getPath();
            Matcher matcher = Pattern.compile("^/(?:webtoon|manhwa|work)/([0-9]+)/?$")
                    .matcher(path == null ? "" : path);
            return matcher.matches() ? "/work/" + matcher.group(1) : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    public static SeriesInfo fetchSeriesInfo(String pageUrl, String cookie) throws Exception {
        String path = seriesPath(pageUrl);
        if (path == null) throw new IOException("마루마루 웹툰·만화 작품 페이지를 열어 주세요.");
        String id = path.substring("/work/".length());
        JSONObject data = getJson(origin(pageUrl) + "/api/works/" + id, pageUrl, cookie);
        JSONObject reader = null;
        String readerUrl = data.optString("episodesUrl", "");
        if (!readerUrl.isEmpty()) {
            readerUrl = absoluteUrl(pageUrl, readerUrl);
            readerEpisodeUrl(readerUrl, "1"); // Validate the reader route before requesting it.
            reader = getJson(readerUrl, pageUrl, null);
        }
        return parseSeries(pageUrl, data, reader);
    }

    static SeriesInfo parseSeries(String pageUrl, JSONObject data, JSONObject reader) throws Exception {
        JSONObject work = data.getJSONObject("work");
        String media = work.optString("mediaType");
        if (!"WEBTOON".equals(media) && !"MANHWA".equals(media)) {
            throw new IOException("마루마루 웹툰·만화만 이미지 다운로드를 지원합니다.");
        }
        String readerUrl = data.optString("episodesUrl", "");
        if (!readerUrl.isEmpty()) readerUrl = absoluteUrl(pageUrl, readerUrl);
        if (reader != null && !reader.optBoolean("ok", true)) {
            throw new IOException("마루마루 회차 목록을 불러오지 못했습니다.");
        }
        JSONArray list = (reader == null ? data : reader).getJSONArray("episodes");
        TreeMap<Integer, EpisodeMeta> episodes = new TreeMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject episode = list.getJSONObject(i);
            double rawNumber = episode.optDouble("number", 0);
            int number = episode.optInt("number", 0);
            String id = episode.optString("id");
            if (number <= 0 || rawNumber != number || !id.matches("[0-9]+") || episodes.containsKey(number)) {
                throw new IOException("마루마루 회차 번호가 잘못되었거나 중복되었습니다.");
            }
            String url = readerUrl.isEmpty() ? origin(pageUrl) + "/episode/" + id
                    : readerEpisodeUrl(readerUrl, id);
            episodes.put(number, new EpisodeMeta(number, episode.optString("title", number + "화"), url));
        }
        if (episodes.isEmpty()) throw new IOException("마루마루에 등록된 회차가 없습니다.");
        String description = reader == null ? "" : reader.optString("synopsis", "");
        if (description.isEmpty()) description = data.optString("synopsis", "");
        if (description.isEmpty()) description = work.optString("description", "");
        String cover = work.optString("coverUrl", "");
        if (cover.isEmpty()) cover = work.optString("thumbUrl", "");
        String tags = work.optString("genres", "").replaceAll("\\s*[,/，]\\s*", ",").trim();
        return new SeriesInfo(work.optString("title", "마루마루 작품"), description,
                cover.isEmpty() ? null : absoluteUrl(pageUrl, cover), tags, pageUrl,
                new ArrayList<>(episodes.values()));
    }

    static String readerEpisodeUrl(String readerUrl, String id) throws Exception {
        if (readerUrl == null || !id.matches("[0-9]+")) throw new IOException("마루마루 회차 주소가 잘못되었습니다.");
        URI reader = new URI(readerUrl);
        String path = reader.getPath();
        if (!"https".equalsIgnoreCase(reader.getScheme()) || reader.getHost() == null ||
                reader.getUserInfo() != null || path == null ||
                !path.matches("/api/v1/reader/[^/]+/works/[0-9]+")) {
            throw new IOException("마루마루 회차 목록 주소가 잘못되었습니다.");
        }
        return new URI(reader.getScheme(), null, reader.getHost(), reader.getPort(),
                path.replaceFirst("/works/[0-9]+$", "/episodes/" + id), reader.getQuery(), null).toASCIIString();
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie) throws Exception {
        URI uri = new URI(episodeUrl);
        String path = uri.getPath();
        String endpoint = episodeUrl;
        String requestCookie = null;
        if (path != null && path.matches("/episode/[0-9]+")) {
            endpoint = origin(episodeUrl) + "/api/episodes/" + path.substring("/episode/".length());
            requestCookie = cookie;
        } else if (path == null || !path.matches("/api/v1/reader/[^/]+/episodes/[0-9]+")) {
            throw new IOException("마루마루 회차 주소가 잘못되었습니다.");
        }
        return parseImages(episodeUrl, getJson(endpoint, episodeUrl, requestCookie));
    }

    static List<String> parseImages(String pageUrl, JSONObject data) throws Exception {
        if (!data.optBoolean("ok", true)) throw new IOException("마루마루 회차를 불러오지 못했습니다.");
        JSONObject item = data.optJSONObject("item");
        JSONArray body;
        if (item != null) {
            if (!item.optBoolean("ready", true)) {
                throw new IOException("마루마루 회차 이미지가 아직 준비되지 않았습니다. 나중에 이어받기를 눌러 주세요.");
            }
            body = item.getJSONArray("body");
        } else {
            body = new JSONArray(data.getString("bodyJson"));
        }
        List<String> images = new ArrayList<>();
        for (int i = 0; i < body.length(); i++) {
            JSONObject block = body.getJSONObject(i);
            String kind = block.optString("kind");
            if ("paid".equals(kind) || "processing".equals(kind)) {
                throw new IOException("마루마루 회차를 지금 내려받을 수 없습니다. 사이트에서 이용 가능 여부를 확인해 주세요.");
            }
            if ("image".equals(kind)) images.add(absoluteUrl(pageUrl, block.getString("url")));
        }
        if (images.isEmpty()) throw new IOException("마루마루 회차에 이미지가 없습니다. 나중에 이어받기를 눌러 주세요.");
        return images;
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        // CDN requests do not need the browser's site cookies.
        byte[] bytes = request(url, referer, null, "image/*,*/*;q=0.8", MAX_IMAGE_BYTES);
        if (!isImage(bytes)) throw new IOException("마루마루 이미지 대신 잘못된 응답을 받았습니다.");
        return bytes;
    }

    static boolean isImage(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        return (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) ||
                (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') ||
                (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
                        bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') ||
                (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F');
    }

    private static JSONObject getJson(String url, String referer, String cookie) throws Exception {
        return new JSONObject(new String(request(url, referer, cookie, "application/json", MAX_JSON_BYTES),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] request(String url, String referer, String cookie, String accept, int limit)
            throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= REQUEST_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("마루마루 요청 중단");
            try {
                return requestOnce(url, referer, cookie, accept, limit);
            } catch (IOException error) {
                if (Thread.currentThread().isInterrupted() ||
                        (error instanceof HttpError && ((HttpError) error).status != 429 &&
                                ((HttpError) error).status < 500)) throw error;
                last = error;
                if (attempt < REQUEST_ATTEMPTS) Thread.sleep(attempt * 1000L);
            }
        }
        throw last;
    }

    private static byte[] requestOnce(String url, String referer, String cookie, String accept, int limit)
            throws Exception {
        String target = absoluteUrl(url, url);
        HttpURLConnection connection = NetworkRetry.track((HttpURLConnection) new URL(target).openConnection());
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", ConnectionCompatibility.requestUserAgent());
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7");
        if ("application/json".equals(accept)) connection.setRequestProperty("X-Site", "marumaru");
        if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpError(code);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("마루마루 요청 중단");
                    if (output.size() + count > limit) throw new IOException("마루마루 응답 크기가 너무 큽니다.");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            NetworkRetry.release(connection);
            connection.disconnect();
        }
    }

    private static String absoluteUrl(String base, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new IOException("마루마루 이미지·데이터 주소가 비어 있습니다.");
        URI uri = new URI(base).resolve(value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("마루마루 이미지·데이터 주소가 올바르지 않습니다.");
        }
        return uri.toASCIIString();
    }

    private static String origin(String url) throws Exception {
        URI uri = new URI(url);
        return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString();
    }
}
