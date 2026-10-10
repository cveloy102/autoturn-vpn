package app.autoturn.vpn;

import android.test.InstrumentationTestCase;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fixtures live under cache; user settings and profiles are never edited. */
public final class ModelValidationTest extends InstrumentationTestCase {
    private File fixtureDir;
    private android.content.Context fixture;

    @Override protected void setUp() throws Exception {
        super.setUp();
        fixtureDir = new File(getInstrumentation().getTargetContext().getCacheDir(), "model-validation");
        fixtureDir.mkdirs();
        fixture = new android.content.ContextWrapper(getInstrumentation().getTargetContext()) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return fixtureDir; }
        };
        for (File file : fixtureDir.listFiles()) file.delete();
    }

    @Override protected void tearDown() throws Exception {
        for (File file : fixtureDir.listFiles()) file.delete();
        fixtureDir.delete();
        super.tearDown();
    }

    private void rejects(String input) throws Exception {
        boolean rejected = false;
        try { ProfileImporter.parse("invalid", input); }
        catch (Exception expected) { rejected = true; }
        assertTrue("Malformed/unsupported profile must be rejected", rejected);
    }

    public void testMigrationPreservesIntentAndSetsTwoHourReturnOnce() throws Exception {
        java.lang.reflect.Method encode = SecureStore.class.getDeclaredMethod("encodeSettings");
        java.lang.reflect.Method write = SecureStore.class.getDeclaredMethod("writeSettings", org.json.JSONObject.class);
        encode.setAccessible(true); write.setAccessible(true);
        SecureStore seed = new SecureStore(fixture);
        seed.add(new Profile("Migration fixture", Profile.AMNEZIA, "migration-config"));
        for (String mode : new String[]{SecureStore.MANUAL_AUTO, SecureStore.MANUAL_ON, SecureStore.MANUAL_OFF}) {
            for (boolean auto : new boolean[]{false, true}) for (boolean keep : new boolean[]{false, true}) {
                SecureStore old = new SecureStore(fixture);
                org.json.JSONObject legacy = (org.json.JSONObject) encode.invoke(old);
                legacy.remove("controlSchema"); legacy.remove("autoReturnHours"); legacy.remove("manualUntil");
                legacy.put("manualMode", mode).put("automation", auto).put("keepAtHome", keep);
                write.invoke(old, legacy);
                long before = System.currentTimeMillis();
                SecureStore migrated = new SecureStore(fixture);
                String expected = !SecureStore.MANUAL_AUTO.equals(mode) ? mode :
                        !auto ? SecureStore.MANUAL_OFF : keep ? SecureStore.MANUAL_ON : SecureStore.MANUAL_AUTO;
                assertEquals(expected, migrated.manualMode);
                assertEquals(2, migrated.autoReturnHours);
                assertFalse(migrated.keepAtHome); assertTrue(migrated.automation);
                assertEquals(1, migrated.profiles.size());
                assertEquals("migration-config", migrated.profiles.get(0).config);
                if (SecureStore.MANUAL_AUTO.equals(expected)) assertEquals(0L, migrated.manualUntil);
                else assertTrue(migrated.manualUntil >= before + 7_200_000L);
                assertEquals("Reload must not restart migration timer", migrated.manualUntil, new SecureStore(fixture).manualUntil);
            }
        }
    }

    public void testReturnDeadlineDurationsCancellationAndStaleEditors() throws Exception {
        SecureStore store = new SecureStore(fixture);
        for (int hours : new int[]{0, 2, 4, 6, 24}) {
            store.setAutoReturnHours(hours);
            long before = System.currentTimeMillis();
            store.setControlMode(SecureStore.MANUAL_ON);
            if (hours == 0) assertEquals(0L, store.manualUntil);
            else {
                assertTrue(store.manualUntil >= before + hours * 3_600_000L);
                assertFalse(SecureStore.expireManualMode(fixture, store.manualUntil - 1));
                assertTrue(SecureStore.expireManualMode(fixture, store.manualUntil));
                assertEquals(SecureStore.MANUAL_AUTO, new SecureStore(fixture).manualMode);
            }
        }
        store.setAutoReturnHours(2); store.setControlMode(SecureStore.MANUAL_ON);
        SecureStore stale = new SecureStore(fixture);
        long previousDeadline = stale.manualUntil;
        Thread.sleep(10);
        store.setControlMode(SecureStore.MANUAL_OFF);
        stale.theme = SecureStore.THEME_LIGHT; stale.save();
        SecureStore latest = new SecureStore(fixture);
        assertEquals(SecureStore.MANUAL_OFF, latest.manualMode);
        assertTrue(latest.manualUntil > previousDeadline);
        assertFalse("Old delivery must not expire a newer manual command", SecureStore.expireManualMode(fixture, previousDeadline));
        latest.setAutoReturnHours(0);
        assertFalse(SecureStore.expireManualMode(fixture, Long.MAX_VALUE));
        latest.setControlMode(SecureStore.MANUAL_AUTO);
        assertEquals(0L, latest.manualUntil);
        assertFalse(SecureStore.expireManualMode(fixture, Long.MAX_VALUE));
    }

    public void testNetworkValidationDoesNotBlockVpnTraffic() {
        // Mobile data without Android validation must still reach the actual VPN probe.
        boolean cellular = ConnectionProbe.isUnderlyingInternet(false, true);
        assertEquals(ConnectionProbe.Result.HEALTHY, ConnectionProbe.classify(true, true, cellular));
        assertEquals(ConnectionProbe.Result.FAILED, ConnectionProbe.classify(false, true, cellular));
        assertEquals(ConnectionProbe.Result.VPN_MISSING, ConnectionProbe.classify(false, false, cellular));
        // An IMS-only network can be validated while ordinary mobile data is absent.
        boolean ims = ConnectionProbe.isUnderlyingInternet(false, false);
        assertEquals(ConnectionProbe.Result.NO_NETWORK, ConnectionProbe.classify(false, true, ims));
        boolean vpnOnly = ConnectionProbe.isUnderlyingInternet(true, true);
        assertEquals(ConnectionProbe.Result.NO_NETWORK, ConnectionProbe.classify(false, true, vpnOnly));
        assertEquals(ConnectionProbe.Result.NO_NETWORK, ConnectionProbe.classify(false, true, false));
        assertEquals(ConnectionProbe.Result.NO_NETWORK, ConnectionProbe.classify(false, false, false));
        // A successful VPN request takes precedence over a transient network snapshot.
        assertEquals(ConnectionProbe.Result.HEALTHY, ConnectionProbe.classify(true, true, false));
    }

    public void testImportValidAndInvalidFormats() throws Exception {
        String vless = "vless://12345678-1234-1234-1234-123456789abc@example.invalid:443?security=reality&type=tcp#Test";
        Profile parsed = ProfileImporter.parse("", " \n" + vless + "\n ");
        assertEquals(Profile.VLESS, parsed.type);
        assertEquals("Test", parsed.name);
        assertEquals(vless, parsed.config);
        for (String bad : new String[]{"", "not a profile", "vpn://unsupported", "{}", "{bad}",
                "vless://example.invalid:443?security=reality", "vless://id@example.invalid?security=reality",
                "vless://id@example.invalid:443?security=tls",
                "vless://id@example.invalid:443?security=reality&type=ws",
                "hysteria2://example.invalid:443", "hysteria2://pw@example.invalid:443?obfs=gecko",
                "[Interface]\nPrivateKey=invalid\n[Peer]\nPublicKey=invalid"}) rejects(bad);
        char[] tooBig = new char[1024*1024+1]; java.util.Arrays.fill(tooBig, 'x');
        rejects(new String(tooBig));
        Profile hy = ProfileImporter.parse("", "hy2://test-password@example.invalid:443?sni=test.invalid#HY");
        assertEquals(Profile.HYSTERIA2, hy.type);
        for (Profile item : new SecureStore(getInstrumentation().getTargetContext()).profiles) {
            if (Profile.AMNEZIA.equals(item.type)) {
                Profile awg = ProfileImporter.parse("Fixture.conf", item.config);
                assertEquals(Profile.AMNEZIA, awg.type);
                assertEquals("Fixture", awg.name);
            }
        }
    }

    public void testEncryptedStoreCrudAndLastProfileDeletion() throws Exception {
        SecureStore store = new SecureStore(fixture);
        assertEquals(SecureStore.THEME_DARK, store.theme);
        Profile first = new Profile("First", Profile.VLESS, "synthetic-first");
        Profile second = new Profile("Second", Profile.VLESS, "synthetic-second");
        store.add(first); store.add(second);
        assertEquals(first.id, store.activeId);
        store.rename(first.id, "Renamed");
        SecureStore reread = new SecureStore(fixture);
        assertEquals("Renamed", reread.profiles.get(0).name);
        assertEquals(first.config, reread.profiles.get(0).config);
        String raw = new String(Files.readAllBytes(new File(fixtureDir, "settings.enc").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("Profile plaintext must not be on disk", raw.contains(first.config));
        assertTrue(store.delete(first.id));
        assertEquals(second.id, store.activeId);
        store.automation = true; store.keepAtHome = true; store.manualMode = SecureStore.MANUAL_ON; store.save();
        assertTrue(store.delete(second.id));
        reread = new SecureStore(fixture);
        assertTrue(reread.profiles.isEmpty()); assertEquals("", reread.activeId);
        assertTrue(reread.automation); assertFalse(reread.keepAtHome);
        assertEquals(SecureStore.MANUAL_OFF, reread.manualMode);
        assertEquals("Deleting the last profile must not schedule a restart", 0L, reread.manualUntil);
        boolean missingRejected = false;
        try { store.delete("unknown"); } catch (IllegalArgumentException expected) { missingRejected = true; }
        assertTrue(missingRejected);
    }

    public void testIndependentRoutingListsAndInstalledFiltering() throws Exception {
        SecureStore store = new SecureStore(fixture);
        Set<String> only = new LinkedHashSet<>(), exclude = new LinkedHashSet<>();
        only.add("com.android.settings"); exclude.add("com.android.systemui");
        store.setAppPolicy(AppPolicy.ONLY, only, exclude);
        SecureStore reread = new SecureStore(fixture);
        assertEquals(only, reread.selectedPackages);
        store.setAppPolicy(AppPolicy.EXCLUDE, only, exclude);
        reread = new SecureStore(fixture);
        assertEquals(exclude, reread.selectedPackages);
        assertEquals(only, reread.onlyPackages); assertEquals(exclude, reread.excludedPackages);
        store.setAppPolicy(AppPolicy.ALL, only, exclude);
        reread = new SecureStore(fixture);
        assertTrue(reread.selectedPackages.isEmpty()); assertEquals(only, reread.onlyPackages);
        Set<String> packages = new LinkedHashSet<>(only);
        packages.add("package.not.installed"); packages.add(fixture.getPackageName());
        assertEquals(only, AppPolicy.installed(fixture, packages));
        store.setAppPolicy(AppPolicy.ONLY, only, exclude); store.connectionGuard = true;
        assertTrue(AppPolicy.routed(fixture, store).contains(fixture.getPackageName()));
    }

    public void testCorruptedEncryptedStoreIsNotOverwritten() throws Exception {
        File file = new File(fixtureDir, "settings.enc");
        byte[] corrupted = "{broken encrypted settings}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file.toPath(), corrupted);
        boolean rejected = false;
        try { new SecureStore(fixture); } catch (Exception expected) { rejected = true; }
        assertTrue(rejected);
        assertTrue(java.util.Arrays.equals(corrupted, Files.readAllBytes(file.toPath())));
    }

    public void testConcurrentStatusReadersNeverObserveBrokenWrites() throws Exception {
        StatusStore.set(fixture, StatusStore.ON, "fixture");
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        Thread[] writers = new Thread[3];
        for (int i = 0; i < writers.length; i++) {
            writers[i] = new Thread(() -> {
                for (int n = 0; n < 35; n++) {
                    StatusStore.set(fixture, n % 2 == 0 ? StatusStore.ON : StatusStore.CONNECTING, "fixture");
                    if (StatusStore.OFF.equals(StatusStore.state(fixture))) failed.set(true);
                }
            });
            writers[i].start();
        }
        for (Thread writer : writers) writer.join();
        assertFalse("Concurrent valid writes must never become a default OFF read", failed.get());
    }

    public void testBootAndUpdateRespectSavedManualModes() throws Exception {
        java.util.concurrent.atomic.AtomicInteger starts=new java.util.concurrent.atomic.AtomicInteger();
        android.content.Context receiverContext=new android.content.ContextWrapper(fixture) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public android.content.ComponentName startForegroundService(android.content.Intent intent) {
                starts.incrementAndGet(); return new android.content.ComponentName(this,AutomationService.class);
            }
        };
        BootReceiver receiver=new BootReceiver();
        for(boolean automation:new boolean[]{false,true}) {
            for(String mode:new String[]{SecureStore.MANUAL_AUTO,SecureStore.MANUAL_ON,SecureStore.MANUAL_OFF}) {
                SecureStore store=new SecureStore(receiverContext);
                store.automation=automation; store.manualMode=mode; store.save();
                for(String event:new String[]{android.content.Intent.ACTION_BOOT_COMPLETED,android.content.Intent.ACTION_MY_PACKAGE_REPLACED}) {
                    starts.set(0); receiver.onReceive(receiverContext,new android.content.Intent(event));
                    assertEquals("Boot/update saved-mode decision",automation || SecureStore.MANUAL_ON.equals(mode)?1:0,starts.get());
                }
                starts.set(0); receiver.onReceive(receiverContext,new android.content.Intent("irrelevant"));
                assertEquals(0,starts.get());
            }
        }
    }

    public void testStaleEditorCannotUndoNewManualOff() throws Exception {
        SecureStore initial=new SecureStore(fixture);
        initial.manualMode=SecureStore.MANUAL_ON; initial.save();
        SecureStore openedEditor=new SecureStore(fixture);
        SecureStore tile=new SecureStore(fixture);
        tile.manualMode=SecureStore.MANUAL_OFF; tile.save();
        Set<String> selected=new LinkedHashSet<>(); selected.add("com.android.settings");
        openedEditor.setAppPolicy(AppPolicy.EXCLUDE,new LinkedHashSet<>(),selected);
        SecureStore actual=new SecureStore(fixture);
        assertEquals("Saving an older routing screen must not undo the tile OFF command",SecureStore.MANUAL_OFF,actual.manualMode);
        assertEquals(AppPolicy.EXCLUDE,actual.appMode);
    }

    public void testStaleFailoverPreservesManualOffAndGuardEdits() throws Exception {
        SecureStore initial=new SecureStore(fixture);
        Profile first=ProfileImporter.parse("", "vless://12345678-1234-1234-1234-123456789abc@example.invalid:443?security=reality&type=tcp#First");
        Profile second=ProfileImporter.parse("", "hy2://fixture@example.invalid:443#Second");
        initial.add(first); initial.add(second);
        initial.manualMode=SecureStore.MANUAL_ON; initial.save();
        SecureStore pendingFailover=new SecureStore(fixture);
        SecureStore user=new SecureStore(fixture);
        user.manualMode=SecureStore.MANUAL_OFF; user.connectionGuard=true;
        user.theme=SecureStore.THEME_LIGHT; user.save();
        pendingFailover.activeId=second.id; pendingFailover.save();
        SecureStore actual=new SecureStore(fixture);
        assertEquals(SecureStore.MANUAL_OFF,actual.manualMode);
        assertTrue(actual.connectionGuard); assertEquals(SecureStore.THEME_LIGHT,actual.theme);
        assertEquals(second.id,actual.activeId);
    }

    public void testConcurrentProfileEditsPreserveNewProfilesAndDeletions() throws Exception {
        SecureStore initial=new SecureStore(fixture);
        Profile first=ProfileImporter.parse("", "hy2://fixture@example.invalid:443#First");
        Profile second=ProfileImporter.parse("", "hy2://fixture@example.invalid:444#Second");
        initial.add(first);
        SecureStore olderEditor=new SecureStore(fixture);
        SecureStore newerEditor=new SecureStore(fixture); newerEditor.add(second);
        olderEditor.rename(first.id,"Renamed");
        SecureStore actual=new SecureStore(fixture);
        assertEquals(2,actual.profiles.size()); assertEquals("Renamed",actual.profiles.get(0).name);
        SecureStore staleRename=new SecureStore(fixture);
        actual.delete(first.id);
        staleRename.rename(first.id,"Must not resurrect");
        actual=new SecureStore(fixture);
        assertEquals(1,actual.profiles.size()); assertEquals(second.id,actual.activeId);
    }

    public void testExplicitManualCommandOverridesNewerModeEvenIfSnapshotMatches() throws Exception {
        SecureStore initial=new SecureStore(fixture); initial.manualMode=SecureStore.MANUAL_ON; initial.save();
        SecureStore olderScreen=new SecureStore(fixture);
        SecureStore tile=new SecureStore(fixture); tile.manualMode=SecureStore.MANUAL_OFF; tile.save();
        olderScreen.manualMode=SecureStore.MANUAL_ON; olderScreen.save("manualMode");
        assertEquals(SecureStore.MANUAL_ON,new SecureStore(fixture).manualMode);
    }
}
