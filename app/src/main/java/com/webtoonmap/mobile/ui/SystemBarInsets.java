package com.webtoonmap.mobile.ui;

import android.app.Activity;
import android.view.View;
import com.webtoonmap.mobile.storage.ThemeSettings;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public final class SystemBarInsets {
    private SystemBarInsets() { }

    public static void apply(Activity activity, View root, boolean lightStatusBar) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        boolean lightIconsBackground = lightStatusBar && !ThemeSettings.isBlack(activity);
        controller.setAppearanceLightStatusBars(lightIconsBackground);
        controller.setAppearanceLightNavigationBars(lightIconsBackground);

        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    initialLeft + bars.left,
                    initialTop + bars.top,
                    initialRight + bars.right,
                    initialBottom + bars.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
