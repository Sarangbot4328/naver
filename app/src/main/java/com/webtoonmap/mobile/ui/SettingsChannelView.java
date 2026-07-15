package com.webtoonmap.mobile.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.storage.StorageSettings;
import com.webtoonmap.mobile.storage.SourceSettings;

public final class SettingsChannelView extends FrameLayout {
    private final MainActivity activity;
    private final TextView path;
    private final TextView version;
    private final RadioGroup sourceGroup;
    private final View joatoonAddressBox;
    private final EditText joatoonUrl;
    private boolean refreshing;

    public SettingsChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_settings, this, true);
        path = findViewById(R.id.storage_path);
        version = findViewById(R.id.app_version);
        sourceGroup = findViewById(R.id.source_group);
        joatoonAddressBox = findViewById(R.id.joatoon_address_box);
        joatoonUrl = findViewById(R.id.joatoon_url);
        findViewById(R.id.choose_folder).setOnClickListener(v -> activity.openStorageFolderPicker());
        findViewById(R.id.reset_folder).setOnClickListener(v -> confirmReset());
        sourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (refreshing) return;
            boolean joatoon = checkedId == R.id.source_joatoon;
            SourceSettings.setSource(activity, joatoon ?
                    SourceSettings.SOURCE_JOATOON : SourceSettings.SOURCE_NAVER);
            joatoonAddressBox.setVisibility(joatoon ? VISIBLE : GONE);
            activity.applyChannelSettings();
            Toast.makeText(activity, joatoon ? "조아툰 채널로 변경했습니다." :
                    "네이버 웹툰 채널로 변경했습니다.", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.save_joatoon_url).setOnClickListener(v -> saveJoatoonUrl());
        refresh();
    }

    public void refresh() {
        refreshing = true;
        path.setText(StorageSettings.displayName(activity));
        boolean joatoon = SourceSettings.isJoatoon(activity);
        sourceGroup.check(joatoon ? R.id.source_joatoon : R.id.source_naver);
        joatoonAddressBox.setVisibility(joatoon ? VISIBLE : GONE);
        joatoonUrl.setText(SourceSettings.getJoatoonUrl(activity));
        try {
            String name = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            version.setText("버전 " + name);
        } catch (Exception ignored) {
            version.setText("버전 1.1");
        }
        refreshing = false;
    }

    private void saveJoatoonUrl() {
        if (!SourceSettings.setJoatoonUrl(activity, joatoonUrl.getText().toString())) {
            Toast.makeText(activity, "https://로 시작하는 올바른 주소를 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        joatoonUrl.setText(SourceSettings.getJoatoonUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "조아툰 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
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
