package app.autoturn.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.InetAddresses;
import android.net.VpnService;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import libXray.DialerController;
import libXray.LibXray;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.InetNetwork;
import org.amnezia.awg.config.Peer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** One Android VPN owner for every protected engine. Engines never own the last TUN fd. */
public final class ProtectedVpnService extends VpnService {
    static final String ACTION_START = "app.autoturn.vpn.PROTECTED_START";
    static final String ACTION_HOLD = "app.autoturn.vpn.PROTECTED_HOLD";
    static final String ACTION_STOP = "app.autoturn.vpn.PROTECTED_STOP";
    private static final String CHANNEL = "protected-vpn";
    private static final int NOTIFICATION = 104;
    // The native Xray core is process-global. Serialize even across recreated Service instances.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static ProtectedVpnService owner;
    private volatile long generation;
    private ParcelFileDescriptor tunnel, xrayFd;
    private String tunnelSpec = "";
    private ProtectedAwgClient awg;
    private boolean xrayRunning;
    private volatile QwdttTransport qwdtt;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL, "Защищённый VPN", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification("Устанавливаем защиту"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && XrayVpnService.ACTION_CAPTCHA.equals(intent.getAction())) {
            QwdttTransport current = qwdtt;
            if (current != null) current.captchaResult(intent.getStringExtra("session"),
                    intent.getStringExtra("request"), intent.getStringExtra("token"));
            return START_STICKY;
        }
        long command = ++generation;
        String action = intent == null ? ACTION_HOLD : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra(XrayVpnService.EXTRA_PROFILE_ID);
        String blockedDetail = intent == null ? null : intent.getStringExtra("blockedDetail");
        startForeground(NOTIFICATION, notification("Защищённый режим · ожидаем VPN"));
        worker.execute(() -> {
            if (command != generation) return;
            if (ACTION_STOP.equals(action)) {
                releaseOwned(); stopSelfResult(startId); return;
            }
            adopt();
            if (ACTION_START.equals(action)) startEngine(id, command);
            else hold(command, blockedDetail);
            if (intent == null) refreshAutomation();
        });
        return ACTION_STOP.equals(action) ? START_NOT_STICKY : START_STICKY;
    }

    private void adopt() {
        if (owner == this) return;
        if (owner != null) owner.releaseOwned();
        owner = this;
    }

    private boolean barrierWanted(SecureStore store) {
        return ProtectionRules.needsBarrier(store.protectedMode, store.manualMode,
                store.isHomeSsid(homeSsid()));
    }

    private String homeSsid() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            boolean wifi = false;
            for (android.net.Network network : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                        && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!wifi) return "";
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            String value = info == null ? "" : info.getSSID();
            if (value == null || WifiManager.UNKNOWN_SSID.equals(value)) return "";
            return value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
        } catch (SecurityException e) { return ""; } // Unknown home status must fail closed.
    }

    private boolean current(String id, long command) throws Exception {
        if (owner != this || command != generation) return false;
        SecureStore store = new SecureStore(this);
        return ProtectionRules.shouldConnect(store.protectedMode, store.manualMode,
                store.isHomeSsid(homeSsid()), store.activeProfile() != null)
                && id != null && id.equals(store.activeId);
    }

    private void hold(long command) {
        hold(command, null);
    }

    private void hold(long command, String detail) {
        if (command != generation) return;
        try {
            SecureStore store = new SecureStore(this);
            if (!barrierWanted(store)) { releaseOwned(); stopSelf(); return; }
            stopEngine();
            if (tunnel == null) ensureTunnel(store, null, isQwdtt(store.activeProfile()));
            Config config = awgConfig(store.activeProfile());
            ensureTunnel(store, config, isQwdtt(store.activeProfile()));
            if (command == generation) blocked(detail != null ? detail :
                    StatusStore.BLOCKED.equals(StatusStore.state(this)) ? StatusStore.detail(this) :
                            "Трафик заблокирован · ожидаем VPN");
        } catch (Exception e) { reportBarrierFailure(); }
    }

    private static boolean isQwdtt(Profile p) { return p != null && Profile.QWDTT.equals(p.type); }

    private Config awgConfig(Profile profile) throws Exception {
        return profile != null && Profile.AMNEZIA.equals(profile.type)
                ? Config.parse(new BufferedReader(new StringReader(profile.config))) : null;
    }

    private void startEngine(String id, long command) {
        try {
            if (!current(id, command)) { hold(command); return; }
            SecureStore store = new SecureStore(this);
            Profile profile = store.activeProfile();
            stopEngine(); // Keep our owning descriptor open while detaching the previous engine.
            // Establish a safe generic barrier even if parsing the selected profile fails.
            if (tunnel == null) ensureTunnel(store, null, isQwdtt(profile));
            Config config = awgConfig(profile);
            ensureTunnel(store, config, isQwdtt(profile));
            blocked("Трафик заблокирован · подключаем " + profile.name);
            acquireWakeLock();
            if (config != null) {
                Config resolved = resolveAwg(config);
                if (!current(id, command)) { hold(command); return; }
                drainPending();
                if (awg == null) awg = new ProtectedAwgClient(this);
                awg.start(tunnel, resolved.toAwgUserspaceString());
            } else {
                JSONArray outbounds;
                if (isQwdtt(profile)) {
                    qwdtt = new QwdttTransport(this);
                    outbounds = qwdtt.start(profile, () -> {
                        try { return current(id, command); } catch (Exception e) { return false; }
                    });
                } else {
                    outbounds = XrayVpnService.outbounds(profile.config, profile.type);
                    resolveXray(outbounds.getJSONObject(0));
                }
                if (!current(id, command)) { hold(command); return; }
                DialerController controller = fd -> protect((int) fd);
                LibXray.registerDialerController(controller);
                LibXray.registerListenerController(controller);
                // Endpoints were resolved explicitly over TLS. No direct UDP/system DNS fallback.
                drainPending();
                xrayFd = ParcelFileDescriptor.dup(tunnel.getFileDescriptor());
                JSONObject json = xrayConfig(outbounds, xrayFd.getFd());
                xrayRunning = true; // Also tear down a partially initialized native core on failure.
                XrayVpnService.invoke("runXray", new JSONObject().put("xrayJson", json.toString()));
                if (qwdtt != null) qwdtt.markVpnReady();
            }
            if (!current(id, command)) { hold(command); return; }
            StatusStore.set(this, StatusStore.CONNECTING, "Защищённый режим · проверяем " + profile.name);
            getSystemService(NotificationManager.class).notify(NOTIFICATION,
                    notification("Защита активна · " + profile.name));
        } catch (Exception e) {
            stopEngine();
            try {
                if (!current(id, command)) { hold(command); return; }
                blocked("Трафик заблокирован · VPN не подключился");
                // Never log credentials/configuration or fall back to the legacy engine.
                Log.w("AutoTurnProtected", "Protected engine unavailable: " + e.getClass().getSimpleName() +
                        "; at " + (e.getStackTrace().length == 0 ? "unknown" : e.getStackTrace()[0]));
                startService(new Intent(this, AutomationService.class)
                        .setAction(AutomationService.ACTION_TUNNEL_FAILED)
                        .putExtra(XrayVpnService.EXTRA_PROFILE_ID, id));
            } catch (Exception ignored) { reportBarrierFailure(); }
        }
    }

    private void ensureTunnel(SecureStore store, Config config, boolean turn) throws Exception {
        if (VpnService.prepare(this) != null) throw new IllegalStateException("VPN permission revoked");
        Set<String> apps = AppPolicy.routed(this, store);
        String mode = store.appMode;
        if (AppPolicy.ONLY.equals(mode)) {
            if (turn) apps.remove(getPackageName()); else apps.add(getPackageName());
        } else if (turn) { apps.add(getPackageName()); mode = AppPolicy.EXCLUDE; }
        List<InetNetwork> addresses = new ArrayList<>();
        if (config != null) for (InetNetwork address : config.getInterface().getAddresses())
            if (address.getAddress() instanceof Inet4Address) addresses.add(address);
        if (config != null && addresses.isEmpty()) throw new IllegalArgumentException("AWG requires IPv4");
        int mtu = config == null ? 1500 : config.getInterface().getMtu().orElse(1280);
        String spec = mode + apps + addresses + ":" + mtu;
        if (tunnel != null && spec.equals(tunnelSpec)) return;
        Builder builder = new Builder().setSession("AutoTurn · Защищённый режим")
                .setMtu(mtu).setBlocking(false).setMetered(false)
                .addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1");
        if (addresses.isEmpty()) builder.addAddress("10.123.45.1", 30);
        else for (InetNetwork address : addresses) builder.addAddress(address.getAddress(), address.getMask());
        // No IPv6 address/route and no allowFamily/allowBypass: Android blocks IPv6 for these UIDs.
        AppPolicy.apply(builder, mode, apps);
        ParcelFileDescriptor replacement = builder.establish();
        if (replacement == null) throw new IllegalStateException("Cannot establish protection");
        ParcelFileDescriptor previous = tunnel;
        tunnel = replacement; tunnelSpec = spec;
        // Same VPN owner: establish the replacement BEFORE releasing the previous interface.
        if (previous != null) previous.close();
    }

    private JSONObject xrayConfig(JSONArray outbounds, int fd) throws Exception {
        outbounds.getJSONObject(0).put("tag", "protected-proxy");
        outbounds.put(new JSONObject().put("tag", "protected-dns-out").put("protocol", "dns")
                .put("settings", new JSONObject().put("rules", new JSONArray().put(new JSONObject()
                        .put("action", "return").put("qType", 28).put("rCode", 0)))));
        JSONArray inbounds = new JSONArray().put(new JSONObject().put("tag", "tun-in").put("protocol", "tun")
                .put("settings", new JSONObject().put("name", "autoturn-secure").put("mtu", 1500)));
        if (qwdtt != null) inbounds.put(qwdtt.probeInbound());
        JSONArray rules = new JSONArray()
                .put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put("tun-in"))
                        .put("port", "53").put("outboundTag", "protected-dns-out"))
                .put(new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put("protected-dns"))
                        .put("outboundTag", "protected-proxy"));
        return new JSONObject().put("log", new JSONObject().put("loglevel", "warning"))
                .put("env", new JSONObject().put("xray.tun.fd", String.valueOf(fd)))
                .put("inbounds", inbounds).put("outbounds", outbounds)
                .put("dns", new JSONObject().put("tag", "protected-dns").put("queryStrategy", "UseIPv4")
                        .put("servers", new JSONArray().put("https://1.1.1.1/dns-query")))
                .put("routing", new JSONObject().put("rules", rules));
    }

    private void resolveXray(JSONObject outbound) throws Exception {
        JSONObject settings = outbound.getJSONObject("settings");
        if (settings.has("address")) settings.put("address", endpoint(settings.getString("address")));
        JSONArray peers = settings.optJSONArray("vnext");
        if (peers != null) for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.getJSONObject(i);
            peer.put("address", endpoint(peer.getString("address")));
        }
    }

    private Config resolveAwg(Config source) throws Exception {
        Config.Builder result = new Config.Builder().setInterface(source.getInterface());
        for (Peer peer : source.getPeers()) {
            InetEndpoint remote = peer.getEndpoint().orElseThrow(() -> new IllegalArgumentException("Missing endpoint"));
            List<String> lines = new ArrayList<>();
            for (String line : peer.toAwgQuickString().split("\\r?\\n")) {
                if (line.trim().startsWith("Endpoint")) line = "Endpoint = " + endpoint(remote.getHost()) + ":" + remote.getPort();
                lines.add(line);
            }
            result.addPeer(Peer.parse(lines));
        }
        return result.build();
    }

    private String endpoint(String host) throws Exception {
        try {
            InetAddress address = InetAddresses.parseNumericAddress(host);
            if (!(address instanceof Inet4Address)) throw new java.io.IOException("Endpoint requires IPv4");
            return address.getHostAddress();
        } catch (IllegalArgumentException notNumeric) { /* Resolve only this VPN endpoint, not app DNS. */ }
        int id = new SecureRandom().nextInt(65536);
        byte[] query = BootstrapDns.query(host, id);
        try (Socket raw = new Socket()) {
            // Socket() is lazy on Android: bind first to obtain a real fd for protect().
            raw.bind(new InetSocketAddress(0));
            if (!protect(raw)) throw new java.io.IOException("Bootstrap protection unavailable");
            raw.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {1, 1, 1, 1}), 853), 3500);
            raw.setSoTimeout(3500);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(raw, "one.one.one.one", 853, false)) {
                SSLParameters params = tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(params); tls.setSoTimeout(3500); tls.startHandshake();
                DataOutputStream out = new DataOutputStream(tls.getOutputStream());
                out.writeShort(query.length); out.write(query); out.flush();
                DataInputStream in = new DataInputStream(tls.getInputStream());
                int length = in.readUnsignedShort();
                if (length < 12 || length > 4096) throw new java.io.IOException("Invalid bootstrap response size");
                byte[] response = new byte[length]; in.readFully(response);
                return InetAddress.getByAddress(BootstrapDns.ipv4(response, id)).getHostAddress();
            }
        }
    }

    private void drainPending() throws Exception {
        // Builder.setBlocking(false), and both native engines use nonblocking descriptors.
        // Avoid fcntlInt (API 30) so protected mode also works on the app's Android 10 minimum.
        byte[] packet = new byte[65536];
        android.system.StructPollfd readable = new android.system.StructPollfd();
        readable.fd = tunnel.getFileDescriptor(); readable.events = (short) OsConstants.POLLIN;
        for (int i = 0; i < 512; i++) {
            // Some native runtimes change shared fd flags. With the engine stopped we are
            // the sole reader; poll before read also makes draining safe on those runtimes.
            if (Os.poll(new android.system.StructPollfd[] {readable}, 0) == 0 ||
                    (readable.revents & OsConstants.POLLIN) == 0) break;
            try { if (Os.read(tunnel.getFileDescriptor(), packet, 0, packet.length) <= 0) break; }
            catch (ErrnoException e) { if (e.errno == OsConstants.EAGAIN) break; throw e; }
        }
    }

    private void stopEngine() {
        if (awg != null) awg.stop();
        if (xrayRunning) {
            try { XrayVpnService.invoke("stopXray", null); } catch (Exception ignored) { }
            xrayRunning = false;
        }
        // This bundled Xray's AndroidTun.Close deliberately does not close its supplied fd.
        if (xrayFd != null) { try { xrayFd.close(); } catch (Exception ignored) { } xrayFd = null; }
        if (qwdtt != null) { qwdtt.close(); qwdtt = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        // tunnel remains open: no reader/engine means no user packets can leave via it.
    }

    private void releaseOwned() {
        if (owner != this) return;
        stopEngine();
        if (tunnel != null) { try { tunnel.close(); } catch (Exception ignored) { } tunnel = null; }
        if (awg != null) { awg.close(); awg = null; }
        tunnelSpec = ""; owner = null;
    }

    private void blocked(String detail) {
        if (tunnel == null) { reportBarrierFailure(); return; }
        StatusStore.set(this, StatusStore.BLOCKED, detail);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(detail));
    }

    private void reportBarrierFailure() {
        String detail = "Не удалось установить защиту · откройте AutoTurn";
        StatusStore.set(this, StatusStore.ERROR, detail);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(detail));
    }

    private void refreshAutomation() {
        startForegroundService(new Intent(this, AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
    }

    private void acquireWakeLock() {
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTurn:Protected");
        wakeLock.setReferenceCounted(false); wakeLock.acquire();
    }

    private Notification notification(String detail) {
        PendingIntent open = PendingIntent.getActivity(this, 104, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setContentTitle("AutoTurn · Защищённый режим")
                .setContentText(detail).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(open).setOngoing(true).build();
    }

    @Override public void onRevoke() {
        ++generation;
        worker.execute(() -> {
            if (owner == this) {
                releaseOwned();
                StatusStore.set(this, StatusStore.ERROR, "Android отключил VPN · защита недоступна");
            }
            stopSelf();
        });
    }

    @Override public void onDestroy() {
        ++generation;
        worker.execute(this::releaseOwned);
        super.onDestroy();
    }
}
