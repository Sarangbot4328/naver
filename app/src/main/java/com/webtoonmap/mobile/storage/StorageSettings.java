package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

public final class StorageSettings {
    private static final String PREFS = "storage_settings";
    private static final String KEY_TREE_URI = "download_tree_uri";

    private StorageSettings() { }

    public static String getTreeUri(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TREE_URI, null);
    }

    public static void setTreeUri(Context context, String uri) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (uri == null || uri.isEmpty()) editor.remove(KEY_TREE_URI);
        else editor.putString(KEY_TREE_URI, uri);
        editor.apply();
    }

    public static String displayName(Context context) {
        String uri = getTreeUri(context);
        if (uri == null) return "앱 내부 저장소 (기본)";
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(context, Uri.parse(uri));
            String name = folder == null ? null : folder.getName();
            return name == null ? uri : name + "\n" + uri;
        } catch (Exception ignored) {
            return uri;
        }
    }
}
