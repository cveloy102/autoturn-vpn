package app.autoturn.vpn;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Authorized live audit of existing profiles. Never prints profile configuration or credentials. */
public final class QwdttAuditTest extends InstrumentationTestCase {
    private Context context;
    private byte[] original;
    private SecureStore baseline;
    private File settings;
    private boolean changed, wifiChanged, idle;
    private void report(String text) {
        android.os.Bundle out = new android.os.Bundle();
        out.putString("stream", text + "\n"); getInstrumentation().sendStatus(0, out);
    }
    private String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd = getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Override protected void setUp() throws Exception {
        super.setUp(); context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath()); baseline = new SecureStore(context);
    }
    private void refresh() {
        context.startForegroundService(new Intent(context, AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
    }
    private void mode(String value) throws Exception {
        changed = true; new SecureStore(context).setControlMode(value); refresh();
    }
    private void waitState(String wanted, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            if (wanted.equals(StatusStore.state(context))) return;
            if (QwdttState.pending(context, new SecureStore(context).activeId)) {
                fail("VK CAPTCHA requested; challenge left for user, no automated solution");
            }
            Thread.sleep(200);
        }
        fail("Expected " + wanted + ", actual=" + StatusStore.state(context) + ", detail=" + StatusStore.detail(context));
    }
    private List<Profile> targets() {
        List<Profile> result = new ArrayList<>();
        for (Profile p : baseline.profiles) if (Profile.QWDTT.equals(p.type)) result.add(p);
        return result;
    }
    public void testInventory() throws Exception {
        report("Installed baseline: profiles=" + baseline.profiles.size() + "; mode=" + baseline.manualMode +
                "; routing=" + baseline.appMode + "; guard=" + baseline.connectionGuard);
        for (Profile p : baseline.orderedProfiles()) {
            String peer = Profile.QWDTT.equals(p.type) ? QwdttConfig.parse(p.config).peer : "not-qwdtt";
            report("PROFILE name=" + p.name + "; type=" + p.type + "; primary=" + p.id.equals(baseline.primaryId) +
                    "; last=" + p.lastResort + "; endpoint=" + peer);
            if (Profile.QWDTT.equals(p.type)) {
                QwdttConfig c = QwdttConfig.parse(p.config);
                report("CONFIG SHAPE " + p.name + "; workers=" + c.workers + "; hashCount=" + c.hashes.split(",").length +
                        "; localPort=" + c.port);
            }
        }
        List<Profile> q = targets();
        if (q.size() == 2) {
            QwdttConfig a = QwdttConfig.parse(q.get(0).config), b = QwdttConfig.parse(q.get(1).config);
            report("PROFILE COMPARISON sameServerPassword=" + a.password.equals(b.password) + "; sameVkHashes=" + a.hashes.equals(b.hashes));
        }
    }
    private void configure(Profile p, String routing) throws Exception {
        // This suite exercises the legacy engine even when the saved baseline is protected.
        changed = true;
        SecureStore legacy = new SecureStore(context);
        legacy.protectedMode = false; legacy.save("protectedMode");
        mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 15000);
        assertNull("Existing VPN permission", android.net.VpnService.prepare(context));
        SecureStore s = new SecureStore(context);
        s.profiles.clear(); s.profiles.add(p); s.primaryId = p.id; s.activeId = p.id;
        s.connectionGuard = true; s.appMode = routing; s.autoReturnHours = 0;
        s.selectedPackages.clear(); s.onlyPackages.clear(); s.excludedPackages.clear();
        if (AppPolicy.ONLY.equals(routing)) s.onlyPackages.add("app.autoturn.vpn.test");
        if (AppPolicy.EXCLUDE.equals(routing)) s.excludedPackages.add("app.autoturn.vpn.test");
        if (AppPolicy.ONLY.equals(routing)) s.selectedPackages.addAll(s.onlyPackages);
        if (AppPolicy.EXCLUDE.equals(routing)) s.selectedPackages.addAll(s.excludedPackages);
        s.save();
    }
    private long start(Profile p) throws Exception {
        long then = SystemClock.elapsedRealtime(); mode(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 180000);
        long elapsed = SystemClock.elapsedRealtime() - then;
        assertEquals(p.id, new SecureStore(context).activeId);
        report("CONNECTED " + p.name + " in " + elapsed + " ms"); return elapsed;
    }
    private JSONObject probe(boolean vpn, boolean udp, String phase) throws Exception {
        shell("run-as app.autoturn.vpn.test rm -f files/routing-probe.json");
        shell("am start -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez wake true --ez udp " + udp +
                " --ez audit " + phase.contains("egress-payload"));
        JSONObject result = null; long until = SystemClock.elapsedRealtime() + 65000;
        while (SystemClock.elapsedRealtime() < until) {
            String raw = shell("run-as app.autoturn.vpn.test cat files/routing-probe.json").trim();
            if (raw.startsWith("{")) { result = new JSONObject(raw); break; }
            Thread.sleep(250);
        }
        report("PROBE " + phase + ": " + result);
        assertNotNull("Independent-app result: " + phase, result);
        assertEquals("VPN routing: " + phase, vpn, result.optBoolean("vpn"));
        assertTrue("HTTPS: " + phase + " " + result, result.optInt("http") >= 200 && result.optInt("http") < 400);
        if (udp) assertTrue("UDP STUN: " + phase + " " + result, result.optBoolean("udp"));
        return result;
    }
    private void noNative() throws Exception {
        long until = SystemClock.elapsedRealtime() + 6000;
        while (SystemClock.elapsedRealtime() < until && !shell("pidof libqwdtt.so").trim().isEmpty()) Thread.sleep(200);
        assertTrue("No orphan qWDTT process", shell("pidof libqwdtt.so").trim().isEmpty());
        assertTrue("No stale runtime session", QwdttState.read(context).optString("session").isEmpty());
    }
    public void testRoutingAndRepeatedStarts() throws Exception {
        assertFalse("At least one installed qWDTT profile", targets().isEmpty());
        for (Profile p : targets()) {
            for (String routing : new String[]{AppPolicy.ALL, AppPolicy.ONLY, AppPolicy.EXCLUDE}) {
                configure(p, routing); start(p);
                probe(!AppPolicy.EXCLUDE.equals(routing), !AppPolicy.EXCLUDE.equals(routing), p.name + " " + routing);
                mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000); noNative();
                report("PASS " + p.name + " " + routing + " and clean stop");
            }
            configure(p, AppPolicy.ALL);
            for (int cycle = 1; cycle <= 3; cycle++) {
                start(p); probe(true, true, p.name + " repeat " + cycle);
                long then = SystemClock.elapsedRealtime(); mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000);
                report("STOP " + p.name + " in " + (SystemClock.elapsedRealtime() - then) + " ms"); noNative();
            }
        }
    }
    public void testEgressAndPayload() throws Exception {
        for (Profile p : targets()) {
            configure(p, AppPolicy.ALL); start(p);
            JSONObject r = probe(true, true, p.name + " egress-payload");
            assertEquals("Exit IP is the authorized VPS", "45.67.202.147", r.optString("egress"));
            assertEquals("Full 256 KiB TLS payload", 262144, r.optInt("payloadBytes"));
        }
    }
    private void waitPhysical(int transport) throws Exception {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        long until = SystemClock.elapsedRealtime() + 45000;
        while (SystemClock.elapsedRealtime() < until) {
            android.net.Network activeNetwork = cm.getActiveNetwork();
            NetworkCapabilities active = cm.getNetworkCapabilities(activeNetwork);
            boolean wifiPresent = false;
            for (android.net.Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && ConnectionProbe.isUnderlyingInternet(context, cm, n) && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiPresent = true;
            }
            // AutoTurn's UID bypasses qWDTT's TUN. Require its actual default physical
            // network, not a cellular network merely kept available behind Wi-Fi.
            // Real HTTPS/UDP probes below establish reachability; Android validation may be blocked.
            if (active != null && ConnectionProbe.isUnderlyingInternet(context, cm, activeNetwork) && active.hasTransport(transport) &&
                    (transport != NetworkCapabilities.TRANSPORT_CELLULAR || !wifiPresent)) {
                report("PHYSICAL DEFAULT confirmed: " + (transport == NetworkCapabilities.TRANSPORT_WIFI ? "Wi-Fi" : "mobile") +
                        "; wifiPresent=" + wifiPresent);
                Thread.sleep(1500); return;
            }
            Thread.sleep(300);
        }
        fail("Physical transport missing: " + transport);
    }
    private void recover(Profile p, String phase, long then) throws Exception {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        int failures = 0;
        while (SystemClock.elapsedRealtime() - then < 120000) {
            if (ConnectionProbe.checkQwdtt(context, cm, p.id) == ConnectionProbe.Result.HEALTHY) {
                report("RECOVER " + p.name + " " + phase + " in " + (SystemClock.elapsedRealtime()-then) + " ms; failed probes=" + failures);
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try { probe(true, true, p.name + " " + phase + " attempt=" + attempt); return; }
                    catch (junit.framework.AssertionFailedError gap) {
                        if (attempt == 3) throw gap;
                        report("HANDOFF transient independent-app gap; retrying " + p.name + " " + phase);
                        Thread.sleep(1500);
                    }
                }
            }
            failures++; Thread.sleep(500);
        }
        fail("No recovery: " + p.name + " " + phase);
    }
    public void testWifiMobileHandoff() throws Exception {
        assertEquals("Wi-Fi initially enabled", "1", shell("settings get global wifi_on").trim());
        assertEquals("Mobile data initially enabled", "1", shell("settings get global mobile_data").trim());
        for (Profile p : targets()) {
            configure(p, AppPolicy.ALL); start(p); probe(true, true, p.name + " before handoff");
            for (int cycle = 1; cycle <= 2; cycle++) {
                waitPhysical(NetworkCapabilities.TRANSPORT_WIFI);
                long then = SystemClock.elapsedRealtime();
                wifiChanged = true; shell("svc wifi disable"); waitPhysical(NetworkCapabilities.TRANSPORT_CELLULAR);
                recover(p, "Wi-Fi -> mobile cycle=" + cycle, then);
                then = SystemClock.elapsedRealtime();
                shell("svc wifi enable"); wifiChanged = false; waitPhysical(NetworkCapabilities.TRANSPORT_WIFI);
                recover(p, "mobile -> Wi-Fi cycle=" + cycle, then);
            }
        }
    }
    public void testScreenOffSustained() throws Exception {
        for (Profile p : targets()) {
            configure(p, AppPolicy.ALL); start(p); probe(true, true, p.name + " before idle");
            idle = true; shell("dumpsys battery unplug"); shell("input keyevent 223");
            assertTrue(shell("cmd deviceidle force-idle").toLowerCase(java.util.Locale.ROOT).contains("idle"));
            int healthy = 0, gaps = 0;
            for (int i = 0; i < 36; i++) {
                Thread.sleep(5000);
                ConnectionProbe.Result r = ConnectionProbe.checkQwdtt(context, context.getSystemService(ConnectivityManager.class), p.id);
                if (r == ConnectionProbe.Result.HEALTHY) { healthy++; gaps = 0; }
                else { report("IDLE GAP " + p.name + " sample=" + i + " " + r); gaps++; }
                assertTrue("No three consecutive failed probes", gaps < 3);
                assertEquals("Profile stable during idle", p.id, new SecureStore(context).activeId);
                if (i % 6 == 5) report("IDLE progress " + p.name + ": " + healthy + "/" + (i+1));
            }
            report("IDLE RESULT " + p.name + " " + healthy + "/36 HTTPS probes over >=180 seconds");
            assertTrue("At least 33/36 probes pass", healthy >= 33);
            shell("cmd deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224"); idle = false;
            probe(true, true, p.name + " after idle");
        }
    }
    public void testCancelAndFailover() throws Exception {
        List<Profile> list = targets(); assertTrue(list.size() >= 2);
        for (Profile p : list) {
            configure(p, AppPolicy.ALL);
            for (int i = 0; i < 3; i++) {
                mode(SecureStore.MANUAL_ON); Thread.sleep(150 + 200*i);
                long then = SystemClock.elapsedRealtime(); mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000);
                assertTrue("Prompt cancel", SystemClock.elapsedRealtime() - then < 5000); noNative();
            }
            report("PASS " + p.name + " three cancellations without orphan process");
        }
        for (int destination = 0; destination < 2; destination++) {
            Profile good = list.get(destination), other = list.get(1-destination);
            configure(other, AppPolicy.ALL);
            SecureStore s = new SecureStore(context); s.profiles.add(good); s.save();
            start(other); probe(true, true, other.name + " before native termination");
            String pid = shell("pidof libqwdtt.so").trim();
            assertTrue("One native process to terminate", pid.matches("[0-9]+"));
            long then = SystemClock.elapsedRealtime();
            shell("run-as app.autoturn.vpn kill -TERM " + pid);
            long until = then + 120000;
            while (SystemClock.elapsedRealtime() < until) {
                SecureStore now = new SecureStore(context);
                if (good.id.equals(now.activeId) && StatusStore.ON.equals(StatusStore.state(context))) break;
                if (QwdttState.pending(context, now.activeId)) fail("CAPTCHA requested");
                Thread.sleep(300);
            }
            assertEquals("Automatic failover destination", good.id, new SecureStore(context).activeId);
            assertEquals(StatusStore.ON, StatusStore.state(context));
            assertEquals("Primary preserved", other.id, new SecureStore(context).primaryId);
            report("FAILOVER to " + good.name + " in " + (SystemClock.elapsedRealtime()-then) + " ms");
            probe(true, true, good.name + " after native termination");
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000);
            mode(SecureStore.MANUAL_ON); Thread.sleep(700);
            assertEquals("Fresh session restarts primary", other.id, new SecureStore(context).activeId);
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000); noNative();
        }
    }
    @Override protected void tearDown() throws Exception {
        try {
            if (wifiChanged) shell("svc wifi enable");
            if (idle) { shell("cmd deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224"); }
            if (changed) {
                mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 15000); noNative();
                SecureStore stop = new SecureStore(context); stop.automation = false;
                stop.manualMode = SecureStore.MANUAL_AUTO; stop.save(); refresh(); Thread.sleep(1500);
                Files.write(settings.toPath(), original);
                if (baseline.automation || SecureStore.MANUAL_ON.equals(baseline.manualMode)) refresh();
                Thread.sleep(1200);
                assertTrue("Encrypted settings restored byte-for-byte", java.util.Arrays.equals(original, Files.readAllBytes(settings.toPath())));
                report("RESTORED baseline encrypted settings byte-for-byte");
            }
        } finally { super.tearDown(); }
    }
}
