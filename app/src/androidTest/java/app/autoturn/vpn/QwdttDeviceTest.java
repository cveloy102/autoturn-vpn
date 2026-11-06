package app.autoturn.vpn;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;

/** Explicit live-device test. Reads the user-authorized link only from an app-private input. */
public final class QwdttDeviceTest extends InstrumentationTestCase {
    private Context context;
    private byte[] original;
    private File settings;
    private SecureStore baseline;

    private void report(String message) {
        android.os.Bundle bundle = new android.os.Bundle(); bundle.putString("stream", message + "\n");
        getInstrumentation().sendStatus(0, bundle);
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

    private void mode(String mode) throws Exception {
        new SecureStore(context).setControlMode(mode); refresh();
    }

    private void waitState(String wanted, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        boolean reportedCaptcha = false;
        while (SystemClock.elapsedRealtime() < end) {
            if (wanted.equals(StatusStore.state(context))) return;
            if (QwdttState.pending(context, new SecureStore(context).activeId) && !reportedCaptcha) {
                report("CAPTCHA_REQUIRED: open AutoTurn and complete VK challenge on the phone");
                reportedCaptcha = true;
            }
            Thread.sleep(200);
        }
        fail("Expected " + wanted + "; actual=" + StatusStore.state(context) + "; detail=" + StatusStore.detail(context));
    }

    private void configure(Profile qwdtt, String routing) throws Exception {
        mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 15000);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(qwdtt);
        store.primaryId = qwdtt.id; store.activeId = qwdtt.id;
        store.connectionGuard = true; store.appMode = routing;
        store.selectedPackages.clear(); store.onlyPackages.clear(); store.excludedPackages.clear();
        if (AppPolicy.ONLY.equals(routing)) store.onlyPackages.add("app.autoturn.vpn.test");
        if (AppPolicy.EXCLUDE.equals(routing)) store.excludedPackages.add("app.autoturn.vpn.test");
        if (AppPolicy.ONLY.equals(routing)) store.selectedPackages.addAll(store.onlyPackages);
        if (AppPolicy.EXCLUDE.equals(routing)) store.selectedPackages.addAll(store.excludedPackages);
        store.save();
    }

