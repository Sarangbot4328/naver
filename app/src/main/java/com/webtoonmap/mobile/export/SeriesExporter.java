package com.webtoonmap.mobile.export;

import android.content.Context;
import android.net.Uri;

import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SeriesExporter {
    public interface Progress {
        void onProgress(int current, int total);
    }

    private SeriesExporter() { }

    public static File export(Context context, SeriesItem series, List<EpisodeItem> episodes,
                              Progress progress) throws Exception {
        if (episodes.isEmpty()) throw new IOException("내보낼 완료 회차가 없습니다.");
        String name = safeName(series.title);
        File exportDir = new File(context.getCacheDir(), "exports");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("내보내기 임시 폴더를 만들 수 없습니다.");
        }
        File output = new File(exportDir, name + ".zip");
        File pending = new File(exportDir, name + ".zip.part");
        if (pending.exists() && !pending.delete()) {
            throw new IOException("이전 내보내기 임시 파일을 지울 수 없습니다.");
        }

        WebtoonStorage storage = new WebtoonStorage(context, series.storageUri);
        String root = name + "/";
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pending))) {
            zip.setLevel(Deflater.BEST_SPEED);
            zip.putNextEntry(new ZipEntry(root));
            zip.closeEntry();
            writeMetadata(zip, root, series, episodes);
            writeThumbnail(context, zip, root, series.thumbnailPath);
            for (int i = 0; i < episodes.size(); i++) {
                EpisodeItem episode = episodes.get(i);
                String filename = String.format(Locale.US, "%03d.zip", episode.number);
                try (InputStream in = storage.openEpisodeZip(series.titleId, episode.number)) {
                    writeEntry(zip, root + filename, in);
                }
                if (progress != null) progress.onProgress(i + 1, episodes.size());
            }
        } catch (Exception error) {
            pending.delete();
            throw error;
        }
        if (output.exists() && !output.delete()) {
            pending.delete();
            throw new IOException("기존 내보내기 파일을 교체할 수 없습니다.");
        }
        if (!pending.renameTo(output)) {
            pending.delete();
            throw new IOException("내보내기 파일을 완성할 수 없습니다.");
        }
        return output;
    }

    private static void writeMetadata(ZipOutputStream zip, String root, SeriesItem series,
                                      List<EpisodeItem> episodes) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("title", series.title);
        metadata.put("description", series.description);
        metadata.put("tags", series.tags);
        JSONArray episodeArray = new JSONArray();
        for (EpisodeItem episode : episodes) {
            JSONObject value = new JSONObject();
            value.put("number", episode.number);
            value.put("title", episode.title);
            value.put("imageCount", episode.imageCount);
            value.put("viewed", episode.viewed);
            episodeArray.put(value);
        }
        metadata.put("episodes", episodeArray);
        byte[] bytes = metadata.toString(2).getBytes(StandardCharsets.UTF_8);
        zip.putNextEntry(new ZipEntry(root + "metadata.json"));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeThumbnail(Context context, ZipOutputStream zip, String root,
                                       String path) throws Exception {
        if (path == null || path.isEmpty()) return;
        try (InputStream in = openPath(context, path)) {
            if (in != null) writeEntry(zip, root + "thumbnail.jpg", in);
        } catch (Exception ignored) { }
    }

    private static InputStream openPath(Context context, String path) throws Exception {
        if (path.startsWith("content://")) {
            return context.getContentResolver().openInputStream(Uri.parse(path));
        }
        File file = new File(path);
        return file.isFile() ? new java.io.FileInputStream(file) : null;
    }

    private static void writeEntry(ZipOutputStream zip, String name, InputStream input)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        copy(input, zip);
        zip.closeEntry();
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("내보내기가 중단되었습니다.");
            }
            output.write(buffer, 0, count);
        }
    }

    private static String safeName(String title) {
        String value = title == null ? "" : title
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim()
                .replaceAll("[. ]+$", "");
        if (value.isEmpty()) value = "웹툰";
        return value.length() > 80 ? value.substring(0, 80).trim() : value;
    }
}
