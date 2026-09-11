package com.webtoonmap.mobile.blacktoon;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Metadata already loaded by the user's site tab, also available after a manual resume. */
public final class BlacktoonMetadataStore {
    private BlacktoonMetadataStore() { }

    private static AtomicFile file(Context context, String key) {
        if (!BlacktoonApi.isSeriesKey(key)) throw new IllegalArgumentException("Invalid series key");
        return new AtomicFile(new File(context.getFilesDir(), key + "_browser.json"));
    }

    public static synchronized void put(Context context, String key, JSONObject snapshot) throws Exception {
        byte[] bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2_000_000) throw new IllegalArgumentException("작품 정보가 너무 큽니다.");
        AtomicFile target = file(context, key);
        FileOutputStream output = null;
        try {
            output = target.startWrite();
            output.write(bytes);
            target.finishWrite(output);
        } catch (Exception error) {
            if (output != null) target.failWrite(output);
            throw error;
        }
    }

    public static synchronized JSONObject get(Context context, String key) {
        try {
            return new JSONObject(new String(file(context, key).readFully(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized void remove(Context context, String key) {
        file(context, key).delete();
    }
}
