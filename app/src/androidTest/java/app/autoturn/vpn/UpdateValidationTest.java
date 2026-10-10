package app.autoturn.vpn;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

public final class UpdateValidationTest extends InstrumentationTestCase {
    private static final String HASH = "a".repeat(64);
    private static final String URL = "https://github.com/cveloy102/autoturn-vpn/releases/download/v1.0.6/AutoTurn-1.0.6.apk";
    private JSONObject release() throws Exception {
        return new JSONObject().put("tag_name", "v1.0.6").put("draft", false).put("prerelease", false)
                .put("body", "Release notes").put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "update.json").put("browser_download_url",
                                "https://github.com/cveloy102/autoturn-vpn/releases/download/v1.0.6/update.json"))
                        .put(new JSONObject().put("name", "AutoTurn-1.0.6.apk").put("size", 1234).put("browser_download_url", URL)));
    }
    private JSONObject metadata() throws Exception {
        return new JSONObject().put("versionCode", 20).put("versionName", "1.0.6")
                .put("apkName", "AutoTurn-1.0.6.apk").put("sha256", HASH);
    }
    private void rejected(JSONObject release, JSONObject metadata) throws Exception {
        try { UpdateRelease.parse(release, metadata); fail("Unsafe release accepted"); }
        catch (junit.framework.AssertionFailedError error) { throw error; }
        catch (Exception expected) { }
    }
    public void testDailyBoundaryAndClockRollback() {
        long now = 1_700_000_000_000L;
        assertTrue(AppUpdater.isDue(0, now));
        assertFalse(AppUpdater.isDue(now, now));
        assertFalse(AppUpdater.isDue(now - AppUpdater.DAY + 1, now));
        assertTrue(AppUpdater.isDue(now - AppUpdater.DAY, now));
        assertTrue(AppUpdater.isDue(now + 1, now));
    }
    public void testVersionOrderingIsNumeric() {
        assertTrue(AppUpdater.isOlderTag("v1.0.5", "1.0.6"));
        assertTrue(AppUpdater.isOlderTag("v1.9.9", "1.10.0"));
        assertFalse(AppUpdater.isOlderTag("v1.10.0", "1.9.9"));
        assertFalse(AppUpdater.isOlderTag("v1.0.6", "1.0.6"));
        assertFalse(AppUpdater.isOlderTag("v1.0.6-beta", "1.0.6"));
        assertFalse(AppUpdater.isOlderTag("v999999999999999999999.0.0", "1.0.6"));
    }
    public void testReleaseAndPersistedMetadataRoundTrip() throws Exception {
        UpdateRelease release = UpdateRelease.parse(release(), metadata());
        assertEquals(20L, release.code); assertEquals(URL, release.url); assertEquals(HASH, release.sha256);
        UpdateRelease restored = UpdateRelease.restore(release.toJson().toString());
        assertEquals(release.code, restored.code); assertEquals(release.size, restored.size);
        assertEquals(release.notes, restored.notes);
    }
    public void testUntrustedUrlsRejected() throws Exception {
        for (String url : new String[]{URL.replace("https:", "http:"), URL.replace("github.com", "evil.example"),
                URL.replace("github.com", "github.com.evil.example"), URL.replace("github.com", "github.com@evil.example"),
                URL.replace("cveloy102", "attacker"), URL + "?x=y", URL + "#fragment",
                URL.replace("github.com", "github.com:443"), URL.replace("v1.0.6", ".."),
                URL.replace("v1.0.6", "%2e%2e")}) {
            try { UpdateRelease.checkedAssetUrl(url); fail("Accepted untrusted URL"); }
            catch (junit.framework.AssertionFailedError error) { throw error; }
            catch (Exception expected) { }
        }
    }
    public void testDraftPrereleaseMissingApkAndOversizeRejected() throws Exception {
        rejected(release().put("draft", true), metadata());
        rejected(release().put("prerelease", true), metadata());
        JSONObject missing = release(); missing.getJSONArray("assets").remove(1); rejected(missing, metadata());
        JSONObject huge = release(); huge.getJSONArray("assets").getJSONObject(1).put("size", AppUpdater.MAX_APK_BYTES + 1);
        rejected(huge, metadata());
        JSONObject zero = release(); zero.getJSONArray("assets").getJSONObject(1).put("size", 0); rejected(zero, metadata());
    }
    public void testMismatchedTagsHashesAndNamesRejected() throws Exception {
        rejected(release().put("tag_name", "v1.0.7"), metadata());
        rejected(release(), metadata().put("sha256", "bad"));
        rejected(release(), metadata().put("versionCode", 0));
        rejected(release(), metadata().put("apkName", "../settings.enc"));
        rejected(release(), metadata().put("versionName", "1.0.6-beta"));
        JSONObject foreign = release(); foreign.getJSONArray("assets").getJSONObject(1)
                .put("browser_download_url", URL.replace("cveloy102", "attacker")); rejected(foreign, metadata());
    }
    public void testLegacyReleaseWithoutMetadataDoesNotInventVersionCode() throws Exception {
        JSONObject legacy = release(); legacy.getJSONArray("assets").remove(0);
        assertNull(UpdateRelease.metadataUrl(legacy));
    }
    public void testNotesAreBounded() throws Exception {
        assertEquals(12000, UpdateRelease.parse(release().put("body", "x".repeat(13000)), metadata()).notes.length());
    }
    private UpdateRelease apkMetadata(File apk, long code, String version) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(apk.toPath())) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return new UpdateRelease(code, version, "", URL, AppUpdater.hex(digest.digest()), apk.length());
    }
    private void invalidApk(File apk, UpdateRelease release) throws Exception {
        try { AppUpdater.validate(getInstrumentation().getTargetContext(), apk, release); fail("Invalid APK accepted"); }
        catch (junit.framework.AssertionFailedError error) { throw error; }
        catch (Exception expected) { }
    }
    public void testSameVersionDowngradeAndWrongHashRejected() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        File installed = new File(context.getApplicationInfo().sourceDir);
        invalidApk(installed, apkMetadata(installed, info.getLongVersionCode(), info.versionName));
        invalidApk(installed, apkMetadata(installed, info.getLongVersionCode() - 1, info.versionName));
        invalidApk(installed, new UpdateRelease(info.getLongVersionCode() + 1, info.versionName, "", URL, HASH, installed.length()));
    }
    public void testWrongPackageAndTruncatedApkRejected() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        File other = new File(getInstrumentation().getContext().getApplicationInfo().sourceDir);
        invalidApk(other, apkMetadata(other, 999, "1.0.6"));
        File broken = new File(context.getCacheDir(), "broken-update.apk");
        try {
            Files.write(broken.toPath(), new byte[]{1, 2, 3, 4});
            invalidApk(broken, apkMetadata(broken, 999, "1.0.6"));
        } finally { broken.delete(); }
    }
    public void testProviderRejectsOtherFilesAndWriting() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        UpdateApkProvider provider = new UpdateApkProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = context.getPackageName() + ".updates";
        provider.attachInfo(context, info);
        assertEquals("application/vnd.android.package-archive", provider.getType(UpdateApkProvider.uri(context)));
        try { provider.openFile(UpdateApkProvider.uri(context), "w"); fail("Write allowed"); }
        catch (java.io.FileNotFoundException expected) { }
        try { provider.getType(android.net.Uri.parse("content://" + info.authority + "/../settings.enc")); fail("Traversal allowed"); }
        catch (IllegalArgumentException expected) { }
        android.content.pm.ProviderInfo declared = context.getPackageManager().getProviderInfo(
                new android.content.ComponentName(context, UpdateApkProvider.class), 0);
        assertFalse(declared.exported); assertTrue(declared.grantUriPermissions);
    }
    public void testCandidateApkWithOriginalSignatureAccepted() throws Exception {
        File candidate = new File("/data/local/tmp/autoturn-update-candidate.apk");
        assertTrue("Host must provide next-version candidate", candidate.isFile());
        Context context = getInstrumentation().getTargetContext();
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(candidate.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        assertNotNull(archive);
        AppUpdater.validate(context, candidate, apkMetadata(candidate, archive.getLongVersionCode(), archive.versionName));
    }
}
