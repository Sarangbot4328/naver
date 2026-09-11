package com.webtoonmap.mobile.network;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SiteAddressUpdater {
    public static final int MAX_INCREMENTS = 20;
    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_HTML_BYTES = 128 * 1024;

    public interface Progress {
        void checking(String source, String candidate, int offset);
        default void verificationBlocked(String source, String current) { }
        default void fallbackFinished(String source, String address) { }
    }

    static final class VerificationBlockedException extends java.io.IOException {
        VerificationBlockedException() { super("자동 접속 확인이 차단되었습니다."); }
    }

    interface Probe {
        String check(String source, String candidate) throws Exception;
    }

    interface BlacktoonFallback {
        String fetch() throws Exception;
    }

    private SiteAddressUpdater() { }

    /** Each site scans its own numbers in order; independent sites run concurrently. */
    public static Map<String, String> fetch(Map<String, String> currentUrls, Progress progress)
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(5);
        Map<String, Future<String>> pending = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> entry : currentUrls.entrySet()) {
                String source = entry.getKey();
                String current = entry.getValue();
                pending.put(source, workers.submit(() ->
                        findReachable(source, current, progress, SiteAddressUpdater::probe)));
            }
            Map<String, String> found = new LinkedHashMap<>();
            for (Map.Entry<String, Future<String>> entry : pending.entrySet()) {
                String url = entry.getValue().get();
                if (url != null) found.put(entry.getKey(), url);
            }
            return found;
        } finally {
            for (Future<String> future : pending.values()) future.cancel(true);
            workers.shutdownNow();
        }
    }

    static String findReachable(String source, String current, Progress progress, Probe probe)
            throws InterruptedException {
        return findReachable(source, current, progress, probe, SiteAddressUpdater::fetchBlacktoonAddress);
    }

    static String findReachable(String source, String current, Progress progress, Probe probe,
                                BlacktoonFallback fallback) throws InterruptedException {
        NumberedAddress start = parse(source, current);
        if (start == null) return null;
        // Current URL plus +1 ... +20, never restarting from a bundled default.
        for (int offset = 0; offset <= MAX_INCREMENTS; offset++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String candidate = start.url(offset);
            if (progress != null) progress.checking(source, candidate, offset);
            try {
                String result = probe.check(source, candidate);
                NumberedAddress finalAddress = parse(source, result);
                if (finalAddress != null && finalAddress.prefix.equals(start.prefix) &&
                        finalAddress.number == start.number + offset) {
                    return finalAddress.url(0);
                }
            } catch (VerificationBlockedException blocked) {
                // An access refusal does not establish that the user's saved address is obsolete.
                // Use the address listing only when the saved Blacktoon address refuses verification.
                if (offset == 0) {
                    if (progress != null) progress.verificationBlocked(source, current);
                    if ("blacktoon".equals(source)) {
                        String listed = null;
                        try {
                            NumberedAddress address = parse(source, fallback.fetch());
                            if (address != null) listed = address.url(0);
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                            throw cancelled;
                        } catch (Exception ignored) { }
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        if (progress != null) progress.fallbackFinished(source, listed);
                        if (listed != null) return listed;
                    }
                    return current;
                }
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw cancelled;
            } catch (Exception ignored) {
                // Failure belongs to this candidate, not the other sites.
            }
        }
        return null;
    }

    private static String fetchBlacktoonAddress() throws Exception {
        Document document = Jsoup.connect("https://majorlink2.com")
                .userAgent(ConnectionCompatibility.requestUserAgent())
                .timeout(15_000).maxBodySize(2 * 1024 * 1024).followRedirects(true).get();
        return parseBlacktoonAddress(document);
    }

    static String parseBlacktoonAddress(Document document) {
        for (Element anchor : document.select("a[href]")) {
            String name = anchor.attr("title").trim();
            if (name.isEmpty()) name = anchor.text();
            name = name.replaceAll("\\s+", "");
            if (!"블랙툰".equals(name) && !"blacktoon".equalsIgnoreCase(name)) continue;
            NumberedAddress address = parse("blacktoon", anchor.absUrl("href"));
            if (address != null) return address.url(0);
        }
        return null;
    }

    static String probe(String source, String candidate) throws Exception {
        String target = candidate;
        for (int redirect = 0; redirect <= 3; redirect++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", ConnectionCompatibility.requestUserAgent());
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7");
            NetworkRetry.track(connection);
            try {
                int code = connection.getResponseCode();
                if ("blacktoon".equals(source) && code == 403) {
                    throw new VerificationBlockedException();
                }
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    String next = resolveRedirect(target, location);
                    NumberedAddress nextAddress = parse(source, next);
                    NumberedAddress firstAddress = parse(source, candidate);
                    if (nextAddress == null || firstAddress == null ||
                            !nextAddress.prefix.equals(firstAddress.prefix) ||
                            nextAddress.number != firstAddress.number) return null;
                    target = next;
                    continue;
                }
                if (code != 200 && code != 403 && code != 503) return null;
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String html = readHtml(stream, connection.getContentType());
                boolean challenge = "challenge".equalsIgnoreCase(connection.getHeaderField("cf-mitigated"));
                if (!isSitePage(source, code, html, challenge)) return null;
                NumberedAddress address = parse(source, target);
                return address == null ? null : address.url(0);
            } finally {
                NetworkRetry.release(connection);
                connection.disconnect();
            }
        }
        return null;
    }

    private static String readHtml(InputStream stream, String contentType) throws Exception {
        if (stream == null) return "";
        Charset charset = StandardCharsets.UTF_8;
        if (contentType != null) {
            Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^;\\s\"']+)").matcher(contentType);
            if (matcher.find()) {
                try { charset = Charset.forName(matcher.group(1)); } catch (Exception ignored) { }
            }
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (output.size() < MAX_HTML_BYTES) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int count = input.read(buffer, 0, Math.min(buffer.length, MAX_HTML_BYTES - output.size()));
                if (count < 0) break;
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), charset);
        }
    }

    static String resolveRedirect(String base, String location) throws Exception {
        // HttpURLConnection exposes raw UTF-8 Location headers as Latin-1 on some runtimes.
        boolean latin1 = true;
        for (int i = 0; i < location.length(); i++) {
            if (location.charAt(i) > 255) { latin1 = false; break; }
        }
        if (latin1) {
            String decoded = new String(location.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            if (decoded.indexOf('\uFFFD') < 0) location = decoded;
        }
        return new URL(new URL(base), location.replace(" ", "%20")).toURI().toASCIIString();
    }

    /** A 200 parking/old-address page and a generic Cloudflare block are not a match. */
    static boolean isSitePage(String source, int code, String html, boolean challengeHeader) {
        String lower = html == null ? "" : html.toLowerCase(Locale.ROOT);
        if (code != 200 && code != 403 && code != 503) return false;
        if (lower.contains("sorry, you have been blocked") ||
                lower.contains("iis windows server") || lower.contains("domain is for sale")) return false;
        if (challengeHeader || (lower.contains("challenge-platform") &&
                (lower.contains("_cf_chl_opt") || lower.contains("cf-chl-")))) return true;
        if (code != 200) return false;
        switch (source) {
            case "blacktoon":
                return lower.contains("/data/toonlist/") ||
                        lower.contains("toon_content_imgs") ||
                        Pattern.compile("/webtoon/[0-9]+\\.html").matcher(lower).find();
            case "wolfdot":
                return Pattern.compile("/(?:list|cl)\\?toon=[0-9]+").matcher(lower).find() ||
                        lower.contains("vimg-area");
            case "toonkor":
                return (lower.contains("툰코") || lower.contains("toonkor")) &&
                        (lower.contains("/assets/index-") || lower.contains("section-item") ||
                                lower.contains("bt_webtoon") || lower.contains("bt_title"));
            case "funbe":
                return (lower.contains("펀비") || lower.contains("funbe")) &&
                        (lower.contains("bt_webtoon") || lower.contains("section-item") ||
                                lower.contains("bt_title"));
            case "newtoki":
                return lower.contains("serial-list") || lower.contains("mana_img") ||
                        Pattern.compile("bo_table=(?:webtoon|comic|manga|fafa)[a-z0-9]*").matcher(lower).find();
            default:
                return false;
        }
    }

    private static final class NumberedAddress {
        final String www, prefix;
        final int number;
        NumberedAddress(String www, String prefix, int number) {
            this.www = www;
            this.prefix = prefix;
            this.number = number;
        }
        String url(int offset) { return "https://" + www + prefix + (number + offset) + ".com"; }
    }

    private static NumberedAddress parse(String source, String url) {
        if (url == null) return null;
        String prefixes;
        switch (source) {
            case "blacktoon": prefixes = "blacktoon"; break;
            case "wolfdot": prefixes = "wfwf"; break;
            case "toonkor": prefixes = "toonkor|tkor"; break;
            case "funbe": prefixes = "funbe"; break;
            case "newtoki": prefixes = "newto|newtoki"; break;
            default: return null;
        }
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            Matcher matcher = Pattern.compile("^(www\\.)?(" + prefixes + ")([0-9]{1,6})\\.com$",
                    Pattern.CASE_INSENSITIVE).matcher(uri.getHost());
            if (!matcher.matches()) return null;
            int number = Integer.parseInt(matcher.group(3));
            if (number <= 0) return null;
            return new NumberedAddress(matcher.group(1) == null ? "" : "www.",
                    matcher.group(2).toLowerCase(Locale.ROOT), number);
        } catch (Exception ignored) {
            return null;
        }
    }
}
