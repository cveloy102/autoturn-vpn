package app.autoturn.vpn;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Private, short-lived cross-process state. Never contains the user's tunnel password. */
final class QwdttState {
    private static final String FILE = "qwdtt-runtime.json";
    private QwdttState() { }

    static synchronized JSONObject read(Context context) {
        try (FileOutputStream mutex = new FileOutputStream(new File(context.getFilesDir(), FILE + ".lock"));
             java.nio.channels.FileLock ignored = mutex.getChannel().lock()) {
            JSONObject result = new JSONObject(new String(new AtomicFile(new File(context.getFilesDir(), FILE)).readFully(),
                    StandardCharsets.UTF_8));
            // Android may kill a service process without onDestroy(). Ignore its old IPC
            // record so a killed/reinstalled app cannot retain a phantom probe or CAPTCHA.
            int owner = result.optInt("ownerPid");
            if (owner <= 0) return new JSONObject();
            try (java.io.FileInputStream processName = new java.io.FileInputStream("/proc/" + owner + "/cmdline")) {
                byte[] name = new byte[256]; int count = processName.read(name);
                if (count <= 0) return new JSONObject();
                String command = new String(name, 0, count, StandardCharsets.UTF_8).split("\u0000", 2)[0];
                if (!command.equals(context.getPackageName() + ":xray")) return new JSONObject();
            }
            return result;
        } catch (Exception ignored) { return new JSONObject(); }
    }

    static synchronized void write(Context context, JSONObject state) throws Exception {
        try (FileOutputStream mutex = new FileOutputStream(new File(context.getFilesDir(), FILE + ".lock"));
             java.nio.channels.FileLock ignored = mutex.getChannel().lock()) {
            AtomicFile file = new AtomicFile(new File(context.getFilesDir(), FILE));
            FileOutputStream out = file.startWrite();
            try {
                out.write(state.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
            } catch (Exception e) { file.failWrite(out); throw e; }
        }
    }

    static boolean pending(Context context, String profileId) {
        JSONObject state = read(context);
        long left = state.optLong("captchaUntil") - SystemClock.elapsedRealtime();
        return profileId.equals(state.optString("profileId")) && left > 0 && left <= 120_000 &&
                !state.optString("captchaUrl").isEmpty();
    }
}