    private void probeApp(boolean vpn, boolean udp) throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try { probeAppOnce(vpn, udp); return; }
            catch (junit.framework.AssertionFailedError transientFailure) {
                if (attempt == 3) throw transientFailure;
                report("Rechecking independent-app traffic after network/wake settles, attempt " + (attempt + 1));
                Thread.sleep(1500);
            }
        }
    }

    private void probeAppOnce(boolean vpn, boolean udp) throws Exception {
        shell("run-as app.autoturn.vpn.test rm -f files/routing-probe.json");
        shell("am start -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez wake true --ez udp " + udp);
        long end = SystemClock.elapsedRealtime() + 25000;
        JSONObject result = null;
        while (SystemClock.elapsedRealtime() < end) {
            String text = shell("run-as app.autoturn.vpn.test cat files/routing-probe.json").trim();
            if (text.startsWith("{")) { result = new JSONObject(text); break; }
            Thread.sleep(300);
        }
        assertNotNull("Independent app probe returned", result);
        assertEquals("Independent app uses expected network", vpn, result.optBoolean("vpn"));
        assertTrue("Independent app HTTPS; error=" + result.optString("error"),
                result.optInt("http") >= 200 && result.optInt("http") < 400);
        if (udp) assertTrue("Real UDP STUN through Android TUN and qWDTT", result.optBoolean("udp"));
    }

    public void testLiveQwdttRoutingFailoverAndStop() throws Exception {
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath()); baseline = new SecureStore(context);
        Files.write(new File(context.getFilesDir(), "qwdtt-baseline.enc").toPath(), original);
        assertNull("Previously granted VPN permission", android.net.VpnService.prepare(context));
        String source = Files.readString(new File(context.getFilesDir(), "qwdtt-test-input.txt").toPath()).trim();
        Profile qwdtt = ProfileImporter.parse("qWDTT", source);
        try {
            for (String routing : new String[]{AppPolicy.ALL, AppPolicy.ONLY, AppPolicy.EXCLUDE}) {
                configure(qwdtt, routing);
                mode(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 310000);
                assertEquals(qwdtt.id, new SecureStore(context).activeId);
                assertEquals(ConnectionProbe.Result.HEALTHY, ConnectionProbe.checkQwdtt(context,
                        context.getSystemService(android.net.ConnectivityManager.class), qwdtt.id));
                probeApp(!AppPolicy.EXCLUDE.equals(routing), !AppPolicy.EXCLUDE.equals(routing));
                report("PASS qWDTT " + routing + ": native launch + verified HTTPS + independent app routing" +
                        (AppPolicy.EXCLUDE.equals(routing) ? "" : " + real UDP"));
            }
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000);
            assertTrue("Runtime session cleared on stop", QwdttState.read(context).optString("session").isEmpty());

            Profile good = null;
            for (Profile item : baseline.profiles) if (Profile.AMNEZIA.equals(item.type)) { good = item; break; }
            assertNotNull("AWG config supplies harmless unreachable fixtures", good);
            String deadConfig = good.config.replaceAll("(?im)^\\s*Endpoint\\s*=.*$", "Endpoint = 127.0.0.1:9");
            assertFalse("Unreachable endpoint substituted", deadConfig.equals(good.config));
            Profile first = new Profile("QA primary unreachable", Profile.AMNEZIA, deadConfig);
            Profile second = new Profile("QA ordinary unreachable", Profile.AMNEZIA, deadConfig);
            configure(qwdtt, AppPolicy.ALL);
            SecureStore store = new SecureStore(context);
            // qWDTT is intentionally stored before the ordinary backup to test the priority flag.
            store.profiles.clear(); store.profiles.add(first); store.profiles.add(qwdtt); store.profiles.add(second);
            store.primaryId = first.id; store.activeId = first.id; store.save();
            mode(SecureStore.MANUAL_ON);
            long end = SystemClock.elapsedRealtime() + 330000;
            boolean sawPrimary = false, sawOrdinary = false, sawLast = false;
            while (SystemClock.elapsedRealtime() < end) {
                SecureStore current = new SecureStore(context);
                if (first.id.equals(current.activeId)) sawPrimary = true;
                if (second.id.equals(current.activeId)) { assertTrue(sawPrimary); sawOrdinary = true; }
                if (qwdtt.id.equals(current.activeId)) { assertTrue(sawOrdinary); sawLast = true; }
                if (qwdtt.id.equals(current.activeId) && StatusStore.ON.equals(StatusStore.state(context))) break;
                Thread.sleep(150);
            }
            assertTrue("Primary then ordinary then last resort all visited", sawPrimary && sawOrdinary && sawLast);
            assertEquals(StatusStore.ON, StatusStore.state(context));
            assertEquals(first.id, new SecureStore(context).primaryId);
            probeApp(true, true);
            report("PASS failover: primary -> ordinary -> qWDTT last; primary preserved; HTTPS + UDP");
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000);
            mode(SecureStore.MANUAL_ON); Thread.sleep(500);
            assertEquals("New session starts with primary", first.id, new SecureStore(context).activeId);
            long stop = SystemClock.elapsedRealtime();
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000);
            assertTrue("Manual stop responds promptly", SystemClock.elapsedRealtime() - stop < 5000);
            report("PASS repeated session starts with primary; manual stop during connection");
        } finally {
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 15000);
            SecureStore stop = new SecureStore(context); stop.automation = false;
            stop.manualMode = SecureStore.MANUAL_AUTO; stop.save(); refresh(); Thread.sleep(1500);
            Files.write(settings.toPath(), original);
            if (baseline.automation || SecureStore.MANUAL_ON.equals(baseline.manualMode)) refresh();
            report("RESTORED original encrypted settings byte-for-byte");
        }
    }

    public void testAddAuthorizedLastResortToUserProfiles() throws Exception {
        context = getInstrumentation().getTargetContext();
        File input = new File(context.getFilesDir(), "qwdtt-test-input.txt");
        Profile imported = ProfileImporter.parse("qWDTT", Files.readString(input.toPath()).trim());
        SecureStore store = new SecureStore(context);
        for (Profile item : store.profiles)
            if (Profile.QWDTT.equals(item.type) && item.config.equals(imported.config)) {
                report("qWDTT profile already present; no duplicate added"); return;
            }
        String primary = store.primaryId;
        java.lang.reflect.Method encode = SecureStore.class.getDeclaredMethod("encodeSettings");
        encode.setAccessible(true);
        JSONObject before = (JSONObject) encode.invoke(store);
        java.util.List<Profile> previous = new java.util.ArrayList<>(store.profiles);
        store.add(imported);
        SecureStore saved = new SecureStore(context);
        assertEquals(primary, saved.primaryId);
        assertTrue(saved.orderedProfiles().get(store.profiles.size()-1).lastResort);
        for (Profile old : previous) {
            Profile preserved = null;
            for (Profile item : saved.profiles) if (old.id.equals(item.id)) preserved = item;
            assertNotNull("Existing profile is preserved", preserved);
            assertTrue("Existing profile fields are unchanged", old.toJson().toString().equals(preserved.toJson().toString()));
        }
        JSONObject after = (JSONObject) encode.invoke(saved);
        before.remove("profiles"); after.remove("profiles");
        assertTrue("All non-profile settings are unchanged", before.toString().equals(after.toString()));
        report("Added authorized qWDTT last-resort profile; original primary preserved");
    }

    public void testQwdttScreenOffAndCancelDuringStartup() throws Exception {
        context = getInstrumentation().getTargetContext();
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath()); baseline = new SecureStore(context);
        Profile qwdtt = ProfileImporter.parse("qWDTT", Files.readString(
                new File(context.getFilesDir(), "qwdtt-test-input.txt").toPath()).trim());
        boolean idle = false;
        try {
            configure(qwdtt, AppPolicy.ALL);
            mode(SecureStore.MANUAL_ON); Thread.sleep(150);
            long stop = SystemClock.elapsedRealtime();
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 10000);
            assertTrue("Stop during native startup is prompt", SystemClock.elapsedRealtime() - stop < 5000);
            long cleanupDeadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < cleanupDeadline &&
                    (!QwdttState.read(context).optString("session").isEmpty() ||
                            !shell("pidof libqwdtt.so").trim().isEmpty())) Thread.sleep(150);
            if (!QwdttState.read(context).optString("session").isEmpty())
                report("Cancellation diagnostic: phase=" + QwdttState.read(context).optString("phase") +
                        "; nativeAlive=" + !shell("pidof libqwdtt.so").trim().isEmpty() +
                        "; status=" + StatusStore.state(context));
            assertTrue("No native session after cancelled startup", QwdttState.read(context).optString("session").isEmpty());
            assertTrue("No orphan native process", shell("pidof libqwdtt.so").trim().isEmpty());
            report("PASS cancel native startup: prompt OFF, runtime cleared, no orphan process");
            mode(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 310000);
            shell("dumpsys battery unplug"); idle = true;
            shell("input keyevent 223");
            String forced = shell("cmd deviceidle force-idle");
            assertTrue("Device enters forced idle", forced.toLowerCase(java.util.Locale.ROOT).contains("idle"));
            int healthy = 0, consecutiveFailures = 0;
            for (int i = 0; i < 12; i++) {
                Thread.sleep(5000);
                assertEquals("qWDTT remains selected while screen is off", qwdtt.id, new SecureStore(context).activeId);
                ConnectionProbe.Result result = ConnectionProbe.checkQwdtt(context,
                        context.getSystemService(android.net.ConnectivityManager.class), qwdtt.id);
                if (result == ConnectionProbe.Result.HEALTHY) { healthy++; consecutiveFailures = 0; }
                else {
                    report("Transient probe gap during forced idle: " + result);
                    assertTrue("No prolonged outage during idle", ++consecutiveFailures < 3);
                }
            }
            assertTrue("At least 10 of 12 real-network probes pass", healthy >= 10);
            report("PASS screen off + forced Doze: " + healthy + "/12 successful HTTPS probes over at least one minute");
            shell("cmd deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224"); idle = false;
            probeApp(true, true);
            report("PASS wake after Doze: independent-app HTTPS + UDP");
        } finally {
            if (idle) { shell("cmd deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224"); }
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 15000);
            SecureStore stop = new SecureStore(context); stop.automation = false;
            stop.manualMode = SecureStore.MANUAL_AUTO; stop.save(); refresh(); Thread.sleep(1500);
            Files.write(settings.toPath(), original);
            if (baseline.automation || SecureStore.MANUAL_ON.equals(baseline.manualMode)) refresh();
            report("RESTORED original encrypted settings byte-for-byte");
        }
    }

    private void waitPhysical(int transport) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 45000;
        android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.net.Network network : manager.getAllNetworks()) {
                android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (ConnectionProbe.isUnderlyingInternet(caps) && caps.hasTransport(transport)) return;
            }
            Thread.sleep(500);
        }
        fail("Physical network did not appear, transport=" + transport);
    }

    private void waitHealthy(String id) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 120000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ConnectionProbe.checkQwdtt(context, context.getSystemService(android.net.ConnectivityManager.class), id) ==
                    ConnectionProbe.Result.HEALTHY) return;
            Thread.sleep(1000);
        }
        fail("qWDTT did not recover after handoff; state=" + StatusStore.state(context));
    }

    public void testQwdttWifiMobileHandoff() throws Exception {
        context = getInstrumentation().getTargetContext();
        if (!"1".equals(shell("settings get global wifi_on").trim()) ||
                !"1".equals(shell("settings get global mobile_data").trim())) {
            report("SKIP handoff: requires initially enabled Wi-Fi and mobile data"); return;
        }
        settings = new File(context.getFilesDir(), "settings.enc");
        original = Files.readAllBytes(settings.toPath()); baseline = new SecureStore(context);
        Profile qwdtt = ProfileImporter.parse("qWDTT", Files.readString(
                new File(context.getFilesDir(), "qwdtt-test-input.txt").toPath()).trim());
        boolean wifiChanged = false;
        try {
            configure(qwdtt, AppPolicy.ALL);
            mode(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 310000);
            shell("svc wifi disable"); wifiChanged = true;
            waitPhysical(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
            waitHealthy(qwdtt.id); probeApp(true, true);
            report("PASS Wi-Fi -> mobile: qWDTT recovered; independent app HTTPS + UDP");
            shell("svc wifi enable"); wifiChanged = false;
            waitPhysical(android.net.NetworkCapabilities.TRANSPORT_WIFI);
            waitHealthy(qwdtt.id); probeApp(true, true);
            report("PASS mobile -> Wi-Fi: qWDTT recovered; independent app HTTPS + UDP");
        } finally {
            if (wifiChanged) shell("svc wifi enable");
            mode(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 15000);
            SecureStore stop = new SecureStore(context); stop.automation = false;
            stop.manualMode = SecureStore.MANUAL_AUTO; stop.save(); refresh(); Thread.sleep(1500);
            Files.write(settings.toPath(), original);
            if (baseline.automation || SecureStore.MANUAL_ON.equals(baseline.manualMode)) refresh();
            report("RESTORED Wi-Fi state and original encrypted settings byte-for-byte");
        }
    }
}
