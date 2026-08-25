package com.webtoonmap.mobile.network;

import com.webtoonmap.mobile.storage.SourceSettings;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SiteAddressUpdater {
    public static final String SOURCE_URL = "https://majorlink2.com";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Pattern NEWTOKI_HOST =
            Pattern.compile("^(?:www\\.)?newto(\\d+)\\.com$", Pattern.CASE_INSENSITIVE);
    private static final int NEWTOKI_CONNECT_TIMEOUT_MS = 12_000;
    private static final int NEWTOKI_READ_TIMEOUT_MS = 12_000;
    private static final Map<String, String> NAME_TO_SOURCE;

    static {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        names.put("일일툰", SourceSettings.SOURCE_ILILTOON);
        names.put("11툰", SourceSettings.SOURCE_ILILTOON);
        names.put("블랙툰", SourceSettings.SOURCE_BLACKTOON);
        names.put("늑대닷컴", SourceSettings.SOURCE_WOLFDOT);
        names.put("툰코", SourceSettings.SOURCE_TOONKOR);
        names.put("펀비", SourceSettings.SOURCE_FUNBE);
        NAME_TO_SOURCE = Collections.unmodifiableMap(names);
    }

    private SiteAddressUpdater() { }

    public static Map<String, String> fetch() throws IOException {
        Document document = Jsoup.connect(SOURCE_URL)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .timeout(30_000)
                .followRedirects(true)
                .get();
        return parse(document);
    }

    /**
     * 현재 뉴토끼 번호부터 시작해 실패할 때마다 번호를 1씩 올려 검사합니다.
     * maxIncrements가 5이면 현재 주소와 다음 주소 5개, 총 6개 후보를 확인합니다.
     */
    public static String findReachableNewtokiUrl(String currentUrl, int maxIncrements) {
        String normalized = SourceSettings.normalizeUrl(currentUrl);
        Integer currentNumber = newtokiNumber(normalized);
        if (currentNumber == null) {
            normalized = SourceSettings.DEFAULT_NEWTOKI_URL;
            currentNumber = newtokiNumber(normalized);
        }
        if (currentNumber == null) return null;

        int attempts = Math.max(0, maxIncrements);
        for (int offset = 0; offset <= attempts; offset++) {
            String candidate = "https://newto" + (currentNumber + offset) + ".com";
            String reachable = probeNewtoki(candidate);
            if (reachable != null) return reachable;
        }
        return null;
    }

    private static String probeNewtoki(String candidate) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(candidate + "/").openConnection();
            connection.setConnectTimeout(NEWTOKI_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(NEWTOKI_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty(
                    "Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            connection.setRequestProperty(
                    "Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");

            int code = connection.getResponseCode();
            String finalUrl = SourceSettings.normalizeUrl(connection.getURL().toString());
            if (newtokiNumber(finalUrl) == null) return null;

            if (code >= 200 && code < 400) return finalUrl;

            // 정상 뉴토끼 주소도 Cloudflare 사용자 확인 중에는 403/503을 반환합니다.
            // CF-Ray가 있으면 사이트가 살아 있고 보안 확인 단계에 도달한 것입니다.
            String cfRay = connection.getHeaderField("CF-Ray");
            String server = connection.getHeaderField("Server");
            boolean cloudflare = (cfRay != null && !cfRay.trim().isEmpty()) ||
                    (server != null && server.toLowerCase(Locale.ROOT).contains("cloudflare"));
            if ((code == 403 || code == 503) && cloudflare) return finalUrl;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    private static Integer newtokiNumber(String url) {
        if (url == null) return null;
        try {
            String host = new URI(url).getHost();
            if (host == null) return null;
            Matcher matcher = NEWTOKI_HOST.matcher(host);
            if (!matcher.matches()) return null;
            int value = Integer.parseInt(matcher.group(1));
            return value > 0 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static Map<String, String> parse(Document document) throws IOException {
        Element section = findFreeWebtoonSection(document);
        if (section == null) {
            throw new IOException("주소 모음에서 무료웹툰 항목을 찾지 못했습니다.");
        }

        LinkedHashMap<String, String> addresses = new LinkedHashMap<>();
        for (Element anchor : section.select("a[href]")) {
            String rawName = anchor.attr("title").trim();
            if (rawName.isEmpty()) rawName = anchor.text().trim();
            String source = NAME_TO_SOURCE.get(normalizeName(rawName));
            if (source == null || addresses.containsKey(source)) continue;

            String rawUrl = anchor.attr("abs:href");
            if (rawUrl.isEmpty()) rawUrl = anchor.attr("href");
            String url = SourceSettings.normalizeUrl(rawUrl);
            if (url == null || isSourceWebsite(url)) continue;
            addresses.put(source, url);
        }

        if (addresses.isEmpty()) {
            throw new IOException("갱신할 웹툰 주소를 찾지 못했습니다.");
        }
        return Collections.unmodifiableMap(addresses);
    }

    private static Element findFreeWebtoonSection(Document document) {
        for (Element block : document.select("article, .main_link_box, dl")) {
            Element heading = block.selectFirst(".h2, h1, h2, h3, h4, dt");
            if (heading != null && "무료웹툰".equals(normalizeName(heading.text())) &&
                    !block.select("a[href]").isEmpty()) {
                return block;
            }
        }

        for (Element heading : document.select(".h2, h1, h2, h3, h4, p, dt")) {
            if (!"무료웹툰".equals(normalizeName(heading.text()))) continue;
            Element candidate = heading;
            for (int depth = 0; depth < 6 && candidate != null; depth++) {
                if (candidate.select("a[href][title]").size() >= 3) return candidate;
                candidate = candidate.parent();
            }
        }
        return null;
    }

    private static boolean isSourceWebsite(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("majorlink2.com/view/") ||
                lower.matches("https://(www\\.)?majorlink2\\.com");
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", "").trim();
    }
}
