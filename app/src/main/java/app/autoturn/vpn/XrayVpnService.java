package app.autoturn.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import libXray.DialerController;
import libXray.LibXray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;

public final class XrayVpnService extends VpnService {
    private static final String TAG = "AutoTurnXray";
    static final String ACTION_START = "app.autoturn.vpn.XRAY_START";
    static final String ACTION_STOP = "app.autoturn.vpn.XRAY_STOP";
    static final String EXTRA_PROFILE_ID = "profileId";
    private static final String CHANNEL = "xray";
    // libXray has one native core per process. Service instances must share its queue.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static XrayVpnService coreOwner;
    private ParcelFileDescriptor tunnel;
    private PowerManager.WakeLock tunnelWakeLock;
    private volatile long requestedGeneration;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Xray VPN", NotificationManager.IMPORTANCE_LOW));
        startForeground(101, notification("Подключаем VPN"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        long commandGeneration = ++requestedGeneration;
        if (ACTION_STOP.equals(intent.getAction())) {
            worker.execute(() -> { stopTunnel(); stopSelfResult(startId); });
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            startForeground(101, notification("Подключаем VPN"));
            String id = intent.getStringExtra(EXTRA_PROFILE_ID);
            worker.execute(() -> startTunnel(id, commandGeneration));
        }
        return START_NOT_STICKY;
    }

    private boolean currentRequest(String id, long commandGeneration) throws Exception {
        if (commandGeneration != requestedGeneration) return false;
        SecureStore store = new SecureStore(this);
        return id != null && id.equals(store.activeId) &&
                !SecureStore.MANUAL_OFF.equals(store.manualMode) &&
                (store.automation || SecureStore.MANUAL_ON.equals(store.manualMode));
    }

    private void startTunnel(String id, long commandGeneration) {
        String protocolName = "VPN";
        try {
            if (!currentRequest(id, commandGeneration)) return;
            if (coreOwner != null) coreOwner.stopTunnel();
            coreOwner = this;
            if (VpnService.prepare(this) != null) throw new IllegalStateException("VPN permission revoked");
            SecureStore store = new SecureStore(this);
            Profile profile = store.activeProfile();
            if (profile == null || !profile.id.equals(id) ||
                    (!Profile.VLESS.equals(profile.type) && !Profile.HYSTERIA2.equals(profile.type)))
                throw new IllegalStateException("Profile changed");
            protocolName = Profile.HYSTERIA2.equals(profile.type) ? "Hysteria 2" : "Reality";

            JSONArray outbounds = outbounds(profile.config, profile.type);
            Set<String> routedApps = AppPolicy.routed(this, store);
            // These profiles use an IPv4-only server. Do not advertise a synthetic IPv6
            // address: cached AAAA/DoH results otherwise send apps into an unreachable path.
            // Android blocks an omitted address family by default (no allowFamily/bypass).
            Builder builder = new Builder().setSession("AutoTurn " + protocolName).setMtu(1500)
                    .addAddress("10.123.45.1", 30).addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .setBlocking(true);
            AppPolicy.apply(builder, store.appMode, routedApps);
            tunnel = builder.establish();
            if (tunnel == null) throw new IllegalStateException("VPN interface unavailable");

            DialerController controller = fd -> protect((int) fd);
            LibXray.registerDialerController(controller);
            LibXray.registerListenerController(controller);
            LibXray.setDNS(controller, "1.1.1.1:53");

            JSONObject config = new JSONObject();
            config.put("log", new JSONObject().put("loglevel", "warning"));
            config.put("env", new JSONObject().put("xray.tun.fd", String.valueOf(tunnel.getFd())));
            JSONArray inbounds = new JSONArray();
            inbounds.put(new JSONObject().put("tag", "tun-in")
                    .put("protocol", "tun")
                    .put("settings", new JSONObject().put("name", "autoturn").put("mtu", 1500)));
            config.put("inbounds", inbounds);
            // The current Reality server is reachable over IPv4, but cannot serve IPv6
            // destinations. Return no AAAA records to apps rather than handing them
            // unreachable IPv6 addresses. Android also blocks IPv6 for this VPN.
            JSONObject dnsRules = new JSONObject().put("action", "return")
                    .put("qType", 28).put("rCode", 0);
            outbounds.put(new JSONObject().put("tag", "dns-out")
                    .put("protocol", "dns")
                    .put("settings", new JSONObject().put("rules", new JSONArray().put(dnsRules))));
            config.put("outbounds", outbounds);
            config.put("dns", new JSONObject().put("servers", new JSONArray().put("https://1.1.1.1/dns-query"))
                    .put("queryStrategy", "UseIPv4"));
            config.put("routing", new JSONObject().put("rules", new JSONArray()
                    .put(new JSONObject().put("type", "field")
                            .put("inboundTag", new JSONArray().put("tun-in"))
                            .put("port", "53").put("outboundTag", "dns-out"))));
            invoke("runXray", new JSONObject().put("xrayJson", config.toString()));
            if (!currentRequest(id, commandGeneration)) { stopTunnel(); return; }
            // Foreground service / battery exemption alone do not keep CPU timers running.
            // QUIC and its health checks must remain alive while this tunnel is active.
            tunnelWakeLock = getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "AutoTurn:XrayTunnel");
            tunnelWakeLock.setReferenceCounted(false);
            tunnelWakeLock.acquire();
            boolean checking = store.connectionGuard;
            StatusStore.set(this, checking ? StatusStore.CONNECTING : StatusStore.ON,
                    checking ? protocolName + " · проверяем соединение" : protocolName + " включён");
            getSystemService(NotificationManager.class).notify(101,
                    notification(checking ? "Проверяем " + protocolName : protocolName + " включён"));
        } catch (Exception e) {
            stopTunnel();
            try { if (!currentRequest(id, commandGeneration)) return; }
            catch (Exception ignored) { return; }
            Log.e(TAG, "Could not start " + protocolName + " tunnel", e);
            StatusStore.set(this, StatusStore.ERROR, protocolName + " не подключился. Проверьте профиль и сеть.");
            getSystemService(NotificationManager.class).notify(101, notification("Ошибка " + protocolName));
            startService(new Intent(this, AutomationService.class)
                    .setAction(AutomationService.ACTION_TUNNEL_FAILED)
                    .putExtra(EXTRA_PROFILE_ID, id));
        }
    }

