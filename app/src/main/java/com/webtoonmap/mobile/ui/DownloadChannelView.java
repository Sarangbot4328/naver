package com.webtoonmap.mobile.ui;

import android.content.BroadcastReceiver;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.download.SeriesDownloadService;
import com.webtoonmap.mobile.storage.WebtoonStorage;

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
    private final SwipeRefreshLayout swipe;
    private boolean receiverRegistered;

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
        swipe = findViewById(R.id.swipe);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
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
                swipe.setRefreshing(false);
            });
        });
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

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_series, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            SeriesItem item = items.get(position);
            h.title.setText(item.title);
            h.tags.setText(item.tags.isEmpty() ? "태그 없음" : "# " + item.tags.replace(",", "  #"));
            String suffix = "complete".equals(item.status) ? "" : " · " + statusLabel(item);
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
            h.delete.setOnClickListener(v -> confirmDelete(item));
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
            if (SeriesDownloadService.isDownloading(item.titleId)) {
                Toast.makeText(getContext(), "다운로드 중인 작품은 완료 후 삭제해 주세요.", Toast.LENGTH_SHORT).show();
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
                        if (deleted) LibraryDatabase.get(getContext()).deleteSeries(item.titleId);
                        final boolean result = deleted;
                        post(() -> {
                            Toast.makeText(getContext(), result ? "작품을 완전히 삭제했습니다." :
                                    "저장 폴더를 삭제하지 못했습니다. 폴더 권한을 확인해 주세요.", Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    })).show();
        }

        private String statusLabel(SeriesItem item) {
            if ("downloading".equals(item.status)) {
                return SeriesDownloadService.isDownloading(item.titleId) ? "다운로드 중" : "이어받기 필요";
            }
            if ("error".equals(item.status)) return "이어받기 필요";
            return item.status;
        }

        @Override public int getItemCount() { return items.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image; final TextView title, tags, count; final Button delete;
            Holder(View view) {
                super(view);
                image = view.findViewById(R.id.thumbnail);
                title = view.findViewById(R.id.title);
                tags = view.findViewById(R.id.tags);
                count = view.findViewById(R.id.count);
                delete = view.findViewById(R.id.delete);
            }
        }
    }
}
