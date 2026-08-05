package com.webtoonmap.mobile.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.download.SeriesDownloadService;
import com.webtoonmap.mobile.download.SourceJobStore;
import com.webtoonmap.mobile.export.SeriesExporter;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.InputStream;

public final class DownloadChannelView extends android.widget.FrameLayout {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SeriesAdapter adapter = new SeriesAdapter();
    private final RecyclerView list;
    private final View empty;
    private final TextView status;
    private final Button exportButton;
    private final SwipeRefreshLayout swipe;
    private boolean receiverRegistered;
    private boolean exporting;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            status.setText(intent.getStringExtra(SeriesDownloadService.EXTRA_MESSAGE));
            refresh();
        }
    };

    public DownloadChannelView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.channel_downloads, this, true);
        list = findViewById(R.id.series_list);
        empty = findViewById(R.id.empty);
        status = findViewById(R.id.download_status);
        exportButton = findViewById(R.id.export);
        swipe = findViewById(R.id.swipe);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
        exportButton.setOnClickListener(v -> chooseExport());
        swipe.setOnRefreshListener(this::refresh);
        refresh();
    }

    public void refresh() {
        swipe.setRefreshing(true);
        executor.execute(() -> {
            List<SeriesItem> rows = LibraryDatabase.get(getContext()).listSeries();
            post(() -> {
                adapter.setItems(rows);
                empty.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
                exportButton.setEnabled(!rows.isEmpty() && !exporting);
                swipe.setRefreshing(false);
            });
        });
    }

    private void chooseExport() {
        if (exporting) return;
        List<SeriesItem> rows = adapter.snapshot();
        if (rows.isEmpty()) {
            Toast.makeText(getContext(), "내보낼 작품이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[rows.size()];
        boolean[] selected = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            SeriesItem item = rows.get(i);
            labels[i] = item.title + "\n" + item.episodeCount + "개 회차";
        }
        new AlertDialog.Builder(getContext())
                .setTitle("내보낼 웹툰 선택 (여러 개 가능)")
                .setMultiChoiceItems(labels, selected,
                        (dialog, which, checked) -> selected[which] = checked)
                .setPositiveButton("내보내기", (dialog, which) -> {
                    List<SeriesItem> chosen = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        if (selected[i]) chosen.add(rows.get(i));
                    }
                    if (chosen.isEmpty()) {
                        Toast.makeText(getContext(), "한 작품 이상 선택해 주세요.",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        exportSeries(chosen);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void exportSeries(List<SeriesItem> items) {
        if (exporting) return;
        for (SeriesItem item : items) {
            if (SeriesDownloadService.isDownloading(item.titleId) ||
                    SeriesDownloadService.isQueued(getContext(), item.titleId)) {
                Toast.makeText(getContext(), "‘" + item.title +
                                "’ 다운로드가 끝난 뒤 내보내 주세요.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        exporting = true;
        exportButton.setEnabled(false);
        exportButton.setText("압축 중…");
        status.setText(items.size() + "개 작품 내보내기 준비 중…");
        AlertDialog progressDialog = new AlertDialog.Builder(getContext())
                .setTitle("데이터 내보내기")
                .setMessage(items.size() + "개 작품 내보내기 준비 중…")
                .setCancelable(false)
                .create();
        progressDialog.show();
        executor.execute(() -> {
            try {
                LibraryDatabase database = LibraryDatabase.get(getContext());
                File file = SeriesExporter.export(getContext(), items, database,
                        (current, total) -> post(() -> {
                            String text = items.size() + "개 작품 압축 중 · " + current + "/" + total + "회차";
                            status.setText(text);
                            progressDialog.setMessage(text);
                        }));
                post(() -> {
                    exporting = false;
                    exportButton.setEnabled(true);
                    exportButton.setText("내보내기");
                    status.setText("내보내기 완료 · " + file.getName());
                    progressDialog.dismiss();
                    new AlertDialog.Builder(getContext())
                            .setTitle("내보내기 완료")
                            .setMessage(items.size() + "개 작품을 하나의 파일로 만들었습니다.\n" + file.getName())
                            .setPositiveButton("공유하기", (dialog, which) -> shareExport(file))
                            .setNegativeButton("닫기", null)
                            .show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ?
                        "내보내기에 실패했습니다." : error.getMessage();
                post(() -> {
                    exporting = false;
                    exportButton.setEnabled(adapter.getItemCount() > 0);
                    exportButton.setText("내보내기");
                    status.setText("내보내기 실패");
                    progressDialog.dismiss();
                    new AlertDialog.Builder(getContext())
                            .setTitle("내보내기 실패")
                            .setMessage(message)
                            .setPositiveButton("확인", null)
                            .show();
                    Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void shareExport(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(getContext(),
                    getContext().getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, file.getName())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri(file.getName(), uri));
            Intent chooser = Intent.createChooser(share, "웹툰 내보내기");
            if (!(getContext() instanceof Activity)) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            getContext().startActivity(chooser);
        } catch (Exception error) {
            Toast.makeText(getContext(), "공유 화면을 열지 못했습니다.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(getContext(), receiver,
                    new IntentFilter(SeriesDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (receiverRegistered) {
            getContext().unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private final class SeriesAdapter extends RecyclerView.Adapter<SeriesAdapter.Holder> {
        private final List<SeriesItem> items = new ArrayList<>();
        void setItems(List<SeriesItem> rows) { items.clear(); items.addAll(rows); notifyDataSetChanged(); }
        List<SeriesItem> snapshot() { return new ArrayList<>(items); }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_series, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            SeriesItem item = items.get(position);
            h.title.setText(item.title);
            h.tags.setText(item.tags.isEmpty() ? "태그 없음" : "# " + item.tags.replace(",", "  #"));
            String state = statusLabel(item);
            String suffix = state.isEmpty() ? "" : " · " + state;
            h.count.setText(item.episodeCount + "개 회차" + suffix);
            h.image.setImageDrawable(null);
            h.image.setTag(item.thumbnailPath);
            if (item.thumbnailPath != null) executor.execute(() -> {
                Bitmap bitmap = decodeThumbnail(item.thumbnailPath);
                h.image.post(() -> {
                    if (item.thumbnailPath.equals(h.image.getTag())) h.image.setImageBitmap(bitmap);
                });
            });
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(getContext(), DownloadedSeriesActivity.class);
                intent.putExtra("title_id", item.titleId);
                getContext().startActivity(intent);
            });
            h.resume.setOnClickListener(v -> resumeDownload(item));
            h.delete.setOnClickListener(v -> confirmDelete(item));
        }

        private void resumeDownload(SeriesItem item) {
            if (SeriesDownloadService.isDownloading(item.titleId)) {
                Toast.makeText(getContext(), "이 작품을 이미 다운로드하고 있습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean queued = SeriesDownloadService.isQueued(getContext(), item.titleId);
            if (queued && SeriesDownloadService.isRunning()) {
                Toast.makeText(getContext(), "이미 다운로드 대기열에 있습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean queueMode = SeriesDownloadService.isRunning();
            status.setText("‘" + item.title + "’ " + (queueMode ? "대기열 추가 중…" : "이어받기 준비 중…"));
            SeriesDownloadService.enqueue(getContext(), item.titleId);
            Toast.makeText(getContext(), queueMode ? "이어받기를 대기열에 추가했습니다." :
                    (queued ? "중단된 대기열을 다시 시작했습니다." :
                            "완성되지 않은 회차부터 이어받습니다."),
                    Toast.LENGTH_SHORT).show();
        }

        private Bitmap decodeThumbnail(String path) {
            if (!path.startsWith("content://")) return BitmapFactory.decodeFile(path);
            try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(path))) {
                return in == null ? null : BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
                return null;
            }
        }

        private void confirmDelete(SeriesItem item) {
            if (SeriesDownloadService.isDownloading(item.titleId) ||
                    SeriesDownloadService.isQueued(getContext(), item.titleId)) {
                Toast.makeText(getContext(), "다운로드 중이거나 대기열에 있는 작품은 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(getContext())
                    .setTitle("작품 삭제")
                    .setMessage("‘" + item.title + "’의 썸네일, 모든 회차 ZIP과 작품 정보를 실제 저장소에서 삭제합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (dialog, which) -> executor.execute(() -> {
                        boolean deleted;
                        try {
                            deleted = new WebtoonStorage(getContext(), item.storageUri)
                                    .deleteSeries(item.titleId);
                        } catch (Exception ignored) {
                            deleted = false;
                        }
                        if (deleted) {
                            LibraryDatabase.get(getContext()).deleteSeries(item.titleId);
                            SourceJobStore.remove(getContext(), item.titleId);
                        }
                        final boolean result = deleted;
                        post(() -> {
                            Toast.makeText(getContext(), result ? "작품을 완전히 삭제했습니다." :
                                    "저장 폴더를 삭제하지 못했습니다. 폴더 권한을 확인해 주세요.", Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    })).show();
        }

        private String statusLabel(SeriesItem item) {
            if (SeriesDownloadService.isDownloading(item.titleId)) return "다운로드 중";
            if (SeriesDownloadService.isQueued(getContext(), item.titleId)) return "대기열";
            if ("downloading".equals(item.status)) {
                return "이어받기 필요";
            }
            if ("error".equals(item.status) || "paused".equals(item.status)) return "이어받기 필요";
            return "complete".equals(item.status) ? "" : item.status;
        }

        @Override public int getItemCount() { return items.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image; final TextView title, tags, count; final Button resume, delete;
            Holder(View view) {
                super(view);
                image = view.findViewById(R.id.thumbnail);
                title = view.findViewById(R.id.title);
                tags = view.findViewById(R.id.tags);
                count = view.findViewById(R.id.count);
                resume = view.findViewById(R.id.resume);
                delete = view.findViewById(R.id.delete);
            }
        }
    }
}






