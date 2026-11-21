package app.autoturn.vpn;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateLogicTest {
    private static final String HASH = "a".repeat(64);

    private static JSONObject release(String tag, String assetName, String url) throws Exception {
        return new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
                .put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "update.json")
                                .put("browser_download_url", "https://github.com/" + UpdateRelease.REPOSITORY + "/releases/download/" + tag + "/update.json"))
                        .put(new JSONObject().put("name", assetName).put("size", 1000).put("browser_download_url", url)));
    }

    private static JSONObject metadata(String version) throws Exception {
        return new JSONObject().put("versionCode", 40).put("versionName", version)
                .put("apkName", "AutoTurn-" + version + ".apk").put("sha256", HASH);
    }

    private static String apkUrl(String version) {
        return "https://github.com/" + UpdateRelease.REPOSITORY + "/releases/download/v" + version + "/AutoTurn-" + version + ".apk";
    }

    @Test public void acceptsMatchingRelease() throws Exception {
        UpdateRelease parsed = UpdateRelease.parse(release("v1.8.0", "AutoTurn-1.8.0.apk", apkUrl("1.8.0")), metadata("1.8.0"));
        assertEquals(40, parsed.code);
        assertEquals("1.8.0", parsed.version);
        assertEquals(HASH, parsed.sha256);
    }

    @Test public void rejectsTamperedReleases() throws Exception {
        try { UpdateRelease.parse(release("v1.8.1", "AutoTurn-1.8.0.apk", apkUrl("1.8.0")), metadata("1.8.0")); fail(); }
        catch (java.io.IOException expected) { /* tag differs from metadata */ }
        try { UpdateRelease.parse(release("v1.8.0", "AutoTurn-1.8.0.apk", "https://evil.example/AutoTurn-1.8.0.apk"),
                metadata("1.8.0")); fail(); }
        catch (java.io.IOException expected) { /* foreign host */ }
        try { UpdateRelease.checkedAssetUrl("https://github.com/other/repo/releases/download/v1/a.apk"); fail(); }
        catch (java.io.IOException expected) { /* foreign repository */ }
        JSONObject draft = release("v1.8.0", "AutoTurn-1.8.0.apk", apkUrl("1.8.0")).put("draft", true);
        try { UpdateRelease.metadataUrl(draft); fail(); }
        catch (java.io.IOException expected) { /* drafts are never offered */ }
    }

    @Test public void tagComparison() {
        assertTrue(AppUpdater.isOlderTag("v1.5.0", "1.7.0"));
        assertFalse(AppUpdater.isOlderTag("v1.7.0", "1.7.0"));
        assertFalse(AppUpdater.isOlderTag("v1.10.0", "1.7.0"));
        assertFalse(AppUpdater.isOlderTag("latest", "1.7.0"));
    }

    @Test public void dailyCheckInterval() {
        assertTrue(AppUpdater.isDue(0, 5));
        assertTrue(AppUpdater.isDue(100, 50));
        assertFalse(AppUpdater.isDue(1000, 1000 + AppUpdater.DAY - 1));
        assertTrue(AppUpdater.isDue(1000, 1000 + AppUpdater.DAY));
    }

    @Test public void hourlyCheckOnOpen() {
        assertEquals(60 * 60 * 1000L, AppUpdater.CHECK_INTERVAL);
        assertTrue(AppUpdater.isDue(0, 5, AppUpdater.CHECK_INTERVAL));
        assertTrue(AppUpdater.isDue(100, 50, AppUpdater.CHECK_INTERVAL));
        assertFalse(AppUpdater.isDue(1000, 1000 + AppUpdater.CHECK_INTERVAL - 1, AppUpdater.CHECK_INTERVAL));
        assertTrue(AppUpdater.isDue(1000, 1000 + AppUpdater.CHECK_INTERVAL, AppUpdater.CHECK_INTERVAL));
    }
}
