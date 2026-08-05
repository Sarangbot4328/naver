package com.webtoonmap.mobile.ui;

import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.AvseeLibraryDatabase;
import com.webtoonmap.mobile.data.AvseeMetadata;
import com.webtoonmap.mobile.data.AvseeVideo;
import com.webtoonmap.mobile.download.AvseeDownloadService;
import com.webtoonmap.mobile.storage.AvseeStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AvseeDownloadChannelView extends FrameLayout {
    private final MainActivity activity;
    private final AvseeLibraryDatabase database;
    private final VideoAdapter adapter;
    private final TextView empty;
    private final TextView status;
    private final View progressPanel;
    private final TextView progressTitle;
    private final TextView progressMessage;
    private final ProgressBar progressBar;
    private final SwipeRefreshLayout swipe;
    private final Button exportButton;
    private final View selectionBar;
    private final TextView selectionCount;
    private final Button selectAllButton;
    private final Button exportSelectedButton;
    private final ActivityResultLauncher<Uri> folderLauncher;
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private final Set<Long> selectedIds = new LinkedHashSet<>();
    private List<AvseeVideo> pendingFolderExport = new ArrayList<>();
    private boolean selectionMode;
    private boolean exporting;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(AvseeDownloadService.EXTRA_MESSAGE);
            String title = intent.getStringExtra(AvseeDownloadService.EXTRA_TITLE);
            int percent = intent.getIntExtra(AvseeDownloadService.EXTRA_PERCENT, -1);
            boolean done = intent.getBooleanExtra(AvseeDownloadService.EXTRA_DONE, false);
            boolean error = intent.getBooleanExtra(AvseeDownloadService.EXTRA_ERROR, false);
            showProgress(title, message, percent);
            if (done) {
                refresh();
                postDelayed(() -> {
                    if (!AvseeDownloadService.isRunning()) progressPanel.setVisibility(GONE);
                }, 4000L);
            } else if (error) {
                postDelayed(() -> {
                    if (!AvseeDownloadService.isRunning()) progressPanel.setVisibility(GONE);
                }, 6000L);
            }
        }
    };

    public AvseeDownloadChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        folderLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::exportToFolder);
        LayoutInflater.from(activity).inflate(R.layout.channel_avsee_downloads, this, true);
        database = new AvseeLibraryDatabase(activity);
        status = findViewById(R.id.video_status);
        progressPanel = findViewById(R.id.download_progress_panel);
        progressTitle = findViewById(R.id.download_progress_title);
        progressMessage = findViewById(R.id.download_progress_message);
        progressBar = findViewById(R.id.download_progress_bar);
        empty = findViewById(R.id.video_empty);
        swipe = findViewById(R.id.video_swipe);
        exportButton = findViewById(R.id.export_videos);
        selectionBar = findViewById(R.id.export_selection_bar);
        selectionCount = findViewById(R.id.export_selection_count);
        selectAllButton = findViewById(R.id.select_all_videos);
        exportSelectedButton = findViewById(R.id.export_selected_videos);
        RecyclerView list = findViewById(R.id.video_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new VideoAdapter();
        list.setAdapter(adapter);
        findViewById(R.id.refresh_videos).setOnClickListener(v -> refresh());
        exportButton.setOnClickListener(v -> beginSelection());
        selectAllButton.setOnClickListener(v -> toggleSelectAll());
        exportSelectedButton.setOnClickListener(v -> showExportChoices());
        findViewById(R.id.cancel_video_selection).setOnClickListener(v -> exitSelectionMode());
        swipe.setOnRefreshListener(this::refresh);
        refresh();
    }

    public void refresh() {
        List<AvseeVideo> videos = database.listAll();
        adapter.replace(videos);
        empty.setVisibility(videos.isEmpty() ? VISIBLE : GONE);
        status.setText(videos.isEmpty() ? "저장한 AVSee 영상을 볼 수 있습니다" :
                "저장된 영상 " + videos.size() + "개");
        exportButton.setEnabled(!videos.isEmpty() && !exporting);
        if (videos.isEmpty() && selectionMode) exitSelectionMode();
        else updateSelectionUi();
        swipe.setRefreshing(false);
    }

    private void syncProgress() {
        if (!AvseeDownloadService.isRunning()) {
            progressPanel.setVisibility(GONE);
            return;
        }
        showProgress(AvseeDownloadService.getCurrentTitle(),
                AvseeDownloadService.getCurrentMessage(),
                AvseeDownloadService.getCurrentPercent());
    }

    private void showProgress(String title, String message, int percent) {
        progressPanel.setVisibility(VISIBLE);
        progressTitle.setText(title == null || title.trim().isEmpty() ?
                "AVSee 영상 다운로드" : title.trim());
        progressMessage.setText(message == null || message.trim().isEmpty() ?
                "다운로드 준비 중…" : message.trim());
        if (percent >= 0) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(Math.min(100, percent));
        } else {
            progressBar.setIndeterminate(true);
        }
    }

    private void beginSelection() {
        if (adapter.getItemCount() == 0) {
            Toast.makeText(activity, "내보낼 영상이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        selectionMode = true;
        selectedIds.clear();
        selectionBar.setVisibility(VISIBLE);
        exportButton.setVisibility(GONE);
        swipe.setEnabled(false);
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        selectionBar.setVisibility(GONE);
        exportButton.setVisibility(VISIBLE);
        exportButton.setEnabled(adapter.getItemCount() > 0 && !exporting);
        swipe.setEnabled(true);
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(AvseeVideo video) {
        if (!selectionMode) return;
        if (!selectedIds.add(video.id)) selectedIds.remove(video.id);
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void toggleSelectAll() {
        if (selectedIds.size() == adapter.getItemCount()) {
            selectedIds.clear();
        } else {
            selectedIds.clear();
            for (AvseeVideo video : adapter.items) selectedIds.add(video.id);
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        if (!selectionMode) return;
        Set<Long> validIds = new HashSet<>();
        for (AvseeVideo video : adapter.items) validIds.add(video.id);
        selectedIds.retainAll(validIds);
        selectionCount.setText(selectedIds.size() + "개 선택");
        selectAllButton.setText(!adapter.items.isEmpty() &&
                selectedIds.size() == adapter.items.size() ? "전체 해제" : "전체 선택");
        exportSelectedButton.setEnabled(!selectedIds.isEmpty() && !exporting);
    }

    private List<AvseeVideo> selectedVideos() {
        List<AvseeVideo> selected = new ArrayList<>();
        for (AvseeVideo video : adapter.items) {
            if (selectedIds.contains(video.id)) selected.add(video);
        }
        return selected;
    }

    private void showExportChoices() {
        List<AvseeVideo> selected = selectedVideos();
        if (selected.isEmpty()) {
            Toast.makeText(activity, "내보낼 영상을 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] choices = {
                "카카오톡 · 텔레그램 · Quick Share로 공유",
                "내부 저장소 · SD카드 · 외장 저장장치에 저장"
        };
        new AlertDialog.Builder(activity)
                .setTitle(selected.size() + "개 영상 내보내기")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) shareVideos(selected);
                    else chooseExportFolder(selected);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void shareVideos(List<AvseeVideo> videos) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();
            for (AvseeVideo video : videos) {
                File file = new File(video.filePath);
                if (!file.isFile()) continue;
                String displayName = uniqueFileName(video, file, usedNames);
                uris.add(FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", file, displayName));
            }
            if (uris.isEmpty()) {
                Toast.makeText(activity, "공유할 영상 파일을 찾을 수 없습니다.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            Intent share = new Intent(uris.size() == 1 ?
                    Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                    .setType("video/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (uris.size() == 1) share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);

            ClipData clip = new ClipData("AVSee 영상", new String[]{"video/*"},
                    new ClipData.Item(uris.get(0)));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            share.setClipData(clip);
            activity.startActivity(Intent.createChooser(share,
                    uris.size() + "개 영상 공유"));
            exitSelectionMode();
        } catch (Exception error) {
            Toast.makeText(activity, "공유 화면을 열지 못했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseExportFolder(List<AvseeVideo> videos) {
        pendingFolderExport = new ArrayList<>(videos);
        folderLauncher.launch(null);
    }

    private void exportToFolder(Uri treeUri) {
        if (treeUri == null) {
            pendingFolderExport.clear();
            return;
        }
        List<AvseeVideo> videos = new ArrayList<>(pendingFolderExport);
        pendingFolderExport.clear();
        if (videos.isEmpty()) return;

        exporting = true;
        updateSelectionUi();
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("영상 내보내는 중");
        progress.setMessage("0 / " + videos.size());
        progress.setIndeterminate(false);
        progress.setCancelable(false);
        progress.show();

        exportExecutor.execute(() -> {
            int copied = 0;
            int failed = 0;
            try {
                DocumentFile directory = DocumentFile.fromTreeUri(activity, treeUri);
                if (directory == null || !directory.isDirectory() || !directory.canWrite()) {
                    throw new IllegalStateException("선택한 위치에 파일을 저장할 수 없습니다.");
                }
                Set<String> usedNames = new HashSet<>();
                int handled = 0;
                for (AvseeVideo video : videos) {
                    File source = new File(video.filePath);
                    if (!source.isFile()) {
                        failed++;
                        handled++;
                        final int current = handled;
                        post(() -> progress.setMessage(current + " / " + videos.size()));
                        continue;
                    }
                    String name = uniqueFileName(video, source, usedNames);
                    DocumentFile destination = null;
                    try {
                        destination = directory.createFile(mimeType(source), name);
                        if (destination == null) throw new IllegalStateException("파일 생성 실패");
                        try (InputStream input = new FileInputStream(source);
                             OutputStream output = activity.getContentResolver()
                                     .openOutputStream(destination.getUri(), "w")) {
                            if (output == null) throw new IllegalStateException("파일 열기 실패");
                            byte[] buffer = new byte[256 * 1024];
                            int count;
                            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                            output.flush();
                        }
                        copied++;
                    } catch (Exception error) {
                        failed++;
                        if (destination != null) destination.delete();
                    }
                    handled++;
                    final int current = handled;
                    post(() -> progress.setMessage(current + " / " + videos.size()));
                }
                final int completed = copied;
                final int errors = failed;
                post(() -> finishFolderExport(progress, completed, errors));
            } catch (Exception error) {
                String message = error.getMessage() == null ?
                        "영상을 내보내지 못했습니다." : error.getMessage();
                post(() -> failFolderExport(progress, message));
            }
        });
    }

    private void finishFolderExport(ProgressDialog progress, int copied, int failed) {
        exporting = false;
        progress.dismiss();
        exitSelectionMode();
        String message = copied + "개 영상을 선택한 위치에 저장했습니다.";
        if (failed > 0) message += "\n저장하지 못한 영상: " + failed + "개";
        new AlertDialog.Builder(activity)
                .setTitle(failed == 0 ? "내보내기 완료" : "내보내기 일부 완료")
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show();
    }

    private void failFolderExport(ProgressDialog progress, String message) {
        exporting = false;
        progress.dismiss();
        updateSelectionUi();
        new AlertDialog.Builder(activity)
                .setTitle("내보내기 실패")
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show();
    }

    private static String uniqueFileName(AvseeVideo video, File source, Set<String> usedNames) {
        String original = source.getName();
        int dot = original.lastIndexOf('.');
        String extension = dot >= 0 ? original.substring(dot).toLowerCase(Locale.US) : ".mp4";
        String base = video.title == null ? "AVSee 영상" : video.title.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ");
        if (base.isEmpty()) base = "AVSee 영상";
        if (base.length() > 80) base = base.substring(0, 80).trim();
        String candidate = base + extension;
        int suffix = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.US))) {
            candidate = base + " (" + suffix++ + ")" + extension;
        }
        return candidate;
    }

    private static String mimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "mp4";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "video/mp4" : type;
    }
    private void play(AvseeVideo video) {
        File file = new File(video.filePath);
        if (!file.isFile()) {
            Toast.makeText(activity, "영상 파일을 찾을 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(activity, VideoPlayerActivity.class)
                .putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
                .putExtra(VideoPlayerActivity.EXTRA_PATH, video.filePath)
                .putExtra(VideoPlayerActivity.EXTRA_THUMBNAIL_PATH, video.thumbnailPath)
                .putExtra(VideoPlayerActivity.EXTRA_TAGS, video.tags)
                .putExtra(VideoPlayerActivity.EXTRA_ACTORS, video.actors)
                .putExtra(VideoPlayerActivity.EXTRA_DESCRIPTION, video.description)
                .putExtra(VideoPlayerActivity.EXTRA_CREATED_AT, video.createdAt)
                .putExtra(VideoPlayerActivity.EXTRA_SIZE_BYTES, video.sizeBytes);
        activity.startActivity(intent);
    }

    private void confirmDelete(AvseeVideo video) {
        new AlertDialog.Builder(activity)
                .setTitle("영상 삭제")
                .setMessage("‘" + video.title + "’ 영상과 썸네일을 삭제합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    AvseeStorage.deleteVideoFiles(video.filePath);
                    database.delete(video.id);
                    refresh();
                    Toast.makeText(activity, "삭제했습니다.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(activity, receiver,
                    new IntentFilter(AvseeDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        refresh();
        syncProgress();
    }

    @Override protected void onDetachedFromWindow() {
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private final class VideoAdapter extends RecyclerView.Adapter<VideoHolder> {
        private final List<AvseeVideo> items = new ArrayList<>();

        void replace(List<AvseeVideo> videos) {
            items.clear();
            items.addAll(videos);
            notifyDataSetChanged();
        }

        @Override public VideoHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_avsee_video, parent, false);
            return new VideoHolder(view);
        }

        @Override public void onBindViewHolder(VideoHolder holder, int position) {
            AvseeVideo video = items.get(position);
            holder.title.setText(video.title);
            holder.meta.setText(AvseeStorage.formatBytes(video.sizeBytes) + " · " + shortDate(video.createdAt));
            String detail = joinDetail(AvseeMetadata.cleanActors(video.actors), video.tags);
            holder.detail.setText(detail.isEmpty() ? "AVSee 다운로드 영상" : detail);
            holder.thumbnail.setImageDrawable(null);
            if (video.thumbnailPath != null && new File(video.thumbnailPath).isFile()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                holder.thumbnail.setImageBitmap(BitmapFactory.decodeFile(video.thumbnailPath, options));
            }
            boolean selected = selectedIds.contains(video.id);
            holder.select.setOnCheckedChangeListener(null);
            holder.select.setVisibility(selectionMode ? VISIBLE : GONE);
            holder.select.setChecked(selected);
            holder.select.setOnCheckedChangeListener((button, checked) -> toggleSelection(video));
            holder.actions.setVisibility(selectionMode ? GONE : VISIBLE);
            holder.itemView.setBackgroundColor(ContextCompat.getColor(activity,
                    selected ? R.color.episode_viewed : R.color.surface));
            holder.itemView.setOnClickListener(v -> {
                if (selectionMode) toggleSelection(video);
                else play(video);
            });
            holder.play.setOnClickListener(v -> play(video));
            holder.delete.setOnClickListener(v -> confirmDelete(video));
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final class VideoHolder extends RecyclerView.ViewHolder {
        final CheckBox select;
        final ImageView thumbnail;
        final TextView title;
        final TextView meta;
        final TextView detail;
        final View actions;
        final Button play;
        final Button delete;

        VideoHolder(View view) {
            super(view);
            select = view.findViewById(R.id.select_video);
            thumbnail = view.findViewById(R.id.video_thumbnail);
            title = view.findViewById(R.id.video_title);
            meta = view.findViewById(R.id.video_meta);
            detail = view.findViewById(R.id.video_detail);
            actions = view.findViewById(R.id.video_actions);
            play = view.findViewById(R.id.play_video);
            delete = view.findViewById(R.id.delete_video);
        }
    }

    private static String shortDate(String value) {
        if (value == null) return "";
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    private static String joinDetail(String actors, String tags) {
        String a = actors == null ? "" : actors.trim();
        String t = tags == null ? "" : tags.trim();
        if (a.isEmpty()) return t;
        if (t.isEmpty()) return a;
        return a + " · " + t;
    }
}
