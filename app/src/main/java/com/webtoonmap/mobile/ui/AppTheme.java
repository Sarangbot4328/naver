package com.webtoonmap.mobile.ui;

import android.app.UiModeManager;
import android.content.Context;
import android.os.Build;
import androidx.appcompat.app.AppCompatDelegate;
import com.webtoonmap.mobile.storage.ThemeSettings;

public final class AppTheme {
    private AppTheme() { }

    public static void apply(Context context) {
        boolean black = ThemeSettings.isBlack(context);
        int mode = black ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() == mode) return;
        if (Build.VERSION.SDK_INT >= 31) {
            UiModeManager manager = context.getSystemService(UiModeManager.class);
            if (manager != null) manager.setApplicationNightMode(
                    black ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO);
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
