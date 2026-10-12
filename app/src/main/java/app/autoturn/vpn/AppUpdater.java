package app.autoturn.vpn;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Independent of VPN settings and services. Android owns the persistent download. */
final class AppUpdater {
    static final long DAY = 24 * 60 * 60 * 1000L;
    static final long MAX_APK_BYTES = 250 * 1024 * 1024L;
    enum Phase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }
    interface Listener { void changed(State state); }
    static final class State {
        final Phase phase;
        final UpdateRelease release;
        final int progress;
        final String message;
        final boolean manual;
        State(Phase phase, UpdateRelease release, int progress, String message, boolean manual) {
            this.phase = phase; this.release = release; this.progress = progress;
            this.message = message; this.manual = manual;
        }
    }
    private static AppUpdater instance;
    static synchronized AppUpdater get(Context context) {
        if (instance == null) instance = new AppUpdater(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final DownloadManager downloads;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private State state = new State(Phase.IDLE, null, -1, "", false);
    private long downloadId;
    private long generation;
    private boolean polling;
    private boolean restored;
    private boolean checkingManual;
    private UpdateRelease availableRelease;

    private AppUpdater(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        downloads = context.getSystemService(DownloadManager.class);
        downloadId = prefs.getLong("download_id", 0);
        try {
            String saved = prefs.getString("available_release", "");
            if (!saved.isEmpty()) {
                UpdateRelease release = UpdateRelease.restore(saved);
                if (release.code > BuildConfig.VERSION_CODE) {
                    availableRelease = release;
                    state = new State(Phase.AVAILABLE, release, -1, "", false);
                } else prefs.edit().remove("available_release").apply();
            }
        } catch (Exception ignored) { prefs.edit().remove("available_release").apply(); }
    }
    synchronized State state() { return state; }
    synchronized UpdateRelease availableRelease() { return availableRelease; }
    synchronized void attach(Listener listener) {
        this.listener = listener;
        listener.changed(state);
        if (!restored) {
            restored = true;
            try {
                String saved = prefs.getString("download_release", "");
                if (!saved.isEmpty()) {
                    UpdateRelease release = UpdateRelease.restore(saved);
                    if (release.code <= BuildConfig.VERSION_CODE) { clearDownload(); return; }
                    if (downloadId != 0) { startPoll(release); return; }
                    if (UpdateApkProvider.apk(context).isFile()) { verifyReady(release); return; }
                }
            } catch (Exception ignored) { clearDownload(); }
        }
    }
    synchronized void detach(Listener value) { if (listener == value) listener = null; }
    private synchronized void publish(Phase phase, UpdateRelease release, int progress, String message, boolean manual) {
        if (phase == Phase.CURRENT) {
            availableRelease = null;
            prefs.edit().remove("available_release").apply();
        } else if (release != null && release.code > BuildConfig.VERSION_CODE) {
            availableRelease = release;
            try { prefs.edit().putString("available_release", release.toJson().toString()).apply(); }
            catch (Exception ignored) { }
        }
        state = new State(phase, release, progress, message, manual);
        State event = state;
        handler.post(() -> {
            Listener current;
            synchronized (AppUpdater.this) {
                if (state != event) return;
                current = listener;
            }
            if (current != null) current.changed(event);
        });
    }
    static boolean isDue(long last, long now) { return last <= 0 || now < last || now - last >= DAY; }
    synchronized void check(boolean manual) {
        if (state.phase == Phase.CHECKING) { if (manual) checkingManual = true; return; }
        if (state.phase == Phase.DOWNLOADING ||
                state.phase == Phase.VERIFYING || state.phase == Phase.READY) return;
        long now = System.currentTimeMillis();
        if (!manual && !isDue(prefs.getLong("last_check", 0), now)) return;
        prefs.edit().putLong("last_check", now).apply();
        checkingManual = manual;
        publish(Phase.CHECKING, null, -1, "Проверяем GitHub…", manual);
        executor.execute(() -> {
            try {
                JSONObject release = readJson(UpdateRelease.API, 2 * 1024 * 1024);
                if (release == null) { checkResult(Phase.CURRENT, null, "Новых релизов пока нет."); return; }
                String metadata = UpdateRelease.metadataUrl(release);
                if (metadata == null) {
                    // Older releases predate the updater. Never infer Android versionCode from a tag.
                    String tag = release.optString("tag_name");
                    if (tag.equals("v" + BuildConfig.VERSION_NAME) || isOlderTag(tag, BuildConfig.VERSION_NAME))
                        checkResult(Phase.CURRENT, null, "Установлена актуальная версия.");
                    else throw new IOException("В релизе отсутствует update.json. Попробуйте позже.");
                    return;
                }
                JSONObject data = readJson(metadata, 64 * 1024);
                if (data == null) throw new IOException("Данные релиза недоступны");
                UpdateRelease candidate = UpdateRelease.parse(release, data);
                checkResult(candidate.code > BuildConfig.VERSION_CODE ? Phase.AVAILABLE : Phase.CURRENT,
                        candidate, "Установлена актуальная версия.");
            } catch (Exception exception) {
                checkResult(Phase.ERROR, null, "Не удалось проверить обновления. Проверьте интернет и повторите попытку позже.");
            }
        });
    }
    private synchronized void checkResult(Phase phase, UpdateRelease release, String message) {
        publish(phase, release, -1, message, checkingManual);
        checkingManual = false;
    }
    static boolean isOlderTag(String tag, String current) {
        if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        try {
            String[] a = tag.substring(1).split("\\."), b = current.split("\\.");
            for (int i = 0; i < 3; i++) {
                long left = Long.parseLong(a[i]), right = Long.parseLong(b[i]);
                if (left != right) return left < right;
            }
        } catch (Exception ignored) { }
        return false;
    }
    synchronized boolean shouldOffer(UpdateRelease release) {
        return prefs.getLong("offered_code", 0) != release.code ||
                isDue(prefs.getLong("offered_at", 0), System.currentTimeMillis());
    }
    void markOffered(UpdateRelease release) {
        prefs.edit().putLong("offered_code", release.code).putLong("offered_at", System.currentTimeMillis()).apply();
    }
    synchronized void download(UpdateRelease release) {
        if (state.phase == Phase.DOWNLOADING || state.phase == Phase.VERIFYING || state.phase == Phase.READY) return;
        try {
            if (release.code <= BuildConfig.VERSION_CODE) throw new IOException();
            UpdateRelease.checkedAssetUrl(release.url);
            File directory = context.getExternalFilesDir("updates");
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException();
            File target = new File(directory, "download.apk");
            if (target.exists() && !target.delete()) throw new IOException();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.url))
                    .setTitle("AutoTurn " + release.version)
                    .setDescription("Обновление приложения")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationUri(Uri.fromFile(target));
            // Commit metadata before enqueue so interrupted launches can discard an orphan safely.
            prefs.edit().putString("download_release", release.toJson().toString()).commit();
            downloadId = downloads.enqueue(request);
            prefs.edit().putLong("download_id", downloadId).commit();
            startPoll(release);
        } catch (Exception exception) {
            publish(Phase.ERROR, release, -1, "Не удалось начать скачивание. Проверьте свободное место и интернет.", true);
        }
    }
    private synchronized void startPoll(UpdateRelease release) {
        if (polling) return;
        polling = true;
        long token = ++generation, id = downloadId;
        publish(Phase.DOWNLOADING, release, -1, "Скачиваем APK…", true);
        poll(release, token, id);
    }
    private void poll(UpdateRelease release, long token, long id) {
        executor.execute(() -> {
            synchronized (this) { if (token != generation) return; }
            try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("Скачивание отменено");
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (bytes > MAX_APK_BYTES || total > MAX_APK_BYTES) throw new IOException("Слишком большой APK");
                if (status == DownloadManager.STATUS_SUCCESSFUL) { copyAndVerify(release, token, id); return; }
                if (status == DownloadManager.STATUS_FAILED) throw new IOException("Скачивание не завершено");
                int progress = total > 0 ? (int) Math.min(100, bytes * 100 / total) : -1;
                synchronized (this) {
                    if (token != generation) return;
                    publish(Phase.DOWNLOADING, release, progress,
                            status == DownloadManager.STATUS_PAUSED ? "Ожидаем сеть…" : "Скачиваем APK…", true);
                }
                handler.postDelayed(() -> {
                    synchronized (AppUpdater.this) { if (token != generation) return; }
                    poll(release, token, id);
                }, 1000);
            } catch (Exception exception) { failDownload(release, token, "Скачивание не удалось. Повторите попытку."); }
        });
    }
    private void copyAndVerify(UpdateRelease release, long token, long id) throws Exception {
        synchronized (this) {
            if (token != generation) return;
            publish(Phase.VERIFYING, release, -1, "Проверяем APK и подпись…", true);
        }
        File target = UpdateApkProvider.apk(context);
        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException();
        File temporary = new File(target.getParentFile(), "pending-" + token + ".apk");
        Uri uri = downloads.getUriForDownloadedFile(id);
        if (uri == null) throw new IOException();
        try {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temporary)) {
            if (in == null) throw new IOException();
            byte[] buffer = new byte[65536];
            long copied = 0;
            for (int count; (count = in.read(buffer)) != -1;) {
                copied += count;
                if (copied > release.size || copied > MAX_APK_BYTES) throw new IOException();
                synchronized (this) { if (token != generation) { temporary.delete(); return; } }
                out.write(buffer, 0, count);
            }
            out.getFD().sync();
        }
        validate(context, temporary, release);
        synchronized (this) {
            if (token != generation) { temporary.delete(); return; }
            if (!temporary.renameTo(target)) throw new IOException();
            downloads.remove(id);
            downloadId = 0; polling = false;
            prefs.edit().remove("download_id").apply();
            publish(Phase.READY, release, 100, "APK проверен. Можно устанавливать.", true);
        }
        } finally { temporary.delete(); }
    }
    synchronized void verifyReady(UpdateRelease release) {
        if (state.phase == Phase.VERIFYING) return;
        long token = ++generation;
        publish(Phase.VERIFYING, release, -1, "Проверяем APK и подпись…", true);
        executor.execute(() -> {
            try {
                validate(context, UpdateApkProvider.apk(context), release);
                synchronized (this) {
                    if (token == generation) publish(Phase.READY, release, 100, "APK проверен. Можно устанавливать.", true);
                }
            } catch (Exception exception) { failDownload(release, token, "APK не прошёл проверку. Скачайте обновление заново."); }
        });
    }
    private synchronized void failDownload(UpdateRelease release, long token, String message) {
        if (token != generation) return;
        clearDownload();
        publish(Phase.ERROR, release, -1, message, true);
    }
    synchronized void cancel() {
        UpdateRelease release = state.release;
        clearDownload();
        publish(release == null ? Phase.IDLE : Phase.AVAILABLE, release, -1, "", false);
        if (release != null) markOffered(release);
    }
    private synchronized void clearDownload() {
        generation++; polling = false;
        if (downloadId != 0) downloads.remove(downloadId);
        downloadId = 0;
        prefs.edit().remove("download_id").remove("download_release").apply();
        File apk = UpdateApkProvider.apk(context);
        apk.delete();
        new File(apk.getParentFile(), "pending.apk").delete();
    }
    static void validate(Context context, File apk, UpdateRelease release) throws Exception {
        if (!apk.isFile() || apk.length() != release.size || apk.length() > MAX_APK_BYTES)
            throw new IOException("Размер APK не совпадает");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(apk)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = in.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        if (!hex(digest.digest()).equals(release.sha256)) throw new IOException("Хеш APK не совпадает");
        PackageManager pm = context.getPackageManager();
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !context.getPackageName().equals(archive.packageName) ||
                archive.getLongVersionCode() != release.code || release.code <= installed.getLongVersionCode() ||
                !release.version.equals(archive.versionName) || archive.signingInfo == null || installed.signingInfo == null ||
                signers(installed).isEmpty() || !signers(archive).equals(signers(installed)))
            throw new IOException("Неверная версия или подпись APK");
    }
    private static Set<String> signers(PackageInfo info) {
        Set<String> signatures = new HashSet<>();
        for (android.content.pm.Signature signature : info.signingInfo.getApkContentsSigners())
            signatures.add(signature.toCharsString());
        return signatures;
    }
    static String hex(byte[] data) {
        StringBuilder value = new StringBuilder(data.length * 2);
        for (byte item : data) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }
    static JSONObject readJson(String address, int maxBytes) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1 ||
                    !(host.equals("api.github.com") || host.equals("github.com") || host.equals("release-assets.githubusercontent.com") ||
                            host.equals("objects.githubusercontent.com"))) throw new IOException("Недопустимый адрес");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "AutoTurn/" + BuildConfig.VERSION_NAME);
            try {
                int status = connection.getResponseCode();
                if (status == 404 && address.equals(UpdateRelease.API)) return null;
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException();
                    url = new URL(url, location); continue;
                }
                if (status != 200) throw new IOException("HTTP " + status);
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = in.read(buffer)) != -1;) {
                        if (out.size() + count > maxBytes) throw new IOException("Ответ слишком большой");
                        out.write(buffer, 0, count);
                    }
                    return new JSONObject(out.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Слишком много перенаправлений");
    }
}
