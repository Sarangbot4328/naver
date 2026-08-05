package com.webtoonmap.mobile.ui;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.export.TransferImporter;
import com.webtoonmap.mobile.storage.SourceSettings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsChannelView extends FrameLayout {
    private final MainActivity activity;
    private final TextView version;
    private final RadioGroup sourceGroup;
    private final RadioGroup viewModeGroup;
    private final View joatoonAddressBox;
    private final View manhwabangAddressBox;
    private final View ililtoonAddressBox;
    private final EditText joatoonUrl;
    private final EditText manhwabangUrl;
    private final EditText ililtoonUrl;
    private final Button importButton;
    private final TextView importStatus;
    private final ActivityResultLauncher<String[]> importLauncher;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private boolean importing;
    private boolean refreshing;

    public SettingsChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_settings, this, true);
        version = findViewById(R.id.app_version);
        sourceGroup = findViewById(R.id.source_group);
        viewModeGroup = findViewById(R.id.view_mode_group);
        joatoonAddressBox = findViewById(R.id.joatoon_address_box);
        manhwabangAddressBox = findViewById(R.id.manhwabang_address_box);
        ililtoonAddressBox = findViewById(R.id.ililtoon_address_box);
        joatoonUrl = findViewById(R.id.joatoon_url);
        manhwabangUrl = findViewById(R.id.manhwabang_url);
        ililtoonUrl = findViewById(R.id.ililtoon_url);
        importButton = findViewById(R.id.import_transfer);
        importStatus = findViewById(R.id.import_status);
        importLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) importTransfer(uri);
                });
        sourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (refreshing) return;
            String source = sourceForCheckedId(checkedId);
            SourceSettings.setSource(activity, source);
            updateAddressVisibility(source);
            activity.applyChannelSettings();
            Toast.makeText(activity, SourceSettings.channelLabel(activity) +
                    " 채널로 변경했습니다.", Toast.LENGTH_SHORT).show();
        });
        viewModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (refreshing) return;
            String mode = checkedId == R.id.view_mode_page
                    ? SourceSettings.VIEW_MODE_PAGE : SourceSettings.VIEW_MODE_SCROLL;
            SourceSettings.setViewMode(activity, mode);
            Toast.makeText(activity, checkedId == R.id.view_mode_page
                            ? "만화책 방식(옆으로 넘김)으로 변경했습니다."
                            : "웹툰 방식(아래로 스크롤)으로 변경했습니다.",
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.save_joatoon_url).setOnClickListener(v -> saveJoatoonUrl());
        findViewById(R.id.save_manhwabang_url).setOnClickListener(v -> saveManhwabangUrl());
        findViewById(R.id.save_ililtoon_url).setOnClickListener(v -> saveIliltoonUrl());
        importButton.setOnClickListener(v -> {
            if (!importing) importLauncher.launch(new String[]{"application/zip", "application/octet-stream"});
        });
        refresh();
    }

    public void refresh() {
        refreshing = true;
        String source = SourceSettings.getSource(activity);
        sourceGroup.check(checkedIdForSource(source));
        updateAddressVisibility(source);
        viewModeGroup.check(SourceSettings.isPageMode(activity)
                ? R.id.view_mode_page : R.id.view_mode_scroll);
        joatoonUrl.setText(SourceSettings.getJoatoonUrl(activity));
        manhwabangUrl.setText(SourceSettings.getManhwabangUrl(activity));
        ililtoonUrl.setText(SourceSettings.getIliltoonUrl(activity));
        try {
            String name = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            version.setText("버전 " + name);
        } catch (Exception ignored) {
            version.setText("버전 1.3");
        }
        refreshing = false;
    }

    private void importTransfer(Uri uri) {
        if (importing) return;
        importing = true;
        importButton.setEnabled(false);
        importButton.setText("가져오는 중…");
        importStatus.setText("파일을 확인하는 중…");
        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("데이터 가져오기")
                .setMessage("파일을 확인하는 중…")
                .setCancelable(false)
                .create();
        progressDialog.show();
        importExecutor.execute(() -> {
            try {
                TransferImporter.Result result = TransferImporter.importArchive(activity, uri,
                        (current, total, title) -> post(() -> {
                            String text = current + "/" + total + " · ‘" + title + "’ 복원 중";
                            importStatus.setText(text);
                            progressDialog.setMessage(text);
                        }));
                post(() -> {
                    importing = false;
                    importButton.setEnabled(true);
                    importButton.setText("데이터 가져오기");
                    activity.refreshDownloads();
                    String summary = "가져오기 완료 · " + result.imported.size() + "개 작품";
                    if (!result.errors.isEmpty()) {
                        summary += " · 오류 " + result.errors.size() + "개";
                        importStatus.setText(summary + "\n" + result.errors.get(0));
                    } else {
                        importStatus.setText(summary);
                    }
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("가져오기 완료")
                            .setMessage(summary + (result.errors.isEmpty() ? "" : "\n" + result.errors.get(0)))
                            .setPositiveButton("확인", null)
                            .show();
                    Toast.makeText(activity, summary, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "가져오기에 실패했습니다." : error.getMessage();
                post(() -> {
                    importing = false;
                    importButton.setEnabled(true);
                    importButton.setText("데이터 가져오기");
                    importStatus.setText("가져오기 실패 · " + message);
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("가져오기 실패")
                            .setMessage(message)
                            .setPositiveButton("확인", null)
                            .show();
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        });
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

    private void saveManhwabangUrl() {
        if (!SourceSettings.setManhwabangUrl(activity, manhwabangUrl.getText().toString())) {
            invalidUrl();
            return;
        }
        manhwabangUrl.setText(SourceSettings.getManhwabangUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "만화방 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void saveIliltoonUrl() {
        if (!SourceSettings.setIliltoonUrl(activity, ililtoonUrl.getText().toString())) {
            invalidUrl();
            return;
        }
        ililtoonUrl.setText(SourceSettings.getIliltoonUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "일일툰 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void invalidUrl() {
        Toast.makeText(activity, "https://로 시작하는 올바른 주소를 입력해 주세요.",
                Toast.LENGTH_LONG).show();
    }

    private String sourceForCheckedId(int checkedId) {
        if (checkedId == R.id.source_joatoon) return SourceSettings.SOURCE_JOATOON;
        if (checkedId == R.id.source_manhwabang) return SourceSettings.SOURCE_MANHWABANG;
        if (checkedId == R.id.source_ililtoon) return SourceSettings.SOURCE_ILILTOON;
        return SourceSettings.SOURCE_NAVER;
    }

    private int checkedIdForSource(String source) {
        if (SourceSettings.SOURCE_JOATOON.equals(source)) return R.id.source_joatoon;
        if (SourceSettings.SOURCE_MANHWABANG.equals(source)) return R.id.source_manhwabang;
        if (SourceSettings.SOURCE_ILILTOON.equals(source)) return R.id.source_ililtoon;
        return R.id.source_naver;
    }

    private void updateAddressVisibility(String source) {
        joatoonAddressBox.setVisibility(SourceSettings.SOURCE_JOATOON.equals(source) ? VISIBLE : GONE);
        manhwabangAddressBox.setVisibility(SourceSettings.SOURCE_MANHWABANG.equals(source) ? VISIBLE : GONE);
        ililtoonAddressBox.setVisibility(SourceSettings.SOURCE_ILILTOON.equals(source) ? VISIBLE : GONE);
    }

}

