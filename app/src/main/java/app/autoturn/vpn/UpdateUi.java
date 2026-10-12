package app.autoturn.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** UI attaches only while the Activity is resumed; downloads survive recreation. */
final class UpdateUi implements AppUpdater.Listener {
    private final MainActivity activity;
    private final AppUpdater updater;
    private boolean resumed;
    private boolean installAfterPermission;
    private boolean installAfterValidation;
    private boolean progressHidden;
    private boolean feedbackRequested;
    private AlertDialog dialog;
    private TextView progressText;
    private ProgressBar progress;
    private AppUpdater.State handled;

    UpdateUi(MainActivity activity) { this.activity = activity; updater = AppUpdater.get(activity); }
    void resume() {
        resumed = true;
        updater.attach(this);
        if (installAfterPermission) {
            installAfterPermission = false;
            if (activity.getPackageManager().canRequestPackageInstalls()) install();
            else showMessage("Разрешение не выдано", "Разрешите установку обновлений для AutoTurn и нажмите «Установить» ещё раз.");
        }
        updater.check(false);
    }
    void pause() { resumed = false; feedbackRequested = false; updater.detach(this); }
    void destroy() { pause(); dismiss(); }
    void notesClosed() { changed(updater.state()); }
    void manualCheck() {
        feedbackRequested = true;
        progressHidden = false;
        AppUpdater.State state = updater.state();
        if (state.phase == AppUpdater.Phase.READY || state.phase == AppUpdater.Phase.DOWNLOADING ||
                state.phase == AppUpdater.Phase.VERIFYING) {
            handled = null; changed(state); return;
        }
        showMessage("Обновления", "Проверяем GitHub…");
        updater.check(true);
    }
    void openUpdate() {
        AppUpdater.State state = updater.state();
        if (state.phase == AppUpdater.Phase.READY || state.phase == AppUpdater.Phase.DOWNLOADING ||
                state.phase == AppUpdater.Phase.VERIFYING) { manualCheck(); return; }
        UpdateRelease release = updater.availableRelease();
        if (release != null) offer(release, false);
        else manualCheck();
    }
    @Override public void changed(AppUpdater.State state) {
        activity.updateHomeUpdate();
        if (!resumed || activity.isFinishing() || activity.isDestroyed() || activity.updateNotesVisible()) return;
        if (state.phase == AppUpdater.Phase.DOWNLOADING || state.phase == AppUpdater.Phase.VERIFYING) {
            if (!progressHidden) showProgress(state);
            return;
        }
        if (state == handled) return;
        handled = state;
        switch (state.phase) {
            case AVAILABLE:
                boolean requested = feedbackRequested;
                feedbackRequested = false;
                if (requested || updater.shouldOffer(state.release)) offer(state.release, false);
                break;
            case READY:
                feedbackRequested = false;
                if (installAfterValidation) { installAfterValidation = false; launchInstaller(); }
                else offer(state.release, true);
                break;
            case CURRENT:
            case ERROR:
                installAfterValidation = false;
                boolean showResult = feedbackRequested && state.manual;
                feedbackRequested = false;
                if (showResult) showMessage("Обновления", state.message);
                break;
            default: break;
        }
    }
    private ThemePalette palette() {
        try { return ThemePalette.of(new SecureStore(activity).theme, activity.getResources().getConfiguration()); }
        catch (Exception ignored) { return ThemePalette.of(SecureStore.THEME_DARK, activity.getResources().getConfiguration()); }
    }
    private void offer(UpdateRelease release, boolean ready) {
        if (release == null) return;
        updater.markOffered(release);
        dismiss();
        String message = ready ? "APK проверен. Android попросит подтвердить установку.\nПрофили и настройки сохранятся." :
                "Установлена " + BuildConfig.VERSION_NAME + ".\nНовая версия: " + release.version +
                        " · " + Math.max(1, release.size / (1024 * 1024)) + " МБ\n\n" +
                        (release.notes.isEmpty() ? "Доступно обновление AutoTurn." : release.notes);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        TextView text = new TextView(activity);
        text.setText(message); text.setTextSize(14); text.setTextColor(palette().text);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        int padding = dp(22); text.setPadding(padding, padding, padding, padding);
        scroll.addView(text);
        dialog = new AlertDialog.Builder(activity).setTitle("AutoTurn " + release.version)
                .setView(scroll).setPositiveButton(ready ? "Установить" : "Скачать", (d, w) -> {
                    progressHidden = false;
                    feedbackRequested = true;
                    if (ready) install(); else updater.download(release);
                }).setNegativeButton("Позже", null).create();
        showStyled();
    }
    private void install() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            dismiss();
            dialog = new AlertDialog.Builder(activity).setTitle("Разрешить обновление")
                    .setMessage("Android требует разрешить AutoTurn устанавливать обновления. Затем вернитесь в приложение.")
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        try {
                            installAfterPermission = true;
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) { installAfterPermission = false; showMessage("Обновления", "Не удалось открыть настройки Android."); }
                    }).setNegativeButton("Позже", null).create();
            showStyled(); return;
        }
        AppUpdater.State state = updater.state();
        if (state.release == null) return;
        installAfterValidation = true;
        feedbackRequested = true;
        progressHidden = false;
        updater.verifyReady(state.release);
    }
    private void launchInstaller() {
        dismiss();
        try {
            Uri uri = UpdateApkProvider.uri(activity);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("AutoTurn update", uri));
            activity.startActivity(intent);
        } catch (Exception e) { showMessage("Обновления", "Не удалось открыть установщик Android. Нажмите «Проверить обновления» и повторите установку."); }
    }
    private void showProgress(AppUpdater.State state) {
        feedbackRequested = true;
        if (dialog == null || !dialog.isShowing() || progressText == null) {
            dismiss();
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(22); layout.setPadding(padding, padding, padding, padding);
            progressText = new TextView(activity); progressText.setTextSize(14);
            progressText.setTextColor(palette().text); progressText.setTypeface(android.graphics.Typeface.MONOSPACE);
            layout.addView(progressText);
            progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(palette().accent));
            progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(palette().accent));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(14));
            params.topMargin = dp(18); layout.addView(progress, params);
            dialog = new AlertDialog.Builder(activity).setTitle("Обновление AutoTurn").setView(layout)
                    .setPositiveButton("В фоне", (d, w) -> { progressHidden = true; })
                    .setNegativeButton("Отменить", (d, w) -> { updater.cancel(); }).create();
            dialog.setOnCancelListener(d -> { progressHidden = true; });
            showStyled();
        }
        progressText.setText(state.message + (state.progress >= 0 ? " " + state.progress + "%" : ""));
        progress.setIndeterminate(state.progress < 0);
        if (state.progress >= 0) progress.setProgress(state.progress, true);
    }
    private void showMessage(String title, String message) {
        dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setPositiveButton("Понятно", null).create();
        showStyled();
    }
    private void showStyled() {
        dialog.show(); TerminalUi.styleDialog(dialog, palette());
    }
    private void dismiss() {
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        progress = null; progressText = null;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
