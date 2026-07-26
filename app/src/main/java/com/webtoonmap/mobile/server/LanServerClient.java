package com.webtoonmap.mobile.server;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LanServerClient {
    public interface Progress {
        void onProgress(long sentOrReceived, long total);
    }

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int UPLOAD_READ_TIMEOUT_MS = 30 * 60 * 1000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 30 * 60 * 1000;

    private LanServerClient() { }

    public static JSONObject health(String baseUrl) throws IOException {
        HttpURLConnection connection = open(baseUrl + "/api/health", "GET", CONNECT_TIMEOUT_MS,
                CONNECT_TIMEOUT_MS);
        try {
            int code = connection.getResponseCode();
            String body = readBody(connection);
            if (code < 200 || code >= 300) {
                throw new IOException("서버 응답 오류 (" + code + ")");
            }
            JSONObject json = parseObject(body);
            boolean ok = json.optBoolean("ok", false)
                    || "webtoonmap-lan".equals(json.optString("service"));
            if (!ok) throw new IOException("웹툰여지도 서버가 아닙니다.");
            return json;
        } finally {
            connection.disconnect();
        }
    }

    public static boolean isReachable(String baseUrl) {
        try {
            health(baseUrl);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static List<LanServerItem> listWebtoons(String baseUrl) throws IOException {
        HttpURLConnection connection = open(baseUrl + "/api/webtoons", "GET", CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS);
        try {
            int code = connection.getResponseCode();
            String body = readBody(connection);
            if (code < 200 || code >= 300) {
                throw new IOException(errorMessage(body, "목록을 불러오지 못했습니다. (" + code + ")"));
            }
            JSONObject json = parseObject(body);
            JSONArray items = json.optJSONArray("items");
            List<LanServerItem> result = new ArrayList<>();
            if (items == null) return result;
            for (int i = 0; i < items.length(); i++) {
                LanServerItem item = LanServerItem.fromJson(items.optJSONObject(i));
                if (item != null) result.add(item);
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    public static LanServerItem upload(Context context, String baseUrl, File file, Progress progress)
            throws IOException {
        if (file == null || !file.isFile()) throw new IOException("업로드할 파일이 없습니다.");
        String boundary = "----WebtoonMap" + System.currentTimeMillis();
        byte[] prefix = buildMultipartPrefix(boundary, file.getName(), context);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long total = prefix.length + file.length() + suffix.length;

        HttpURLConnection connection = open(baseUrl + "/api/upload", "POST", CONNECT_TIMEOUT_MS,
                UPLOAD_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("X-Device-Name", deviceName(context));
        connection.setFixedLengthStreamingMode(total);
        try {
            try (OutputStream raw = connection.getOutputStream();
                 BufferedOutputStream out = new BufferedOutputStream(raw, 64 * 1024);
                 InputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
                out.write(prefix);
                long sent = prefix.length;
                if (progress != null) progress.onProgress(sent, total);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    sent += read;
                    if (progress != null) progress.onProgress(sent, total);
                }
                out.write(suffix);
                sent += suffix.length;
                if (progress != null) progress.onProgress(sent, total);
                out.flush();
            }
            int code = connection.getResponseCode();
            String body = readBody(connection);
            if (code < 200 || code >= 300) {
                throw new IOException(errorMessage(body, "서버 업로드에 실패했습니다. (" + code + ")"));
            }
            JSONObject json = parseObject(body);
            LanServerItem item = LanServerItem.fromJson(json.optJSONObject("item"));
            if (item == null) throw new IOException("서버 응답이 올바르지 않습니다.");
            return item;
        } finally {
            connection.disconnect();
        }
    }

    public static File download(String baseUrl, String id, File destination, Progress progress)
            throws IOException {
        if (id == null || id.trim().isEmpty()) throw new IOException("다운로드 ID가 없습니다.");
        if (destination == null) throw new IOException("저장 위치가 없습니다.");
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("임시 폴더를 만들 수 없습니다.");
        }
        File pending = new File(destination.getAbsolutePath() + ".part");
        if (pending.exists() && !pending.delete()) {
            throw new IOException("이전 다운로드 임시 파일을 지울 수 없습니다.");
        }

        HttpURLConnection connection = open(
                baseUrl + "/api/webtoons/" + encodePath(id) + "/download",
                "GET", CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readBody(connection);
                throw new IOException(errorMessage(body, "다운로드에 실패했습니다. (" + code + ")"));
            }
            long total = connection.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(pending), 64 * 1024)) {
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    received += read;
                    if (progress != null) progress.onProgress(received, total > 0 ? total : received);
                }
                out.flush();
            }
            if (destination.exists() && !destination.delete()) {
                pending.delete();
                throw new IOException("기존 임시 파일을 교체할 수 없습니다.");
            }
            if (!pending.renameTo(destination)) {
                // fallback copy
                try (InputStream in = new FileInputStream(pending);
                     OutputStream out = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                }
                pending.delete();
            }
            return destination;
        } catch (Exception error) {
            pending.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error.getMessage() == null ? "다운로드 실패" : error.getMessage(), error);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] buildMultipartPrefix(String boundary, String filename, Context context) {
        String safeName = filename == null ? "package.wtoon.zip" : filename.replace("\"", "'");
        String body =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"device\"\r\n\r\n" +
                        deviceName(context) + "\r\n" +
                        "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n" +
                        "Content-Type: application/zip\r\n\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String deviceName(Context context) {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        return "Android " + model;
    }

    private static HttpURLConnection open(String url, String method, int connectTimeout,
                                          int readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/zip, */*");
        connection.setRequestProperty("User-Agent", "WebtoonMap-Android-LAN/1.0");
        return connection;
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream stream;
        try {
            stream = connection.getInputStream();
        } catch (IOException error) {
            stream = connection.getErrorStream();
            if (stream == null) throw error;
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONObject parseObject(String body) throws IOException {
        try {
            return new JSONObject(body == null ? "{}" : body);
        } catch (Exception error) {
            throw new IOException("서버 응답을 해석할 수 없습니다.");
        }
    }

    private static String errorMessage(String body, String fallback) {
        try {
            JSONObject json = new JSONObject(body == null ? "{}" : body);
            String error = json.optString("error", "").trim();
            if (!error.isEmpty()) return error;
        } catch (Exception ignored) { }
        return fallback;
    }

    private static String encodePath(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception ignored) {
            return value;
        }
    }

    public static String percent(long current, long total) {
        if (total <= 0) return LanServerItem.formatBytes(current);
        int value = (int) Math.min(100, Math.max(0, (current * 100) / total));
        return String.format(Locale.US, "%d%% · %s / %s", value,
                LanServerItem.formatBytes(current), LanServerItem.formatBytes(total));
    }
}
