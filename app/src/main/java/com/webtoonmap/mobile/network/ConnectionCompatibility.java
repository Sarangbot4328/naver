package com.webtoonmap.mobile.network;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.webtoonmap.mobile.storage.SourceSettings;

public final class ConnectionCompatibility {
    private static final String LEGACY_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";
    private static final int OLD_WEBVIEW_MAJOR = 120;
    private static volatile String requestUserAgent = LEGACY_USER_AGENT;

    private ConnectionCompatibility() { }

    public static void configure(Context context) {
        requestUserAgent = SourceSettings.isCompatibilityMode(context)
                ? webViewUserAgent(context) : LEGACY_USER_AGENT;
    }


    public static void configureForWebView(Context context) {
        requestUserAgent = webViewUserAgent(context);
    }
    public static String requestUserAgent() {
        return requestUserAgent;
    }

    public static String webViewUserAgent(Context context) {
        try {
            return WebSettings.getDefaultUserAgent(context)
                    .replace("; wv", "")
                    .replace(" Version/4.0", "");
        } catch (Exception ignored) {
            return LEGACY_USER_AGENT;
        }
    }

    public static PackageInfo webViewPackage() {
        try {
            return WebView.getCurrentWebViewPackage();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String webViewVersion() {
        PackageInfo info = webViewPackage();
        return info == null || info.versionName == null ? "확인 불가" : info.versionName;
    }

    public static boolean isWebViewLikelyOld() {
        PackageInfo info = webViewPackage();
        if (info == null || info.versionName == null) return true;
        try {
            int dot = info.versionName.indexOf('.');
            String major = dot < 0 ? info.versionName : info.versionName.substring(0, dot);
            return Integer.parseInt(major) < OLD_WEBVIEW_MAJOR;
        } catch (Exception ignored) {
            return true;
        }
    }

    public static void openWebViewUpdate(Context context) {
        PackageInfo info = webViewPackage();
        String packageName = info == null ? "com.google.android.webview" : info.packageName;
        Intent market = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + packageName));
        market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(market);
        } catch (Exception ignored) {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://play.google.com/store/apps/details?id=" + packageName));
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(browser);
            } catch (Exception second) {
                Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + packageName));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
            }
        }
    }
}
