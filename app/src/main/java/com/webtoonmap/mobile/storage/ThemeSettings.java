package com.webtoonmap.mobile.storage;

import android.content.Context;

public final class ThemeSettings {
    public static final String WHITE = "white";
    public static final String BLACK = "black";
    private static final String PREFS = "appearance_settings";

    private ThemeSettings() { }

    public static String getTheme(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", WHITE);
        return BLACK.equals(saved) ? BLACK : WHITE;
    }

    public static boolean isBlack(Context context) {
        return BLACK.equals(getTheme(context));
    }

    public static void setTheme(Context context, String theme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("theme", BLACK.equals(theme) ? BLACK : WHITE).apply();
    }
}
