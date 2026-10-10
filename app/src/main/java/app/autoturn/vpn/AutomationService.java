package app.autoturn.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.VpnService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.amnezia.awg.backend.GoBackend;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

public final class AutomationService extends Service {
    static final String ACTION_REFRESH = "app.autoturn.vpn.REFRESH";
    static final String ACTION_TUNNEL_FAILED = "app.autoturn.vpn.TUNNEL_FAILED";
    private static final String CHANNEL = "automation";
    private static final String TAG = "AutoTurnGuard";
    private static final long CHECK_INTERVAL_MS = 5_000;
    private static final long START_GRACE_MS = 8_000;
    private static final long ALL_FAILED_PAUSE_MS = 15_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probes = Executors.newSingleThreadExecutor();
    private Future<?> activeProbe;
    private volatile int latestStartId;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private ConnectivityManager.NetworkCallback vpnCallback;
    private volatile String ssid = "";
    private String runningId = "";
    private String runningType = "";
    private String runningPolicy = "";
    private String guardProfileId = "";
    private boolean verified;
    private boolean hadHealthyConnection;
    private int consecutiveTrafficFailures;
    private int failedProfiles;
    private int generation;
    private int observedGeneration;
    private Network observedVpn;
    private boolean healthScheduled;
    private BroadcastReceiver wakeReceiver;
    private long startedAt;
    private long pauseUntil;
    private GoBackend backend;
    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "AutoTurn"; }
        @Override public void onStateChange(State state) { }
    };
    private final Runnable delayedApply = () -> enqueue(this::applyDecision);
    private final Runnable returnToAuto = () -> enqueue(this::applyDecision);
    private final Runnable healthTick = () -> enqueue(this::checkHealth);

    private void enqueue(Runnable action) {
        try { worker.execute(action); }
        catch (RejectedExecutionException ignored) { /* A late network callback during shutdown. */ }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Автоматизация VPN",
                NotificationManager.IMPORTANCE_LOW));
        startForeground(100, notification("Следим за домашней сетью"));
        connectivity = getSystemService(ConnectivityManager.class);
        wakeReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                enqueue(() -> {
                    Log.i(TAG, "Screen on; checking connection without opening app");
                    if (!runningId.isEmpty()) scheduleHealth(0);
                    else applyDecision();
                });
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        callback = new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override public void onAvailable(Network network) { schedule(1000); }
            @Override public void onLost(Network network) { ssid = ""; schedule(500); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                Object info = caps.getTransportInfo();
                if (info instanceof WifiInfo) {
                    String name = ((WifiInfo) info).getSSID();
                    if (name != null && !WifiManager.UNKNOWN_SSID.equals(name)) {
                        if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\""))
                            name = name.substring(1, name.length() - 1);
                        ssid = name;
                    } else ssid = "";
                }
                schedule(1000);
            }
        };
        connectivity.registerNetworkCallback(request, callback);
        vpnCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                enqueue(() -> {
                    observedVpn = network;
                    observedGeneration = generation;
                    if (!runningId.isEmpty() && !verified) scheduleHealth(250);
                });
            }
            @Override public void onLost(Network network) {
                enqueue(() -> {
                    if (network.equals(observedVpn) && observedGeneration == generation) {
                        observedVpn = null;
                        if (!runningId.isEmpty() && verified) {
                            Log.i(TAG, "VPN network lost; checking immediately");
                            scheduleHealth(0);
                        }
                    }
                });
            }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), vpnCallback);
        schedule();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        // A new foreground-service request also needs acknowledgment when the existing
        // instance is being stopped/restarted; onCreate alone is insufficient.
        String foregroundDetail = StatusStore.detail(this);
        startForeground(100, notification(foregroundDetail.isEmpty() ? "Следим за домашней сетью" : foregroundDetail));
        if (intent != null && ACTION_TUNNEL_FAILED.equals(intent.getAction())) {
            String failedId = intent.getStringExtra(XrayVpnService.EXTRA_PROFILE_ID);
            worker.execute(() -> handleTunnelFailure(failedId));
            return START_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction()))
            worker.execute(this::applyDecision);
        else schedule();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (wakeReceiver != null) unregisterReceiver(wakeReceiver);
        handler.removeCallbacks(delayedApply);
        handler.removeCallbacks(returnToAuto);
        handler.removeCallbacks(healthTick);
        if (callback != null) connectivity.unregisterNetworkCallback(callback);
        if (vpnCallback != null) connectivity.unregisterNetworkCallback(vpnCallback);
        worker.execute(() -> {
            stopCurrent();
            StatusStore.set(this, StatusStore.OFF, "Автоматизация выключена");
        });
        worker.shutdown();
        probes.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void schedule() {
        schedule(1000);
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(delayedApply);
        handler.postDelayed(delayedApply, delayMs);
    }

    private void applyDecision() {
        final int decisionStartId = latestStartId;
        SecureStore store = null;
        try {
            SecureStore.expireManualMode(this, System.currentTimeMillis());
            store = new SecureStore(this);
            handler.removeCallbacks(returnToAuto);
            if (!SecureStore.MANUAL_AUTO.equals(store.manualMode) && store.manualUntil > 0)
                handler.postDelayed(returnToAuto, Math.max(1, store.manualUntil - System.currentTimeMillis()));
            AutoReturnReceiver.sync(this);
            if (!guardEnabled(store)) clearHealth();
            if (!store.automation && SecureStore.MANUAL_AUTO.equals(store.manualMode)) {
                stopSelfResult(decisionStartId); return;
            }
            Profile profile = store.activeProfile();
            if (SecureStore.MANUAL_OFF.equals(store.manualMode)) {
                stopCurrent();
                clearHealth();
                StatusStore.set(this, StatusStore.OFF, "VPN выключен вручную");
                updateNotification("VPN выключен вручную");
                return;
            }
            if (profile == null || (SecureStore.MANUAL_AUTO.equals(store.manualMode) && store.homeSsid.isEmpty())) {
                stopCurrent();
                clearHealth();
                StatusStore.set(this, StatusStore.ERROR, "Выберите профиль и домашний Wi‑Fi");
                return;
            }
            boolean atHome = store.homeSsid.equals(ssid);
            boolean shouldRun = SecureStore.MANUAL_ON.equals(store.manualMode) || !atHome;
            if (!shouldRun) {
                stopCurrent();
                clearHealth();
                StatusStore.set(this, StatusStore.OFF, "Домашний Wi‑Fi · VPN выключен");
                updateNotification("VPN выключен дома");
                return;
            }
            if (guardEnabled(store) && VpnService.prepare(this) != null) {
                stopCurrent();
                clearHealth();
                StatusStore.set(this, StatusStore.ERROR, "Разрешение VPN отозвано · откройте AutoTurn");
                return;
            }
            if (!profile.id.equals(guardProfileId)) {
                guardProfileId = profile.id;
                failedProfiles = 0;
                hadHealthyConnection = false;
                pauseUntil = 0;
            }
            if (guardEnabled(store) && SystemClock.elapsedRealtime() < pauseUntil) return;
            String policy = store.appMode + ":" + store.selectedPackages + ":" + guardEnabled(store);
            if (profile.id.equals(runningId) && policy.equals(runningPolicy) &&
                    !StatusStore.ERROR.equals(StatusStore.state(this))) {
                if (guardEnabled(store) && !healthScheduled)
                    scheduleHealth(verified ? CHECK_INTERVAL_MS : 1000);
                return;
            }
            stopCurrent();
            verified = false;
            generation++;
            StatusStore.set(this, StatusStore.CONNECTING,
                    SecureStore.MANUAL_ON.equals(store.manualMode) ? "Включаем VPN вручную" :
                    atHome ? "Включаем вручную для домашней сети" :
                            ssid.isEmpty() ? "Сеть не определена · подключаем VPN" :
                                    "Вне домашней сети · подключаемся");
            if (Profile.AMNEZIA.equals(profile.type)) {
                if (backend == null) {
                    backend = new GoBackend(this);
                }
                final int handshakeGeneration = generation;
                backend.setStatusCallback(connected -> {
                        if (!connected) return;
                        enqueue(() -> {
                            try {
                                SecureStore current = new SecureStore(this);
                                if (generation == handshakeGeneration && profile.id.equals(runningId) &&
                                        profile.id.equals(current.activeId) && wantsVpn(current) && !guardEnabled(current))
                                    StatusStore.set(this, StatusStore.ON, "AmneziaWG подключён");
                            } catch (Exception ignored) { }
                        });
                });
                Config config = Config.parse(new BufferedReader(new StringReader(profile.config)));
                config = AppPolicy.forAmnezia(config, store.appMode,
                        AppPolicy.routed(this, store));
                backend.setState(tunnel, Tunnel.State.UP, config);
                runningType = Profile.AMNEZIA;
                runningId = profile.id;
                runningPolicy = policy;
                updateNotification("AmneziaWG · ожидаем соединения");
            } else {
                Intent start = new Intent(this, XrayVpnService.class).setAction(XrayVpnService.ACTION_START);
                start.putExtra(XrayVpnService.EXTRA_PROFILE_ID, profile.id);
                runningType = profile.type;
                runningId = profile.id;
                runningPolicy = policy;
                startForegroundService(start);
                updateNotification(Profile.HYSTERIA2.equals(profile.type) ?
                        "Подключаем Hysteria 2" : "Подключаем Reality");
            }
            startedAt = SystemClock.elapsedRealtime();
            if (guardEnabled(store)) Log.i(TAG, "Started " + profile.type + "; checking connection");
            if (guardEnabled(store)) scheduleHealth(1000);
        } catch (Exception e) {
            stopCurrent();
            StatusStore.set(this, StatusStore.ERROR, "VPN не удалось включить. Откройте AutoTurn.");
            updateNotification("Ошибка подключения VPN");
            if (store != null && guardEnabled(store)) {
                try { recover(store); }
                catch (Exception ignored) { scheduleHealth(CHECK_INTERVAL_MS); }
            }
        }
    }

    private boolean guardEnabled(SecureStore store) {
        return store.connectionGuard;
    }

    private void clearHealth() {
        handler.removeCallbacks(healthTick);
        healthScheduled = false;
        verified = false;
        hadHealthyConnection = false;
        consecutiveTrafficFailures = 0;
        failedProfiles = 0;
        pauseUntil = 0;
    }

    private void scheduleHealth(long delayMs) {
        handler.removeCallbacks(healthTick);
        healthScheduled = true;
        handler.postDelayed(healthTick, delayMs);
    }

    private boolean wantsVpn(SecureStore store) {
        if (!store.automation && SecureStore.MANUAL_AUTO.equals(store.manualMode)) return false;
        if (SecureStore.MANUAL_OFF.equals(store.manualMode)) return false;
        if (store.activeProfile() == null) return false;
        return SecureStore.MANUAL_ON.equals(store.manualMode) ||
                (!store.homeSsid.isEmpty() && !store.homeSsid.equals(ssid));
    }

    private void checkHealth() {
        healthScheduled = false;
        if (activeProbe != null) return;
        try {
            SecureStore store = new SecureStore(this);
            if (!guardEnabled(store) || !wantsVpn(store)) return;
            long remainingPause = pauseUntil - SystemClock.elapsedRealtime();
            if (remainingPause > 0) {
                scheduleHealth(remainingPause);
                return;
            }
            if (runningId.isEmpty()) {
                applyDecision();
                return;
            }
            if (!runningId.equals(store.activeId)) {
                applyDecision();
                return;
            }
            final int probeGeneration = generation;
            final String probeProfileId = runningId;
            activeProbe = probes.submit(() -> {
                ConnectionProbe.Result result = ConnectionProbe.check(this, connectivity);
                enqueue(() -> finishHealth(result, probeGeneration, probeProfileId));
            });
        } catch (Exception e) {
            StatusStore.set(this, StatusStore.ERROR, "Не удалось проверить VPN · повторим проверку");
            scheduleHealth(2000);
        }
    }

    private void finishHealth(ConnectionProbe.Result result, int probeGeneration, String probeProfileId) {
        // Commands and all tunnel state remain serialized on worker. A cancelled old probe
        // cannot mark a replacement tunnel healthy, fail it, or re-enable a manually stopped VPN.
        if (probeGeneration != generation || !probeProfileId.equals(runningId)) return;
        activeProbe = null;
        try {
            SecureStore store = new SecureStore(this);
            if (!guardEnabled(store) || !wantsVpn(store) || !runningId.equals(store.activeId)) {
                applyDecision();
                return;
            }
            if (result == ConnectionProbe.Result.HEALTHY) {
                consecutiveTrafficFailures = 0;
                if (!verified) Log.i(TAG, "Connection verified in " +
                        (SystemClock.elapsedRealtime() - startedAt) + " ms");
                verified = true;
                hadHealthyConnection = true;
                failedProfiles = 0;
                pauseUntil = 0;
                StatusStore.set(this, StatusStore.ON, "VPN проверен · " + store.activeProfile().name);
                scheduleHealth(CHECK_INTERVAL_MS);
            } else if (result == ConnectionProbe.Result.NO_NETWORK) {
                consecutiveTrafficFailures = 0;
                StatusStore.set(this, StatusStore.CONNECTING, "Нет сети · ждём восстановления");
                scheduleHealth(2000);
            } else if (result == ConnectionProbe.Result.VPN_PERMISSION_LOST) {
                stopCurrent();
                StatusStore.set(this, StatusStore.ERROR, "Разрешение VPN отозвано · откройте AutoTurn");
            } else if ((result == ConnectionProbe.Result.VPN_MISSING || result == ConnectionProbe.Result.FAILED) && !verified &&
                    SystemClock.elapsedRealtime() - startedAt < START_GRACE_MS) {
                scheduleHealth(1000);
            } else {
                if (result == ConnectionProbe.Result.FAILED && ++consecutiveTrafficFailures < 2) {
                    StatusStore.set(this, StatusStore.CONNECTING, "VPN не передаёт трафик · перепроверяем");
                    updateNotification("Перепроверяем доступ через VPN");
                    scheduleHealth(2000);
                    return;
                }
                Log.i(TAG, "Connection check failed: " + result + "; advancing profile");
                recover(store);
            }
        } catch (Exception e) {
            StatusStore.set(this, StatusStore.ERROR, "Не удалось проверить VPN · повторим проверку");
            scheduleHealth(2000);
        }
    }

    private void handleTunnelFailure(String failedId) {
        if (failedId == null || !failedId.equals(runningId)) return;
        try {
            SecureStore store = new SecureStore(this);
            if (guardEnabled(store) && wantsVpn(store) && failedId.equals(store.activeId))
                recover(store);
        } catch (Exception e) {
            scheduleHealth(1000);
        }
    }

    private void recover(SecureStore store) throws Exception {
        Profile current = store.activeProfile();
        if (current == null) return;
        Profile next = nextProfile(store, current.id);
        Log.i(TAG, "Failover from " + current.type + " to " +
                (next == null ? current.type : next.type));
        failedProfiles++;
        stopCurrent();
        verified = false;
        if (next != null) {
            store.activeId = next.id;
            store.save();
            guardProfileId = next.id;
        }
        // If a tunnel worked earlier, retry it once before pausing the whole cycle.
        if (failedProfiles >= store.profiles.size() * (hadHealthyConnection ? 2 : 1)) {
            failedProfiles = 0;
            pauseUntil = SystemClock.elapsedRealtime() + ALL_FAILED_PAUSE_MS;
            StatusStore.set(this, StatusStore.ERROR, "Профили недоступны · повторим через 15 секунд");
            updateNotification("Ожидаем повторного подключения VPN");
            scheduleHealth(ALL_FAILED_PAUSE_MS);
            return;
        }
        String detail = next == null ? "Повторяем подключение " + current.name :
                "Пробуем профиль " + next.name;
        StatusStore.set(this, StatusStore.CONNECTING, detail);
        updateNotification(detail);
        applyDecision();
    }

    private Profile nextProfile(SecureStore store, String currentId) {
        if (store.profiles.size() < 2) return null;
        for (int i = 0; i < store.profiles.size(); i++) {
            if (currentId.equals(store.profiles.get(i).id))
                return store.profiles.get((i + 1) % store.profiles.size());
        }
        return store.profiles.get(0);
    }

    private void stopCurrent() {
        consecutiveTrafficFailures = 0;
        generation++;
        if (activeProbe != null) {
            activeProbe.cancel(true);
            activeProbe = null;
        }
        handler.removeCallbacks(healthTick);
        healthScheduled = false;
        verified = false;
        observedVpn = null;
        try {
            if (Profile.AMNEZIA.equals(runningType) && backend != null)
                backend.setState(tunnel, Tunnel.State.DOWN, null);
            if (Profile.VLESS.equals(runningType) || Profile.HYSTERIA2.equals(runningType)) {
                Intent stop = new Intent(this, XrayVpnService.class).setAction(XrayVpnService.ACTION_STOP);
                startService(stop);
            }
        } catch (Exception ignored) {
            StatusStore.set(this, StatusStore.ERROR, "Не удалось остановить VPN");
        }
        runningId = "";
        runningType = "";
        runningPolicy = "";
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("AutoTurn")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(100, notification(text));
    }
}
