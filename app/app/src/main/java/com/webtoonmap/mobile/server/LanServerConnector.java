package com.webtoonmap.mobile.server;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves a reachable LAN server base URL using saved endpoint first, then mDNS discovery.
 */
public final class LanServerConnector {
    public static final String SERVICE_TYPE = "_webtoonmap._tcp.";
    private static final long DISCOVERY_TIMEOUT_MS = 4500L;

    public static final class Connection {
        public final String baseUrl;
        public final String host;
        public final int port;
        public final String displayName;

        public Connection(String baseUrl, String host, int port, String displayName) {
            this.baseUrl = baseUrl;
            this.host = host;
            this.port = port;
            this.displayName = displayName == null ? "" : displayName;
        }
    }

    private LanServerConnector() { }

    public static Connection connect(Context context) {
        Context app = context.getApplicationContext();

        // 1) Saved base URL / host
        String savedBase = LanServerSettings.getBaseUrl(app);
        if (savedBase != null && LanServerClient.isReachable(savedBase)) {
            return new Connection(savedBase, LanServerSettings.getHost(app),
                    LanServerSettings.getPort(app), LanServerSettings.getDisplayName(app));
        }

        String host = LanServerSettings.getHost(app);
        if (host != null && !host.trim().isEmpty()) {
            String base = "http://" + host.trim() + ":" + LanServerSettings.getPort(app);
            if (LanServerClient.isReachable(base)) {
                LanServerSettings.saveEndpoint(app, host.trim(), LanServerSettings.getPort(app),
                        base, LanServerSettings.getDisplayName(app));
                return new Connection(base, host.trim(), LanServerSettings.getPort(app),
                        LanServerSettings.getDisplayName(app));
            }
        }

        // 2) mDNS / NSD discovery
        Connection discovered = discover(app);
        if (discovered != null && LanServerClient.isReachable(discovered.baseUrl)) {
            LanServerSettings.saveEndpoint(app, discovered.host, discovered.port,
                    discovered.baseUrl, discovered.displayName);
            return discovered;
        }
        return null;
    }

    public static Connection connectManual(Context context, String rawAddress) throws Exception {
        LanServerSettings.ParsedEndpoint parsed = LanServerSettings.parseManual(rawAddress);
        if (parsed == null) throw new IllegalArgumentException("IP 또는 주소:포트 형식이 올바르지 않습니다.");
        if (!LanServerClient.isReachable(parsed.baseUrl)) {
            throw new java.io.IOException(
                    "서버에 연결할 수 없습니다. 같은 Wi-Fi인지, 서버가 켜져 있는지 확인해 주세요.");
        }
        String name = "";
        try {
            org.json.JSONObject health = LanServerClient.health(parsed.baseUrl);
            name = health.optString("name", "");
        } catch (Exception ignored) { }
        LanServerSettings.saveEndpoint(context.getApplicationContext(), parsed.host, parsed.port,
                parsed.baseUrl, name);
        return new Connection(parsed.baseUrl, parsed.host, parsed.port, name);
    }

    private static Connection discover(Context context) {
        NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (manager == null) return null;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Connection> found = new AtomicReference<>();
        AtomicReference<NsdManager.DiscoveryListener> discoveryRef = new AtomicReference<>();
        AtomicReference<NsdManager.ResolveListener> resolveRef = new AtomicReference<>();
        Handler main = new Handler(Looper.getMainLooper());

        NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // keep discovering until timeout
            }

            @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                try {
                    InetAddress address = serviceInfo.getHost();
                    if (address == null) return;
                    String host = address.getHostAddress();
                    if (host == null || host.isEmpty()) return;
                    // Prefer IPv4
                    if (host.contains(":") && !host.contains(".")) return;
                    int port = serviceInfo.getPort() > 0 ? serviceInfo.getPort()
                            : LanServerSettings.DEFAULT_PORT;
                    String base = "http://" + host + ":" + port;
                    if (!LanServerClient.isReachable(base)) return;
                    if (found.compareAndSet(null, new Connection(base, host, port,
                            serviceInfo.getServiceName()))) {
                        latch.countDown();
                    }
                } catch (Exception ignored) { }
            }
        };
        resolveRef.set(resolveListener);

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                latch.countDown();
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }

            @Override public void onDiscoveryStarted(String serviceType) { }

            @Override public void onDiscoveryStopped(String serviceType) { }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (found.get() != null) return;
                String type = serviceInfo.getServiceType();
                if (type == null) return;
                if (!type.contains("webtoonmap")) return;
                try {
                    manager.resolveService(serviceInfo, resolveListener);
                } catch (Exception ignored) { }
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
        };
        discoveryRef.set(discoveryListener);

        try {
            // NsdManager callbacks are expected on the calling thread's looper in some versions;
            // start discovery on main thread for compatibility.
            CountDownLatch started = new CountDownLatch(1);
            main.post(() -> {
                try {
                    manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                            discoveryListener);
                } catch (Exception ignored) {
                    latch.countDown();
                } finally {
                    started.countDown();
                }
            });
            started.await(1, TimeUnit.SECONDS);
            latch.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            main.post(() -> {
                try {
                    NsdManager.DiscoveryListener listener = discoveryRef.get();
                    if (listener != null) manager.stopServiceDiscovery(listener);
                } catch (Exception ignored) { }
            });
        }
        return found.get();
    }
}
