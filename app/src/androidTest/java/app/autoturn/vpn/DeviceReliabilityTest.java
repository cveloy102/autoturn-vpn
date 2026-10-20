package app.autoturn.vpn;

import android.test.InstrumentationTestCase;

/** Live-device scenarios are explicitly selected by the host runner. Never prints credentials. */
public final class DeviceReliabilityTest extends InstrumentationTestCase {
    private android.content.Context context;
    private byte[] original;
    private SecureStore baseline;
    private java.io.File settings;
    private boolean live;

    private String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd = getInstrumentation().getUiAutomation().executeShellCommand(command);
             android.os.ParcelFileDescriptor.AutoCloseInputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) bytes.write(buffer, 0, n);
            return bytes.toString("UTF-8");
        }
    }

    private void beginLive() throws Exception {
        context = getInstrumentation().getTargetContext();
        baseline = new SecureStore(context);
        settings = new java.io.File(context.getFilesDir(), "settings.enc");
        original = java.nio.file.Files.readAllBytes(settings.toPath());
        java.nio.file.Files.write(new java.io.File(context.getFilesDir(), "reliability-baseline.enc").toPath(), original);
        assertNull("VPN permission required before live checks", android.net.VpnService.prepare(context));
        live = true;
        command(SecureStore.MANUAL_OFF);
        waitState(StatusStore.OFF, 15000);
    }

    private void refresh() {
        context.startForegroundService(new android.content.Intent(context, AutomationService.class)
                .setAction(AutomationService.ACTION_REFRESH));
    }

    private void command(String mode) throws Exception {
        SecureStore store = new SecureStore(context);
        store.setControlMode(mode);
        refresh();
    }

    private long waitState(String wanted, long timeout) throws Exception {
        long start = android.os.SystemClock.elapsedRealtime();
        while (android.os.SystemClock.elapsedRealtime() - start < timeout) {
            if (wanted.equals(StatusStore.state(context))) return android.os.SystemClock.elapsedRealtime() - start;
            Thread.sleep(100);
        }
        SecureStore current = new SecureStore(context);
        fail("Expected state " + wanted + "; actual state " + StatusStore.state(context) +
                "; saved manual=" + current.manualMode + "; active type=" +
                (current.activeProfile() == null ? "none" : current.activeProfile().type));
        return -1;
    }

    private Profile findProfile(String type) throws Exception {
        for (Profile profile : baseline.profiles) if (type.equals(profile.type)) return profile;
        fail("Required profile type missing: " + type);
        return null;
    }

    private void verifyTraffic() throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        ConnectionProbe.Result result;
        do {
            result = ConnectionProbe.check(context, context.getSystemService(android.net.ConnectivityManager.class));
            if (result != ConnectionProbe.Result.NO_NETWORK) break;
            Thread.sleep(250);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        assertEquals("Verified HTTPS after underlying network settles", ConnectionProbe.Result.HEALTHY, result);
    }

    private void configure(Profile profile, boolean guard, String routing) throws Exception {
        SecureStore store = new SecureStore(context);
        store.activeId = profile.id;
        store.primaryId = profile.id;
        store.connectionGuard = guard;
        store.manualMode = SecureStore.MANUAL_OFF;
        store.appMode = routing;
        store.selectedPackages.clear();
        store.onlyPackages.clear();
        store.excludedPackages.clear();
        if (!AppPolicy.ALL.equals(routing)) store.selectedPackages.add("app.autoturn.vpn.test");
        if (AppPolicy.ONLY.equals(routing)) store.onlyPackages.addAll(store.selectedPackages);
        if (AppPolicy.EXCLUDE.equals(routing)) store.excludedPackages.addAll(store.selectedPackages);
        store.save();
        refresh();
        waitState(StatusStore.OFF, 15000);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (live) {
                command(SecureStore.MANUAL_OFF);
                waitState(StatusStore.OFF, 15000);
                SecureStore stopped = new SecureStore(context);
                stopped.automation = false; stopped.manualMode = SecureStore.MANUAL_AUTO; stopped.save(); refresh();
                Thread.sleep(1100);
                java.nio.file.Files.write(settings.toPath(), original);
                if (baseline.automation || SecureStore.MANUAL_ON.equals(baseline.manualMode)) refresh();
                report("RESTORED: original encrypted settings byte for byte");
            }
        } finally { super.tearDown(); }
    }

    public void testPrimarySurvivesFailoverAndStartsEverySession() throws Exception {
        beginLive();
        Profile good = findProfile(Profile.AMNEZIA);
        String deadConfig = good.config.replaceAll("(?im)^\\s*Endpoint\\s*=.*$", "Endpoint = 127.0.0.1:9");
        assertFalse("Fault fixture must replace the endpoint", good.config.equals(deadConfig));
        Profile primary = new Profile("QA unavailable primary", Profile.AMNEZIA, deadConfig);
        configure(good, true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(good); store.profiles.add(primary); store.save();
        store.selectPrimary(primary.id);
        for (int cycle = 1; cycle <= 2; cycle++) {
            command(SecureStore.MANUAL_ON);
            waitState(StatusStore.CONNECTING, 5000);
            assertEquals("Every new session starts with primary", primary.id, new SecureStore(context).activeId);
            waitState(StatusStore.ON, 60000);
            SecureStore connected = new SecureStore(context);
            assertEquals("Working backup carries the traffic", good.id, connected.activeId);
            assertEquals("Failover must not change the primary", primary.id, connected.primaryId);
            assertEquals("Primary remains the top card", primary.id, connected.orderedProfiles().get(0).id);
            verifyTraffic();
            Thread.sleep(5500);
            assertEquals("Health ticks retain a working backup", good.id, new SecureStore(context).activeId);
            report("PASS primary cycle " + cycle + ": primary first, verified backup, primary and order preserved");
            command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 5000);
        }
    }

    public void testAmneziaRepeatedRealTraffic() throws Exception {
        beginLive();
        Profile profile = findProfile(Profile.AMNEZIA);
        configure(profile, true, AppPolicy.ALL);
        SecureStore awgOnly = new SecureStore(context);
        awgOnly.profiles.clear(); awgOnly.profiles.add(profile); awgOnly.save();
        for (int cycle = 1; cycle <= 3; cycle++) {
            command(SecureStore.MANUAL_ON);
            long startMs = waitState(StatusStore.ON, 45000);
            assertEquals("Verify AWG itself rather than a fallback protocol", profile.id, new SecureStore(context).activeId);
            assertEquals("Guard must verify actual traffic", ConnectionProbe.Result.HEALTHY,
                    ConnectionProbe.check(context, context.getSystemService(android.net.ConnectivityManager.class)));
            report("PASS AWG cycle " + cycle + ": verified HTTPS over VPN; start=" + startMs + "ms");
            Thread.sleep(5200);
            command(SecureStore.MANUAL_OFF);
            long stopMs = waitState(StatusStore.OFF, 15000);
            Thread.sleep(700);
            assertEquals("Manual OFF stays OFF", StatusStore.OFF, StatusStore.state(context));
            report("PASS AWG cycle " + cycle + ": stop=" + stopMs + "ms; manual OFF retained");
            assertTrue("OFF must respond promptly", stopMs < 3500);
        }
    }

    private void probeOtherApp(boolean expectedVpn) throws Exception {
        shell("run-as app.autoturn.vpn.test rm -f files/routing-probe.json");
        shell("am start -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity");
        long end = android.os.SystemClock.elapsedRealtime() + 20000;
        String data = "";
        while (android.os.SystemClock.elapsedRealtime() < end) {
            data = shell("run-as app.autoturn.vpn.test cat files/routing-probe.json").trim();
            if (data.startsWith("{")) break;
            Thread.sleep(200);
        }
        assertTrue("Companion traffic probe returned", data.startsWith("{"));
        org.json.JSONObject result = new org.json.JSONObject(data);
        assertEquals("Actual network of separate app UID", expectedVpn, result.optBoolean("vpn"));
        assertTrue("Real app HTTPS succeeds; error=" + result.optString("error"),
                result.optInt("http") >= 200 && result.optInt("http") < 400);
    }

    public void testLiveRoutingModes() throws Exception {
        beginLive();
        for (int cycle = 1; cycle <= 2; cycle++) {
            for (String mode : new String[]{AppPolicy.ALL, AppPolicy.ONLY, AppPolicy.EXCLUDE}) {
                configure(findProfile(Profile.AMNEZIA), true, mode);
                command(SecureStore.MANUAL_ON);
                waitState(StatusStore.ON, 45000);
                probeOtherApp(!AppPolicy.EXCLUDE.equals(mode));
                assertEquals(ConnectionProbe.Result.HEALTHY,
                        ConnectionProbe.check(context, context.getSystemService(android.net.ConnectivityManager.class)));
                report("PASS routing cycle " + cycle + ": " + mode + "; separate app UID + real HTTPS");
                command(SecureStore.MANUAL_OFF);
                waitState(StatusStore.OFF, 15000);
            }
        }
    }

    public void testDeadVlessFailoverRepeated() throws Exception {
        beginLive();
        Profile bad = findProfile(Profile.VLESS), good = findProfile(Profile.AMNEZIA);
        for (int cycle = 1; cycle <= 3; cycle++) {
            configure(bad, true, AppPolicy.ALL);
            SecureStore store = new SecureStore(context);
            store.profiles.clear(); store.profiles.add(bad); store.profiles.add(good); store.save();
            command(SecureStore.MANUAL_ON);
            long start = android.os.SystemClock.elapsedRealtime();
            boolean sawFalseOn = false;
            while (android.os.SystemClock.elapsedRealtime() - start < 60000) {
                SecureStore current = new SecureStore(context);
                if (StatusStore.ON.equals(StatusStore.state(context))) {
                    if (bad.id.equals(current.activeId)) sawFalseOn = true;
                    if (good.id.equals(current.activeId)) break;
                }
                Thread.sleep(100);
            }
            assertFalse("Dead VLESS must never be reported as verified ON", sawFalseOn);
            assertEquals("Guard chooses working AWG", good.id, new SecureStore(context).activeId);
            assertEquals("Failover preserves the user primary", bad.id, new SecureStore(context).primaryId);
            waitState(StatusStore.ON, 10000);
            assertEquals(ConnectionProbe.Result.HEALTHY,
                    ConnectionProbe.check(context, context.getSystemService(android.net.ConnectivityManager.class)));
            report("PASS failover cycle " + cycle + ": dead VLESS -> verified AWG in " +
                    (android.os.SystemClock.elapsedRealtime() - start) + "ms");
            command(SecureStore.MANUAL_OFF);
            waitState(StatusStore.OFF, 15000);
        }
    }

    public void testOffInterruptsDeadConnectionProbe() throws Exception {
        beginLive();
        // Self-contained fixture: users can remove their intentionally broken VLESS profile.
        Profile bad = ProfileImporter.parse("Offline fixture",
                "hy2://test-password@192.0.2.1:443?sni=test.invalid");
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(bad); store.activeId = bad.id; store.save();
        configure(bad, true, AppPolicy.ALL);
        command(SecureStore.MANUAL_ON);
        waitState(StatusStore.CONNECTING, 5000);
        Thread.sleep(1700);
        assertEquals("Unavailable fixture is still being checked", StatusStore.CONNECTING, StatusStore.state(context));
        command(SecureStore.MANUAL_OFF);
        long elapsed = waitState(StatusStore.OFF, 15000);
        report("OFF during dead probe: " + elapsed + "ms");
        assertTrue("Stop command must not wait for network probe", elapsed < 2500);
        Thread.sleep(6000);
        assertEquals("Late probe must not restart VPN", StatusStore.OFF, StatusStore.state(context));
    }

    public void testAllFailedPauseCanBeCancelled() throws Exception {
        beginLive();
        Profile bad = findProfile(Profile.VLESS);
        configure(bad, true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(bad); store.save();
        command(SecureStore.MANUAL_ON);
        waitState(StatusStore.ERROR, 30000);
        assertTrue("Exhausted profiles enter bounded retry pause", StatusStore.detail(context).contains("15"));
        command(SecureStore.MANUAL_OFF);
        waitState(StatusStore.OFF, 5000);
        Thread.sleep(17000);
        assertEquals("Cancel during retry pause stays off", StatusStore.OFF, StatusStore.state(context));
        report("PASS all profiles failed: bounded retry + manual cancellation survives retry timer");
    }

    private String wifiName() throws Exception {
        android.net.wifi.WifiInfo info = context.getSystemService(android.net.wifi.WifiManager.class).getConnectionInfo();
        String name = info == null ? null : info.getSSID();
        if (name != null && !android.net.wifi.WifiManager.UNKNOWN_SSID.equals(name))
            return name.replace("\"", "");
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("SSID: \\\"([^\\\"]+)\\\"")
                .matcher(shell("cmd wifi status"));
        assertTrue("Connected Wi-Fi required for home/away checks", match.find());
        return match.group(1);
    }

    public void testHomeAwayAndOverrides() throws Exception {
        beginLive();
        boolean wifi = shell("cmd wifi status").contains("Wifi is enabled");
        try {
        shell("svc wifi enable");
        long wifiDeadline = android.os.SystemClock.elapsedRealtime() + 35000;
        while (android.os.SystemClock.elapsedRealtime() < wifiDeadline &&
                !shell("cmd wifi status").contains("SSID:")) Thread.sleep(500);
        String home = wifiName();
        configure(findProfile(Profile.AMNEZIA), true, AppPolicy.ALL);
        Thread.sleep(1800);
        for (int cycle = 1; cycle <= 2; cycle++) {
            SecureStore store = new SecureStore(context);
            store.automation = true; store.manualMode = SecureStore.MANUAL_AUTO;
            store.homeSsid = home; store.keepAtHome = false; store.save(); refresh();
            Thread.sleep(1200); waitState(StatusStore.OFF, 15000);
            store = new SecureStore(context); store.setControlMode(SecureStore.MANUAL_ON); refresh();
            waitState(StatusStore.ON, 45000);
            command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 5000);
            store = new SecureStore(context); store.homeSsid = "autoturn-test-away";
            store.keepAtHome = false; store.save(); refresh();
            Thread.sleep(1500);
            assertEquals("Away cannot override manual OFF", StatusStore.OFF, StatusStore.state(context));
            command(SecureStore.MANUAL_AUTO); waitState(StatusStore.ON, 45000);
            store = new SecureStore(context); store.homeSsid = home; store.save(); refresh();
            waitState(StatusStore.OFF, 15000);
            command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000);
            store = new SecureStore(context);
            store.manualUntil = System.currentTimeMillis() + 2500; store.save("manualUntil"); refresh();
            Thread.sleep(2800);
            waitState(StatusStore.OFF, 15000);
            assertEquals("Timed ON returns to AUTO and stops VPN at home", SecureStore.MANUAL_AUTO, new SecureStore(context).manualMode);
            report("PASS home/away cycle " + cycle + ": AUTO at home/away, explicit ON/OFF precedence");
        }
        } finally { shell("svc wifi " + (wifi ? "enable" : "disable")); }
    }

    public void testTimedManualReturnAndCancellation() throws Exception {
        beginLive();
        configure(findProfile(Profile.AMNEZIA), true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.homeSsid = "autoturn-test-away"; store.save();
        for (int cycle = 1; cycle <= 2; cycle++) {
            store = new SecureStore(context); store.setControlMode(SecureStore.MANUAL_OFF);
            store.manualUntil = System.currentTimeMillis() + 2500; store.save("manualUntil"); refresh();
            long savedDeadline = store.manualUntil;
            new BootReceiver().onReceive(context, new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));
            assertEquals("Boot handling must preserve the pending deadline", savedDeadline, new SecureStore(context).manualUntil);
            waitState(StatusStore.OFF, 5000);
            Thread.sleep(2800); waitState(StatusStore.ON, 45000); verifyTraffic();
            assertEquals(SecureStore.MANUAL_AUTO, new SecureStore(context).manualMode);
            report("PASS timed OFF -> AUTO away: real AWG traffic, cycle " + cycle);
            // Simulate a delivery from the previous timer after a newer command.
            store = new SecureStore(context); store.setControlMode(SecureStore.MANUAL_ON);
            store.manualUntil = System.currentTimeMillis() + 2000; store.save("manualUntil"); refresh();
            store = new SecureStore(context); store.setAutoReturnHours(0); store.setControlMode(SecureStore.MANUAL_OFF); refresh();
            waitState(StatusStore.OFF, 5000); Thread.sleep(2400);
            new AutoReturnReceiver().onReceive(context, new android.content.Intent(context, AutoReturnReceiver.class));
            Thread.sleep(700);
            assertEquals(SecureStore.MANUAL_OFF, new SecureStore(context).manualMode);
            assertEquals(StatusStore.OFF, StatusStore.state(context));
            report("PASS old timer delivery cannot override OFF with return disabled, cycle " + cycle);
        }
    }

    public void testTimedReturnWithScreenOffAndDoze() throws Exception {
        beginLive();
        configure(findProfile(Profile.AMNEZIA), true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.homeSsid = "autoturn-test-away"; store.save();
        store.setControlMode(SecureStore.MANUAL_OFF);
        store.manualUntil = System.currentTimeMillis() + 8000; store.save("manualUntil"); refresh();
        waitState(StatusStore.OFF, 5000);
        assertTrue("This device permits precise alarms through its battery exemption",
                context.getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms());
        try {
            shell("dumpsys battery unplug"); shell("input keyevent KEYCODE_SLEEP");
            shell("dumpsys deviceidle force-idle");
            assertEquals("IDLE", shell("dumpsys deviceidle get deep").trim());
            Thread.sleep(11000);
            assertFalse("Timer does not require screen wake", context.getSystemService(android.os.PowerManager.class).isInteractive());
            assertEquals("Return happens while screen is off", SecureStore.MANUAL_AUTO, new SecureStore(context).manualMode);
            waitState(StatusStore.ON, 45000); verifyTraffic();
            report("PASS persistent alarm in deep Doze with screen off: OFF -> AUTO, actual AWG HTTPS");
        } finally {
            shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent KEYCODE_WAKEUP");
        }
    }

    private int tileState() throws Exception {
        String dump = shell("dumpsys activity service com.android.systemui/.SystemUIService");
        int start = dump.indexOf("spec=custom(app.autoturn.vpn/.VpnTileService)");
        int end = start < 0 ? -1 : dump.indexOf("forceExpandIcon=", start);
        if (end > start) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(",state=(\\d+)").matcher(dump.substring(start, end));
            if (match.find()) return Integer.parseInt(match.group(1));
        }
        return -1;
    }

    private void waitTile(int wanted) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 7000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (tileState() == wanted) return;
            Thread.sleep(150);
        }
        assertEquals("Actual SystemUI tile state", wanted, tileState());
    }

    private void waitTileConnection(String connection) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 7000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            String dump = shell("dumpsys activity service com.android.systemui/.SystemUIService");
            // SystemUI dumps a multiline label across two lines.
            int start = dump.indexOf("spec=custom(app.autoturn.vpn/.VpnTileService)");
            if (start >= 0) {
                int end = dump.indexOf("expandedAccessibilityClassName", start);
                if (end > start && java.util.regex.Pattern.compile("label=AutoTurn\\s+" +
                        java.util.regex.Pattern.quote(connection) + ",").matcher(dump.substring(start, end)).find()) return;
            }
            Thread.sleep(150);
        }
        fail("Actual SystemUI tile must show connection " + connection);
    }

    public void testTileConnectionLabelFollowsProfiles() throws Exception {
        beginLive();
        try {
            shell("cmd statusbar expand-settings");
            for (String type : new String[]{Profile.AMNEZIA, Profile.HYSTERIA2, Profile.VLESS}) {
                Profile profile = findProfile(type);
                configure(profile, true, AppPolicy.ALL);
                String label = Profile.AMNEZIA.equals(type) ? "AmneziaWG" : Profile.HYSTERIA2.equals(type) ? "Hysteria 2" : "VLESS";
                waitTile(1); waitTileConnection(label);
                if (!Profile.VLESS.equals(type)) {
                    command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000);
                    assertEquals(profile.id, new SecureStore(context).activeId);
                    verifyTraffic(); waitTile(2); waitTileConnection(label);
                    shell("cmd statusbar collapse"); shell("cmd statusbar expand-settings"); waitTileConnection(label);
                    command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 5000); waitTile(1); waitTileConnection(label);
                }
                report("PASS real tile connection label: " + label + "; selected profile, ON/OFF and reopen where working");
            }
        } finally { shell("cmd statusbar collapse"); }
    }

    public void testRealTileAndRapidCommands() throws Exception {
        beginLive();
        configure(findProfile(Profile.AMNEZIA), true, AppPolicy.ALL);
        try {
            shell("cmd statusbar expand-settings"); Thread.sleep(800);
            for (int cycle = 1; cycle <= 3; cycle++) {
                shell("cmd statusbar click-tile app.autoturn.vpn/.VpnTileService");
                waitState(StatusStore.ON, 45000); waitTile(2);
                shell("cmd statusbar collapse"); shell("cmd statusbar expand-settings"); waitTile(2);
                shell("cmd statusbar click-tile app.autoturn.vpn/.VpnTileService");
                waitState(StatusStore.OFF, 5000); waitTile(1);
                report("PASS real tile cycle " + cycle + ": ON/OFF, SystemUI highlight, reopen");
            }
            for (int i = 0; i < 20; i++) {
                shell("cmd statusbar click-tile app.autoturn.vpn/.VpnTileService");
                Thread.sleep(70);
            }
            waitState(StatusStore.OFF, 7000);
            Thread.sleep(2000);
            assertEquals("Even rapid tile clicks retain last OFF command", SecureStore.MANUAL_OFF,
                    new SecureStore(context).manualMode);
            assertEquals(StatusStore.OFF, StatusStore.state(context));
            waitTile(1);
            report("PASS 20 rapid real tile clicks: last command retained, no delayed resurrection");
        } finally { shell("cmd statusbar collapse"); }
    }

    public void testNetworkLossAndRecoveryRepeated() throws Exception {
        beginLive();
        boolean wifi = shell("cmd wifi status").contains("Wifi is enabled");
        boolean data = "1".equals(shell("settings get global mobile_data").trim());
        configure(findProfile(Profile.AMNEZIA), true, AppPolicy.ALL);
        command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000);
        try {
            for (int cycle = 1; cycle <= 3; cycle++) {
                String activeId = new SecureStore(context).activeId;
                shell("svc data disable"); shell("svc wifi disable");
                long end = android.os.SystemClock.elapsedRealtime() + 20000;
                while (android.os.SystemClock.elapsedRealtime() < end &&
                        !StatusStore.detail(context).contains("Нет сети")) Thread.sleep(150);
                assertTrue("Missing underlying network must be reported", StatusStore.detail(context).contains("Нет сети"));
                assertEquals("Offline must not burn through profiles", activeId, new SecureStore(context).activeId);
                if (data) shell("svc data enable");
                if (wifi) shell("svc wifi enable");
                waitState(StatusStore.ON, 60000);
                assertEquals(ConnectionProbe.Result.HEALTHY,
                        ConnectionProbe.check(context, context.getSystemService(android.net.ConnectivityManager.class)));
                report("PASS network-loss cycle " + cycle + ": offline wait, profile preserved, verified recovery");
            }
        } finally {
            shell("svc data " + (data ? "enable" : "disable"));
            shell("svc wifi " + (wifi ? "enable" : "disable"));
        }
    }

    public void testPermissionRevokedAndRestored() throws Exception {
        beginLive();
        configure(findProfile(Profile.AMNEZIA), true, AppPolicy.ALL);
        command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000);
        try {
            shell("cmd appops set app.autoturn.vpn ACTIVATE_VPN deny");
            refresh(); waitState(StatusStore.ERROR, 12000);
            assertTrue("Permission-loss explanation is shown", StatusStore.detail(context).contains("Разрешение VPN"));
            assertNotNull(android.net.VpnService.prepare(context));
            report("PASS revoked permission: tunnel stopped and explicit error shown");
        } finally { shell("cmd appops set app.autoturn.vpn ACTIVATE_VPN allow"); }
        assertNull(android.net.VpnService.prepare(context));
        refresh(); waitState(StatusStore.ON, 45000);
        report("PASS restored permission: verified reconnection");
    }

    public void testHysteriaIpv4AndSustainedTraffic() throws Exception {
        beginLive();
        Profile profile = findProfile(Profile.HYSTERIA2);
        configure(profile, true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(profile); store.save();
        command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000);
        android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network vpn = null;
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) vpn = n;
        }
        assertNotNull("VPN available", vpn);
        for (android.net.LinkAddress address : cm.getLinkProperties(vpn).getLinkAddresses())
            assertFalse("IPv4-only VPN must not advertise IPv6", address.getAddress() instanceof java.net.Inet6Address);
        try (java.net.Socket socket = vpn.getSocketFactory().createSocket()) {
            try {
                socket.connect(new java.net.InetSocketAddress("2606:4700:4700::1111", 443), 2500);
                fail("IPv6 must not bypass VPN over cellular");
            } catch (java.io.IOException expected) { }
        }
        report("PASS IPv4-only VPN: no IPv6 address; literal IPv6 cannot bypass VPN");
        for (int cycle = 1; cycle <= 20; cycle++) {
            verifyTraffic();
            java.net.HttpURLConnection request = (java.net.HttpURLConnection) vpn.openConnection(
                    new java.net.URL("https://www.google.com/generate_204"));
            try {
                request.setConnectTimeout(6000); request.setReadTimeout(6000);
                request.setUseCaches(false);
                assertEquals("Google HTTPS works through Hysteria", 204, request.getResponseCode());
            } finally { request.disconnect(); }
            assertEquals("No profile switch", profile.id, new SecureStore(context).activeId);
            assertEquals("VPN remains healthy", StatusStore.ON, StatusStore.state(context));
            report("PASS sustained Hysteria traffic " + cycle + "/20");
            Thread.sleep(15000);
        }
    }

    public void testHysteriaConnectedWithoutTrafficFailover() throws Exception {
        beginLive();
        Profile primary = findProfile(Profile.HYSTERIA2), backup = findProfile(Profile.AMNEZIA);
        configure(primary, true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(primary); store.profiles.add(backup); store.save();
        command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000); verifyTraffic();
        report("FAULT_READY: Hysteria connected and verified; host may block its UDP session");
        long start = android.os.SystemClock.elapsedRealtime();
        boolean sawRecheck = false;
        while (android.os.SystemClock.elapsedRealtime() - start < 65000) {
            SecureStore current = new SecureStore(context);
            if (primary.id.equals(current.activeId) && StatusStore.detail(context).contains("перепроверяем")) {
                assertTrue("Tunnel stays up while traffic fails", shell("dumpsys connectivity").contains("AutoTurn Hysteria 2"));
                sawRecheck = true;
            }
            if (backup.id.equals(current.activeId) && StatusStore.ON.equals(StatusStore.state(context))) break;
            Thread.sleep(100);
        }
        assertTrue("Traffic loss triggers confirmation despite raised tunnel", sawRecheck);
        assertEquals("Working backup selected", backup.id, new SecureStore(context).activeId);
        waitState(StatusStore.ON, 10000); verifyTraffic();
        report("PASS connected-but-no-internet Hysteria -> verified Amnezia in " +
                (android.os.SystemClock.elapsedRealtime() - start) + "ms");
    }

    public void testHysteriaRealTraffic() throws Exception {
        beginLive();
        Profile profile = findProfile(Profile.HYSTERIA2);
        configure(profile, true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(profile); store.save();
        for (int cycle = 1; cycle <= 10; cycle++) {
            command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000);
            verifyTraffic();
            command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 5000);
            report("PASS Hysteria2 cycle " + cycle + ": verified traffic, stop");
        }
    }

    public void testXrayCommandsAndProcessDeath() throws Exception {
        beginLive();
        Profile bad = findProfile(Profile.VLESS), good = findProfile(Profile.AMNEZIA);
        configure(bad, false, AppPolicy.ALL);
        // Guard disabled: ON means local engine started, deliberately not verified connectivity.
        for (int cycle = 1; cycle <= 3; cycle++) {
            command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 10000);
            command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 5000);
            Thread.sleep(800);
            assertEquals("Stopped Xray cannot publish a stale ON", StatusStore.OFF, StatusStore.state(context));
            report("PASS Xray local engine cycle " + cycle + ": START/STOP; stale status suppressed");
        }
        configure(bad, true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(bad); store.profiles.add(good); store.save();
        command(SecureStore.MANUAL_ON); waitState(StatusStore.CONNECTING, 5000);
        Thread.sleep(800);
        String pid = shell("pidof app.autoturn.vpn:xray").trim();
        assertTrue("Dedicated Xray process running", pid.matches("[0-9]+"));
        shell("run-as app.autoturn.vpn kill -9 " + pid);
        waitState(StatusStore.ON, 60000);
        assertEquals("Engine crash fails over to healthy AWG", good.id, new SecureStore(context).activeId);
        assertEquals(ConnectionProbe.Result.HEALTHY,
                ConnectionProbe.check(context, context.getSystemService(android.net.ConnectivityManager.class)));
        report("PASS killed Xray engine: verified failover to AWG");
    }

    public void testAutomationServiceRestartBurst() throws Exception {
        beginLive();
        configure(findProfile(Profile.AMNEZIA), true, AppPolicy.ALL);
        for (int i = 0; i < 20; i++) {
            SecureStore store = new SecureStore(context);
            store.automation = false; store.manualMode = SecureStore.MANUAL_AUTO; store.save(); refresh();
            Thread.sleep(50);
            store = new SecureStore(context);
            store.manualMode = SecureStore.MANUAL_ON; store.save(); refresh();
            Thread.sleep(50);
        }
        waitState(StatusStore.ON, 45000);
        Thread.sleep(2500);
        assertEquals("Last restart request survives old service stop", StatusStore.ON, StatusStore.state(context));
        command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 5000);
        report("PASS 20 automation-service stop/start bursts: last request retained, no foreground timeout");
    }

    public void testDozeMaintainsVpn() throws Exception {
        beginLive();
        Profile profile = findProfile(Profile.AMNEZIA);
        configure(profile, true, AppPolicy.ALL);
        command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000);
        try {
            shell("dumpsys battery unplug");
            shell("dumpsys deviceidle force-idle");
            assertEquals("Forced deep idle reached", "IDLE", shell("dumpsys deviceidle get deep").trim());
            Thread.sleep(12000);
            assertEquals(profile.id, new SecureStore(context).activeId);
            assertEquals(StatusStore.ON, StatusStore.state(context));
            assertEquals(ConnectionProbe.Result.HEALTHY,
                    ConnectionProbe.check(context, context.getSystemService(android.net.ConnectivityManager.class)));
            report("PASS forced deep Doze: VPN remains verified, actual HTTPS succeeds");
        } finally {
            shell("dumpsys deviceidle unforce");
            shell("dumpsys battery reset");
        }
    }

    public void testHysteriaScreenOffAndDoze() throws Exception {
        beginLive();
        Profile profile = findProfile(Profile.HYSTERIA2);
        configure(profile, true, AppPolicy.ALL);
        SecureStore store = new SecureStore(context);
        store.profiles.clear(); store.profiles.add(profile); store.save();
        command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000); verifyTraffic();
        assertTrue("Active Xray holds CPU lock", shell("dumpsys power").contains("AutoTurn:XrayTunnel"));
        try {
            shell("dumpsys battery unplug");
            shell("input keyevent KEYCODE_SLEEP");
            shell("dumpsys deviceidle force-idle");
            assertEquals("Deep idle forced", "IDLE", shell("dumpsys deviceidle get deep").trim());
            for (int cycle = 1; cycle <= 20; cycle++) {
                Thread.sleep(30000);
                assertFalse("Screen stays off without opening app",
                        context.getSystemService(android.os.PowerManager.class).isInteractive());
                verifyTraffic();
                assertEquals("Hysteria remains selected in background", profile.id, new SecureStore(context).activeId);
                assertEquals("Background connection remains healthy", StatusStore.ON, StatusStore.state(context));
                report("PASS Hysteria screen-off deep idle " + cycle + "/20");
            }
        } finally {
            shell("dumpsys deviceidle unforce");
            shell("dumpsys battery reset");
            shell("input keyevent KEYCODE_WAKEUP");
        }
        command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF, 5000);
        Thread.sleep(1000);
        // The historical event log also names the lock, so inspect only active locks.
        String power = shell("dumpsys power");
        int active = power.indexOf("Wake Locks:");
        int end = power.indexOf("Suspend Blockers:", active);
        assertTrue("Power report has active wake-lock section", active >= 0 && end > active);
        assertFalse("Stopping Xray releases its CPU lock", power.substring(active, end).contains("AutoTurn:XrayTunnel"));
        report("PASS stop releases Xray CPU lock; screen wake checked without opening app");
    }

    public void testBulkDownloadAndUpload() throws Exception {
        beginLive();
        for (String type : new String[]{Profile.AMNEZIA, Profile.HYSTERIA2}) {
            Profile profile = findProfile(type);
            configure(profile, true, AppPolicy.ALL);
            SecureStore store = new SecureStore(context);
            store.profiles.clear(); store.profiles.add(profile); store.save();
            command(SecureStore.MANUAL_ON); waitState(StatusStore.ON, 45000); verifyTraffic();
            android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
            android.net.Network vpn = manager.getActiveNetwork();
            assertTrue(manager.getNetworkCapabilities(vpn).hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN));
            for (int cycle = 1; cycle <= 3; cycle++) {
                long started = android.os.SystemClock.elapsedRealtime();
                java.net.HttpURLConnection download = (java.net.HttpURLConnection)vpn.openConnection(
                        new java.net.URL("https://speed.cloudflare.com/__down?bytes=524288"));
                download.setConnectTimeout(6000); download.setReadTimeout(10000); download.setUseCaches(false);
                int total=0;
                try (java.io.InputStream in=download.getInputStream()) {
                    byte[] buffer=new byte[16384]; int n;
                    while((n=in.read(buffer))!=-1)total+=n;
                } finally { download.disconnect(); }
                assertEquals("Full TLS payload must arrive",524288,total);
                long downloadMs=android.os.SystemClock.elapsedRealtime()-started;
                java.net.HttpURLConnection upload=(java.net.HttpURLConnection)vpn.openConnection(
                        new java.net.URL("https://speed.cloudflare.com/__up"));
                upload.setRequestMethod("POST"); upload.setDoOutput(true);
                upload.setConnectTimeout(6000); upload.setReadTimeout(10000); upload.setFixedLengthStreamingMode(262144);
                try {
                    try(java.io.OutputStream out=upload.getOutputStream()) {
                        byte[] buffer=new byte[16384]; for(int i=0;i<16;i++)out.write(buffer);
                    }
                    int code=upload.getResponseCode(); assertTrue("Upload accepted",code>=200 && code<400);
                } finally { upload.disconnect(); }
                report("PASS bulk "+type+" cycle "+cycle+": 512KiB received, 256KiB sent; download="+downloadMs+"ms");
            }
            command(SecureStore.MANUAL_OFF); waitState(StatusStore.OFF,5000);
        }
    }
    private void report(String message) {
        android.os.Bundle data = new android.os.Bundle();
        data.putString("stream", "\n" + message + "\n");
        getInstrumentation().sendStatus(0, data);
    }
    public void testInventory() throws Exception {
        SecureStore store = new SecureStore(getInstrumentation().getTargetContext());
        int awg = 0, vless = 0, hy2 = 0;
        for (Profile profile : store.profiles) {
            if (Profile.AMNEZIA.equals(profile.type)) awg++;
            if (Profile.VLESS.equals(profile.type)) vless++;
            if (Profile.HYSTERIA2.equals(profile.type)) hy2++;
        }
        report("Profile inventory: AmneziaWG=" + awg + ", VLESS=" + vless + ", Hysteria2=" + hy2);
        report("VPN permission=" + (android.net.VpnService.prepare(getInstrumentation().getTargetContext()) == null));
        report("Settings: automation=" + store.automation + ", guard=" + store.connectionGuard +
                ", manual=" + store.manualMode + ", routing=" + store.appMode);
        report("Active profile type=" + (store.activeProfile() == null ? "none" : store.activeProfile().type));
        assertFalse("At least one profile is required", store.profiles.isEmpty());
    }
}
