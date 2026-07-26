package com.webtoonmap.mobile.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.export.TransferImporter;
import com.webtoonmap.mobile.server.LanServerClient;
import com.webtoonmap.mobile.server.LanServerConnector;
import com.webtoonmap.mobile.server.LanServerItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerChannelView extends FrameLayout {
    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TextView statusView;
    private final TextView emptyView;
    private final SwipeRefreshLayout swipe;
    private final ServerAdapter adapter = new ServerAdapter();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private String baseUrl;

    public ServerChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_server, this, true);
        statusView = findViewById(R.id.server_status);
        emptyView = findViewById(R.id.server_empty);
        swipe = findViewById(R.id.server_swipe);
        RecyclerView list = findViewById(R.id.server_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        findViewById(R.id.server_refresh).setOnClickListener(v -> refresh(true));
        findViewById(R.id.server_open_downloads).setOnClickListener(v -> activity.showDownloads());
        swipe.setOnRefreshListener(() -> refresh(true));
        refresh(true);
    }

    public boolean canGoBack() {
        return false;
    }

    public void goBack() { }

    public void goHome() {
        refresh(true);
    }

    public void destroyWebView() {
        // no-op: server channel does not use WebView
    }

    public void refresh() {
        refresh(false);
    }

    public void refresh(boolean forceDiscover) {
        if (!loading.compareAndSet(false, true)) {
            swipe.setRefreshing(true);
            return;
        }
        swipe.setRefreshing(true);
        statusView.setText(forceDiscover ? "서버를 찾는 중…" : "서버 연결 확인 중…");
        executor.execute(() -> {
            try {
                LanServerConnector.Connection connection = LanServerConnector.connect(activity);
                if (connection == null) {
                    postDisconnected();
                    return;
                }
                baseUrl = connection.baseUrl;
                List<LanServerItem> items = LanServerClient.listWebtoons(connection.baseUrl);
                post(() -> {
                    loading.set(false);
                    swipe.setRefreshing(false);
                    adapter.setItems(items);
                    String where = connection.host + ":" + connection.port;
                    if (connection.displayName != null && !connection.displayName.isEmpty()) {
                        statusView.setText("연결됨 · " + connection.displayName + " · " + where);
                    } else {
                        statusView.setText("연결됨 · " + where);
                    }
                    if (items.isEmpty()) {
                        emptyView.setVisibility(VISIBLE);
                        emptyView.setText("서버에 업로드된 웹툰이 없습니다.\n다운로드 탭 → 내보내기 → 서버에 올리기로 올려 주세요.");
                    } else {
                        emptyView.setVisibility(GONE);
                    }
                });
            } catch (Exception error) {
                postDisconnected(error.getMessage());
            }
        });
    }

    private void postDisconnected() {
        postDisconnected(null);
    }

    private void postDisconnected(String detail) {
        post(() -> {
            loading.set(false);
            swipe.setRefreshing(false);
            baseUrl = null;
            adapter.setItems(new ArrayList<>());
            emptyView.setVisibility(VISIBLE);
            emptyView.setText("서버가 연결되지 않았습니다");
            if (detail != null && !detail.trim().isEmpty()) {
                statusView.setText("연결 실패 · " + detail);
            } else {
                statusView.setText("같은 Wi-Fi에서 서버 프로그램이 실행 중인지 확인해 주세요.");
            }
        });
    }

    private void downloadItem(LanServerItem item) {
        if (item == null) return;
        if (baseUrl == null) {
            Toast.makeText(activity, "서버가 연결되지 않았습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!downloading.compareAndSet(false, true)) {
            Toast.makeText(activity, "다른 패키지를 가져오는 중입니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("서버에서 가져오기")
                .setMessage("‘" + item.title + "’ 다운로드 준비 중…")
                .setCancelable(false)
                .create();
        progressDialog.show();

        executor.execute(() -> {
            File archive = null;
            try {
                File dir = new File(activity.getCacheDir(), "server-downloads");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new java.io.IOException("임시 폴더를 만들 수 없습니다.");
                }
                archive = new File(dir, "server-" + item.id + "-" + System.currentTimeMillis() + ".wtoon.zip");
                final File target = archive;
                LanServerClient.download(baseUrl, item.id, target, (current, total) -> post(() ->
                        progressDialog.setMessage("다운로드 중…\n" +
                                LanServerClient.percent(current, total))));
                post(() -> progressDialog.setMessage("앱 라이브러리에 등록 중…"));
                TransferImporter.Result result = TransferImporter.importArchiveFile(activity, target,
                        (current, total, title) -> post(() ->
                                progressDialog.setMessage(current + "/" + total + " · ‘" + title + "’ 등록 중")));
                post(() -> {
                    downloading.set(false);
                    progressDialog.dismiss();
                    activity.refreshDownloads();
                    String summary = "가져오기 완료 · " + result.imported.size() + "개 작품";
                    if (!result.errors.isEmpty()) {
                        summary += " · 오류 " + result.errors.size() + "개";
                    }
                    statusView.setText(summary);
                    new AlertDialog.Builder(activity)
                            .setTitle("다운로드 완료")
                            .setMessage(summary + (result.errors.isEmpty() ? "\n다운로드 탭에서 확인할 수 있습니다."
                                    : "\n" + result.errors.get(0)))
                            .setPositiveButton("다운로드 탭", (d, w) -> activity.showDownloads())
                            .setNegativeButton("닫기", null)
                            .show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "서버에서 가져오지 못했습니다." : error.getMessage();
                post(() -> {
                    downloading.set(false);
                    progressDialog.dismiss();
                    statusView.setText("가져오기 실패");
                    new AlertDialog.Builder(activity)
                            .setTitle("가져오기 실패")
                            .setMessage(message)
                            .setPositiveButton("확인", null)
                            .show();
                });
            } finally {
                if (archive != null) {
                    // importArchiveFile already deletes; double-safe
                    // no-op if gone
                    archive.delete();
                }
            }
        });
    }

    private final class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.Holder> {
        private final List<LanServerItem> items = new ArrayList<>();

        void setItems(List<LanServerItem> next) {
            items.clear();
            if (next != null) items.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_server_item, parent, false);
            return new Holder(view);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            LanServerItem item = items.get(position);
            holder.title.setText(item.title);
            holder.detail.setText(item.detailLine());
            holder.download.setEnabled(!downloading.get());
            holder.download.setOnClickListener(v -> downloadItem(item));
        }

        @Override public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView detail;
            final Button download;

            Holder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.server_item_title);
                detail = itemView.findViewById(R.id.server_item_detail);
                download = itemView.findViewById(R.id.server_item_download);
            }
        }
    }
}
