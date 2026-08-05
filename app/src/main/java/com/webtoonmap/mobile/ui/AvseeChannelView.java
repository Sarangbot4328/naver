package com.webtoonmap.mobile.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.AvseeMetadata;
import com.webtoonmap.mobile.download.AvseeDownloadService;
import com.webtoonmap.mobile.storage.AvseeSettings;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AvseeChannelView extends FrameLayout {
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";
    private static final String PAGE_SCRIPT =
            "(function(){try{" +
            "var q=function(s){return document.querySelector(s)};" +
            "var clean=function(v){return String(v||'').replace(/\\s+/g,' ').trim()};" +
            "var m=function(n){var e=q('meta[property=\\\"'+n+'\\\"],meta[name=\\\"'+n+'\\\"]');return e?clean(e.content):''};" +
            "var abs=function(x,b){if(!x)return'';try{return new URL(x,b||location.href).href}catch(e){return''}};" +
            "var texts=function(s){var out=[],seen={};var es=document.querySelectorAll(s);" +
            "for(var i=0;i<es.length&&out.length<20;i++){var t=clean(es[i].textContent||es[i].content);" +
            "if(t&&t.length<100&&!seen[t]){seen[t]=1;out.push(t)}}return out};" +
            "var field=function(labels){var same=function(v){v=clean(v).replace(/[:：]$/,'').toLowerCase();" +
            "for(var i=0;i<labels.length;i++)if(v===labels[i].toLowerCase())return true;return false};" +
            "var rows=document.querySelectorAll('tr');for(var i=0;i<rows.length;i++){" +
            "var cells=rows[i].querySelectorAll('th,td');if(cells.length>1&&same(cells[0].innerText))" +
            "return clean(cells[1].innerText)}var terms=document.querySelectorAll('dt');" +
            "for(var i=0;i<terms.length;i++){if(same(terms[i].innerText)){var n=terms[i].nextElementSibling;" +
            "if(n&&String(n.tagName).toLowerCase()==='dd')return clean(n.innerText)}}" +
            "var keys=document.querySelectorAll('.label,.tit,.meta-label,.info-label,strong,b');" +
            "for(var i=0;i<keys.length;i++){if(!same(keys[i].innerText))continue;var p=keys[i].parentElement;" +
            "if(!p)continue;var v=p.querySelector('.value,.text,.cont,.content,.meta-value,.info-value');" +
            "if(v&&v!==keys[i])return clean(v.innerText);var n=keys[i].nextElementSibling;" +
            "if(n)return clean(n.innerText)}return''};" +
            "var ld={};var take=function(x){if(!x)return;if(Array.isArray(x)){for(var i=0;i<x.length;i++)take(x[i]);return}" +
            "if(typeof x!=='object')return;var ty=clean(x['@type']).toLowerCase();" +
            "if(ty.indexOf('video')>=0&&!ld.name)ld=x;if(x['@graph'])take(x['@graph'])};" +
            "var js=document.querySelectorAll('script[type=\\\"application/ld+json\\\"]');" +
            "for(var ji=0;ji<js.length;ji++){try{take(JSON.parse(js[ji].textContent))}catch(e){}}" +
            "var names=function(x){if(!x)return'';if(!Array.isArray(x))x=[x];var out=[];" +
            "for(var i=0;i<x.length;i++){var n=clean(typeof x[i]==='string'?x[i]:(x[i].name||x[i].alternateName));if(n)out.push(n)}return out.join(', ')};" +
            "var docs=[document];for(var di=0;di<docs.length;di++){var fr=docs[di].querySelectorAll('iframe');" +
            "for(var fi=0;fi<fr.length;fi++){try{if(fr[fi].contentDocument)docs.push(fr[fi].contentDocument)}catch(e){}}}" +
            "var u='';for(var di=0;di<docs.length&&!u;di++){var doc=docs[di];var vs=doc.querySelectorAll('video');" +
            "for(var vi=0;vi<vs.length&&!u;vi++){var v=vs[vi];var cs=[v.currentSrc,v.src,v.getAttribute('data-src')];" +
            "for(var ci=0;ci<cs.length;ci++){var z=abs(cs[ci],doc.baseURI);if(z&&z.indexOf('blob:')!==0&&z.indexOf('data:')!==0&&!/\\.html?(\\?|$)/i.test(z)){u=z;break}}" +
            "if(!u){var ss=v.querySelectorAll('source');for(var si=0;si<ss.length;si++){var z=abs(ss[si].src,doc.baseURI);if(z&&z.indexOf('blob:')!==0){u=z;break}}}}" +
            "if(!u){var es=doc.querySelectorAll('source[src],a[href],video[data-src]');for(var ei=0;ei<es.length;ei++){var z=abs(es[ei].src||es[ei].href||es[ei].getAttribute('data-src'),doc.baseURI);if(/\\.(mp4|webm|m4v|mov)(\\?|$)/i.test(z)){u=z;break}}}}" +
            "if(!u&&performance&&performance.getEntriesByType){var a=performance.getEntriesByType('resource');" +
            "for(var i=a.length-1;i>=0;i--){if(/\\.(mp4|webm|m4v|mov|m3u8)(\\?|$)/i.test(a[i].name)){u=a[i].name;break}}}" +
            "var thumb=ld.thumbnailUrl||ld.thumbnail||m('og:image')||m('twitter:image');if(Array.isArray(thumb))thumb=thumb[0];" +
            "if(!thumb){var pv=q('video[poster]');thumb=pv?pv.poster:''}" +
            "var actors=names(ld.actor||ld.actors||ld.performer);" +
            "if(!actors)actors=texts('[itemprop=\\\"actor\\\"],[itemprop=\\\"performer\\\"]," +
            "[itemprop=\\\"actor\\\"] [itemprop=\\\"name\\\"],.actor a,.actors a,.actor-name," +
            ".actress a,.performer a,.cast a,.star-name,a[href*=\\\"/actor/\\\"]," +
            "a[href*=\\\"/actress/\\\"],[class*=\\\"actor\\\"] a,[class*=\\\"cast\\\"] a').join(', ');" +
            "if(!actors)actors=field(['배우','배우명','여배우','출연','출연자'," +
            "'Actor','Actress','Cast','Starring']);actors=clean(actors);" +
            "var productAt=actors.search(/품번|작품번호/);if(productAt>=0)actors=clean(" +
            "actors.substring(0,productAt).replace(/(?:및|\\/|·|\\|)\\s*$/,''));" +
            "if(!actors||actors==='및'||/(?:등록합니다|댓글|문의|요청|알려\\s*주세요)/.test(actors))actors='';" +
            "var tags=Array.isArray(ld.keywords)?ld.keywords.join(', '):clean(ld.keywords);" +
            "if(!tags)tags=m('keywords');if(!tags)tags=texts('a[rel=\\\"tag\\\"],.tags a,.tag-list a,.genre a').join(', ');" +
            "if(!tags)tags=field(['태그','장르','카테고리','Tags','Genre']);" +
            "var desc=clean(ld.description)||m('og:description')||m('description');" +
            "if(!desc){var de=q('.video-description,.description,[class*=\\\"description\\\"]');desc=de?clean(de.textContent):''}" +
            "return JSON.stringify({title:clean(ld.name)||m('og:title')||clean(document.title)||'AVSee 영상'," +
            "video:u,thumb:abs(thumb),description:desc,tags:clean(tags),actors:clean(actors)});" +
            "}catch(e){return JSON.stringify({error:String(e)})}})()";

    private static final String FULLSCREEN_SCRIPT =
            "(function(){try{if(window.__ddmjGuardBound)return;window.__ddmjGuardBound=true;" +
            "var marks=['doubleclick','googlesyndication','googleadservices','exoclick','exosrv'," +
            "'popads','propellerads','adsterra','trafficjunky','juicyads','popcash','/ads/'," +
            "'/ad/','vast','pre-roll','preroll','popunder'];" +
            "var bad=function(u){u=String(u||'').toLowerCase();for(var i=0;i<marks.length;i++)" +
            "if(u.indexOf(marks[i])>=0)return true;return false};" +
            "var guide=/접속주소안내|최신\\s*AVseeTV/i.test((document.body&&document.body.innerText||'').slice(0,3000));" +
            "if(!guide)window.open=function(){return null};" +
            "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?" +
            "e.target.closest('a[href]'):null;if(!a||!guide)return;try{var u=new URL(a.href,location.href);" +
            "if(u.host.toLowerCase().indexOf('avsee')>=0){" +
            "e.preventDefault();e.stopImmediatePropagation();location.href=u.href;return false}" +
            "}catch(x){}},true);" +
            "var bind=function(doc){if(!doc||doc.__ddmjFullscreenBound)return;" +
            "doc.__ddmjFullscreenBound=true;doc.addEventListener('play',function(e){var v=e.target;" +
            "if(!v||String(v.tagName).toLowerCase()!=='video'||bad(v.currentSrc||v.src))return;" +
            "if(v.clientWidth>0&&(v.clientWidth<240||v.clientHeight<120))return;" +
            "var f=v.requestFullscreen||v.webkitRequestFullscreen||v.webkitEnterFullscreen;" +
            "if(f){try{var p=f.call(v);if(p&&p.catch)p.catch(function(){})}catch(x){}}},true);" +
            "var fs=doc.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){try{bind(fs[i].contentDocument)}catch(x){}}};" +
            "bind(document)}catch(e){}})()";
    private final MainActivity activity;
    private final WebView webView;
    private final ProgressBar progress;
    private final TextView status;
    private final Button navigationButton;
    private final Button downloadButton;
    private final Button stopButton;
    private volatile String lastMediaUrl = "";
    private volatile Map<String, String> lastMediaHeaders = Collections.emptyMap();
    private String pageTitle = "";
    private String pageThumb = "";
    private String pageTags = "";
    private String pageActors = "";
    private String pageDescription = "";
    private String metadataPageUrl = "";
    private boolean receiverRegistered;
    private boolean clearHistoryOnNextPage;
    private boolean cleanCaptureInProgress;
    private int cleanCaptureAttempts;
    private int lastMediaScore = Integer.MIN_VALUE;
    private String lockedContentHost = "";
    private String lastTrustedUrl = "";
    private View fullscreenView;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int previousSystemUiVisibility;
    private int previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(AvseeDownloadService.EXTRA_MESSAGE);
            if (message != null) status.setText(message);
            if (intent.getBooleanExtra(AvseeDownloadService.EXTRA_DONE, false)) {
                activity.refreshDownloads();
                Toast.makeText(activity, message == null ? "다운로드 완료" : message,
                        Toast.LENGTH_LONG).show();
            } else if (intent.getBooleanExtra(AvseeDownloadService.EXTRA_ERROR, false)) {
                Toast.makeText(activity, message == null ? "다운로드 실패" : message,
                        Toast.LENGTH_LONG).show();
            }
            updateButtons();
        }
    };

    public AvseeChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Color.WHITE);

        webView = new WebView(activity);
        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LayoutParams progressParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        addView(progress, progressParams);

        status = new TextView(activity);
        status.setText("영상 페이지에서 재생한 뒤 다운로드를 누르세요.");
        status.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        status.setTextSize(12);
        status.setBackgroundColor(0xEFFFFFFF);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        LayoutParams statusParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.BOTTOM;
        statusParams.setMargins(0, 0, 0, dp(70));
        addView(status, statusParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setElevation(dp(8));

        stopButton = new Button(activity);
        stopButton.setText("중단");
        stopButton.setTextColor(Color.WHITE);
        stopButton.setTextSize(14);
        stopButton.setAllCaps(false);
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(198, 40, 40)));
        actions.addView(stopButton, new LinearLayout.LayoutParams(dp(92), dp(52)));

        navigationButton = new Button(activity);
        navigationButton.setText("\uC774\uB3D9 \u25BE");
        navigationButton.setTextColor(Color.WHITE);
        navigationButton.setTextSize(14);
        navigationButton.setAllCaps(false);
        navigationButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(70, 78, 90)));
        LinearLayout.LayoutParams navigationParams = new LinearLayout.LayoutParams(dp(78), dp(52));
        navigationParams.setMarginStart(dp(8));
        actions.addView(navigationButton, navigationParams);


        downloadButton = new Button(activity);
        downloadButton.setText("영상 다운로드");
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setTextSize(14);
        downloadButton.setAllCaps(false);
        downloadButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.green)));
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(dp(142), dp(52));
        downloadParams.setMarginStart(dp(8));
        actions.addView(downloadButton, downloadParams);
        LayoutParams actionParams = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(52));
        actionParams.gravity = Gravity.END | Gravity.BOTTOM;
        actionParams.setMargins(dp(16), dp(16), dp(16), dp(12));
        addView(actions, actionParams);

        configureWebView();
        navigationButton.setOnClickListener(v -> showNavigationMenu());
        downloadButton.setOnClickListener(v -> capturePageInfo(this::confirmDownload));
        stopButton.setOnClickListener(v -> {
            AvseeDownloadService.cancelAll(activity);
            status.setText("다운로드를 중단했습니다.");
            updateButtons();
        });
        webView.loadUrl(AvseeSettings.getBaseUrl(activity));
        updateButtons();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(MOBILE_UA);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreen(view, callback);
            }

            @Override public void onHideCustomView() {
                exitFullscreen();
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                                                    boolean isUserGesture, Message resultMsg) {
                WebView popup = new WebView(activity);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(
                            WebView popupView, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        if (isAddressGuideNavigation(uri)) {
                            trustAvseeDestination(uri);
                            webView.post(() -> webView.loadUrl(uri.toString()));
                            status.setText("최신 AVseeTV 주소로 이동합니다.");
                        } else {
                            status.setText("광고 팝업을 차단했습니다.");
                        }
                        popupView.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Uri startedUri = Uri.parse(url);
                if (isAddressGuideNavigation(startedUri)) {
                    trustAvseeDestination(startedUri);
                }
                if (shouldBlockExternalNavigation(startedUri)) {
                    view.stopLoading();
                    String safeUrl = lastTrustedUrl.isEmpty() ?
                            AvseeSettings.getBaseUrl(activity) : lastTrustedUrl;
                    view.post(() -> view.loadUrl(safeUrl));
                    status.setText("광고 페이지 이동을 차단하고 영상 페이지로 돌아갑니다.");
                    return;
                }
                if (!sameDocumentUrl(metadataPageUrl, url)) {
                    pageTitle = "";
                    pageThumb = "";
                    pageTags = "";
                    pageActors = "";
                    pageDescription = "";
                    metadataPageUrl = url == null ? "" : url;
                }
                lastMediaUrl = "";
                lastMediaHeaders = Collections.emptyMap();
                lastMediaScore = Integer.MIN_VALUE;
                status.setText(cleanCaptureInProgress ?
                        "광고를 제외하고 본 영상 주소를 찾는 중…" : "페이지를 불러오는 중…");
                updateButtons();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    if (request.isForMainFrame() && isAddressGuideNavigation(uri)) {
                        trustAvseeDestination(uri);
                        status.setText("최신 AVseeTV 주소로 이동합니다.");
                        return false;
                    }
                    if (request.isForMainFrame() && shouldBlockExternalNavigation(uri)) {
                        status.setText("외부 광고 페이지 이동을 차단했습니다.");
                        Toast.makeText(activity, "광고 페이지 이동을 차단했습니다.",
                                Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                }
                status.setText("외부 앱을 여는 광고 요청을 차단했습니다.");
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isMediaRequest(request)) rememberMedia(url, request.getRequestHeaders());
                return super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (clearHistoryOnNextPage) {
                    view.clearHistory();
                    clearHistoryOnNextPage = false;
                }
                rememberContentHost(url);
                if (isHttpsUrl(url) && !shouldBlockExternalNavigation(Uri.parse(url))) {
                    lastTrustedUrl = url;
                }
                view.evaluateJavascript(FULLSCREEN_SCRIPT, ignored -> { });
                if (cleanCaptureInProgress) {
                    view.getSettings().setJavaScriptEnabled(true);
                    status.setText("광고 제외 페이지에서 본 영상 주소를 확인하는 중…");
                    cleanCaptureAttempts = 0;
                    postDelayed(AvseeChannelView.this::continueCleanCapture, 1000L);
                    return;
                }
                capturePageInfo(null);
                status.setText("영상을 재생하면 다운로드 주소를 자동으로 감지합니다.");
                updateButtons();
            }
        });
    }

    private void startCleanCapture() {
        String pageUrl = webView.getUrl();
        if (!isHttpsUrl(pageUrl) || cleanCaptureInProgress) return;
        cleanCaptureInProgress = true;
        cleanCaptureAttempts = 0;
        lastMediaUrl = "";
        lastMediaHeaders = Collections.emptyMap();
        lastMediaScore = Integer.MIN_VALUE;
        status.setText("광고를 제외하고 본 영상 주소를 찾는 중…");
        webView.getSettings().setJavaScriptEnabled(false);
        webView.reload();
        updateButtons();
    }

    private void rememberMedia(String url, Map<String, String> headers) {
        String lower = url.toLowerCase(Locale.US);
        String current = lastMediaUrl.toLowerCase(Locale.US);
        int score = scoreMediaCandidate(url, headers);
        if (score < lastMediaScore) return;
        if (current.contains(".mp4") && lower.contains(".m3u8")) return;
        lastMediaScore = score;
        lastMediaUrl = url;
        lastMediaHeaders = headers == null ? Collections.emptyMap() : new HashMap<>(headers);
        post(() -> {
            status.setText(lower.contains(".m3u8") ?
                    "HLS(m3u8) 영상이 감지됐습니다." : "영상 주소 감지 완료 · 다운로드할 수 있습니다.");
            updateButtons();
        });
    }

    private void continueCleanCapture() {
        if (!cleanCaptureInProgress) return;
        capturePageInfo(() -> {
            cleanCaptureAttempts += 1;
            boolean strongCandidate = lastMediaUrl != null && !lastMediaUrl.isEmpty() &&
                    lastMediaScore >= 60;
            if ((strongCandidate && cleanCaptureAttempts >= 4) || cleanCaptureAttempts >= 12) {
                cleanCaptureInProgress = false;
                updateButtons();
                confirmDownload();
                return;
            }
            status.setText("본 영상 주소를 확인하는 중… " + cleanCaptureAttempts + "/12");
            postDelayed(this::continueCleanCapture, 900L);
        });
    }

    private void capturePageInfo(Runnable after) {
        webView.evaluateJavascript(PAGE_SCRIPT, value -> {
            try {
                Object decoded = new JSONTokener(value).nextValue();
                String json = decoded instanceof String ? (String) decoded : value;
                JSONObject object = new JSONObject(json);
                String capturedTitle = clean(object.optString("title", ""));
                String capturedThumb = clean(object.optString("thumb", ""));
                String capturedTags = clean(object.optString("tags", ""));
                String capturedActors = AvseeMetadata.cleanActors(
                        object.optString("actors", ""));
                String capturedDescription = clean(object.optString("description", ""));
                if (!capturedTitle.isEmpty()) pageTitle = capturedTitle;
                if (!capturedThumb.isEmpty()) pageThumb = capturedThumb;
                if (!capturedTags.isEmpty()) pageTags = capturedTags;
                if (!capturedActors.isEmpty()) pageActors = capturedActors;
                if (!capturedDescription.isEmpty()) pageDescription = capturedDescription;
                String detected = clean(object.optString("video", ""));
                if (isLikelyDomMediaUrl(detected) &&
                        !sameDocumentUrl(detected, webView.getUrl())) {
                    rememberMedia(detected, Collections.emptyMap());
                }
            } catch (Exception ignored) { }
            if (after != null) after.run();
        });
    }

    private void confirmDownload() {
        String pageUrl = webView.getUrl();
        if (!isHttpsUrl(pageUrl)) {
            Toast.makeText(activity, "설정한 AVSee 사이트의 영상 페이지에서 사용해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String videoUrl = sameDocumentUrl(lastMediaUrl, pageUrl) ||
                isDocumentOrPlayerUrl(lastMediaUrl) || lastMediaScore < 60 ? "" : lastMediaUrl;
        if (videoUrl == null || videoUrl.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("영상 주소를 찾지 못했습니다")
                    .setMessage("페이지의 영상 재생 버튼을 누르고 2~3초 뒤 다시 시도하세요.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        if (videoUrl.toLowerCase(Locale.US).contains(".m3u8")) {
            Toast.makeText(activity, "현재 버전은 MP4 직접 영상만 다운로드할 수 있습니다.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String title = pageTitle.isEmpty() ? clean(webView.getTitle()) : pageTitle;
        String cookie = mergeCookies(header(lastMediaHeaders, "Cookie"),
                CookieManager.getInstance().getCookie(videoUrl),
                CookieManager.getInstance().getCookie(pageUrl));
        String referer = firstNonEmpty(header(lastMediaHeaders, "Referer"), pageUrl);
        String userAgent = firstNonEmpty(header(lastMediaHeaders, "User-Agent"), MOBILE_UA);
        String finalTitle = title.isEmpty() ? "AVSee 영상" : title;
        boolean queueing = AvseeDownloadService.isRunning();
        new AlertDialog.Builder(activity)
                .setTitle(queueing ? "다운로드 대기열 추가" : "영상 다운로드")
                .setMessage(queueing ? "‘" + finalTitle + "’ 영상을 현재 다운로드 다음 순서에 추가합니다." :
                        "‘" + finalTitle + "’ 영상을 기기에 저장합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton(queueing ? "추가" : "시작", (dialog, which) -> {
                    AvseeDownloadService.enqueue(activity, finalTitle, videoUrl, pageThumb,
                            pageUrl, pageTags, pageActors, pageDescription,
                            referer, cookie, userAgent);
                    status.setText(queueing ? "다음 영상으로 다운로드 대기열에 추가했습니다." :
                            "다운로드를 시작했습니다. 다른 영상도 대기열에 추가할 수 있습니다.");
                    updateButtons();
                }).show();
    }

    private void updateButtons() {
        boolean running = AvseeDownloadService.isRunning();
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
        downloadButton.setText(cleanCaptureInProgress ? "영상 찾는 중…" :
                (running ? "대기열 추가" : "영상 다운로드"));
        downloadButton.setEnabled(!cleanCaptureInProgress);
        String url = webView.getUrl();
        boolean guidePage = url != null && AvseeSettings.isConfiguredHost(activity, url);
        boolean mediaDetected = lastMediaUrl != null && !lastMediaUrl.isEmpty();
        downloadButton.setVisibility(isHttpsUrl(url) && (!guidePage || mediaDetected)
                ? View.VISIBLE : View.GONE);
    }

    private void showNavigationMenu() {
        PopupMenu popup = new PopupMenu(activity, navigationButton);
        popup.getMenu().add(0, 1, 0, "\uB4A4\uB85C \uAC00\uAE30")
                .setEnabled(webView.canGoBack());
        popup.getMenu().add(0, 2, 1, "\uC55E\uC73C\uB85C \uAC00\uAE30")
                .setEnabled(webView.canGoForward());
        popup.getMenu().add(0, 3, 2, "\uBA54\uC778\uC73C\uB85C \uAC00\uAE30");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                webView.goBack();
                return true;
            }
            if (item.getItemId() == 2) {
                webView.goForward();
                return true;
            }
            if (item.getItemId() == 3) {
                goHome();
                return true;
            }
            return false;
        });
        popup.show();
    }


    public boolean canGoBack() { return webView.canGoBack(); }
    public void goBack() { webView.goBack(); }
    public void goHome() {
        clearHistoryOnNextPage = true;
        cleanCaptureInProgress = false;
        cleanCaptureAttempts = 0;
        lockedContentHost = "";
        lastTrustedUrl = "";
        webView.getSettings().setJavaScriptEnabled(true);
        webView.stopLoading();
        webView.loadUrl(AvseeSettings.getBaseUrl(activity));
    }

    public boolean isFullscreen() { return fullscreenView != null; }

    public void exitFullscreen() {
        if (fullscreenView == null) return;
        View view = fullscreenView;
        fullscreenView = null;
        FrameLayout container = fullscreenContainer;
        fullscreenContainer = null;
        if (container != null) {
            container.removeView(view);
            ViewParent containerParent = container.getParent();
            if (containerParent instanceof ViewGroup) {
                ((ViewGroup) containerParent).removeView(container);
            }
        } else {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        }
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(previousSystemUiVisibility);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        activity.refreshKeepScreenOnForDownload();
        activity.setRequestedOrientation(previousOrientation);
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        if (callback != null) callback.onCustomViewHidden();
    }

    private void showFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;
        previousOrientation = activity.getRequestedOrientation();
        View decor = activity.getWindow().getDecorView();
        previousSystemUiVisibility = decor.getSystemUiVisibility();
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        view.setBackgroundColor(Color.BLACK);

        FrameLayout container = new FrameLayout(activity);
        container.setBackgroundColor(Color.BLACK);
        fullscreenContainer = container;
        container.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ImageButton exitButton = new ImageButton(activity);
        exitButton.setImageResource(R.drawable.ic_player_fullscreen_exit);
        exitButton.setContentDescription("전체화면 종료");
        exitButton.setPadding(dp(11), dp(11), dp(11), dp(11));
        GradientDrawable exitBackground = new GradientDrawable();
        exitBackground.setColor(0x99000000);
        exitBackground.setShape(GradientDrawable.OVAL);
        exitButton.setBackground(exitBackground);
        exitButton.setOnClickListener(ignored -> exitFullscreen());
        FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(dp(46), dp(46));
        exitParams.gravity = Gravity.END | Gravity.BOTTOM;
        exitParams.setMargins(dp(10), dp(10), dp(12), dp(12));
        container.addView(exitButton, exitParams);

        ((ViewGroup) decor).addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(activity, receiver,
                    new IntentFilter(AvseeDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    public void destroyWebView() {
        exitFullscreen();
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        webView.stopLoading();
        webView.destroy();
    }

    private void rememberContentHost(String url) {
        if (!isHttpsUrl(url)) return;
        try {
            String host = Uri.parse(url).getHost();
            String configured = Uri.parse(AvseeSettings.getBaseUrl(activity)).getHost();
            if (host != null && hostMatches(configured, host) && lockedContentHost.isEmpty()) {
                lockedContentHost = host.toLowerCase(Locale.US);
            }
        } catch (Exception ignored) { }
    }

    private boolean isAddressGuideNavigation(Uri destination) {
        if (destination == null || destination.getHost() == null ||
                !isLikelyAvseeHost(destination.getHost()) ||
                isLikelyAdUrl(destination.toString())) return false;
        String configured;
        try {
            configured = Uri.parse(AvseeSettings.getBaseUrl(activity)).getHost();
        } catch (Exception ignored) {
            return false;
        }
        return isAddressGuideUrl(webView.getUrl(), configured) ||
                isAddressGuideUrl(lastTrustedUrl, configured);
    }

    private static boolean isAddressGuideUrl(String url, String configuredHost) {
        if (!isHttpsUrl(url)) return false;
        try {
            String host = Uri.parse(url).getHost();
            if (!hostMatches(configuredHost, host)) return false;
            String firstLabel = host == null ? "" :
                    host.toLowerCase(Locale.US).split("\\.")[0];
            return firstLabel.startsWith("url");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLikelyAvseeHost(String host) {
        if (host == null) return false;
        for (String label : host.toLowerCase(Locale.US).split("\\.")) {
            if ("avsee".equals(label) || label.matches("avsee\\d+")) return true;
        }
        return false;
    }

    private void trustAvseeDestination(Uri uri) {
        if (uri == null || uri.getHost() == null) return;
        lockedContentHost = uri.getHost().toLowerCase(Locale.US);
        lastTrustedUrl = uri.toString();
    }

    private boolean shouldBlockExternalNavigation(Uri uri) {
        return uri != null && isLikelyAdUrl(uri.toString());
    }

    private static boolean hostMatches(String allowed, String actual) {
        String a = allowed == null ? "" : allowed.toLowerCase(Locale.US);
        String b = actual == null ? "" : actual.toLowerCase(Locale.US);
        return !a.isEmpty() && (b.equals(a) || b.endsWith("." + a) || a.endsWith("." + b));
    }

    private static boolean isLikelyAdUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        String[] markers = {
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "exoclick", "exosrv", "popads", "propellerads", "adsterra",
                "trafficjunky", "juicyads", "popcash", "popunder"
        };
        for (String marker : markers) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }


    private static int scoreMediaCandidate(String url, Map<String, String> headers) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        String accept = header(headers, "Accept").toLowerCase(Locale.US);
        String fetchDest = header(headers, "Sec-Fetch-Dest").toLowerCase(Locale.US);
        int score = 0;
        if (lower.matches(".*\\.mp4(\\?.*)?(#.*)?$")) score += 100;
        else if (lower.matches(".*\\.(webm|m4v|mov)(\\?.*)?(#.*)?$")) score += 60;
        else if (lower.contains(".m3u8")) score += 10;
        if (accept.contains("video/")) score += 80;
        if ("video".equals(fetchDest)) score += 80;
        if (!header(headers, "Range").isEmpty()) score += 30;
        if (lower.matches(".*(doubleclick|googlesyndication|advert|/ads?[/?._-]|vast|pre-?roll|popunder|preview|trailer|sample|thumb).*")) {
            score -= 120;
        }
        return score;
    }

    private static boolean isDocumentOrPlayerUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            String path = Uri.parse(url).getPath();
            String lower = path == null ? "" : path.toLowerCase(Locale.US);
            return lower.endsWith(".php") || lower.endsWith(".html") ||
                    lower.endsWith(".htm") || lower.contains("/player/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLikelyDomMediaUrl(String url) {
        if (!isHttpsUrl(url) || isDocumentOrPlayerUrl(url)) return false;
        if (isMediaUrl(url)) return true;
        try {
            String path = Uri.parse(url).getPath();
            String lower = path == null ? "" : path.toLowerCase(Locale.US);
            return lower.contains("/video/") || lower.contains("/stream/") ||
                    lower.contains("/media/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isMediaUrl(String url) {
        if (url == null || url.isEmpty() || url.startsWith("blob:")) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.matches(".*\\.(mp4|webm|m4v|mov|m3u8)(\\?.*)?(#.*)?$");
    }

    private static boolean isMediaRequest(WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (!isHttpsUrl(url) || isDocumentOrPlayerUrl(url)) return false;
        if (isMediaUrl(url)) return true;
        if (request.isForMainFrame()) return false;
        Map<String, String> headers = request.getRequestHeaders();
        String accept = header(headers, "Accept").toLowerCase(Locale.US);
        String range = header(headers, "Range");
        String fetchDest = header(headers, "Sec-Fetch-Dest").toLowerCase(Locale.US);
        return !range.isEmpty() || "video".equals(fetchDest) || accept.contains("video/") ||
                accept.contains("application/vnd.apple.mpegurl") ||
                accept.contains("application/x-mpegurl");
    }

    private static boolean sameDocumentUrl(String first, String second) {
        if (first == null || second == null) return false;
        try {
            String a = Uri.parse(first).buildUpon().fragment(null).build().toString();
            String b = Uri.parse(second).buildUpon().fragment(null).build().toString();
            return a.equals(b);
        } catch (Exception ignored) {
            return first.equals(second);
        }
    }

    private static boolean isHttpsUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return clean(entry.getValue());
        }
        return "";
    }

    private static String mergeCookies(String... cookieHeaders) {
        Map<String, String> jar = new HashMap<>();
        for (String cookieHeader : cookieHeaders) {
            if (cookieHeader == null) continue;
            for (String part : cookieHeader.split(";")) {
                String item = part.trim();
                int equals = item.indexOf('=');
                if (equals > 0) jar.putIfAbsent(item.substring(0, equals).trim(), item.substring(equals + 1).trim());
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
