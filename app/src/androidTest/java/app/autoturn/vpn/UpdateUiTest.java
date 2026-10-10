package app.autoturn.vpn;

import android.app.AlertDialog;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Real GitHub requests, native settings button, lifecycle and scroll checks. */
public final class UpdateUiTest extends InstrumentationTestCase {
    private MainActivity activity;
    private Object field(Object object, String name) throws Exception {
        Field value = object.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(object);
    }
    private Button button(View root, String prefix) {
        if (root instanceof Button && ((Button) root).getText().toString().startsWith(prefix)) return (Button) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            Button result = button(((ViewGroup) root).getChildAt(i), prefix); if (result != null) return result;
        }
        return null;
    }
    private void ui(Runnable action) { getInstrumentation().runOnMainSync(action); }
    private void settle() throws Exception { Thread.sleep(350); getInstrumentation().waitForIdleSync(); }
    private void launch() throws Exception {
        Intent intent = new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity) getInstrumentation().startActivitySync(intent); settle();
        AlertDialog notes = (AlertDialog) field(activity, "releaseNotesDialog");
        if (notes != null) ui(() -> button(notes.getWindow().getDecorView(), "Понятно").performClick());
        ui(() -> {
            try {
                Method method = MainActivity.class.getDeclaredMethod("navigateTo", int.class); method.setAccessible(true); method.invoke(activity, 2);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
    }
    private void waitCheck() throws Exception {
        long end = System.currentTimeMillis() + 35000;
        while (AppUpdater.get(activity).state().phase == AppUpdater.Phase.CHECKING && System.currentTimeMillis() < end) Thread.sleep(100);
        settle();
        assertTrue("GitHub request must finish", AppUpdater.get(activity).state().phase != AppUpdater.Phase.CHECKING);
    }
    @Override protected void tearDown() throws Exception {
        if (activity != null && !activity.isFinishing()) ui(() -> activity.finish());
        getInstrumentation().waitForIdleSync(); super.tearDown();
    }
    public void testManualButtonChecksImmediatelyAndPreservesScroll() throws Exception {
        launch(); waitCheck();
        ScrollView scroll = (ScrollView) field(activity, "currentScroll");
        ui(() -> scroll.fullScroll(View.FOCUS_DOWN)); settle();
        int position = scroll.getScrollY(); View content = scroll.getChildAt(0);
        long old = activity.getSharedPreferences("app_updates", 0).getLong("last_check", 0);
        Button action = button(activity.getWindow().getDecorView(), "Проверить обновления"); assertNotNull(action);
        long started = android.os.SystemClock.elapsedRealtime(); ui(() -> action.performClick());
        assertTrue("Network must not block the UI", android.os.SystemClock.elapsedRealtime() - started < 1000);
        waitCheck();
        assertTrue(activity.getSharedPreferences("app_updates", 0).getLong("last_check", 0) > old);
        assertTrue("Manual result is visible", ((AlertDialog) field(field(activity, "updateUi"), "dialog")).isShowing());
        assertSame(scroll, field(activity, "currentScroll")); assertSame(content, scroll.getChildAt(0)); assertEquals(position, scroll.getScrollY());
    }
    public void testRepeatedResumeDoesNotCheckAgainWithinADay() throws Exception {
        launch(); waitCheck();
        long previous = activity.getSharedPreferences("app_updates", 0).getLong("last_check", 0);
        assertTrue(previous > 0);
        ui(() -> {
            try { Method method = MainActivity.class.getDeclaredMethod("onResume"); method.setAccessible(true); method.invoke(activity); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        settle(); assertEquals(previous, activity.getSharedPreferences("app_updates", 0).getLong("last_check", 0));
    }
    public void testManualRequestDuringAutomaticCheckShowsItsResult() throws Exception {
        launch(); waitCheck();
        ui(() -> {
            activity.getSharedPreferences("app_updates", 0).edit().putLong("last_check", 0).commit();
            AppUpdater.get(activity).check(false);
            try { ((UpdateUi) field(activity, "updateUi")).manualCheck(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        waitCheck();
        assertTrue(AppUpdater.get(activity).state().manual);
        AlertDialog dialog = (AlertDialog) field(field(activity, "updateUi"), "dialog"); assertNotNull(dialog); assertTrue(dialog.isShowing());
    }
    public void testClosingActivityDuringCheckDoesNotKeepItsListener() throws Exception {
        launch(); waitCheck();
        AppUpdater updater = AppUpdater.get(activity);
        ui(() -> { updater.check(true); activity.finish(); });
        waitCheck(); assertNull(field(updater, "listener"));
    }
    public void testVerifiedApkOpensStandardAndroidFlow() throws Exception {
        launch(); waitCheck();
        android.content.Context context = activity;
        assertTrue("Allow app updates before this device test", context.getPackageManager().canRequestPackageInstalls());
        java.io.File source = new java.io.File("/data/local/tmp/autoturn-update-candidate.apk");
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageArchiveInfo(source.getAbsolutePath(), 0);
        assertNotNull(info);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(source.toPath())) {
            byte[] bytes = new byte[65536];
            for (int count; (count = in.read(bytes)) != -1;) digest.update(bytes, 0, count);
        }
        UpdateRelease candidate = new UpdateRelease(info.getLongVersionCode(), info.versionName, "", "", AppUpdater.hex(digest.digest()), source.length());
        AppUpdater.validate(context, source, candidate);
        java.io.File verified = UpdateApkProvider.apk(context);
        verified.getParentFile().mkdirs();
        java.nio.file.Files.copy(source.toPath(), verified.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            ui(() -> {
                try {
                    Object updateUi = field(activity, "updateUi");
                    Method install = UpdateUi.class.getDeclaredMethod("launchInstaller"); install.setAccessible(true); install.invoke(updateUi);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            android.view.accessibility.AccessibilityNodeInfo root = null;
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(200);
                root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
                if (root != null && root.getPackageName() != null &&
                        (root.getPackageName().toString().contains("intentresolver") ||
                                (root.getPackageName().toString().contains("packageinstaller") &&
                                        !root.findAccessibilityNodeInfosByText("Обновить").isEmpty()))) break;
            }
            assertNotNull(root);
            assertTrue("Android owns the APK opening flow", root.getPackageName().toString().contains("packageinstaller") ||
                    root.getPackageName().toString().contains("intentresolver"));
            android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (java.io.OutputStream out = new java.io.FileOutputStream(new java.io.File(context.getCacheDir(), "update-installer-test.png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } finally { screenshot.recycle(); }
            }
            assertTrue(getInstrumentation().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            settle();
        } finally {
            getInstrumentation().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            verified.delete();
        }
    }
}
