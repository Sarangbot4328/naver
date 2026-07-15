package com.webtoonmap.mobile.ui;

import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.storage.StorageSettings;

public final class SettingsChannelView extends FrameLayout {
    private final MainActivity activity;
    private final TextView path;
    private final TextView version;

    public SettingsChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_settings, this, true);
        path = findViewById(R.id.storage_path);
        version = findViewById(R.id.app_version);
        findViewById(R.id.choose_folder).setOnClickListener(v -> activity.openStorageFolderPicker());
        findViewById(R.id.reset_folder).setOnClickListener(v -> confirmReset());
        refresh();
    }

    public void refresh() {
        path.setText(StorageSettings.displayName(activity));
        try {
            String name = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            version.setText("버전 " + name);
        } catch (Exception ignored) {
            version.setText("버전 1.1");
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(activity)
                .setTitle("기본 저장소로 변경")
                .setMessage("앞으로 새로 받는 작품을 앱 내부 저장소에 저장합니다. 기존 작품은 이동하거나 삭제하지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("변경", (dialog, which) -> {
                    StorageSettings.setTreeUri(activity, null);
                    refresh();
                    Toast.makeText(activity, "기본 저장소로 변경했습니다.", Toast.LENGTH_SHORT).show();
                }).show();
    }
}