    private JSONArray outbounds(String source, String type) throws Exception {
        if (Profile.HYSTERIA2.equals(type) &&
                (source.startsWith("hysteria2://") || source.startsWith("hy2://")))
            return new JSONArray().put(Hysteria2Config.outbound(source));
        JSONArray candidates;
        if (source.trim().startsWith("{")) candidates = new JSONObject(source).getJSONArray("outbounds");
        else {
            JSONObject response = invoke("convertShareLinksToXrayJson",
                    new JSONObject().put("text", source));
            candidates = response.getJSONObject("data").getJSONArray("outbounds");
        }
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.getJSONObject(i);
            JSONObject stream = item.optJSONObject("streamSettings");
            if (stream == null) continue;
            if (Profile.VLESS.equals(type) && "vless".equalsIgnoreCase(item.optString("protocol")) &&
                    "reality".equalsIgnoreCase(stream.optString("security")))
                return new JSONArray().put(item);
            if (Profile.HYSTERIA2.equals(type) && "hysteria".equalsIgnoreCase(item.optString("protocol")) &&
                    "hysteria".equalsIgnoreCase(stream.optString("network")) &&
                    "tls".equalsIgnoreCase(stream.optString("security")))
                return new JSONArray().put(item);
        }
        throw new IllegalArgumentException("No supported outbound for " + type);
    }

    private JSONObject invoke(String method, JSONObject payload) throws Exception {
        JSONObject request = new JSONObject().put("apiVersion", 3).put("method", method);
        if (payload != null) request.put("payload", payload);
        JSONObject response = new JSONObject(LibXray.invoke(request.toString()));
        if (!response.optBoolean("success")) throw new IllegalStateException("Xray operation failed");
        return response;
    }

    private void stopTunnel() {
        if (tunnelWakeLock != null) {
            if (tunnelWakeLock.isHeld()) tunnelWakeLock.release();
            tunnelWakeLock = null;
        }
        // Late destruction of an old Service must not stop a newer instance's core.
        if (coreOwner == this) {
            try { invoke("stopXray", null); } catch (Exception ignored) { }
            try { LibXray.resetDNS(); } catch (Exception ignored) { }
            coreOwner = null;
        }
        if (tunnel != null) {
            try { tunnel.close(); } catch (Exception ignored) { }
            tunnel = null;
        }
    }

    @Override public void onRevoke() {
        requestedGeneration++;
        worker.execute(() -> { stopTunnel(); stopSelf(); });
        super.onRevoke();
    }

    @Override public void onDestroy() {
        requestedGeneration++;
        worker.execute(this::stopTunnel);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("AutoTurn")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true).build();
    }
}
