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
    static final String ACTION_RESTART_QUEUE = "app.autoturn.vpn.RESTART_QUEUE";
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
    // The Wi-Fi network the current SSID was read from; another network's loss must not clear it.
    private volatile Network ssidNetwork;
    private String runningId = "";
    private String runningType = "";
    private String runningPolicy = "";
    private String runningAttemptId = "";
    private boolean runningProtected;
    private String guardProfileId = "";
    private boolean verified;
    private boolean hadHealthyConnection;
    private int consecutiveTrafficFailures;
    // Consecutive successful checks; a long healthy run slows checks down (see HealthSchedule).
    private int healthyStreak;
    private int failedProfiles;
    private int generation;
    private int observedGeneration;
    private Network observedVpn;
    private boolean healthScheduled;
    private BroadcastReceiver wakeReceiver;
    private long startedAt;
    private long pauseUntil;
    private EndpointUpdates endpointUpdates;
    private GoBackend backend;
    private LiveIsland island;
    // Profiles that failed during the transition the island is showing (red segments).
    private final java.util.Set<String> islandFailed = new java.util.HashSet<>();
    private BroadcastReceiver statusReceiver;
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
        endpointUpdates = new EndpointUpdates(this,false);
        island = new LiveIsland(this);
        // Tunnels in other processes (Xray, protected mode) report "connected" only via StatusStore.
        statusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { enqueue(AutomationService.this::statusChanged); }
        };
        // Android before 13 ignores the flag; the broadcast is package-scoped anyway.
        registerReceiver(statusReceiver, new IntentFilter(StatusStore.ACTION_CHANGED), Context.RECEIVER_NOT_EXPORTED);
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
            @Override public void onAvailable(Network network) {
                enqueue(AutomationService.this::physicalNetworkChanged);
                schedule(1000);
            }
            @Override public void onLost(Network network) {
                if (network.equals(ssidNetwork)) { ssid = ""; ssidNetwork = null; }
                enqueue(AutomationService.this::physicalNetworkChanged);
                schedule(500);
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                Object info = caps.getTransportInfo();
                if (info instanceof WifiInfo) {
                    String name = ((WifiInfo) info).getSSID();
                    if (name != null && !WifiManager.UNKNOWN_SSID.equals(name)) {
                        if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\""))
                            name = name.substring(1, name.length() - 1);
                        ssid = name;
                        ssidNetwork = network;
                    } else if (ssidNetwork == null || network.equals(ssidNetwork)) {
                        ssid = "";
                        ssidNetwork = null;
                    }
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
            String attemptId = intent.getStringExtra(XrayVpnService.EXTRA_ATTEMPT_ID);
            worker.execute(() -> handleTunnelFailure(failedId, attemptId));
            return START_STICKY;
        }
        if (intent != null && ACTION_RESTART_QUEUE.equals(intent.getAction())) {
            worker.execute(this::restartQueue);
        } else if (intent != null && ACTION_REFRESH.equals(intent.getAction()))
            worker.execute(this::applyDecision);
        else schedule();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if(endpointUpdates!=null)endpointUpdates.close();
        if (wakeReceiver != null) unregisterReceiver(wakeReceiver);
        if (statusReceiver != null) unregisterReceiver(statusReceiver);
        handler.removeCallbacks(delayedApply);
        handler.removeCallbacks(returnToAuto);
        handler.removeCallbacks(healthTick);
        if (callback != null) connectivity.unregisterNetworkCallback(callback);
        if (vpnCallback != null) connectivity.unregisterNetworkCallback(vpnCallback);
        worker.execute(() -> {
            clearIsland();
            try {
                SecureStore store = new SecureStore(this);
                if (needsProtection(store)) {
                    holdProtection("Трафик заблокирован · автоматизация остановлена");
                } else {
                    stopCurrent();
                    StatusStore.set(this, StatusStore.OFF, "Автоматизация выключена");
                }
            } catch (Exception ignored) {
                // Do not tear down a live protected service if settings cannot be read.
                if (!runningProtected) stopCurrent();
            }
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
            island.setEnabled(store.liveIsland);
            handler.removeCallbacks(returnToAuto);
            if (!SecureStore.MANUAL_AUTO.equals(store.manualMode) && store.manualUntil > 0)
                handler.postDelayed(returnToAuto, Math.max(1, store.manualUntil - System.currentTimeMillis()));
            AutoReturnReceiver.sync(this);
            if (!guardEnabled(store)) clearHealth();
            if (!store.protectedMode && !store.automation && SecureStore.MANUAL_AUTO.equals(store.manualMode)) {
                guardProfileId = "";
                clearIsland();
                stopSelfResult(decisionStartId); return;
            }
            Profile candidate = store.activeProfile();
            if (SecureStore.MANUAL_OFF.equals(store.manualMode)) {
                if (needsProtection(store)) holdProtection("VPN остановлен · трафик заблокирован");
                else {
                    stopCurrent();
                    StatusStore.set(this, StatusStore.OFF, "VPN выключен вручную");
                }
                clearHealth();
                guardProfileId = "";
                clearIsland();
                updateNotification(needsProtection(store) ? "VPN остановлен · защита активна" : "VPN выключен вручную");
                return;
            }
            if (candidate == null || !candidate.enabled) {
                Profile first = store.firstConnectionProfile();
                if (first != null) {
                    if (!store.chooseConnection(store.primaryId, store.activeId, first.id)) { schedule(0); return; }
                    candidate = store.activeProfile();
                } else candidate = null;
            }
            if (candidate == null || (!store.protectedMode && SecureStore.MANUAL_AUTO.equals(store.manualMode) && store.homeSsids.isEmpty())) {
                if (needsProtection(store)) holdProtection(store.profiles.isEmpty() ? "Трафик заблокирован · добавьте VPN-профиль" :
                        "Трафик заблокирован · все профили отключены");
                else {
                    stopCurrent();
                    StatusStore.set(this, StatusStore.ERROR, store.profiles.isEmpty() ? "Выберите профиль и домашний Wi-Fi" :
                            candidate == null ? "Все профили отключены · разрешите подключение в Профилях" : "Выберите домашний Wi-Fi");
                }
                clearHealth();
                guardProfileId = "";
                clearIsland();
                return;
            }
            boolean atHome = store.isHomeSsid(ssid);
            boolean shouldRun = SecureStore.MANUAL_ON.equals(store.manualMode) || !atHome;
            if (!shouldRun) {
                stopCurrent();
                clearHealth();
                guardProfileId = "";
                clearIsland();
                StatusStore.set(this, StatusStore.OFF, "Домашний Wi-Fi · VPN выключен");
                updateNotification("VPN выключен дома");
                return;
            }
            // A fresh VPN session always starts with the user's primary profile.
            // Failover retains guardProfileId so network callbacks keep a working backup.
            if (guardProfileId.isEmpty() && store.firstConnectionProfile() != null) {
                if (!store.chooseConnection(store.primaryId, store.activeId, store.firstConnectionProfile().id)) {
                    schedule(0);
                    return;
                }
                candidate = store.activeProfile();
            }
            if (guardEnabled(store) && VpnService.prepare(this) != null) {
                stopCurrent();
                clearHealth();
                StatusStore.set(this, StatusStore.ERROR, "Разрешение VPN отозвано · откройте AutoTurn");
                island.show(LiveIsland.Content.permissionLost());
                return;
            }
            final Profile profile = candidate;
            if (!profile.id.equals(guardProfileId)) {
                guardProfileId = profile.id;
                failedProfiles = 0;
                hadHealthyConnection = false;
                pauseUntil = 0;
            }
            if (guardEnabled(store) && SystemClock.elapsedRealtime() < pauseUntil) return;
            String policy = store.appMode + ":" + store.selectedPackages + ":" + guardEnabled(store) + ":" + store.protectedMode;
            if (profile.id.equals(runningId) && policy.equals(runningPolicy) &&
                    !StatusStore.ERROR.equals(StatusStore.state(this))) {
                if (guardEnabled(store) && !healthScheduled)
                    scheduleHealth(verified ? HealthSchedule.healthyInterval(Math.max(1, healthyStreak)) : 1000);
                return;
            }
            stopCurrent(store.protectedMode);
            verified = false;
            generation++;
            runningAttemptId = java.util.UUID.randomUUID().toString();
            StatusStore.set(this, StatusStore.CONNECTING,
                    SecureStore.MANUAL_ON.equals(store.manualMode) ? "Включаем VPN вручную" :
                    atHome ? "Включаем вручную для домашней сети" :
                            locationHidesHome(store) ? "Геолокация выключена · дом не определить, подключаем VPN" :
                            ssid.isEmpty() && !hasPhysicalWifi() ? "Мобильная сеть · подключаем VPN" :
                            ssid.isEmpty() ? "Сеть не определена · подключаем VPN" :
                                    "Вне домашней сети · подключаемся");
            showConnecting(store, profile);
            if (store.protectedMode) {
                runningProtected = true;
                runningType = profile.type;
                runningId = profile.id;
                runningPolicy = policy;
                startForegroundService(new Intent(this, ProtectedVpnService.class)
                        .setAction(ProtectedVpnService.ACTION_START)
                        .putExtra(XrayVpnService.EXTRA_PROFILE_ID, profile.id)
                        .putExtra(XrayVpnService.EXTRA_ATTEMPT_ID, runningAttemptId));
            } else if (Profile.AMNEZIA.equals(profile.type)) {
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
                config = new EndpointResolver(this,()->{
                    try {
                        SecureStore latest=new SecureStore(this);
                        return generation==handshakeGeneration && profile.id.equals(latest.activeId) && wantsVpn(latest);
                    }catch(Exception e){return false;}
                },false).resolveAwg(config);
                SecureStore afterResolution=new SecureStore(this);
                if(!profile.id.equals(afterResolution.activeId) || !wantsVpn(afterResolution)){schedule(0);return;}
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
                start.putExtra(XrayVpnService.EXTRA_ATTEMPT_ID, runningAttemptId);
                runningType = profile.type;
                runningId = profile.id;
                runningPolicy = policy;
                startForegroundService(start);
                updateNotification(Profile.HYSTERIA2.equals(profile.type) ?
                        "Подключаем Hysteria 2" : Profile.QWDTT.equals(profile.type) ? "Подключаем qWDTT" : "Подключаем Reality");
            }
            startedAt = SystemClock.elapsedRealtime();
            if (guardEnabled(store)) Log.i(TAG, "Started " + profile.type + "; checking connection");
            if (guardEnabled(store)) scheduleHealth(1000);
        } catch (Exception e) {
            boolean protect = store != null && needsProtection(store);
            stopCurrent(protect);
            if (protect) holdProtection("Трафик заблокирован · VPN не удалось включить");
            else StatusStore.set(this, StatusStore.ERROR, "VPN не удалось включить. Откройте AutoTurn.");
            updateNotification("Ошибка подключения VPN");
            if (store != null && guardEnabled(store)) {
                try { recover(store); }
                catch (Exception ignored) { scheduleHealth(CHECK_INTERVAL_MS); }
            }
        }
    }

    private boolean guardEnabled(SecureStore store) {
        return store.connectionGuard || store.protectedMode;
    }

    private void restartQueue() {
        try {
            SecureStore store = new SecureStore(this);
            if (store.firstConnectionProfile() == null) { applyDecision(); return; }
            if (!wantsVpn(store)) store.setControlMode(SecureStore.MANUAL_ON);
            stopCurrent(store.protectedMode);
            clearHealth();
            guardProfileId = "";
            islandFailed.clear();
            applyDecision();
        } catch (Exception ignored) {
            StatusStore.set(this, StatusStore.ERROR, "Не удалось начать очередь заново");
        }
    }

    private boolean needsProtection(SecureStore store) {
        return ProtectionRules.needsBarrier(store.protectedMode, store.manualMode, store.isHomeSsid(ssid));
    }

    private void holdProtection(String detail) {
        // Only the VPN owner may announce BLOCKED, after successfully establishing its TUN.
        StatusStore.set(this, StatusStore.CONNECTING, "Устанавливаем блокировку трафика");
        stopCurrent(true);
        runningProtected = true;
        startForegroundService(new Intent(this, ProtectedVpnService.class).setAction(ProtectedVpnService.ACTION_HOLD)
                .putExtra("blockedDetail", detail));
    }

    /**
     * Android hides the Wi-Fi name while location is off, so "home" cannot be recognised and the
     * VPN (correctly) stays on. True when that is the reason, so the status can say so.
     */
    private boolean locationHidesHome(SecureStore store) {
        if (!ssid.isEmpty() || store.homeSsids.isEmpty() || SecureStore.MANUAL_ON.equals(store.manualMode)) return false;
        try {
            android.location.LocationManager location = getSystemService(android.location.LocationManager.class);
            if (location == null || location.isLocationEnabled()) return false;
        } catch (RuntimeException ignored) { return false; }
        return hasPhysicalWifi();
    }

    /** True when a real (non-VPN) Wi-Fi network is connected. */
    private boolean hasPhysicalWifi() {
        try {
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    // Worker thread. Wi-Fi appearing or disappearing is when a tunnel most often breaks:
    // return to fast checks and look again soon instead of waiting out a slow interval.
    private void physicalNetworkChanged() {
        healthyStreak = 0;
        if (!runningId.isEmpty() && verified && healthScheduled) scheduleHealth(3000);
    }

    private void clearHealth() {
        handler.removeCallbacks(healthTick);
        healthScheduled = false;
        healthyStreak = 0;
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
        if (store.protectedMode) return ProtectionRules.shouldConnect(true, store.manualMode,
                store.isHomeSsid(ssid), store.firstConnectionProfile() != null);
        if (!store.automation && SecureStore.MANUAL_AUTO.equals(store.manualMode)) return false;
        if (SecureStore.MANUAL_OFF.equals(store.manualMode)) return false;
        if (store.firstConnectionProfile() == null) return false;
        return SecureStore.MANUAL_ON.equals(store.manualMode) ||
                (!store.homeSsids.isEmpty() && !store.isHomeSsid(ssid));
    }

    private void checkHealth() {
        healthScheduled = false;
        if (activeProbe != null) return;
        try {
            SecureStore store = new SecureStore(this);
            island.setEnabled(store.liveIsland);
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
            final boolean qwdtt = Profile.QWDTT.equals(runningType);
            final boolean confirmation = consecutiveTrafficFailures > 0;
            if (qwdtt && QwdttState.pending(this, runningId)) {
                StatusStore.set(this, StatusStore.CONNECTING, "qWDTT · требуется капча VK — откройте AutoTurn");
                island.show(LiveIsland.Content.captcha());
                scheduleHealth(2000);
                return;
            }
            activeProbe = probes.submit(() -> {
                ConnectionProbe.Result outcome;
                try {
                    outcome = qwdtt ? ConnectionProbe.checkQwdtt(this, connectivity, probeProfileId, confirmation) :
                            ConnectionProbe.check(this, connectivity, confirmation);
                } catch (Throwable failure) {
                    // An unexpected error must not leave activeProbe set forever, which would stop all checks.
                    Log.w(TAG, "Connection probe failed: " + failure.getClass().getSimpleName());
                    outcome = ConnectionProbe.Result.FAILED;
                }
                final ConnectionProbe.Result result = outcome;
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
        if (result != ConnectionProbe.Result.HEALTHY) healthyStreak = 0;
        try {
            SecureStore store = new SecureStore(this);
            island.setEnabled(store.liveIsland);
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
                StatusStore.set(this, StatusStore.ON, "VPN проверен · " + store.activeProfile().name +
                        (locationHidesHome(store) ? " · включите геолокацию, чтобы узнавать дом" : ""));
                islandFailed.clear();
                island.done(StatusStore.detail(this));
                scheduleHealth(HealthSchedule.healthyInterval(++healthyStreak));
            } else if (result == ConnectionProbe.Result.NO_NETWORK) {
                consecutiveTrafficFailures = 0;
                StatusStore.set(this, StatusStore.CONNECTING, "Нет сети · ждём восстановления");
                // A verified VPN losing the network (metro, lift) is not worth an island each time.
                if (!verified || island.showing()) island.show(LiveIsland.Content.noNetwork());
                scheduleHealth(2000);
            } else if (result == ConnectionProbe.Result.VPN_PERMISSION_LOST) {
                stopCurrent();
                StatusStore.set(this, StatusStore.ERROR, "Разрешение VPN отозвано · откройте AutoTurn");
                island.show(LiveIsland.Content.permissionLost());
            } else if ((result == ConnectionProbe.Result.VPN_MISSING || result == ConnectionProbe.Result.FAILED) && !verified &&
                    SystemClock.elapsedRealtime() - startedAt <
                            (Profile.QWDTT.equals(runningType) ? 310_000 : START_GRACE_MS)) {
                // A pending VPN is different from a missing physical network. In particular,
                // do not retain an earlier NO_NETWORK label throughout qWDTT's startup window.
                StatusStore.set(this, StatusStore.CONNECTING, "Подключаем VPN · " + store.activeProfile().name);
                showConnecting(store, store.activeProfile());
                scheduleHealth(1000);
            } else {
                if (result == ConnectionProbe.Result.FAILED && ++consecutiveTrafficFailures < 2) {
                    Log.i(TAG, "Short traffic probe inconclusive; confirming before failover");
                    StatusStore.set(this, verified ? StatusStore.ON : StatusStore.CONNECTING,
                            "Проверяем доступ через VPN · соединение сохраняется");
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

    private void handleTunnelFailure(String failedId, String attemptId) {
        if (failedId == null || !failedId.equals(runningId) || runningAttemptId.isEmpty() || !runningAttemptId.equals(attemptId)) return;
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
        if (!current.enabled) { applyDecision(); return; }
        island.setEnabled(store.liveIsland);
        islandFailed.add(current.id);
        if(store.protectedMode)startService(new Intent(this,ProtectedVpnService.class)
                .setAction(ProtectedVpnService.ACTION_REFRESH_ENDPOINT).putExtra(XrayVpnService.EXTRA_PROFILE_ID,current.id));
        else if(endpointUpdates!=null)endpointUpdates.failed(current.id);
        Profile next = nextProfile(store, current.id);
        Log.i(TAG, "Failover from " + current.type + " to " +
                (next == null ? current.type : next.type));
        if (next != null && !store.chooseConnection(store.primaryId, current.id, next.id)) {
            applyDecision();
            return;
        }
        failedProfiles++;
        stopCurrent(store.protectedMode);
        verified = false;
        if (next != null) guardProfileId = next.id;
        // If a tunnel worked earlier, retry it once before pausing the whole cycle.
        if (failedProfiles >= store.connectionProfiles().size() * (hadHealthyConnection ? 2 : 1)) {
            failedProfiles = 0;
            pauseUntil = SystemClock.elapsedRealtime() + ALL_FAILED_PAUSE_MS;
            if (store.protectedMode) holdProtection("Трафик заблокирован · профили недоступны, повторим через 15 секунд");
            else StatusStore.set(this, StatusStore.ERROR, "Профили недоступны · повторим через 15 секунд");
            islandFailed.clear();
            island.show(LiveIsland.Content.pause(System.currentTimeMillis() + ALL_FAILED_PAUSE_MS));
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

    /** Worker thread. Shows which profile of the queue is being tried, with failed ones in red. */
    private void showConnecting(SecureStore store, Profile profile) {
        if (profile == null) return;
        java.util.List<Profile> ordered = store.connectionProfiles();
        java.util.List<Boolean> failed = new java.util.ArrayList<>();
        int current = -1;
        for (int i = 0; i < ordered.size(); i++) {
            failed.add(islandFailed.contains(ordered.get(i).id));
            if (ordered.get(i).id.equals(profile.id)) current = i;
        }
        island.show(LiveIsland.Content.connecting(profile.name, failed, current));
    }

    private void clearIsland() {
        islandFailed.clear();
        if (island != null) island.clear();
    }

    /** Worker thread. Catches "connected" and "off" reported by tunnels in other processes. */
    private void statusChanged() {
        if (!island.showing()) return;
        String state = StatusStore.state(this);
        String detail = StatusStore.detail(this);
        if (StatusStore.OFF.equals(state)) clearIsland();
        else if (StatusStore.ON.equals(state) && !detail.startsWith("Проверяем")) {
            islandFailed.clear();
            island.done(detail);
        }
    }

    private Profile nextProfile(SecureStore store, String currentId) {
        java.util.List<Profile> ordered = store.connectionProfiles();
        if (ordered.size() < 2) return null;
        for (int i = 0; i < ordered.size(); i++) {
            if (currentId.equals(ordered.get(i).id))
                return ordered.get((i + 1) % ordered.size());
        }
        return ordered.get(0);
    }

    private void stopCurrent() {
        stopCurrent(false);
    }

    private void stopCurrent(boolean keepProtection) {
        runningAttemptId = "";
        consecutiveTrafficFailures = 0;
        healthyStreak = 0;
        generation++;
        if (activeProbe != null) {
            activeProbe.cancel(true);
            activeProbe = null;
        }
        handler.removeCallbacks(healthTick);
        healthScheduled = false;
        verified = false;
        observedVpn = null;
        boolean protectedStopSent = false;
        try {
            if (runningProtected) {
                if (keepProtection) startService(new Intent(this, ProtectedVpnService.class)
                        .setAction(ProtectedVpnService.ACTION_HOLD));
                else {
                    stopProtectedOwner(true);
                    protectedStopSent = true;
                }
                if (!keepProtection) runningProtected = false;
            } else if (Profile.AMNEZIA.equals(runningType) && backend != null)
                backend.setState(tunnel, Tunnel.State.DOWN, null);
            else if (Profile.VLESS.equals(runningType) || Profile.HYSTERIA2.equals(runningType) || Profile.QWDTT.equals(runningType)) {
                Intent stop = new Intent(this, XrayVpnService.class).setAction(XrayVpnService.ACTION_STOP);
                startService(stop);
            }
        } catch (Exception ignored) {
            StatusStore.set(this, StatusStore.ERROR, "Не удалось остановить VPN");
        }
        runningId = "";
        runningType = "";
        runningPolicy = "";
        // Also release an orphan owner after AutomationService was recreated in another process.
        if (!keepProtection && !protectedStopSent) {
            try { stopProtectedOwner(false); }
            catch (Exception ignored) { StatusStore.set(this, StatusStore.ERROR, "Не удалось остановить защиту"); }
        }
    }

    @SuppressWarnings("deprecation")
    private void stopProtectedOwner(boolean knownRunning) {
        if (!knownRunning) {
            // Android still exposes our own services. Do not create a protected process
            // or notification during ordinary legacy-mode decisions when none exists.
            android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
            boolean found = false;
            if (manager != null) for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
                if (ProtectedVpnService.class.getName().equals(service.service.getClassName())) { found = true; break; }
            if (!found) return;
        }
        // Android binds to an established VpnService. stopService() alone need not
        // destroy a bound service, so the owner must explicitly close its master TUN.
        startService(new Intent(this, ProtectedVpnService.class).setAction(ProtectedVpnService.ACTION_STOP));
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
