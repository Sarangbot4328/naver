package com.webtoonmap.mobile.ui;

import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.download.SeriesDownloadService;

public final class NaverChannelView extends FrameLayout {
    private static final String HOME = "https://comic.naver.com/webtoon";
    private final MainActivity activity;
    private final WebView webView;
    private final ProgressBar progress;
    private final Button downloadButton;

    public NaverChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Color.WHITE);

        webView = new WebView(activity);
        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LayoutParams progressParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        addView(progress, progressParams);

        downloadButton = new Button(activity);
        downloadButton.setText("전체 다운로드");
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setTextSize(14);
        downloadButton.setAllCaps(false);
        downloadButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.green, activity.getTheme())));
        downloadButton.setElevation(dp(8));
        LayoutParams buttonParams = new LayoutParams(dp(142), dp(52));
        buttonParams.gravity = Gravity.END | Gravity.BOTTOM;
        buttonParams.setMargins(dp(16), dp(16), dp(16), dp(20));
        addView(downloadButton, buttonParams);

        configureWebView();
        downloadButton.setOnClickListener(v -> confirmDownload());
        webView.loadUrl(HOME);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri.getHost() != null && (uri.getHost().equals("comic.naver.com") ||
                        uri.getHost().endsWith(".naver.com"))) return false;
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                updateDownloadButton(url);
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateDownloadButton(url);
            }
        });
    }

    private void updateDownloadButton(String url) {
        downloadButton.setVisibility(titleIdFrom(url) == null ? View.GONE : View.VISIBLE);
    }

    private void confirmDownload() {
        String titleId = titleIdFrom(webView.getUrl());
        if (titleId == null) {
            Toast.makeText(activity, "먼저 작품 페이지를 열어 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (SeriesDownloadService.isRunning()) {
            Toast.makeText(activity, "현재 다른 다운로드가 진행 중입니다.", Toast.LENGTH_SHORT).show();
            activity.showDownloads();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("전체 다운로드")
                .setMessage("현재 이용 가능한 공개 회차를 모두 저장합니다. 이미 받은 회차는 건너뜁니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("시작", (dialog, which) -> {
                    SeriesDownloadService.start(activity, titleId);
                    Toast.makeText(activity, "다운로드를 시작했습니다.", Toast.LENGTH_SHORT).show();
                    activity.showDownloads();
                }).show();
    }

    private String titleIdFrom(String url) {
        if (url == null) return null;
        try {
            String value = Uri.parse(url).getQueryParameter("titleId");
            return value != null && value.matches("\\d+") ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean canGoBack() { return webView.canGoBack(); }
    public void goBack() { webView.goBack(); }
    public void destroyWebView() { webView.stopLoading(); webView.destroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
