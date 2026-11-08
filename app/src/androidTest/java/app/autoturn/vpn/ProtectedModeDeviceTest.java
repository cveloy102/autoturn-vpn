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

/** Opt-in live audit. Never contacts real VPN endpoints; restores encrypted settings in tearDown. */
public final class ProtectedModeDeviceTest extends InstrumentationTestCase {
    private Context context;
    private byte[] original;
    private File settings;

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
        shell("am start -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez udpDns true");
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
        assertFalse("HTTPS did not escape", result.has("http"));
        report("PASS " + phase + ": retained VPN, no DNS reply, no HTTPS");
    }

    public void testManualStopAndFailedCrossEngineCycle() throws Exception {
        if (!optedIn()) { report("SKIP live protected audit: requires -e protectedModeAudit true"); return; }
        context = getInstrumentation().getTargetContext();
        assertNull("Grant VPN permission before the live audit", android.net.VpnService.prepare(context));
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath());
        for (String mode : new String[] {AppPolicy.ONLY, AppPolicy.EXCLUDE}) {
            configure(mode);
            blockedProbe(mode + " manual OFF");
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
                assertTrue("VPN barrier survives the engine handover", vpn);
                if (StatusStore.BLOCKED.equals(StatusStore.state(context)) &&
                        StatusStore.detail(context).contains("профили недоступны")) {
                    sawAllFailed = true; break;
                }
                Thread.sleep(25);
            }
            assertTrue("Reached the all-profiles-failed pause", sawAllFailed);
            blockedProbe(mode + " all profiles failed");
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (original != null) {
                stopForRestore();
                Files.write(settings.toPath(), original);
                assertTrue("Encrypted settings restored exactly", java.util.Arrays.equals(original, Files.readAllBytes(settings.toPath())));
                refresh();
                report("RESTORED original encrypted settings; no Wi-Fi or system VPN setting changed");
            }
        } finally { super.tearDown(); }
    }
}
