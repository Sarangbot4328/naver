package com.webtoonmap.mobile.export;

import android.content.Context;
import android.net.Uri;

import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.download.SourceJobStore;
import com.webtoonmap.mobile.joatoon.JoatoonApi;
import com.webtoonmap.mobile.storage.SourceSettings;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SeriesExporter {
    public interface Progress {
        void onProgress(int current, int total);
    }

    private static final String FORMAT = "wtoon-transfer";
    private static final int VERSION = 1;

    private SeriesExporter() { }

    public static File export(Context context, List<SeriesItem> seriesItems,
                              LibraryDatabase database, Progress progress) throws Exception {
        if (seriesItems == null || seriesItems.isEmpty()) {
            throw new IOException("내보낼 작품이 없습니다.");
        }

        int totalEpisodes = 0;
        for (SeriesItem series : seriesItems) {
            List<EpisodeItem> episodes = database.listEpisodes(series.titleId);
            if (episodes.isEmpty()) throw new IOException("‘" + series.title + "’에 내보낼 회차가 없습니다.");
            totalEpisodes += episodes.size();
        }

        File exportDir = new File(context.getCacheDir(), "exports");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("내보내기 임시 폴더를 만들 수 없습니다.");
        }
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String filename = "웹툰여지도_이전_" + seriesItems.size() + "작품_" + date + ".wtoon.zip";
        File output = new File(exportDir, filename);
        File pending = new File(exportDir, filename + ".part");
        if (pending.exists() && !pending.delete()) {
            throw new IOException("이전 내보내기 임시 파일을 지울 수 없습니다.");
        }

        JSONArray webtoons = new JSONArray();
        int completed = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pending))) {
            zip.setLevel(Deflater.BEST_SPEED);
            for (int index = 0; index < seriesItems.size(); index++) {
                SeriesItem series = seriesItems.get(index);
                List<EpisodeItem> episodes = database.listEpisodes(series.titleId);
                String slug = String.format(Locale.US, "%03d", index);
                String prefix = "items/" + slug + "/";
                WebtoonStorage storage = new WebtoonStorage(context, series.storageUri);

                JSONArray episodeArray = new JSONArray();
                Integer lastRead = null;
                for (EpisodeItem episode : episodes) {
                    String episodeName = String.format(Locale.US, "%03d.zip", episode.number);
                    try (InputStream in = storage.openEpisodeZip(series.titleId, episode.number)) {
                        writeEntry(zip, prefix + "episodes/" + episodeName, in);
                    }
                    JSONObject value = new JSONObject();
                    value.put("episode_number", episode.number);
                    value.put("folder_name", episodeName);
                    value.put("title", episode.title);
                    value.put("image_count", episode.imageCount);
                    value.put("viewed", episode.viewed);
                    episodeArray.put(value);
                    if (episode.viewed && (lastRead == null || episode.number > lastRead)) {
                        lastRead = episode.number;
                    }
                    completed++;
                    if (progress != null) progress.onProgress(completed, totalEpisodes);
                }

                String thumbnailExt = writeThumbnail(context, zip, prefix, series.thumbnailPath)
                        ? ".jpg" : null;
                JSONObject item = new JSONObject();
                String source = sourceFromTitleId(series.titleId);
                String transferTitleId = series.titleId;
                String pageUrl = null;
                SourceJobStore.Job sourceJob = SourceJobStore.get(context, series.titleId);
                if (sourceJob != null) {
                    source = sourceJob.source;
                    if (sourceJob.remoteId != null && !sourceJob.remoteId.isEmpty()) {
                        transferTitleId = sourceJob.remoteId;
                    }
                    pageUrl = sourceJob.pageUrl(baseUrlFor(context, source));
                } else if ("joatoon".equals(source) && series.titleId.startsWith("joatoon_")) {
                    transferTitleId = series.titleId.substring("joatoon_".length());
                    pageUrl = JoatoonApi.seriesUrl(SourceSettings.getJoatoonUrl(context), transferTitleId);
                } else if ("naver".equals(source)) {
                    pageUrl = "https://comic.naver.com/webtoon/list?titleId=" + transferTitleId;
                }
                item.put("slug", slug);
                item.put("title", series.title);
                item.put("description", series.description);
                item.put("tags", tagsArray(series.tags));
                item.put("source", source);
                item.put("title_id", transferTitleId);
                item.put("page_url", pageUrl == null ? JSONObject.NULL : pageUrl);
                item.put("created_at", Instant.now().toString());
                item.put("last_read_episode", lastRead == null ? JSONObject.NULL : lastRead);
                item.put("episode_count", episodes.size());
                item.put("episodes", episodeArray);
                item.put("thumbnail_ext", thumbnailExt == null ? JSONObject.NULL : thumbnailExt);
                webtoons.put(item);
            }

            JSONObject manifest = new JSONObject();
            manifest.put("format", FORMAT);
            manifest.put("version", VERSION);
            manifest.put("exporter", "android");
            manifest.put("exported_at", Instant.now().toString());
            manifest.put("webtoon_count", webtoons.length());
            manifest.put("webtoons", webtoons);
            byte[] bytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(bytes);
            zip.closeEntry();
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

    private static boolean writeThumbnail(Context context, ZipOutputStream zip, String prefix,
                                          String path) {
        if (path == null || path.isEmpty()) return false;
        try (InputStream in = openPath(context, path)) {
            if (in == null) return false;
            writeEntry(zip, prefix + "thumbnail.jpg", in);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JSONArray tagsArray(String tags) {
        JSONArray result = new JSONArray();
        if (tags == null) return result;
        for (String tag : tags.split("[,，]")) {
            String value = tag.trim();
            if (!value.isEmpty()) result.put(value);
        }
        return result;
    }

    private static String sourceFromTitleId(String titleId) {
        String value = titleId == null ? "" : titleId.toLowerCase(Locale.US);
        if (value.startsWith("joatoon_")) return "joatoon";
        if (value.startsWith("manhwabang_")) return "manhwabang";
        if (value.startsWith("ililtoon_")) return "ililtoon";
        return "naver";
    }

    private static String baseUrlFor(Context context, String source) {
        if (SourceSettings.SOURCE_JOATOON.equals(source)) return SourceSettings.getJoatoonUrl(context);
        if (SourceSettings.SOURCE_MANHWABANG.equals(source)) return SourceSettings.getManhwabangUrl(context);
        if (SourceSettings.SOURCE_ILILTOON.equals(source)) return SourceSettings.getIliltoonUrl(context);
        return "https://comic.naver.com";
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
}
