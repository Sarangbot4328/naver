package com.webtoonmap.mobile.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.data.AvseeLibraryDatabase;
import com.webtoonmap.mobile.data.AvseeMetadata;
import com.webtoonmap.mobile.storage.AvseeStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AvseeDownloadService extends Service {
    public static final String ACTION_PROGRESS = "com.ddmjspace.mobile.AVSEE_PROGRESS";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_TITLE = "title";

    private static final String ACTION_ENQUEUE = "enqueue_avsee";
    private static final String CHANNEL_ID = "avsee_download";
    private static final int NOTIFICATION_ID = 9102;
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static volatile boolean cancelled;
    private static volatile HttpURLConnection activeConnection;
    private static volatile File activeFolder;
    private static volatile String currentTitle = "";
    private static volatile String currentMessage = "";
    private static volatile int currentPercent = -1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static boolean isRunning() {
        return PENDING.get() > 0;
    }

    public static String getCurrentTitle() { return currentTitle; }
    public static String getCurrentMessage() { return currentMessage; }
    public static int getCurrentPercent() { return currentPercent; }

    public static void enqueue(Context context, String title, String videoUrl, String thumbnailUrl,
                               String pageUrl, String tags, String actors, String description,
                               String referer, String cookie, String userAgent) {
        cancelled = false;
        Intent intent = new Intent(context, AvseeDownloadService.class)
                .setAction(ACTION_ENQUEUE)
                .putExtra("title", title)
                .putExtra("video_url", videoUrl)
                .putExtra("thumbnail_url", thumbnailUrl)
                .putExtra("page_url", pageUrl)
                .putExtra("tags", tags)
                .putExtra("actors", actors)
                .putExtra("description", description)
                .putExtra("referer", referer)
                .putExtra("cookie", cookie)
                .putExtra("user_agent", userAgent);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void cancelAll(Context context) {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    @Override public void onCreate() {
        super.onCreate();
        AvseeStorage.cleanupIncomplete(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "AVSee 다운로드", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("AVSee 영상 다운로드 진행 상태");
        manager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_ENQUEUE.equals(intent.getAction())) return START_NOT_STICKY;
        PENDING.incrementAndGet();
        Job job = Job.from(intent);
        startForeground(NOTIFICATION_ID, notification("다운로드 대기 중…", 0, 0));
        executor.execute(() -> {
            currentTitle = nonEmpty(job.title, "AVSee 영상");
            currentMessage = "다운로드 준비 중…";
            currentPercent = 0;
            sendStatus(currentMessage, false, false);
            try {
                download(job);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                sendStatus("다운로드가 중단되었습니다.", false, true);
            } catch (Exception error) {
                String message = cancelled ? "다운로드가 중단되었습니다." : error.getMessage();
                if (message == null || message.trim().isEmpty()) message = "다운로드에 실패했습니다.";
                sendStatus(message, false, true);
            } finally {
                activeConnection = null;
                if (PENDING.decrementAndGet() <= 0) {
                    PENDING.set(0);
                    cancelled = false;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    private void download(Job job) throws Exception {
        if (job.videoUrl == null || !job.videoUrl.startsWith("https://")) {
            throw new IllegalArgumentException("올바른 HTTPS 영상 주소를 찾지 못했습니다.");
        }
        if (job.videoUrl.toLowerCase(Locale.US).contains(".m3u8")) {
            throw new IllegalArgumentException("HLS(m3u8) 영상은 현재 버전에서 지원하지 않습니다.");
        }
        AvseeLibraryDatabase db = new AvseeLibraryDatabase(this);
        if (db.hasPage(job.pageUrl)) {
            throw new IllegalStateException("이미 다운로드한 게시물입니다.");
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "DdmjSpace:AvseeDownload");
        wakeLock.acquire(6L * 60L * 60L * 1000L);
        File folder = null;
        try {
            checkCancelled();
            folder = AvseeStorage.createVideoFolder(this, job.title);
            activeFolder = folder;
            File part = new File(folder, "video" + AvseeStorage.extensionFromUrl(job.videoUrl) + ".part");
            File video = new File(folder,
                    "video" + AvseeStorage.extensionFromUrl(job.videoUrl));
            long size = downloadFile(job.videoUrl, part, job, true);
            if (size < 64L * 1024L) throw new IllegalStateException("받은 파일이 너무 작아 영상으로 확인할 수 없습니다.");
            if (!part.renameTo(video)) throw new IllegalStateException("다운로드 파일을 저장하지 못했습니다.");

            String thumbnailPath = null;
            if (job.thumbnailUrl != null && job.thumbnailUrl.startsWith("https://")) {
                try {
                    File thumbnail = new File(folder, "thumbnail.jpg");
                    if (downloadFile(job.thumbnailUrl, thumbnail, job, false) > 0) {
                        thumbnailPath = thumbnail.getAbsolutePath();
                    }
                } catch (Exception ignored) { }
            }

            String createdAt = utcNow();
            db.insert(nonEmpty(job.title, "AVSee 영상"), video.getAbsolutePath(), thumbnailPath,
                    job.pageUrl, job.tags, AvseeMetadata.cleanActors(job.actors),
                    job.description, createdAt, size);
            if (activeFolder == folder) activeFolder = null;
            sendStatus("다운로드 완료 · " + nonEmpty(job.title, "AVSee 영상"), true, false);
            updateNotification("다운로드 완료", 0, 0);
        } catch (Exception error) {
            if (folder != null) AvseeStorage.deleteFolder(folder);
            if (activeFolder == folder) activeFolder = null;
            throw error;
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
            db.close();
        }
    }

    private long downloadFile(String source, File destination, Job job, boolean reportProgress)
            throws Exception {
        HttpURLConnection connection = openFollowingRedirects(source, job, reportProgress);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("영상 서버 접근 실패 · HTTP " + status);
        String type = connection.getContentType();
        if (reportProgress && type != null && type.toLowerCase(Locale.US).contains("text/html")) {
            throw new IllegalStateException("영상 대신 HTML이 반환되었습니다. 요청: " +
                    describeUrl(source) + " · 최종: " + describeUrl(connection.getURL().toString()));
        }
        long total = connection.getContentLengthLong();
        long received = 0L;
        byte[] buffer = new byte[256 * 1024];
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(destination)) {
            int count;
            int lastPercent = -1;
            while ((count = input.read(buffer)) >= 0) {
                checkCancelled();
                output.write(buffer, 0, count);
                received += count;
                if (reportProgress) {
                    int percent = total > 0 ? (int) Math.min(100L, received * 100L / total) : 0;
                    if (percent != lastPercent && (total <= 0 || percent % 2 == 0)) {
                        String message = total > 0 ? "다운로드 " + percent + "%" :
                                "다운로드 " + AvseeStorage.formatBytes(received);
                        currentMessage = message;
                        currentPercent = total > 0 ? percent : -1;
                        updateNotification(message, percent, total > 0 ? 100 : 0);
                        sendStatus(message, false, false);
                        lastPercent = percent;
                    }
                }
            }
        } finally {
            connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
        return received;
    }

    private HttpURLConnection openFollowingRedirects(String source, Job job, boolean reportProgress)
            throws Exception {
        URL current = new URL(source);
        for (int redirect = 0; redirect < 8; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            activeConnection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Accept", reportProgress ? "*/*" : "image/*,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");
            if (reportProgress) connection.setRequestProperty("Range", "bytes=0-");
            if (job.referer != null && !job.referer.isEmpty()) {
                connection.setRequestProperty("Referer", job.referer);
                String origin = originOf(job.referer);
                if (!origin.isEmpty()) connection.setRequestProperty("Origin", origin);
            }
            if (job.cookie != null && !job.cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", job.cookie);
            }
            if (job.userAgent != null && !job.userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", job.userAgent);
            }
            int status = connection.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) return connection;
                URL next = new URL(current, location);
                connection.disconnect();
                if (activeConnection == connection) activeConnection = null;
                current = next;
                continue;
            }
            return connection;
        }
        throw new IllegalStateException("영상 주소의 리다이렉트가 너무 많습니다.");
    }

    private static String describeUrl(String value) {
        try {
            URL parsed = new URL(value);
            String path = parsed.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (path.length() > 80) path = path.substring(0, 77) + "...";
            return parsed.getHost() + path;
        } catch (Exception ignored) {
            return "알 수 없는 주소";
        }
    }

    private static String originOf(String referer) {
        try {
            URL parsed = new URL(referer);
            int port = parsed.getPort();
            String authority = parsed.getHost() +
                    (port > 0 && port != parsed.getDefaultPort() ? ":" + port : "");
            return parsed.getProtocol() + "://" + authority;
        } catch (Exception ignored) {
            return "";
        }
    }
    private void checkCancelled() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
    }

    private Notification notification(String message, int progress, int max) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.webtoonmap.mobile.R.drawable.ic_app)
                .setContentTitle("직박구리 스페이스 모바일")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(progress > 0 || max == 0);
        if (max > 0) builder.setProgress(max, progress, false);
        else if (isRunning()) builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void updateNotification(String message, int progress, int max) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification(message, progress, max));
    }

    private void sendStatus(String message, boolean done, boolean error) {
        currentMessage = message == null ? "" : message;
        if (done) currentPercent = 100;
        else if (error) currentPercent = -1;
        Intent intent = new Intent(ACTION_PROGRESS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_MESSAGE, currentMessage)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_ERROR, error)
                .putExtra(EXTRA_PERCENT, currentPercent)
                .putExtra(EXTRA_TITLE, currentTitle);
        sendBroadcast(intent);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    @Override public void onDestroy() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        File folder = activeFolder;
        if (folder != null) AvseeStorage.deleteFolder(folder);
        activeFolder = null;
        PENDING.set(0);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class Job {
        final String title;
        final String videoUrl;
        final String thumbnailUrl;
        final String pageUrl;
        final String tags;
        final String actors;
        final String description;
        final String referer;
        final String cookie;
        final String userAgent;

        private Job(Intent intent) {
            title = intent.getStringExtra("title");
            videoUrl = intent.getStringExtra("video_url");
            thumbnailUrl = intent.getStringExtra("thumbnail_url");
            pageUrl = intent.getStringExtra("page_url");
            tags = intent.getStringExtra("tags");
            actors = intent.getStringExtra("actors");
            description = intent.getStringExtra("description");
            referer = intent.getStringExtra("referer");
            cookie = intent.getStringExtra("cookie");
            userAgent = intent.getStringExtra("user_agent");
        }

        static Job from(Intent intent) {
            return new Job(intent);
        }
    }
}
