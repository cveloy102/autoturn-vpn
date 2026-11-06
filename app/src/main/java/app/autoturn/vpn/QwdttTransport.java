package app.autoturn.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns one native process; raw native output and configuration are never logged. */
final class QwdttTransport implements AutoCloseable {
    private final Context context;
    private final JSONObject state = new JSONObject();
    private Process process;
    private volatile boolean ready, closed, vpnActive, outputEnded;
    private final String session = UUID.randomUUID().toString();
    private static final int CAPTCHA_NOTIFICATION = 103;

    QwdttTransport(Context context) { this.context = context; }

    JSONArray start(Profile profile, BooleanSupplier current) throws Exception {
        if (!current.getAsBoolean()) throw new InterruptedException("Connection cancelled");
        QwdttConfig config = QwdttConfig.parse(profile.config);
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libqwdtt.so");
        if (!binary.isFile()) throw new IllegalStateException("qWDTT requires Android arm64");
        int socksPort;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            socksPort = socket.getLocalPort();
        }
        int probePort;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            probePort = socket.getLocalPort();
        }
        if (probePort == socksPort) throw new IllegalStateException("Local port collision");
        String password = UUID.randomUUID().toString();
        synchronized (this) {
            if (closed) throw new InterruptedException("Connection cancelled");
            state.put("profileId", profile.id).put("session", session).put("port", probePort)
                    .put("ownerPid", android.os.Process.myPid())
                    .put("user", "autoturn").put("password", password).put("phase", "starting");
            QwdttState.write(context, state);
        }
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(),
                "-peer", config.peer, "-listen", "127.0.0.1:" + config.port,
                "-n", String.valueOf(config.workers), "-device-id", deviceId(),
                "-vk-auth", "anonymous", "-vk-anon-path", "vkcalls", "-captcha-mode", "wv",
                "-mode", "socks", "-socks", "127.0.0.1:" + socksPort,
                "-socks-auth", "-socks-user", "autoturn");
        builder.environment().put("AUTOTURN_PASSWORD", config.password);
        builder.environment().put("AUTOTURN_VK_HASHES", config.hashes);
        builder.environment().put("AUTOTURN_SOCKS_PASSWORD", password);
        File workingDirectory = new File(context.getCacheDir(), "qwdtt");
        if (!workingDirectory.isDirectory() && !workingDirectory.mkdirs())
            throw new IllegalStateException("qWDTT private directory unavailable");
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        process = builder.start();
        Thread reader = new Thread(() -> readOutput(profile.id), "qwdtt-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = SystemClock.elapsedRealtime() + 150_000;
        long maximum = SystemClock.elapsedRealtime() + 300_000;
        while (!ready) {
            if (!current.getAsBoolean() || closed) throw new InterruptedException("Connection cancelled");
            if (!process.isAlive() || outputEnded) throw new IllegalStateException("qWDTT transport exited");
            synchronized (this) {
                deadline = Math.min(maximum, Math.max(deadline, state.optLong("captchaUntil") + 15_000));
            }
            if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("qWDTT start timed out");
            Thread.sleep(100);
        }
        if (!current.getAsBoolean()) throw new InterruptedException("Connection cancelled");
        return new JSONArray().put(new JSONObject().put("tag", "qwdtt-out").put("protocol", "socks")
                .put("settings", new JSONObject().put("servers", new JSONArray().put(new JSONObject()
                        .put("address", "127.0.0.1").put("port", socksPort)
                        .put("users", new JSONArray().put(new JSONObject().put("user", "autoturn")
                                .put("pass", password)))))));
    }

    synchronized JSONObject probeInbound() throws Exception {
        return new JSONObject().put("tag", "qwdtt-probe-in").put("listen", "127.0.0.1")
                .put("port", state.getInt("port")).put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "password").put("udp", false)
                        .put("accounts", new JSONArray().put(new JSONObject().put("user", state.getString("user"))
                                .put("pass", state.getString("password")))));
    }

    synchronized void markVpnReady() throws Exception {
        if (closed || !ready || outputEnded || process == null || !process.isAlive())
            throw new IllegalStateException("qWDTT stopped before VPN setup");
        state.put("phase", "active");
        QwdttState.write(context, state);
        vpnActive = true;
    }

    private String deviceId() {
        // Stable per AutoTurn installation, independent from the native client's working directory.
        android.content.SharedPreferences prefs = context.getSharedPreferences("qwdtt-device", Context.MODE_PRIVATE);
        String id = prefs.getString("id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            if (!prefs.edit().putString("id", id).commit()) throw new IllegalStateException("Cannot save device id");
        }
        return id;
    }

    private void readOutput(String profileId) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = input.readLine()) != null) {
                // Do not expose native logs: they can include keys, hashes and TURN credentials.
                if (line.startsWith("CAPTCHA_SOLVE|")) {
                    String[] parts = line.split("\\|", 4);
                    if (parts.length == 4) requestCaptcha(parts[2]);
                } else if (line.contains("[SOCKS] listening")) {
                    synchronized (this) {
                        if (closed) return;
                        state.put("phase", "ready").remove("captchaUrl");
                        state.remove("captchaUntil");
                        QwdttState.write(context, state);
                        ready = true;
                    }
                    context.getSystemService(NotificationManager.class).cancel(CAPTCHA_NOTIFICATION);
                }
            }
        } catch (Exception ignored) { /* Exit is handled below without logging sensitive output. */ }
        outputEnded = true;
        // Startup failures are reported by start()/markVpnReady(), once. This callback
        // only reports loss of an already active transport, avoiding duplicate failover.
        if (!closed && vpnActive) {
            try {
                synchronized (this) {
                    if (closed) return;
                    state.put("phase", "failed");
                    QwdttState.write(context, state);
                }
                StatusStore.set(context, StatusStore.ERROR, "qWDTT · транспорт остановлен");
                context.startService(new Intent(context, AutomationService.class)
                        .setAction(AutomationService.ACTION_TUNNEL_FAILED)
                        .putExtra(XrayVpnService.EXTRA_PROFILE_ID, profileId));
            } catch (Exception ignored) { /* Guard's next probe also detects the loss. */ }
        }
    }

    private synchronized void requestCaptcha(String url) throws Exception {
        if (closed || !QwdttCaptchaActivity.allowedUrl(url)) return;
        state.put("captchaUrl", url).put("captchaUntil", SystemClock.elapsedRealtime() + 110_000)
                .put("captchaRequest", UUID.randomUUID().toString());
        QwdttState.write(context, state);
        StatusStore.set(context, StatusStore.CONNECTING, "qWDTT · требуется капча VK — откройте AutoTurn");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("qwdtt-captcha", "Капча VK", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, QwdttCaptchaActivity.class)
                .putExtra("session", session).putExtra("request", state.getString("captchaRequest"));
        manager.notify(CAPTCHA_NOTIFICATION, new Notification.Builder(context, "qwdtt-captcha")
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("AutoTurn · капча VK")
                .setContentText("Нажмите, чтобы продолжить подключение qWDTT")
                .setContentIntent(PendingIntent.getActivity(context, 103, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build());
    }

    synchronized void captchaResult(String expectedSession, String request, String token) {
        if (closed || !session.equals(expectedSession) || request == null || !request.equals(state.optString("captchaRequest")) ||
                token == null || token.length() > 16384 || token.contains("\n") || token.contains("\r") ||
                SystemClock.elapsedRealtime() >= state.optLong("captchaUntil")) return;
        try {
            process.getOutputStream().write(("CAPTCHA_RESULT|" + token + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            state.remove("captchaUrl"); state.remove("captchaUntil"); state.remove("captchaRequest");
            QwdttState.write(context, state);
            context.getSystemService(NotificationManager.class).cancel(CAPTCHA_NOTIFICATION);
        } catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        closed = true;
        // Invalidate the challenge and probe immediately, before native shutdown can wait.
        if (session.equals(QwdttState.read(context).optString("session"))) {
            try { QwdttState.write(context, new JSONObject()); } catch (Exception ignored) { }
            context.getSystemService(NotificationManager.class).cancel(CAPTCHA_NOTIFICATION);
        }
        if (process != null) {
            try {
                process.getOutputStream().close();
                process.destroy();
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1500, TimeUnit.MILLISECONDS);
                }
            } catch (Exception ignored) { process.destroyForcibly(); }
        }
    }
}
