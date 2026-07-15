package com.webtoonmap.mobile.ui;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.storage.SourceSettings;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class OfflineViewerActivity extends AppCompatActivity {
    private String titleId;
    private List<EpisodeItem> episodes;
    private int episodeIndex;
    private WebView webView;
    private TextView titleView;
    private Button previous;
    private Button next;
    private boolean pageMode;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int loadGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_viewer);
        SystemBarInsets.apply(this, findViewById(R.id.viewer_root), false);
        titleId = getIntent().getStringExtra("title_id");
        int episodeNo = getIntent().getIntExtra("episode_no", -1);
        if (titleId == null || episodeNo < 0) { finish(); return; }

        episodes = LibraryDatabase.get(this).listEpisodes(titleId);
        episodeIndex = 0;
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).number == episodeNo) episodeIndex = i;

        webView = findViewById(R.id.image_list);
        titleView = findViewById(R.id.title);
        previous = findViewById(R.id.previous);
        next = findViewById(R.id.next);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        previous.setOnClickListener(v -> loadEpisode(episodeIndex - 1));
        next.setOnClickListener(v -> loadEpisode(episodeIndex + 1));

        pageMode = SourceSettings.isPageMode(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        loadEpisode(episodeIndex);
    }

    private void loadEpisode(int index) {
        if (index < 0 || index >= episodes.size()) return;
        episodeIndex = index;
        EpisodeItem episode = episodes.get(index);
        SeriesItem series = LibraryDatabase.get(this).getSeries(titleId);
        titleView.setText((series == null ? "웹툰" : series.title) + " · " + episode.number + "화");
        previous.setEnabled(index > 0);
        next.setEnabled(index + 1 < episodes.size());

        int generation = ++loadGeneration;
        webView.loadData("<html><body style='background:#111;color:#aaa;text-align:center;padding-top:80px'>ZIP 회차를 여는 중…</body></html>",
                "text/html", "UTF-8");
        String storageUri = series == null ? null : series.storageUri;
        executor.execute(() -> {
            try {
                File dir = extractEpisode(storageUri, episode.number);
                File[] images = dir.listFiles(file -> file.isFile() && file.getName().matches("(?i).+\\.(jpg|jpeg|png|webp)$"));
                if (images == null) images = new File[0];
                Arrays.sort(images, Comparator.comparing(File::getName));
                boolean hasNext = index + 1 < episodes.size();
                String html = pageMode ? buildPageHtml(images, hasNext) : buildScrollHtml(images, hasNext);
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) return;
                    LibraryDatabase.get(OfflineViewerActivity.this)
                            .markEpisodeViewed(titleId, episode.number);
                    webView.loadDataWithBaseURL("file://" + dir.getAbsolutePath() + "/", html,
                            "text/html", "UTF-8", null);
                    webView.scrollTo(0, 0);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "ZIP 회차를 열지 못했습니다." : e.getMessage();
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) return;
                    webView.loadData("<html><body style='background:#111;color:#ff8a80;padding:30px'>" +
                                    escapeHtml(message) + "</body></html>", "text/html", "UTF-8");
                });
            }
        });
    }

    private String buildScrollHtml(File[] images, boolean hasNext) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=3'><style>html,body{margin:0;background:#111}img{display:block;width:100%;height:auto}</style></head><body>");
        for (File image : images) html.append("<img src='").append(image.getName()).append("' loading='lazy'>");
        html.append("<div style='height:64px;color:#aaa;text-align:center;padding-top:30px'>")
                .append(hasNext ? "상단의 다음 버튼으로 계속 보기" : "마지막 회차입니다")
                .append("</div></body></html>");
        return html.toString();
    }

    private String buildPageHtml(File[] images, boolean hasNext) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=3'><style>"
                + "html,body{margin:0;background:#111;height:100%;overflow:hidden}"
                + ".pager{display:flex;flex-direction:row;height:100vh;width:100vw;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory;-webkit-overflow-scrolling:touch}"
                + ".page{flex:0 0 100vw;width:100vw;height:100vh;scroll-snap-align:start;display:flex;align-items:center;justify-content:center}"
                + ".page img{max-width:100vw;max-height:100vh;width:auto;height:auto;display:block}"
                + ".endpage{flex:0 0 100vw;width:100vw;height:100vh;scroll-snap-align:start;display:flex;align-items:center;justify-content:center;color:#aaa;text-align:center;padding:0 24px}"
                + "</style></head><body><div class='pager'>");
        for (File image : images) {
            html.append("<div class='page'><img src='").append(image.getName()).append("'></div>");
        }
        html.append("<div class='endpage'>")
                .append(hasNext ? "상단의 다음 버튼으로 다음 회차 보기" : "마지막 회차입니다")
                .append("</div></div></body></html>");
        return html.toString();
    }

    private File extractEpisode(String storageUri, int episode) throws Exception {
        File viewerRoot = new File(getCacheDir(), "viewer");
        WebtoonStorage.deleteRecursively(viewerRoot);
        File dir = new File(viewerRoot, titleId + "/" + String.format(Locale.US, "%03d", episode));
        if (!dir.mkdirs()) throw new IllegalStateException("임시 뷰어 폴더 생성 실패");
        try (InputStream source = new WebtoonStorage(this, storageUri).openEpisodeZip(titleId, episode);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String safeName = new File(entry.getName()).getName();
                if (!safeName.matches("(?i).+\\.(jpg|jpeg|png|webp)$")) continue;
                File output = new File(dir, safeName);
                try (FileOutputStream out = new FileOutputStream(output)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        out.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
        return dir;
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override protected void onDestroy() {
        loadGeneration++;
        executor.shutdownNow();
        WebtoonStorage.deleteRecursively(new File(getCacheDir(), "viewer"));
        webView.destroy();
        super.onDestroy();
    }
}
