package com.webtoonmap.mobile.ui;

import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.activation.ActivationStore;
import com.webtoonmap.mobile.download.AvseeDownloadService;
import com.webtoonmap.mobile.storage.AvseeSettings;
import com.webtoonmap.mobile.storage.AvseeStorage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AvseeSettingsChannelView extends FrameLayout {
    private final MainActivity activity;
    private final EditText url;
    private final TextView storage;
    private final TextView activationUser;
    private final TextView version;
    private final Button clearTemporaryButton;
    private final TextView temporaryStatus;
    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();
    private boolean cleaning;

    public AvseeSettingsChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_avsee_settings, this, true);
        url = findViewById(R.id.avsee_url);
        storage = findViewById(R.id.avsee_storage_path);
        activationUser = findViewById(R.id.activation_user);
        version = findViewById(R.id.avsee_app_version);
        clearTemporaryButton = findViewById(R.id.clear_temporary_data);
        temporaryStatus = findViewById(R.id.temporary_data_status);
        findViewById(R.id.save_avsee_url).setOnClickListener(v -> saveUrl());
        clearTemporaryButton.setOnClickListener(v -> confirmClearTemporaryData());
        refresh();
    }

    public void refresh() {
        url.setText(AvseeSettings.getBaseUrl(activity));
        storage.setText(AvseeStorage.root(activity).getAbsolutePath());
        activationUser.setText("등록 사용자: " + ActivationStore.getUserName(activity));
        try {
            String name = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            version.setText("버전 " + name);
        } catch (Exception ignored) {
            version.setText("버전 2.1");
        }
    }

    private void confirmClearTemporaryData() {
        if (cleaning) return;
        if (AvseeDownloadService.isRunning()) {
            temporaryStatus.setText("다운로드가 끝난 뒤 임시 데이터를 삭제해 주세요.");
            Toast.makeText(activity, "다운로드 중에는 임시 파일을 삭제할 수 없습니다.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("임시 데이터 삭제")
                .setMessage("중단된 다운로드 파일과 앱이 만든 임시 캐시를 삭제합니다.\n\n완료된 영상, 썸네일, 영상 정보와 앱 설정은 유지됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> clearTemporaryData())
                .show();
    }

    private void clearTemporaryData() {
        if (cleaning) return;
        if (AvseeDownloadService.isRunning()) {
            temporaryStatus.setText("다운로드가 끝난 뒤 임시 데이터를 삭제해 주세요.");
            return;
        }
        cleaning = true;
        clearTemporaryButton.setEnabled(false);
        clearTemporaryButton.setText("삭제하는 중…");
        temporaryStatus.setText("임시 데이터를 확인하는 중…");
        cleanupExecutor.execute(() -> {
            try {
                AvseeStorage.CleanupResult result = AvseeStorage.clearTemporaryData(activity);
                post(() -> {
                    cleaning = false;
                    clearTemporaryButton.setEnabled(true);
                    clearTemporaryButton.setText("임시 데이터 삭제");
                    String message = result.isEmpty() ? "삭제할 임시 데이터가 없습니다." :
                            "임시 파일 " + result.removedFiles + "개 · " +
                                    AvseeStorage.formatBytes(result.removedBytes) + " 삭제 완료";
                    temporaryStatus.setText(message);
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ?
                        "임시 데이터를 삭제하지 못했습니다." : error.getMessage();
                post(() -> {
                    cleaning = false;
                    clearTemporaryButton.setEnabled(true);
                    clearTemporaryButton.setText("임시 데이터 삭제");
                    temporaryStatus.setText("삭제 실패 · " + message);
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    private void saveUrl() {
        if (!AvseeSettings.setBaseUrl(activity, url.getText().toString())) {
            Toast.makeText(activity, "https://로 시작하는 올바른 주소를 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        url.setText(AvseeSettings.getBaseUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "AVSee 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }
}
