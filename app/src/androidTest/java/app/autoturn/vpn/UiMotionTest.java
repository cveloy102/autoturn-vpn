package app.autoturn.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ScrollView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/** Runs in the installed app process. Never changes saved profiles or routing rules. */
public final class UiMotionTest extends InstrumentationTestCase {
    private Activity activity;

    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(object, values);
    }

    private void ui(Runnable action) { getInstrumentation().runOnMainSync(action); }

    private void settle() throws Exception {
        Thread.sleep(350);
        getInstrumentation().waitForIdleSync();
    }

    private <T extends Activity> T launch(Class<T> type) throws Exception {
        Intent intent = new Intent(getInstrumentation().getTargetContext(), type);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = getInstrumentation().startActivitySync(intent);
        settle();
        if (activity instanceof MainActivity) {
            AlertDialog notes = (AlertDialog) field(activity, "releaseNotesDialog");
            if (notes != null) ui(() -> findButton(notes.getWindow().getDecorView(), "Понятно").performClick());
        }
        settle();
        return type.cast(activity);
    }

    private void navigate(MainActivity main, int page) throws Exception {
        ui(() -> {
            try { invoke(main, "navigateTo", new Class<?>[]{int.class}, page); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
    }

    @Override protected void tearDown() throws Exception {
        if (activity != null) ui(() -> activity.finish());
        getInstrumentation().waitForIdleSync();
        super.tearDown();
    }

    public void testStatusUpdatesKeepNativeViewsAndScroll() throws Exception {
        MainActivity main = launch(MainActivity.class);
        ScrollView scroll = (ScrollView) field(main, "currentScroll");
        ui(() -> ((ViewGroup) scroll.getChildAt(0)).addView(new View(main),
                new android.widget.LinearLayout.LayoutParams(1, 500)));
        settle();
        ui(() -> scroll.scrollTo(0, 140));
        settle();
        int y = scroll.getScrollY();
        assertTrue("Home must have scrollable content", y > 0);
        View content = scroll.getChildAt(0);
        String originalState = StatusStore.state(main);
        String originalDetail = StatusStore.detail(main);
        try {
            // Repeat actual app broadcasts, then exercise all visual status transitions.
            for (int i = 0; i < 40; i++) {
                ui(() -> main.sendBroadcast(new Intent(StatusStore.ACTION_CHANGED).setPackage(main.getPackageName())));
                getInstrumentation().waitForIdleSync();
            }
            for (String state : new String[]{StatusStore.CONNECTING, StatusStore.ON, StatusStore.ERROR, StatusStore.OFF}) {
                ui(() -> StatusStore.set(main, state, "UI test"));
                settle();
                assertSame("Status must not replace ScrollView", scroll, field(main, "currentScroll"));
                assertSame("Status must not replace the content", content, scroll.getChildAt(0));
                assertEquals("Status must not reset scroll", y, scroll.getScrollY());
            }
        } finally {
            ui(() -> StatusStore.set(main, originalState, originalDetail));
            settle();
        }
    }

    public void testNavigationAndResumeRestorePosition() throws Exception {
        MainActivity main = launch(MainActivity.class);
        navigate(main, 2);
        ScrollView settings = (ScrollView) field(main, "currentScroll");
        android.graphics.Rect viewport = new android.graphics.Rect();
        settings.getGlobalVisibleRect(viewport);
        assertTrue("The scroll viewport starts below the status bar",
                viewport.top >= (int) field(main, "systemTopInset"));
        ui(() -> settings.scrollTo(0, 500));
        settle();
        int y = settings.getScrollY();
        assertTrue("Settings must scroll", y > 0);
        settings.getGlobalVisibleRect(viewport);
        assertTrue("Scrolling must not draw underneath the status bar",
                viewport.top >= (int) field(main, "systemTopInset"));
        navigate(main, 1);
        navigate(main, 2);
        ScrollView restored = (ScrollView) field(main, "currentScroll");
        assertEquals("Returning to a tab restores its scroll", y, restored.getScrollY());
        View content = restored.getChildAt(0);
        ui(() -> {
            try { invoke(main, "onResume", new Class<?>[]{}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
        assertSame("Resume must not rebuild unchanged screen", content, restored.getChildAt(0));
        assertEquals(y, restored.getScrollY());

        for (int i = 0; i < 15; i++) {
            final int destination = i % 3;
            ui(() -> {
                try { invoke(main, "navigateTo", new Class<?>[]{int.class}, destination); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            Thread.sleep(35);
        }
        settle();
        FrameLayout host = (FrameLayout) field(main, "pageHost");
        assertEquals("Interrupted transitions must remove old pages", 1, host.getChildCount());
        assertEquals(1f, host.getChildAt(0).getAlpha());
        assertEquals(0f, host.getChildAt(0).getTranslationX());
    }

    public void testActivityRecreationRestoresPageAndScroll() throws Exception {
        MainActivity main = launch(MainActivity.class);
        navigate(main, 2);
        ScrollView scroll = (ScrollView) field(main, "currentScroll");
        ui(() -> scroll.scrollTo(0, 400));
        settle();
        int y = scroll.getScrollY();
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
        ui(() -> main.recreate());
        Activity recreated = monitor.waitForActivityWithTimeout(5000);
        getInstrumentation().removeMonitor(monitor);
        assertNotNull("Activity recreated", recreated);
        activity = recreated;
        settle();
        assertEquals(2, field(recreated, "page"));
        assertEquals(y, ((ScrollView) field(recreated, "currentScroll")).getScrollY());
    }

    public void testAppSelectionAndSeparateModeLists() throws Exception {
        AppRoutingActivity routing = launch(AppRoutingActivity.class);
        for (int i = 0; i < 120 && !(boolean) field(routing, "appsLoaded"); i++) Thread.sleep(100);
        assertTrue("Installed applications loaded", (boolean) field(routing, "appsLoaded"));
        ui(() -> {
            try { invoke(routing, "changeMode", new Class<?>[]{String.class}, AppPolicy.EXCLUDE); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
        ListView list = (ListView) field(routing, "list");
        assertTrue("Applications visible", list.getCount() > 5);
        ui(() -> list.setSelection(5));
        settle();
        int first = list.getFirstVisiblePosition();
        View row = list.getChildAt(0);
        int top = row.getTop();
        CheckBox checkbox = findCheckBox(row);
        assertNotNull(checkbox);
        ui(() -> checkbox.performClick());
        settle();
        assertSame("Toggling app must not recreate its row", row, list.getChildAt(0));
        assertEquals(first, list.getFirstVisiblePosition());
        assertEquals(top, list.getChildAt(0).getTop());
        Set<?> excluded = new java.util.HashSet<>((Set<?>) field(routing, "selected"));
        ui(() -> {
            try { invoke(routing, "changeMode", new Class<?>[]{String.class}, AppPolicy.ONLY); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
        ui(() -> {
            try { invoke(routing, "changeMode", new Class<?>[]{String.class}, AppPolicy.EXCLUDE); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
        assertEquals("Modes retain separate selections", excluded, field(routing, "selected"));
        EditText search = (EditText) field(routing, "search");
        ui(() -> search.setText("telegram"));
        settle();
        assertTrue("Search filters applications", list.getCount() < 5);
        ui(() -> search.setText(""));
        settle();
        assertTrue(list.getCount() > 5);
        // No save() call: user's persistent routing rules remain unchanged.
    }

    public void testContentRefreshWaitsUntilGestureEnds() throws Exception {
        MainActivity main = launch(MainActivity.class);
        navigate(main, 2);
        ScrollView scroll = (ScrollView) field(main, "currentScroll");
        ui(() -> scroll.scrollTo(0, 150));
        settle();
        int y = scroll.getScrollY();
        SecureStore store = (SecureStore) field(main, "store");
        long now = android.os.SystemClock.uptimeMillis();
        ui(() -> {
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                    android.view.MotionEvent.ACTION_DOWN, 20, 20, 0);
            scroll.dispatchTouchEvent(down);
            down.recycle();
            store.connectionGuard = !store.connectionGuard; // In memory only.
            try { invoke(main, "render", new Class<?>[]{}); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(220);
        assertSame("Refresh deferred while gesture is active", scroll, field(main, "currentScroll"));
        ui(() -> {
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_CANCEL, 20, 20, 0);
            scroll.dispatchTouchEvent(up);
            up.recycle();
        });
        Thread.sleep(550);
        getInstrumentation().waitForIdleSync();
        ScrollView refreshed = (ScrollView) field(main, "currentScroll");
        assertNotSame("Deferred refresh runs after the gesture", scroll, refreshed);
        assertEquals("Refresh keeps the scroll offset", y, refreshed.getScrollY());
    }

    public void testThemeDefaultsAndSystemResolution() {
        for (String old : new String[]{null, "", "obsidian", "titanium", "legacy", "unknown"}) {
            assertEquals(SecureStore.THEME_DARK, SecureStore.normalizeTheme(old));
            assertFalse(ThemePalette.of(old, false).light);
        }
        assertFalse(ThemePalette.of(SecureStore.THEME_DARK, false).light);
        assertFalse(ThemePalette.of(SecureStore.THEME_DARK, true).light);
        assertTrue(ThemePalette.of(SecureStore.THEME_LIGHT, false).light);
        assertTrue(ThemePalette.of(SecureStore.THEME_LIGHT, true).light);
        assertTrue(ThemePalette.of(SecureStore.THEME_SYSTEM, false).light);
        assertFalse(ThemePalette.of(SecureStore.THEME_SYSTEM, true).light);
    }

    public void testThemeChooserPersistsAndPreservesProfiles() throws Exception {
        java.io.File settings = new java.io.File(getInstrumentation().getTargetContext().getFilesDir(), "settings.enc");
        byte[] backup = java.nio.file.Files.readAllBytes(settings.toPath());
        SecureStore original = new SecureStore(getInstrumentation().getTargetContext());
        try {
            MainActivity main = launch(MainActivity.class);
            navigate(main, 2);
            String[] titles = {"Светлая", "Как в системе", "Тёмная"};
            String[] modes = {SecureStore.THEME_LIGHT, SecureStore.THEME_SYSTEM, SecureStore.THEME_DARK};
            for (int i = 0; i < titles.length; i++) {
                android.widget.RadioButton option = findRadio(main.getWindow().getDecorView(), titles[i]);
                assertNotNull(option);
                ui(() -> option.performClick());
                settle();
                SecureStore saved = new SecureStore(main);
                assertEquals(modes[i], saved.theme);
                assertEquals(original.profiles.size(), saved.profiles.size());
                assertEquals(original.activeId, saved.activeId);
                assertEquals(original.appMode, saved.appMode);
                for (int p = 0; p < original.profiles.size(); p++) {
                    assertEquals(original.profiles.get(p).id, saved.profiles.get(p).id);
                    assertTrue("Profile content preserved", original.profiles.get(p).config.equals(saved.profiles.get(p).config));
                }
                assertTrue(findRadio(main.getWindow().getDecorView(), titles[i]).isChecked());
                ThemePalette palette = (ThemePalette) field(main, "palette");
                assertEquals(ThemePalette.of(modes[i], main.getResources().getConfiguration()).light, palette.light);
                int flags = main.getWindow().getDecorView().getSystemUiVisibility();
                assertEquals(palette.light, (flags & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0);
                if (i == 0) {
                    android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                            new java.io.File(main.getFilesDir(), "theme-light-settings.png"))) {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
                    screenshot.recycle();
                }
            }
        } finally {
            if (activity != null) { ui(() -> activity.finish()); settle(); activity = null; }
            java.nio.file.Files.write(settings.toPath(), backup);
        }
    }

    public void testControlAndReturnChoosersPersistWithoutChangingProfiles() throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        java.io.File settings = new java.io.File(context.getFilesDir(), "settings.enc");
        byte[] backup = java.nio.file.Files.readAllBytes(settings.toPath());
        SecureStore baseline = new SecureStore(context);
        java.util.List<String> profiles = new java.util.ArrayList<>();
        for (Profile profile : baseline.profiles) profiles.add(profile.toJson().toString());
        try {
            MainActivity main = launch(MainActivity.class);
            navigate(main, 2);
            int[] hours = {0, 2, 4, 6, 24};
            for (int i = 0; i < hours.length; i++) {
                ui(() -> { try { invoke(main, "showAutoReturnChooser", new Class<?>[]{}); }
                    catch (Exception e) { throw new RuntimeException(e); } });
                AlertDialog dialog = (AlertDialog) field(main, "controlDialog");
                final int selected = i;
                assertEquals(5, dialog.getListView().getCount());
                if (i == 1) saveControlScreenshot(main, "auto-return-choice.png");
                ui(() -> dialog.getListView().performItemClick(null, selected, selected)); settle();
                assertEquals(hours[i], new SecureStore(context).autoReturnHours);
            }
            for (int selected : new int[]{2, 1, 0}) {
                ui(() -> { try { invoke(main, "showModeChooser", new Class<?>[]{}); }
                    catch (Exception e) { throw new RuntimeException(e); } });
                AlertDialog dialog = (AlertDialog) field(main, "controlDialog");
                assertEquals(3, dialog.getListView().getCount());
                if (selected == 2) saveControlScreenshot(main, "vpn-mode-choice.png");
                ui(() -> dialog.getListView().performItemClick(null, selected, selected)); settle();
                assertEquals(selected == 0 ? SecureStore.MANUAL_AUTO : selected == 1 ? SecureStore.MANUAL_ON :
                        SecureStore.MANUAL_OFF, new SecureStore(context).manualMode);
            }
            SecureStore actual = new SecureStore(context);
            java.util.List<String> after = new java.util.ArrayList<>();
            for (Profile profile : actual.profiles) after.add(profile.toJson().toString());
            assertEquals(profiles, after);
            assertEquals(baseline.appMode, actual.appMode);
            assertEquals(baseline.homeSsid, actual.homeSsid);
        } finally {
            if (activity != null) { ui(() -> activity.finish()); settle(); activity = null; }
            java.nio.file.Files.write(settings.toPath(), backup);
            AutoReturnReceiver.sync(context);
            context.startForegroundService(new Intent(context, AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
        }
    }

    private void saveControlScreenshot(MainActivity main, String name) throws Exception {
        Thread.sleep(350);
        getInstrumentation().waitForIdleSync();
        android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(main.getFilesDir(), name))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { screenshot.recycle(); }
    }

    public void testControlChooserColorsFollowLightAndDarkTheme() throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        java.io.File settings = new java.io.File(context.getFilesDir(), "settings.enc");
        byte[] backup = java.nio.file.Files.readAllBytes(settings.toPath());
        try {
            for (String theme : new String[]{SecureStore.THEME_LIGHT, SecureStore.THEME_DARK}) {
                SecureStore store = new SecureStore(context); store.theme = theme; store.save();
                MainActivity main = launch(MainActivity.class); navigate(main, 2);
                ui(() -> { try { invoke(main, "showAutoReturnChooser", new Class<?>[]{}); }
                    catch (Exception e) { throw new RuntimeException(e); } });
                settle();
                AlertDialog dialog = (AlertDialog) field(main, "controlDialog");
                android.widget.CheckedTextView row = (android.widget.CheckedTextView) dialog.getListView().getChildAt(0);
                ThemePalette palette = (ThemePalette) field(main, "palette");
                assertEquals(palette.text, row.getCurrentTextColor());
                assertEquals(palette.accent, row.getCheckMarkTintList().getColorForState(
                        new int[]{android.R.attr.state_checked}, 0));
                saveControlScreenshot(main, "auto-return-" + theme + ".png");
                ui(main::finish); settle(); activity = null;
            }
        } finally {
            if (activity != null) { ui(() -> activity.finish()); settle(); activity = null; }
            java.nio.file.Files.write(settings.toPath(), backup);
            AutoReturnReceiver.sync(context);
            context.startForegroundService(new Intent(context, AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
        }
    }

    public void testRoutingDraftSurvivesThemeRecreation() throws Exception {
        AppRoutingActivity routing = launch(AppRoutingActivity.class);
        ui(() -> {
            try { invoke(routing, "changeMode", new Class<?>[]{String.class}, AppPolicy.EXCLUDE); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        for (int i = 0; i < 30 && !(Boolean) field(routing, "appsLoaded"); i++) Thread.sleep(200);
        ui(() -> {
            try {
                ((Set<String>) field(routing, "selected")).add("com.android.settings");
                ((EditText) field(routing, "search")).setText("android");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(AppRoutingActivity.class.getName(), null, false);
        ui(() -> routing.recreate());
        Activity recreated = monitor.waitForActivityWithTimeout(5000);
        getInstrumentation().removeMonitor(monitor);
        assertNotNull(recreated);
        activity = recreated;
        settle();
        for (int i = 0; i < 30 && !(Boolean) field(recreated, "appsLoaded"); i++) Thread.sleep(200);
        getInstrumentation().waitForIdleSync();
        assertEquals(AppPolicy.EXCLUDE, field(recreated, "mode"));
        assertEquals("android", field(recreated, "searchText"));
        assertTrue(((Set<String>) field(recreated, "selected")).contains("com.android.settings"));
    }

    private android.widget.RadioButton findRadio(View view, String text) {
        if (view instanceof android.widget.RadioButton && text.contentEquals(((android.widget.RadioButton) view).getText()))
            return (android.widget.RadioButton) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            android.widget.RadioButton found = findRadio(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private android.widget.Button findButton(View view, String text) {
        if (view instanceof android.widget.Button && ((android.widget.Button) view).getText().toString().equals(text))
            return (android.widget.Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Button button = findButton(group.getChildAt(i), text);
                if (button != null) return button;
            }
        }
        return null;
    }

    private CheckBox findCheckBox(View view) {
        if (view instanceof CheckBox) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckBox checkbox = findCheckBox(group.getChildAt(i));
                if (checkbox != null) return checkbox;
            }
        }
        return null;
    }
}
