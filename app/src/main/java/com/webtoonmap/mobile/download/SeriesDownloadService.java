package com.webtoonmap.mobile.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
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
    private static final String ACTION_PROCESS = "com.webtoonmap.mobile.PROCESS_DOWNLOAD_QUEUE";
    private static final String ACTION_STOP = "com.webtoonmap.mobile.STOP_CURRENT_DOWNLOAD";
    private static final String CHANNEL_ID = "webtoon_downloads";
    private static final int NOTIFICATION_ID = 2001;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String currentTitleId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;
    private volatile Thread workerThread;
    private volatile boolean serviceDestroying;

    public static boolean isRunning() { return RUNNING.get(); }
    public static boolean isDownloading(String titleId) {
        return RUNNING.get() && titleId != null && titleId.equals(currentTitleId);
    }

    public static boolean enqueue(Context context, String titleId) {
        boolean added = DownloadQueue.add(context.getApplicationContext(), titleId);
        Intent intent = new Intent(context, SeriesDownloadService.class);
        intent.setAction(ACTION_PROCESS);
        intent.putExtra(EXTRA_TITLE_ID, titleId);
        ContextCompat.startForegroundService(context, intent);
        return added;
    }

    public static boolean isQueued(Context context, String titleId) {
        return titleId != null && DownloadQueue.contains(context.getApplicationContext(), titleId);
    }

    public static void stopAll(Context context) {
        Intent intent = new Intent(context, SeriesDownloadService.class);
        intent.setAction(ACTION_STOP);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":webtoon-download");
        wakeLock.setReferenceCounted(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "웹툰 다운로드", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("다운로드 준비 중…", 0, 0));
        String action = intent == null ? ACTION_PROCESS : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            DownloadQueue.clear(this);
            if (RUNNING.get()) {
                cancelRequested.set(true);
                Thread thread = workerThread;
                if (thread != null) thread.interrupt();
                broadcast("전체 다운로드와 대기열을 중단하는 중…", false, false);
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_STICKY;
        }
        ensureWorker();
        return START_STICKY;
    }

    private void ensureWorker() {
        if (DownloadQueue.size(this) == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            broadcast("대기열에 추가됨 · " + DownloadQueue.size(this) + "개 작품", false, false);
            return;
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
        executor.execute(this::drainQueue);
    }

    private void drainQueue() {
        workerThread = Thread.currentThread();
        try {
            while (true) {
                String titleId = DownloadQueue.peek(this);
                if (titleId == null) break;
                currentTitleId = titleId;
                cancelRequested.set(false);
                Thread.interrupted();
                try {
                    downloadOne(titleId);
                } catch (Exception e) {
                    boolean cancelled = cancelRequested.get() || Thread.currentThread().isInterrupted() ||
                            e instanceof InterruptedException;
                    Thread.interrupted();
                    if (cancelled) {
                        LibraryDatabase.get(this).setSeriesStatus(titleId, "paused");
                        broadcast("전체 다운로드 중단됨 · 대기열과 미완성 회차를 정리했습니다.", true, false);
                    } else {
                        LibraryDatabase.get(this).setSeriesStatus(titleId, "error");
                        String message = e.getMessage() == null ? "다운로드 실패" : e.getMessage();
                        update("오류 · " + message, 0, 0);
                        broadcast(message, true, true);
                    }
                } finally {
                    cleanupIncomplete(titleId);
                    if (!serviceDestroying) DownloadQueue.remove(this, titleId);
                    currentTitleId = null;
                }
            }
        } finally {
            workerThread = null;
            currentTitleId = null;
            RUNNING.set(false);
            if (!serviceDestroying && DownloadQueue.size(this) > 0) {
                ensureWorker();
            } else {
                releaseWakeLock();
                if (!serviceDestroying) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        }
    }

    private void downloadOne(String titleId) throws Exception {
        LibraryDatabase db = LibraryDatabase.get(this);
        checkCancelled();
        String cookie = CookieManager.getInstance().getCookie(NaverApi.ORIGIN);
        update("작품 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        NaverApi.SeriesInfo info = NaverApi.fetchSeriesInfo(titleId, cookie);
        checkCancelled();
        SeriesItem existing = db.getSeries(titleId);
        String storageUri = existing == null ? StorageSettings.getTreeUri(this) : existing.storageUri;
        WebtoonStorage storage = new WebtoonStorage(this, storageUri);

        update("미완성 회차 파일을 정리하는 중…", 0, 0);
        cleanupDownloadCache(titleId);
        storage.cleanupIncomplete(titleId);

        String thumbnailPath = saveThumbnail(info, storage, cookie);
        checkCancelled();
        if (thumbnailPath == null && existing != null) thumbnailPath = existing.thumbnailPath;
        db.upsertSeries(new SeriesItem(titleId, info.title, info.description, info.tags,
                thumbnailPath, storage.storageUri(), "downloading", 0));

        List<NaverApi.EpisodeMeta> episodes = NaverApi.fetchFreeEpisodes(titleId, cookie);
        checkCancelled();
        if (episodes.isEmpty()) throw new IllegalStateException("다운로드 가능한 공개 회차가 없습니다.");

        int current = 0;
        for (NaverApi.EpisodeMeta episode : episodes) {
            checkCancelled();
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
            checkCancelled();
            String referer = NaverApi.ORIGIN + "/" + info.segment + "/detail?titleId=" +
                    titleId + "&no=" + episode.number;
            int saved = 0;
            File tempDir = new File(getCacheDir(), "download-zips");
            if (!tempDir.exists() && !tempDir.mkdirs()) throw new IllegalStateException("임시 폴더 생성 실패");
            File tempZip = new File(tempDir, titleId + "-" + episode.number + ".zip.part");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tempZip))) {
                    for (int i = 0; i < images.size(); i++) {
                        checkCancelled();
                        String imageUrl = images.get(i);
                        byte[] bytes = NaverApi.downloadBytes(imageUrl, referer, cookie);
                        checkCancelled();
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
                checkCancelled();
                if (saved == 0) throw new IllegalStateException(episode.number + "화 저장 실패");
                storage.writeEpisodeZip(titleId, episode.number, tempZip);
            } finally {
                tempZip.delete();
            }
            checkCancelled();
            db.upsertEpisode(new EpisodeItem(titleId, episode.number, episode.title, saved, false));
            update(label + " ZIP 저장 완료", current, episodes.size());
        }
        db.setSeriesStatus(titleId, "complete");
        update("완료 · " + episodes.size() + "개 공개 회차", episodes.size(), episodes.size());
        broadcast("다운로드 완료", true, false);
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("다운로드 중단");
        }
    }

    private void cleanupIncomplete(String titleId) {
        Thread.interrupted();
        try {
            cleanupDownloadCache(titleId);
            SeriesItem item = LibraryDatabase.get(this).getSeries(titleId);
            String storageUri = item == null ? StorageSettings.getTreeUri(this) : item.storageUri;
            new WebtoonStorage(this, storageUri).cleanupIncomplete(titleId);
        } catch (Exception ignored) { }
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

    private void cleanupDownloadCache(String titleId) {
        File tempDir = new File(getCacheDir(), "download-zips");
        File[] files = tempDir.listFiles((dir, name) ->
                name.startsWith(titleId + "-") && name.endsWith(".zip.part"));
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) throw new IllegalStateException("미완성 압축 파일 정리 실패");
        }
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
        serviceDestroying = true;
        cancelRequested.set(true);
        Thread thread = workerThread;
        if (thread != null) thread.interrupt();
        RUNNING.set(false);
        currentTitleId = null;
        releaseWakeLock();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
