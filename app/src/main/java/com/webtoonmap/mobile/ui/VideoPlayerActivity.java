package com.webtoonmap.mobile.ui;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.AvseeMetadata;
import com.webtoonmap.mobile.storage.AvseeStorage;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class VideoPlayerActivity extends AppCompatActivity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_THUMBNAIL_PATH = "thumbnail_path";
    public static final String EXTRA_TAGS = "tags";
    public static final String EXTRA_ACTORS = "actors";
    public static final String EXTRA_DESCRIPTION = "description";
    public static final String EXTRA_CREATED_AT = "created_at";
    public static final String EXTRA_SIZE_BYTES = "size_bytes";

    private static final String STATE_POSITION = "position";
    private static final long CONTROLS_TIMEOUT_MS = 3500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            updateProgress();
            handler.postDelayed(this, 500L);
        }
    };
    private final Runnable controlsHider = this::hideControls;

    private VideoView player;
    private View playerRoot;
    private View toolbar;
    private View playerStage;
    private View detailsScroll;
    private View controls;
    private ProgressBar loading;
    private ImageButton playPauseButton;
    private ImageButton fullscreenButton;
    private SeekBar seekBar;
    private TextView positionText;
    private int resumePosition;
    private boolean fullscreen;
    private boolean unlockAfterPortrait;
    private boolean prepared;
    private boolean userSeeking;
    private boolean wasPlayingBeforePause;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_video_player);

        playerRoot = findViewById(R.id.player_root);
        toolbar = findViewById(R.id.player_toolbar);
        playerStage = findViewById(R.id.player_stage);
        detailsScroll = findViewById(R.id.player_details_scroll);
        controls = findViewById(R.id.player_controls);
        loading = findViewById(R.id.player_loading);
        playPauseButton = findViewById(R.id.player_play_pause);
        fullscreenButton = findViewById(R.id.player_fullscreen);
        seekBar = findViewById(R.id.player_seek);
        positionText = findViewById(R.id.player_position);
        player = findViewById(R.id.video_player);

        SystemBarInsets.apply(this, playerRoot, true);
        findViewById(R.id.player_back).setOnClickListener(v -> finish());
        playPauseButton.setOnClickListener(v -> togglePlayback());
        fullscreenButton.setOnClickListener(v -> {
            if (fullscreen) exitLandscapeFullscreen();
            else enterLandscapeFullscreen();
        });
        playerStage.setOnClickListener(v -> toggleControls());
        player.setOnClickListener(v -> toggleControls());
        configureSeekBar();
        bindDetails();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).isFile()) {
            Toast.makeText(this, "영상 파일을 찾을 수 없습니다.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (state != null) resumePosition = state.getInt(STATE_POSITION, 0);
        player.setKeepScreenOn(true);
        player.setVideoPath(path);
        player.setOnPreparedListener(mediaPlayer -> {
            prepared = true;
            loading.setVisibility(View.GONE);
            if (resumePosition > 0) player.seekTo(resumePosition);
            player.start();
            updatePlaybackButton();
            updateProgress();
            scheduleControlsHide();
        });
        player.setOnCompletionListener(mediaPlayer -> {
            updatePlaybackButton();
            updateProgress();
            showControls(false);
        });
        player.setOnInfoListener((mediaPlayer, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) loading.setVisibility(View.VISIBLE);
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END ||
                    what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                loading.setVisibility(View.GONE);
            }
            return false;
        });
        player.setOnErrorListener((mediaPlayer, what, extra) -> {
            loading.setVisibility(View.GONE);
            Toast.makeText(this, "이 영상 형식은 기기 기본 플레이어에서 재생할 수 없습니다.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        applyFullscreenUi(getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE);
        handler.post(progressUpdater);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (fullscreen) exitLandscapeFullscreen();
                else finish();
            }
        });
    }

    private void bindDetails() {
        String title = clean(getIntent().getStringExtra(EXTRA_TITLE));
        if (title.isEmpty()) title = "다운로드 영상";
        ((TextView) findViewById(R.id.player_toolbar_title)).setText(title);
        ((TextView) findViewById(R.id.player_title)).setText(title);

        String date = clean(getIntent().getStringExtra(EXTRA_CREATED_AT));
        if (date.length() >= 10) date = date.substring(0, 10);
        long size = getIntent().getLongExtra(EXTRA_SIZE_BYTES, 0L);
        String meta = size > 0 ? AvseeStorage.formatBytes(size) : "";
        if (!date.isEmpty()) meta = meta.isEmpty() ? date : meta + " · " + date;
        ((TextView) findViewById(R.id.player_file_meta)).setText(
                meta.isEmpty() ? "기기에 저장된 영상" : meta);

        ImageView thumbnail = findViewById(R.id.player_thumbnail);
        String thumbnailPath = clean(getIntent().getStringExtra(EXTRA_THUMBNAIL_PATH));
        if (!thumbnailPath.isEmpty() && new File(thumbnailPath).isFile()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            thumbnail.setImageBitmap(BitmapFactory.decodeFile(thumbnailPath, options));
        } else {
            thumbnail.setVisibility(View.GONE);
        }

        String actors = AvseeMetadata.cleanActors(
                getIntent().getStringExtra(EXTRA_ACTORS));
        String tags = clean(getIntent().getStringExtra(EXTRA_TAGS));
        String description = clean(getIntent().getStringExtra(EXTRA_DESCRIPTION));

        View actorsSection = findViewById(R.id.player_actors_section);
        actorsSection.setVisibility(actors.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.player_actors)).setText(actors);

        View tagsSection = findViewById(R.id.player_tags_section);
        tagsSection.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        if (!tags.isEmpty()) addTagChips(tags);

        View descriptionSection = findViewById(R.id.player_description_section);
        descriptionSection.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.player_description)).setText(description);

        boolean hasDetails = !actors.isEmpty() || !tags.isEmpty() || !description.isEmpty();
        findViewById(R.id.player_no_details).setVisibility(hasDetails ? View.GONE : View.VISIBLE);
    }

    private void addTagChips(String value) {
        LinearLayout list = findViewById(R.id.player_tag_list);
        Set<String> tags = new LinkedHashSet<>();
        for (String part : value.split("[,|·#;\\n]+")) {
            String tag = clean(part);
            if (!tag.isEmpty()) tags.add(tag);
        }
        if (tags.isEmpty()) tags.add(value);
        for (String tag : tags) {
            TextView chip = new TextView(this);
            chip.setText("# " + tag);
            chip.setTextColor(ContextCompat.getColor(this, R.color.green_dark));
            chip.setTextSize(13f);
            chip.setBackgroundResource(R.drawable.bg_player_tag);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            list.addView(chip, params);
        }
    }

    private void configureSeekBar() {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || !prepared) return;
                int duration = player.getDuration();
                if (duration > 0) {
                    int target = (int) ((long) duration * progress / bar.getMax());
                    positionText.setText(formatTime(target) + " / " + formatTime(duration));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                showControls(false);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (prepared) {
                    int duration = player.getDuration();
                    if (duration > 0) {
                        player.seekTo((int) ((long) duration * bar.getProgress() / bar.getMax()));
                    }
                }
                userSeeking = false;
                scheduleControlsHide();
            }
        });
    }

    private void togglePlayback() {
        if (!prepared) return;
        if (player.isPlaying()) player.pause();
        else player.start();
        updatePlaybackButton();
        showControls(!player.isPlaying());
        if (player.isPlaying()) scheduleControlsHide();
    }

    private void updatePlaybackButton() {
        boolean playing = prepared && player.isPlaying();
        playPauseButton.setImageResource(playing ?
                R.drawable.ic_player_pause : R.drawable.ic_player_play);
        playPauseButton.setContentDescription(playing ? "일시정지" : "재생");
    }

    private void updateProgress() {
        if (!prepared || userSeeking) return;
        int duration = Math.max(0, player.getDuration());
        int position = Math.max(0, player.getCurrentPosition());
        if (duration > 0) {
            seekBar.setProgress((int) ((long) position * seekBar.getMax() / duration));
        }
        positionText.setText(formatTime(position) + " / " + formatTime(duration));
        updatePlaybackButton();
    }

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) hideControls();
        else showControls(true);
    }

    private void showControls(boolean keepVisible) {
        handler.removeCallbacks(controlsHider);
        controls.animate().cancel();
        controls.setAlpha(1f);
        controls.setVisibility(View.VISIBLE);
        if (!keepVisible) scheduleControlsHide();
    }

    private void hideControls() {
        if (!prepared || !player.isPlaying() || userSeeking) return;
        controls.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
            controls.setVisibility(View.GONE);
            controls.setAlpha(1f);
        }).start();
    }

    private void scheduleControlsHide() {
        handler.removeCallbacks(controlsHider);
        if (prepared && player.isPlaying()) {
            handler.postDelayed(controlsHider, CONTROLS_TIMEOUT_MS);
        }
    }

    private void enterLandscapeFullscreen() {
        unlockAfterPortrait = false;
        applyFullscreenUi(true);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void exitLandscapeFullscreen() {
        unlockAfterPortrait = true;
        applyFullscreenUi(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void applyFullscreenUi(boolean enabled) {
        fullscreen = enabled;
        toolbar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        detailsScroll.setVisibility(enabled ? View.GONE : View.VISIBLE);
        playerRoot.setBackgroundColor(enabled ? Color.BLACK :
                ContextCompat.getColor(this, R.color.background));
        fullscreenButton.setImageResource(enabled ?
                R.drawable.ic_player_fullscreen_exit : R.drawable.ic_player_fullscreen);
        fullscreenButton.setContentDescription(enabled ? "전체화면 종료" : "전체화면");

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) playerStage.getLayoutParams();
        if (enabled) {
            params.height = 0;
            params.weight = 1f;
            playerStage.setLayoutParams(params);
        } else {
            params.weight = 0f;
            playerStage.setLayoutParams(params);
            playerStage.post(this::applyNormalPlayerHeight);
        }

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        if (enabled) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
            controller.setAppearanceLightStatusBars(true);
            controller.setAppearanceLightNavigationBars(true);
        }
        ViewCompat.requestApplyInsets(playerRoot);
        showControls(false);
    }

    private void applyNormalPlayerHeight() {
        if (fullscreen) return;
        int width = playerStage.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) playerStage.getLayoutParams();
        params.height = Math.max(dp(190), width * 9 / 16);
        params.weight = 0f;
        playerStage.setLayoutParams(params);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        applyFullscreenUi(landscape);
        if (!landscape && unlockAfterPortrait) {
            unlockAfterPortrait = false;
            player.postDelayed(() -> setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED), 300L);
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_POSITION, player == null ? 0 : player.getCurrentPosition());
        super.onSaveInstanceState(outState);
    }

    @Override protected void onPause() {
        if (player != null) {
            wasPlayingBeforePause = player.isPlaying();
            resumePosition = player.getCurrentPosition();
            player.pause();
            updatePlaybackButton();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prepared && wasPlayingBeforePause) {
            player.start();
            wasPlayingBeforePause = false;
            scheduleControlsHide();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.stopPlayback();
        super.onDestroy();
    }

    private static String formatTime(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remaining = seconds % 60;
        return hours > 0 ? String.format(Locale.getDefault(), "%d:%02d:%02d",
                hours, minutes, remaining) :
                String.format(Locale.getDefault(), "%02d:%02d", minutes, remaining);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}