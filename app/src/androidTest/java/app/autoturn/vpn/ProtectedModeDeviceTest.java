package app.autoturn.vpn;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import org.amnezia.awg.crypto.KeyPair;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/** Opt-in device audits; real profiles require a separate flag. Settings are restored in tearDown. */
public final class ProtectedModeDeviceTest extends InstrumentationTestCase {
    private Context context;
    private byte[] original;
    private File settings;
    private boolean restoreWifi;

    private boolean optedIn() {
        return "true".equals(((InstrumentationTestRunner) getInstrumentation()).getArguments()
                .getString("protectedModeAudit"));
    }

    private void report(String message) {
        android.os.Bundle out = new android.os.Bundle(); out.putString("stream", message + "\n");
        getInstrumentation().sendStatus(0, out);
    }

    private String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd = getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void refresh() {
        context.startForegroundService(new Intent(context, AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
    }

    private void waitState(String state, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            if (state.equals(StatusStore.state(context))) return;
            Thread.sleep(100);
        }
        fail("Expected " + state + ", actual " + StatusStore.state(context));
    }

    private void stopForRestore() throws Exception {
        SecureStore store = new SecureStore(context);
        store.protectedMode = false; store.save("protectedMode");
        store.setControlMode(SecureStore.MANUAL_OFF); refresh();
        waitState(StatusStore.OFF, 15000);
        context.stopService(new Intent(context, ProtectedVpnService.class));
        Thread.sleep(1000);
    }

    private void configure(String mode) throws Exception {
        stopForRestore();
        KeyPair local = new KeyPair(), remote = new KeyPair();
        String config = "[Interface]\nPrivateKey = " + local.getPrivateKey().toBase64() +
                "\nAddress = 10.90.0.2/32\nDNS = 1.1.1.1\n[Peer]\nPublicKey = " + remote.getPublicKey().toBase64() +
                "\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:9\n";
        SecureStore store = new SecureStore(context);
        store.profiles.clear();
        Profile awg = new Profile("Offline AWG fixture", Profile.AMNEZIA, config);
        Profile hy2 = new Profile("Offline Hysteria fixture", Profile.HYSTERIA2,
                "hysteria2://test-only@127.0.0.1:9?sni=example.com");
        store.profiles.add(awg); store.profiles.add(hy2); store.primaryId = awg.id; store.activeId = awg.id;
        store.homeSsids.clear(); store.autoReturnHours = 0;
        Set<String> only = new LinkedHashSet<>(), excluded = new LinkedHashSet<>();
        only.add("app.autoturn.vpn.test"); excluded.add("com.android.settings");
        store.setAppPolicy(mode, only, excluded);
        store.protectedMode = true; store.connectionGuard = true;
        store.save("protectedMode", "connectionGuard", "homeSsids", "autoReturnHours");
        store.setControlMode(SecureStore.MANUAL_OFF); refresh();
        waitState(StatusStore.BLOCKED, 15000);
    }

    private void blockedProbe(String phase) throws Exception {
        shell("run-as app.autoturn.vpn.test rm -f files/routing-probe.json");
        shell("am start -f 0x18000000 -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez udpDns true --ez blocked true");
        long until = SystemClock.elapsedRealtime() + 20000;
        JSONObject result = null;
        while (SystemClock.elapsedRealtime() < until) {
            String text = shell("run-as app.autoturn.vpn.test cat files/routing-probe.json").trim();
            if (text.startsWith("{")) { result = new JSONObject(text); break; }
            Thread.sleep(100);
        }
        assertNotNull("Probe completed", result);
        assertTrue("A VPN remains assigned to the test app", result.optBoolean("vpn"));
        assertFalse("No DNS reply escaped the blocked tunnel", result.optBoolean("dnsPacketReply", true));
        assertTrue("External TLS probe ran without depending on system DNS timeout", result.has("tlsExternal"));
        assertFalse("No verified external TLS connection", result.optBoolean("tlsExternal"));
        assertFalse("HTTPS did not escape", result.has("http"));
        report("PASS " + phase + ": retained VPN, no DNS reply, no HTTPS");
    }

    public void testManualStopAndFailedCrossEngineCycle() throws Exception {
        if (!optedIn()) { report("SKIP live protected audit: requires -e protectedModeAudit true"); return; }
        context = getInstrumentation().getTargetContext();
        assertNull("Grant VPN permission before the live audit", android.net.VpnService.prepare(context));
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath());
        stopForRestore();
        shell("run-as app.autoturn.vpn.test rm -f files/barrier-watch.json");
        shell("am start -f 0x18000000 -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez barrierWatch true --ei watchMillis 3000");
        Thread.sleep(4500);
        JSONObject control = new JSONObject(shell("run-as app.autoturn.vpn.test cat files/barrier-watch.json").trim());
        assertTrue("Direct control: verified TLS is observable", control.optInt("tlsConnected") > 0);
        assertTrue("Direct control: fresh DNS answers are observable", control.optInt("dnsReplies") > 0);
        report("PASS direct positive control: " + control);
        for (String mode : new String[] {AppPolicy.ONLY, AppPolicy.EXCLUDE}) {
            configure(mode);
            blockedProbe(mode + " manual OFF");
            shell("run-as app.autoturn.vpn.test rm -f files/barrier-watch.json");
            shell("am start -f 0x18000000 -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez barrierWatch true");
            new SecureStore(context).setControlMode(SecureStore.MANUAL_ON); refresh();
            boolean sawAllFailed = false;
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            long until = SystemClock.elapsedRealtime() + 65000;
            while (SystemClock.elapsedRealtime() < until) {
                boolean vpn = false;
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
                }
                if (!vpn) {
                    boolean escaped = false;
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(
                                new byte[] {1,1,1,1}), 443), 1500);
                        escaped = true;
                    } catch (Exception ignored) { }
                    report("HANDOVER OBSERVATION: VPN absent from ConnectivityManager; direct TCP connected=" + escaped +
                            "; status=" + StatusStore.state(context));
                    assertFalse("No direct TCP escape during the handover", escaped);
                }
                if (StatusStore.BLOCKED.equals(StatusStore.state(context)) &&
                        StatusStore.detail(context).contains("профили недоступны")) {
                    sawAllFailed = true; break;
                }
                Thread.sleep(25);
            }
            assertTrue("Reached the all-profiles-failed pause", sawAllFailed);
            assertWatch(mode + " engine handover");
            blockedProbe(mode + " all profiles failed");
        }
    }

    private void assertWatch(String phase) throws Exception {
        long watchUntil = SystemClock.elapsedRealtime() + 50000;
        JSONObject watch = null;
        while (SystemClock.elapsedRealtime() < watchUntil) {
            String raw = shell("run-as app.autoturn.vpn.test cat files/barrier-watch.json").trim();
            if (raw.startsWith("{")) {
                watch = new JSONObject(raw);
                assertEquals("No external TLS escape", 0, watch.optInt("tlsConnected"));
                assertEquals("No UDP DNS escape", 0, watch.optInt("dnsReplies"));
                assertEquals("No IPv6 escape", 0, watch.optInt("ipv6Connected"));
                if (watch.optBoolean("complete")) break;
            }
            Thread.sleep(200);
        }
        assertNotNull("Independent-app watcher", watch);
        assertTrue("Watcher finished", watch.optBoolean("complete"));
        assertTrue("Repeated traffic attempts", watch.optInt("attempts") >= 20);
        report("PASS " + phase + " continuous independent-app watch: " + watch);
    }

    public void testHeldRoutingChanges() throws Exception {
        if (!optedIn()) return;
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath());
        configure(AppPolicy.ONLY);
        SecureStore store = new SecureStore(context);
        String awgId = store.activeId;
        // HOLD does not start or parse a qWDTT transport. Only its UID exemption changes.
        Profile turn = new Profile("Held qWDTT fixture", Profile.QWDTT, "{}");
        store.profiles.add(turn); store.save();
        shell("run-as app.autoturn.vpn.test rm -f files/barrier-watch.json");
        shell("am start -f 0x18000000 -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez barrierWatch true");
        for (String mode : new String[] {AppPolicy.ONLY, AppPolicy.EXCLUDE}) {
            for (String id : new String[] {turn.id, awgId}) {
                store = new SecureStore(context);
                Set<String> only = new LinkedHashSet<>(), excluded = new LinkedHashSet<>();
                only.add("app.autoturn.vpn.test"); excluded.add("com.android.settings");
                store.setAppPolicy(mode, only, excluded);
                store.activeId = id; store.primaryId = id; store.save("activeId", "primaryId");
                refresh(); Thread.sleep(500); waitState(StatusStore.BLOCKED, 15000);
                blockedProbe(mode + (id.equals(turn.id) ? " held qWDTT" : " held AWG"));
            }
        }
        assertWatch("qWDTT UID exemption and app-policy changes");
    }

    public void testInstalledProfiles() throws Exception {
        if (!"true".equals(((InstrumentationTestRunner) getInstrumentation()).getArguments()
                .getString("protectedRealProfiles"))) return;
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath());
        SecureStore baseline = new SecureStore(context);
        for (Profile profile : baseline.orderedProfiles()) {
            String targetType = ((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("profileType");
            if (targetType != null && !targetType.equals(profile.type)) continue;
            stopForRestore();
            SecureStore store = new SecureStore(context);
            store.profiles.clear(); store.profiles.add(profile);
            store.activeId = profile.id; store.primaryId = profile.id;
            store.homeSsids.clear(); store.autoReturnHours = 0;
            store.appMode = AppPolicy.ONLY;
            store.onlyPackages.clear(); store.onlyPackages.add("app.autoturn.vpn.test");
            store.onlyPackages.add("com.android.chrome");
            store.selectedPackages.clear(); store.selectedPackages.addAll(store.onlyPackages);
            store.protectedMode = true; store.connectionGuard = true; store.save();
            store.setControlMode(SecureStore.MANUAL_ON); refresh();
            long until = SystemClock.elapsedRealtime() + (Profile.QWDTT.equals(profile.type) ? 180000 : 45000);
            while (SystemClock.elapsedRealtime() < until && !StatusStore.ON.equals(StatusStore.state(context))) {
                if (QwdttState.pending(context, profile.id)) fail("qWDTT requests user CAPTCHA");
                Thread.sleep(200);
            }
            assertEquals("Profile connected: " + profile.name + "; " + StatusStore.detail(context),
                    StatusStore.ON, StatusStore.state(context));
            shell("run-as app.autoturn.vpn.test rm -f files/routing-probe.json");
            shell("am start -f 0x18000000 -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez udpDns true --ez audit true");
            until = SystemClock.elapsedRealtime() + 65000;
            JSONObject result = null;
            while (SystemClock.elapsedRealtime() < until) {
                String raw = shell("run-as app.autoturn.vpn.test cat files/routing-probe.json").trim();
                if (raw.startsWith("{")) { result = new JSONObject(raw); break; }
                Thread.sleep(200);
            }
            report("REAL PROFILE " + profile.name + ": " + result);
            assertNotNull(result);
            assertTrue("Independent app assigned to VPN", result.optBoolean("vpn"));
            assertTrue("UDP DNS answer through VPN", result.optBoolean("dnsPacketReply"));
            assertTrue("HTTPS through VPN", result.optInt("http") >= 200 && result.optInt("http") < 400);
            assertFalse("Public exit observed", result.optString("egress").isEmpty());
            assertEquals("TLS payload", 262144, result.optInt("payloadBytes"));
            new SecureStore(context).setControlMode(SecureStore.MANUAL_OFF); refresh();
            waitState(StatusStore.BLOCKED, 15000);
            blockedProbe(profile.name + " manual OFF after live connection");
        }
    }

    public void testBlockedWifiMobileHandoff() throws Exception {
        if (!optedIn()) return;
        context = getInstrumentation().getTargetContext();
        assertEquals("Wi-Fi initially enabled", "1", shell("settings get global wifi_on").trim());
        assertEquals("Mobile data initially enabled", "1", shell("settings get global mobile_data").trim());
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath());
        configure(AppPolicy.ONLY);
        blockedProbe("Wi-Fi before handoff");
        restoreWifi = true; shell("svc wifi disable");
        waitPhysical(NetworkCapabilities.TRANSPORT_CELLULAR);
        blockedProbe("mobile after Wi-Fi disconnect");
        shell("svc wifi enable");
        waitPhysical(NetworkCapabilities.TRANSPORT_WIFI);
        blockedProbe("Wi-Fi after mobile handoff");
    }

    public void testBrowserDns() throws Exception {
        if (!"true".equals(((InstrumentationTestRunner) getInstrumentation()).getArguments()
                .getString("protectedBrowserAudit"))) return;
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath());
        SecureStore baseline = new SecureStore(context);
        Profile selected = null;
        for (Profile profile : baseline.orderedProfiles())
            if (Profile.HYSTERIA2.equals(profile.type)) { selected = profile; break; }
        assertNotNull("Installed Hysteria profile", selected);
        stopForRestore();
        if ("1".equals(shell("settings get global wifi_on").trim()) &&
                "1".equals(shell("settings get global mobile_data").trim())) {
            restoreWifi = true; shell("svc wifi disable");
            waitPhysical(NetworkCapabilities.TRANSPORT_CELLULAR);
        }
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(selected); store.activeId = selected.id; store.primaryId = selected.id;
        store.homeSsids.clear(); store.autoReturnHours = 0; store.appMode = AppPolicy.ONLY;
        store.onlyPackages.clear(); store.onlyPackages.add("com.android.chrome");
        store.selectedPackages.clear(); store.selectedPackages.addAll(store.onlyPackages);
        store.protectedMode = true; store.connectionGuard = true; store.save();
        store.setControlMode(SecureStore.MANUAL_ON); refresh(); waitState(StatusStore.ON, 45000);
        report(shell("am start -W -f 0x10000000 -a android.intent.action.VIEW -c android.intent.category.BROWSABLE " +
                "-d https://browserleaks.com/dns -p com.android.chrome"));
        Thread.sleep(35000);
        android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getFilesDir(), "protected-browser-dns.png"))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { screenshot.recycle(); }
        report("Browser DNS screenshot captured for visual inspection");
    }

    private void waitPhysical(int transport) throws Exception {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        long until = SystemClock.elapsedRealtime() + 45000;
        while (SystemClock.elapsedRealtime() < until) {
            boolean found = false, wifi = false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (!ConnectionProbe.isUnderlyingInternet(caps)) continue;
                wifi |= caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                found |= caps.hasTransport(transport) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            if (found && (transport != NetworkCapabilities.TRANSPORT_CELLULAR || !wifi)) return;
            Thread.sleep(200);
        }
        fail("Physical transport unavailable: " + transport);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (restoreWifi) shell("svc wifi enable");
            if (original != null) {
                stopForRestore();
                Files.write(settings.toPath(), original);
                assertTrue("Encrypted settings restored exactly", java.util.Arrays.equals(original, Files.readAllBytes(settings.toPath())));
                refresh();
                report("RESTORED original encrypted settings and initial Wi-Fi state; system VPN settings unchanged");
            }
        } finally { super.tearDown(); }
    }
}
