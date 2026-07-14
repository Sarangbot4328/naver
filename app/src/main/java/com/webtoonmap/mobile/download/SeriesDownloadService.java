package com.webtoonmap.mobile.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.naver.NaverApi;
import com.webtoonmap.mobile.storage.StorageSettings;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SeriesDownloadService extends Service {
    public static final String ACTION_PROGRESS = "com.webtoonmap.mobile.DOWNLOAD_PROGRESS";
    public static final String EXTRA_TITLE_ID = "title_id";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_ERROR = "error";
    private static final String CHANNEL_ID = "webtoon_downloads";
    private static final int NOTIFICATION_ID = 2001;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String currentTitleId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static boolean isRunning() { return RUNNING.get(); }
    public static boolean isDownloading(String titleId) {
        return RUNNING.get() && titleId != null && titleId.equals(currentTitleId);
    }

    public static void start(Context context, String titleId) {
        Intent intent = new Intent(context, SeriesDownloadService.class);
        intent.putExtra(EXTRA_TITLE_ID, titleId);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "웹툰 다운로드", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String titleId = intent == null ? null : intent.getStringExtra(EXTRA_TITLE_ID);
        if (titleId == null || !titleId.matches("\\d+")) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("다운로드 준비 중…", 0, 0));
        if (!RUNNING.compareAndSet(false, true)) {
            broadcast("다른 작품을 다운로드 중입니다.", false, true);
            return START_NOT_STICKY;
        }
        currentTitleId = titleId;
        executor.execute(() -> download(titleId, startId));
        return START_NOT_STICKY;
    }

    private void download(String titleId, int startId) {
        LibraryDatabase db = LibraryDatabase.get(this);
        try {
            String cookie = CookieManager.getInstance().getCookie(NaverApi.ORIGIN);
            update("작품 정보를 불러오는 중…", 0, 0);
            NaverApi.SeriesInfo info = NaverApi.fetchSeriesInfo(titleId, cookie);
            SeriesItem existing = db.getSeries(titleId);
            String storageUri = existing == null ? StorageSettings.getTreeUri(this) : existing.storageUri;
            WebtoonStorage storage = new WebtoonStorage(this, storageUri);

            String thumbnailPath = saveThumbnail(info, storage, cookie);
            if (thumbnailPath == null && existing != null) thumbnailPath = existing.thumbnailPath;
            db.upsertSeries(new SeriesItem(titleId, info.title, info.description, info.tags,
                    thumbnailPath, storage.storageUri(), "downloading", 0));

            List<NaverApi.EpisodeMeta> episodes = NaverApi.fetchFreeEpisodes(titleId, cookie);
            if (episodes.isEmpty()) throw new IllegalStateException("다운로드 가능한 공개 회차가 없습니다.");

            int current = 0;
            for (NaverApi.EpisodeMeta episode : episodes) {
                current++;
                if (db.hasCompleteEpisode(titleId, episode.number) &&
                        storage.episodeZipExists(titleId, episode.number)) {
                    update(current + "/" + episodes.size() + "화 · 이미 보유", current, episodes.size());
                    continue;
                }
                String label = current + "/" + episodes.size() + "화 · " + episode.title;
                update(label + " 분석 중", current - 1, episodes.size());
                List<String> images = NaverApi.fetchEpisodeImages(
                        titleId, episode.number, info.segment, cookie);
                String referer = NaverApi.ORIGIN + "/" + info.segment + "/detail?titleId=" +
                        titleId + "&no=" + episode.number;
                int saved = 0;
                File tempDir = new File(getCacheDir(), "download-zips");
                if (!tempDir.exists() && !tempDir.mkdirs()) throw new IllegalStateException("임시 폴더 생성 실패");
                File tempZip = new File(tempDir, titleId + "-" + episode.number + ".zip.part");
                try {
                    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tempZip))) {
                        for (int i = 0; i < images.size(); i++) {
                            String imageUrl = images.get(i);
                            byte[] bytes = NaverApi.downloadBytes(imageUrl, referer, cookie);
                            String entryName = String.format(Locale.US, "%03d", i + 1) + imageExtension(imageUrl);
                            zip.putNextEntry(new ZipEntry(entryName));
                            zip.write(bytes);
                            zip.closeEntry();
                            saved++;
                            if ((i + 1) % 5 == 0 || i + 1 == images.size()) {
                                update(label + " · " + (i + 1) + "/" + images.size() + "장 압축", current - 1, episodes.size());
                            }
                        }
                    }
                    if (saved == 0) throw new IllegalStateException(episode.number + "화 저장 실패");
                    storage.writeEpisodeZip(titleId, episode.number, tempZip);
                } finally {
                    tempZip.delete();
                }
                db.upsertEpisode(new EpisodeItem(titleId, episode.number, episode.title, saved));
                update(label + " ZIP 저장 완료", current, episodes.size());
            }
            db.setSeriesStatus(titleId, "complete");
            update("완료 · " + episodes.size() + "개 공개 회차", episodes.size(), episodes.size());
            broadcast("다운로드 완료", true, false);
        } catch (Exception e) {
            db.setSeriesStatus(titleId, "error");
            String message = e.getMessage() == null ? "다운로드 실패" : e.getMessage();
            update("오류 · " + message, 0, 0);
            broadcast(message, true, true);
        } finally {
            RUNNING.set(false);
            currentTitleId = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private String saveThumbnail(NaverApi.SeriesInfo info, WebtoonStorage storage, String cookie) {
        if (info.thumbnailUrl == null || info.thumbnailUrl.isEmpty()) return null;
        try {
            byte[] bytes = NaverApi.downloadBytes(info.thumbnailUrl,
                    NaverApi.listUrl(info.titleId, info.segment), cookie);
            return storage.writeThumbnail(info.titleId, bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String imageExtension(String url) {
        String clean = url == null ? "" : url.toLowerCase(Locale.US).replaceFirst("[?#].*$", "");
        if (clean.endsWith(".png")) return ".png";
        if (clean.endsWith(".webp")) return ".webp";
        if (clean.endsWith(".jpeg")) return ".jpeg";
        return ".jpg";
    }

    private void update(String message, int current, int total) {
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(message, current, total));
        broadcast(message, false, false);
    }

    private android.app.Notification notification(String message, int current, int total) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("웹툰여지도 · 다운로드")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(pending);
        if (total > 0) builder.setProgress(total, current, false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void broadcast(String message, boolean done, boolean error) {
        Intent intent = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_DONE, done);
        intent.putExtra(EXTRA_ERROR, error);
        sendBroadcast(intent);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
