package app.autoturn.vpn;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import org.json.JSONObject;

/** End-to-end release checks against the actual public feed, including on an unpublished RC. */
public final class PublicationUpdateTest extends InstrumentationTestCase {
    private Context context;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
    }

    private UpdateRelease latest() throws Exception {
        JSONObject release = AppUpdater.readJson(UpdateRelease.API, 2 * 1024 * 1024);
        assertNotNull("Public release", release);
        JSONObject metadata = AppUpdater.readJson(UpdateRelease.metadataUrl(release), 64 * 1024);
        assertNotNull("Public update.json", metadata);
        return UpdateRelease.parse(release, metadata);
    }

    private void awaitCheck(AppUpdater updater) {
        long end = SystemClock.elapsedRealtime() + 45_000;
        while (updater.state().phase == AppUpdater.Phase.CHECKING && SystemClock.elapsedRealtime() < end)
            SystemClock.sleep(100);
        assertFalse("Public release check timed out", updater.state().phase == AppUpdater.Phase.CHECKING);
    }

    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void click(View root, String label) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT);
        assertFalse(label, matches.isEmpty());
        getInstrumentation().runOnMainSync(matches.get(0)::performClick);
    }

    public void testActualCheckButtonShowsPublicResult() throws Exception {
        MainActivity main = (MainActivity) getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            getInstrumentation().waitForIdleSync();
            AlertDialog notes = (AlertDialog) field(main, "releaseNotesDialog");
            if (notes != null) click(notes.getWindow().getDecorView(), "Понятно");
            Method navigate = MainActivity.class.getDeclaredMethod("navigateTo", int.class);
            navigate.setAccessible(true);
            getInstrumentation().runOnMainSync(() -> {
                try { navigate.invoke(main, 2); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            click(main.getWindow().getDecorView(), "Проверить обновления");
            AppUpdater updater = AppUpdater.get(context);
            awaitCheck(updater);
            getInstrumentation().waitForIdleSync();
            AlertDialog dialog = (AlertDialog) field(field(main, "updateUi"), "dialog");
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            if (updater.state().phase == AppUpdater.Phase.AVAILABLE) {
                assertTrue(updater.availableRelease().code > BuildConfig.VERSION_CODE);
                assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            } else {
                assertEquals(AppUpdater.Phase.CURRENT, updater.state().phase);
                TextView message = dialog.findViewById(android.R.id.message);
                assertNotNull(message);
                assertEquals(updater.state().message, message.getText().toString());
            }
        } finally { getInstrumentation().runOnMainSync(main::finish); }
    }

    public void testPublicReleaseMetadata() throws Exception {
        UpdateRelease release = latest();
        assertTrue(release.code > 0);
        assertTrue(release.size > 0);
    }

    public void testInstalledVersionComparedWithPublicRelease() throws Exception {
        long installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        assertEquals(BuildConfig.VERSION_CODE, installed);
        UpdateRelease release = latest();
        AppUpdater updater = AppUpdater.get(context);
        updater.check(true);
        awaitCheck(updater);
        assertEquals(release.code > installed ? AppUpdater.Phase.AVAILABLE : AppUpdater.Phase.CURRENT, updater.state().phase);
    }

    public void testPublishedDownloadAndSignature() throws Exception {
        UpdateRelease release = latest();
        File settings = new File(context.getFilesDir(), "settings.enc");
        byte[] before = Files.readAllBytes(settings.toPath());
        if (release.code > BuildConfig.VERSION_CODE) {
            AppUpdater updater = AppUpdater.get(context);
            updater.check(true);
            awaitCheck(updater);
            assertEquals(AppUpdater.Phase.AVAILABLE, updater.state().phase);
            updater.download(release);
            long end = SystemClock.elapsedRealtime() + 240_000;
            while (updater.state().phase != AppUpdater.Phase.READY && SystemClock.elapsedRealtime() < end) {
                assertFalse(updater.state().message, updater.state().phase == AppUpdater.Phase.ERROR);
                SystemClock.sleep(200);
            }
            assertEquals(AppUpdater.Phase.READY, updater.state().phase);
            AppUpdater.validate(context, UpdateApkProvider.apk(context), release);
        } else {
            verifyPublishedArchiveWithoutInstalling(release);
        }
        assertTrue("Reading a public release preserves VPN settings",
                Arrays.equals(before, Files.readAllBytes(settings.toPath())));
    }

    private void verifyPublishedArchiveWithoutInstalling(UpdateRelease release) throws Exception {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        String filename = "public-" + java.util.UUID.randomUUID() + ".apk";
        File apk = new File(context.getExternalFilesDir("qa-updates"), filename);
        long id = manager.enqueue(new DownloadManager.Request(Uri.parse(release.url))
                .setDestinationInExternalFilesDir(context, "qa-updates", filename));
        try {
            long end = SystemClock.elapsedRealtime() + 240_000;
            int status = DownloadManager.STATUS_PENDING;
            while (status != DownloadManager.STATUS_SUCCESSFUL && SystemClock.elapsedRealtime() < end) {
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    assertTrue("DownloadManager still tracks the APK", cursor.moveToFirst());
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    assertFalse("Public APK download failed", status == DownloadManager.STATUS_FAILED);
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL) SystemClock.sleep(250);
            }
            assertEquals(DownloadManager.STATUS_SUCCESSFUL, status);
            assertEquals(release.size, apk.length());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(apk.toPath())) {
                byte[] bytes = new byte[65536];
                for (int n; (n = in.read(bytes)) != -1;) digest.update(bytes, 0, n);
            }
            assertEquals(release.sha256, AppUpdater.hex(digest.digest()));
            PackageManager pm = context.getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            assertNotNull(archive);
            assertEquals(installed.packageName, archive.packageName);
            assertEquals(release.code, archive.getLongVersionCode());
            assertEquals(release.version, archive.versionName);
            assertEquals(new HashSet<>(Arrays.asList(installed.signingInfo.getApkContentsSigners())),
                    new HashSet<>(Arrays.asList(archive.signingInfo.getApkContentsSigners())));
            try {
                AppUpdater.validate(context, apk, release);
                fail("A current or older public build must not be offered as an upgrade");
            } catch (IOException expected) { /* APK identity was independently checked above. */ }
        } finally {
            manager.remove(id);
            Files.deleteIfExists(apk.toPath());
        }
    }
}
