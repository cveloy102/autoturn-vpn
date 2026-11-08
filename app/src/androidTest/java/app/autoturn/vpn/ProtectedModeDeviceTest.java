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
    private final long auditStartedAt = System.currentTimeMillis();

    private boolean optedIn() {
        return "true".equals(((InstrumentationTestRunner) getInstrumentation()).getArguments()
                .getString("protectedModeAudit"));
    }

    public void testSavedSettingsMatchAuditBaseline() throws Exception {
        if (!"true".equals(((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("compareSavedSettings"))) return;
        context = getInstrumentation().getTargetContext();
        java.lang.reflect.Method keyMethod = SecureStore.class.getDeclaredMethod("key"); keyMethod.setAccessible(true);
        javax.crypto.SecretKey secret = (javax.crypto.SecretKey) keyMethod.invoke(new SecureStore(context));
        JSONObject[] values = new JSONObject[2];
        String[] names = {"audit-expected.enc", "settings.enc"};
        for (int i = 0; i < names.length; i++) {
            JSONObject envelope = new JSONObject(Files.readString(new File(context.getFilesDir(), names[i]).toPath()));
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, new javax.crypto.spec.GCMParameterSpec(128,
                    android.util.Base64.decode(envelope.getString("iv"), android.util.Base64.NO_WRAP)));
            values[i] = new JSONObject(new String(cipher.doFinal(android.util.Base64.decode(envelope.getString("data"),
                    android.util.Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8));
        }
        Set<String> keys = new LinkedHashSet<>(), differences = new LinkedHashSet<>();
        values[0].keys().forEachRemaining(keys::add); values[1].keys().forEachRemaining(keys::add);
        for (String key : keys) if (!String.valueOf(values[0].opt(key)).equals(String.valueOf(values[1].opt(key)))) differences.add(key);
        // Print field names only. Profile configurations and decrypted values never leave the phone.
        report("SETTINGS differing fields: " + differences);
        assertTrue("All saved settings and profile data equal the pre-audit backup", differences.isEmpty());
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
        String requestedIndex = ((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("browserProfileIndex");
        for (Profile profile : baseline.orderedProfiles())
            if (Profile.HYSTERIA2.equals(profile.type)) { selected = profile; break; }
        if (requestedIndex != null) selected = baseline.orderedProfiles().get(Integer.parseInt(requestedIndex));
        assertNotNull("Installed browser-audit profile", selected);
        report("BROWSER PROFILE " + selected.name);
        String physical = ((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("browserNetwork", "mobile");
        boolean direct = "true".equals(((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("browserDirect"));
        stopForRestore();
        String expectedSsid = ((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("browserWifiSsid");
        if ("wifi".equals(physical)) {
            waitPhysical(NetworkCapabilities.TRANSPORT_WIFI);
            if (expectedSsid != null) assertEquals("Browser starts on requested router", expectedSsid, wifiSsid());
        }
        else if ("1".equals(shell("settings get global wifi_on").trim()) &&
                "1".equals(shell("settings get global mobile_data").trim())) {
            restoreWifi = true; shell("svc wifi disable");
            waitPhysical(NetworkCapabilities.TRANSPORT_CELLULAR);
        }
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(selected); store.activeId = selected.id; store.primaryId = selected.id;
        store.homeSsids.clear(); store.autoReturnHours = 0; store.appMode = AppPolicy.ONLY;
        store.onlyPackages.clear(); store.onlyPackages.add("com.android.chrome");
        store.selectedPackages.clear(); store.selectedPackages.addAll(store.onlyPackages);
        store.protectedMode = !direct; store.connectionGuard = true; store.save();
        store.setControlMode(direct ? SecureStore.MANUAL_OFF : SecureStore.MANUAL_ON);
        refresh(); waitState(direct ? StatusStore.OFF : StatusStore.ON,
                Profile.QWDTT.equals(selected.type) ? 180000 : 45000);
        if (direct) report("BROWSER BASELINE: VPN off, direct Wi-Fi access");
        NetworkCapabilities caps = context.getSystemService(ConnectivityManager.class).getNetworkCapabilities(
                context.getSystemService(ConnectivityManager.class).getActiveNetwork());
        int expected = "wifi".equals(physical) ? NetworkCapabilities.TRANSPORT_WIFI : NetworkCapabilities.TRANSPORT_CELLULAR;
        assertTrue("Browser audit uses requested physical network", caps != null && caps.hasTransport(expected));
        report(shell("am start -W -f 0x10000000 -a android.intent.action.VIEW -c android.intent.category.BROWSABLE " +
                "-d https://browserleaks.com/dns -p com.android.chrome"));
        long browserUntil = SystemClock.elapsedRealtime() + 35000;
        while (SystemClock.elapsedRealtime() < browserUntil) {
            if (expectedSsid != null) assertEquals("Router remains unchanged during DNS audit", expectedSsid, wifiSsid());
            Thread.sleep(500);
        }
        android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getFilesDir(), "protected-browser-dns.png"))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { screenshot.recycle(); }
        report("Browser DNS screenshot captured for visual inspection");
    }

    private void trafficProbe(boolean vpn, String phase) throws Exception {
        // OFF is a saved/UI state; Android removes the VPN network asynchronously.
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        long networkUntil = SystemClock.elapsedRealtime() + 10000;
        boolean present;
        do {
            present = false;
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) present = true;
            }
            if (present == vpn) break;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < networkUntil);
        assertEquals("Android VPN network settled before independent probe", vpn, present);
        shell("run-as app.autoturn.vpn.test rm -f files/routing-probe.json");
        shell("am start -f 0x18000000 -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez udpDns true");
        long until = SystemClock.elapsedRealtime() + 30000;
        JSONObject result = null;
        while (SystemClock.elapsedRealtime() < until) {
            String raw = shell("run-as app.autoturn.vpn.test cat files/routing-probe.json").trim();
            if (raw.startsWith("{")) { result = new JSONObject(raw); break; }
            Thread.sleep(200);
        }
        assertNotNull("Independent traffic probe completed", result);
        assertEquals("Independent app VPN assignment", vpn, result.optBoolean("vpn"));
        assertTrue("DNS answer", result.optBoolean("dnsPacketReply"));
        assertTrue("HTTPS response", result.optInt("http") >= 200 && result.optInt("http") < 400);
        report("PASS " + phase + ": " + result);
    }

    public void testProtectedHomeTimersTileAndDisable() throws Exception {
        if (!optedIn()) return;
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath());
        SecureStore baseline = new SecureStore(context);
        Profile selected = null;
        for (Profile profile : baseline.orderedProfiles())
            if (Profile.HYSTERIA2.equals(profile.type)) { selected = profile; break; }
        assertNotNull("Installed Hysteria profile", selected);
        java.util.regex.Matcher name = java.util.regex.Pattern.compile("Wifi is connected to \"([^\"]+)\"")
                .matcher(shell("cmd wifi status"));
        assertTrue("Connected Wi-Fi", name.find());
        stopForRestore();
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(selected); store.activeId = selected.id; store.primaryId = selected.id;
        store.homeSsids.clear(); store.homeSsids.add(name.group(1)); store.autoReturnHours = 0;
        store.appMode = AppPolicy.ONLY; store.onlyPackages.clear(); store.onlyPackages.add("app.autoturn.vpn.test");
        store.selectedPackages.clear(); store.selectedPackages.addAll(store.onlyPackages);
        store.protectedMode = true; store.connectionGuard = true; store.save();
        store.setControlMode(SecureStore.MANUAL_AUTO); refresh(); waitState(StatusStore.OFF, 15000);
        trafficProbe(false, "protected AUTO at home releases VPN");
        new SecureStore(context).setControlMode(SecureStore.MANUAL_ON); refresh(); waitState(StatusStore.ON, 45000);
        trafficProbe(true, "manual ON overrides home");
        store = new SecureStore(context); store.manualUntil = System.currentTimeMillis() + 2500;
        store.save("manualUntil"); refresh(); waitState(StatusStore.OFF, 15000);
        assertEquals("Timed ON returns to AUTO", SecureStore.MANUAL_AUTO, new SecureStore(context).manualMode);
        report("PASS protected timed ON -> AUTO at home");
        store = new SecureStore(context); store.homeSsids.clear(); store.homeSsids.add("qa-not-this-wifi"); store.save();
        refresh(); waitState(StatusStore.ON, 45000);
        new SecureStore(context).setControlMode(SecureStore.MANUAL_OFF); refresh(); waitState(StatusStore.BLOCKED, 15000);
        blockedProbe("protected manual OFF away");
        store = new SecureStore(context); store.manualUntil = System.currentTimeMillis() + 2500;
        store.save("manualUntil"); refresh(); waitState(StatusStore.ON, 45000);
        assertEquals("Timed OFF returns to AUTO", SecureStore.MANUAL_AUTO, new SecureStore(context).manualMode);
        report("PASS protected timed OFF -> AUTO away reconnects");
        new SecureStore(context).setControlMode(SecureStore.MANUAL_OFF); refresh(); waitState(StatusStore.BLOCKED, 15000);
        try {
            shell("cmd statusbar expand-settings"); Thread.sleep(600);
            shell("cmd statusbar click-tile app.autoturn.vpn/.VpnTileService"); waitState(StatusStore.ON, 45000);
            shell("cmd statusbar click-tile app.autoturn.vpn/.VpnTileService"); waitState(StatusStore.BLOCKED, 15000);
            for (int i = 0; i < 6; i++) {
                shell("cmd statusbar click-tile app.autoturn.vpn/.VpnTileService"); Thread.sleep(100);
            }
            waitState(StatusStore.BLOCKED, 15000); Thread.sleep(1500);
            assertEquals(SecureStore.MANUAL_OFF, new SecureStore(context).manualMode);
            report("PASS protected real tile ON/OFF and six rapid clicks retain final OFF");
        } finally { shell("cmd statusbar collapse"); }
        store = new SecureStore(context); store.protectedMode = false; store.save("protectedMode"); refresh();
        waitState(StatusStore.OFF, 15000);
        trafficProbe(false, "disabling protected mode restores direct access after manual OFF");
    }

    public void testAwgOwnerRepeatedRelease() throws Exception {
        if (!optedIn()) return;
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc"); original = Files.readAllBytes(settings.toPath());
        SecureStore baseline = new SecureStore(context);
        int profiles = 0;
        for (Profile profile : baseline.orderedProfiles()) {
            if (!Profile.AMNEZIA.equals(profile.type)) continue;
            profiles++;
            for (int cycle = 1; cycle <= 2; cycle++) {
                stopForRestore();
                SecureStore store = new SecureStore(context);
                store.profiles.clear(); store.profiles.add(profile); store.activeId = profile.id; store.primaryId = profile.id;
                store.homeSsids.clear(); store.autoReturnHours = 0; store.appMode = AppPolicy.ONLY;
                store.onlyPackages.clear(); store.onlyPackages.add("app.autoturn.vpn.test");
                store.selectedPackages.clear(); store.selectedPackages.addAll(store.onlyPackages);
                store.protectedMode = true; store.connectionGuard = true; store.save();
                store.setControlMode(SecureStore.MANUAL_ON); refresh(); waitState(StatusStore.ON, 45000);
                trafficProbe(true, profile.name + " cycle " + cycle);
                new SecureStore(context).setControlMode(SecureStore.MANUAL_OFF); refresh(); waitState(StatusStore.BLOCKED, 15000);
                blockedProbe(profile.name + " held before release " + cycle);
                store = new SecureStore(context); store.protectedMode = false; store.save("protectedMode"); refresh();
                waitState(StatusStore.OFF, 15000); trafficProbe(false, profile.name + " released " + cycle);
                Thread.sleep(1000);
            }
        }
        assertTrue("At least one AWG profile audited", profiles > 0);
    }

    private String wifiSsid() throws Exception {
        java.util.regex.Matcher name = java.util.regex.Pattern.compile("Wifi is connected to \"([^\"]+)\"")
                .matcher(shell("cmd wifi status"));
        return name.find() ? name.group(1) : "";
    }

    private boolean clickWifiText(String text) {
        android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if (root == null || !"com.android.settings".equals(String.valueOf(root.getPackageName()))) return false;
        for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
            CharSequence description = node.getText() != null ? node.getText() : node.getContentDescription();
            if (description == null) continue;
            String label = description.toString().replace("\uFFFC", "").trim();
            if (!label.equals(text) && !label.startsWith(text + ",") &&
                    !label.startsWith(text + " ") && !label.startsWith(text + "\n")) continue;
            for (int level = 0; node != null && level < 5; level++, node = node.getParent())
                if (node.isClickable()) return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private void switchSavedWifi(String ssid) throws Exception {
        if (ssid.equals(wifiSsid())) return;
        shell("am start -W -a android.settings.WIFI_SETTINGS");
        long until = SystemClock.elapsedRealtime() + 45000;
        boolean clicked = false;
        while (SystemClock.elapsedRealtime() < until) {
            if (ssid.equals(wifiSsid())) {
                waitPhysical(NetworkCapabilities.TRANSPORT_WIFI);
                shell("input keyevent 4"); return;
            }
            if (!clicked) clicked = clickWifiText(ssid);
            else { clickWifiText("Подключить"); clickWifiText("Connect"); }
            Thread.sleep(500);
        }
        fail("Saved Wi-Fi did not connect: " + ssid);
    }

    public void testSavedWifiNetworkTransitions() throws Exception {
        if (!optedIn()) return;
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc"); original = Files.readAllBytes(settings.toPath());
        SecureStore baseline = new SecureStore(context);
        String initialSsid = ((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("plainWifiSsid", wifiSsid());
        assertFalse("Initial Wi-Fi connected", initialSsid.isEmpty());
        assertFalse("Begin on the plain router", "Keen_5G".equals(initialSsid));
        try {
            switchSavedWifi(initialSsid);
            configure(AppPolicy.ONLY);
            blockedProbe("plain router before saved-network switch");
            shell("run-as app.autoturn.vpn.test rm -f files/barrier-watch.json");
            shell("am start -f 0x18000000 -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez barrierWatch true");
            switchSavedWifi("Keen_5G"); blockedProbe("held VPN on Keen_5G");
            switchSavedWifi(initialSsid); blockedProbe("held VPN back on plain router");
            assertWatch("plain router -> Keen_5G -> plain router");
            stopForRestore();
            Profile selected = null;
            for (Profile profile : baseline.orderedProfiles())
                if (Profile.HYSTERIA2.equals(profile.type)) { selected = profile; break; }
            assertNotNull("Installed Hysteria", selected);
            SecureStore store = new SecureStore(context);
            store.profiles.clear(); store.profiles.add(selected); store.activeId = selected.id; store.primaryId = selected.id;
            store.homeSsids.clear(); store.homeSsids.add("Keen_5G"); store.autoReturnHours = 0;
            store.appMode = AppPolicy.ONLY; store.onlyPackages.clear(); store.onlyPackages.add("app.autoturn.vpn.test");
            store.selectedPackages.clear(); store.selectedPackages.addAll(store.onlyPackages);
            store.protectedMode = true; store.connectionGuard = true; store.save();
            store.setControlMode(SecureStore.MANUAL_AUTO); refresh(); waitState(StatusStore.ON, 45000);
            trafficProbe(true, "AUTO away on plain router");
            switchSavedWifi("Keen_5G"); waitState(StatusStore.OFF, 20000);
            trafficProbe(false, "AUTO intentionally releases phone VPN at Keen_5G home");
            switchSavedWifi(initialSsid); waitState(StatusStore.ON, 45000);
            trafficProbe(true, "AUTO reconnects after returning to plain router");
        } finally { switchSavedWifi(initialSsid); }
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
                Thread.sleep(1000);
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
                    for (android.app.ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 30)) {
                        if (exit.getTimestamp() < auditStartedAt || !exit.getProcessName().startsWith(context.getPackageName() + ":protected")) continue;
                        int reason = exit.getReason();
                        assertFalse("No protected service crash during audit or cleanup: " + exit.getProcessName() + " reason=" + reason,
                                reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                                reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                                reason == android.app.ApplicationExitInfo.REASON_ANR);
                    }
                }
            }
        } finally { super.tearDown(); }
    }
}
