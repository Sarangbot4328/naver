package com.webtoonmap.mobile;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.webtoonmap.mobile.activation.ActivationActivity;
import com.webtoonmap.mobile.activation.ActivationStore;
import com.webtoonmap.mobile.download.AvseeDownloadService;
import com.webtoonmap.mobile.ui.AvseeDownloadChannelView;
import com.webtoonmap.mobile.ui.AvseeChannelView;
import com.webtoonmap.mobile.ui.AvseeSettingsChannelView;
import com.webtoonmap.mobile.ui.SystemBarInsets;

public final class MainActivity extends AppCompatActivity {
    private FrameLayout content;
    private Button naverButton;
    private Button downloadsButton;
    private Button settingsButton;
    private AvseeChannelView naverView;
    private AvseeDownloadChannelView downloadsView;
    private AvseeSettingsChannelView settingsView;
    private int selectedChannel = 0;
    private boolean downloadReceiverRegistered;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshKeepScreenOnForDownload();
            if (intent.getBooleanExtra(AvseeDownloadService.EXTRA_DONE, false) ||
                    intent.getBooleanExtra(AvseeDownloadService.EXTRA_ERROR, false)) {
                content.postDelayed(MainActivity.this::refreshKeepScreenOnForDownload, 250L);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ActivationStore.isActivated(this)) {
            startActivity(new Intent(this, ActivationActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        SystemBarInsets.apply(this, findViewById(R.id.main_root), true);
        content = findViewById(R.id.content);
        naverButton = findViewById(R.id.nav_avsee);
        downloadsButton = findViewById(R.id.nav_downloads);
        settingsButton = findViewById(R.id.nav_settings);
        naverView = new AvseeChannelView(this);
        downloadsView = new AvseeDownloadChannelView(this);
        settingsView = new AvseeSettingsChannelView(this);

        naverButton.setOnClickListener(v -> showNaver());
        downloadsButton.setOnClickListener(v -> showDownloads());
        settingsButton.setOnClickListener(v -> showSettings());
        showNaver();
        requestNotificationPermission();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (naverView.isFullscreen()) {
                    naverView.exitFullscreen();
                } else if (selectedChannel != 0) {
                    showNaver();
                } else if (naverView.canGoBack()) {
                    naverView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    public void showDownloads() {
        selectedChannel = 1;
        swap(downloadsView);
        downloadsView.refresh();
        tintNavigation();
    }

    public void refreshDownloads() {
        if (downloadsView != null) downloadsView.refresh();
    }

    private void showNaver() {
        selectedChannel = 0;
        swap(naverView);
        tintNavigation();
    }

    private void showSettings() {
        selectedChannel = 2;
        settingsView.refresh();
        swap(settingsView);
        tintNavigation();
    }

    public void applyChannelSettings() {
        boolean showing = selectedChannel == 0;
        if (naverView != null) naverView.destroyWebView();
        naverView = new AvseeChannelView(this);
        if (showing) swap(naverView);
        tintNavigation();
    }

    private void swap(android.view.View view) {
        if (view.getParent() == content) return;
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void tintNavigation() {
        int active = ContextCompat.getColor(this, R.color.green_dark);
        int idle = ContextCompat.getColor(this, R.color.text_secondary);
        naverButton.setTextColor(selectedChannel == 0 ? active : idle);
        downloadsButton.setTextColor(selectedChannel == 1 ? active : idle);
        settingsButton.setTextColor(selectedChannel == 2 ? active : idle);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 400);
        }
    }

    public void refreshKeepScreenOnForDownload() {
        boolean keepAwake = AvseeDownloadService.isRunning() ||
                (naverView != null && naverView.isFullscreen());
        if (keepAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (!downloadReceiverRegistered) {
            ContextCompat.registerReceiver(this, downloadReceiver,
                    new IntentFilter(AvseeDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            downloadReceiverRegistered = true;
        }
        refreshKeepScreenOnForDownload();
    }

    @Override protected void onStop() {
        if (downloadReceiverRegistered) {
            unregisterReceiver(downloadReceiver);
            downloadReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (naverView != null) naverView.destroyWebView();
        super.onDestroy();
    }
}


