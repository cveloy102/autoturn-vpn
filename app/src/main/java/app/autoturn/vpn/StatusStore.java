package app.autoturn.vpn;

import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class StatusStore {
    static final String ACTION_CHANGED = "app.autoturn.vpn.STATUS_CHANGED";
    static final String OFF = "off";
    static final String CONNECTING = "connecting";
    static final String ON = "on";
    static final String ERROR = "error";
    private static final String FILE = "runtime.json";

    private StatusStore() {}

    static synchronized void set(Context context, String state, String detail) {
        // AtomicFile needs mutual exclusion, including across the separate Xray process.
        try (FileOutputStream mutex = new FileOutputStream(new File(context.getFilesDir(), FILE + ".lock"));
             java.nio.channels.FileLock ignoredLock = mutex.getChannel().lock()) {
            AtomicFile file = new AtomicFile(new File(context.getFilesDir(), FILE));
            FileOutputStream out = file.startWrite();
            try {
                byte[] data = new JSONObject().put("state", state).put("detail", detail)
                        .toString().getBytes(StandardCharsets.UTF_8);
                out.write(data);
                file.finishWrite(out);
            } catch (Exception e) { file.failWrite(out); }
        } catch (Exception ignored) { }
        Intent intent = new Intent(ACTION_CHANGED).setPackage(context.getPackageName());
        context.sendBroadcast(intent);
        VpnTileService.refresh(context);
    }

    static String state(Context context) {
        return read(context).optString("state", OFF);
    }

    static String detail(Context context) {
        return read(context).optString("detail", "");
    }

    private static synchronized JSONObject read(Context context) {
        try (FileOutputStream mutex = new FileOutputStream(new File(context.getFilesDir(), FILE + ".lock"));
             java.nio.channels.FileLock ignoredLock = mutex.getChannel().lock();
             FileInputStream in = new AtomicFile(new File(context.getFilesDir(), FILE)).openRead()) {
            byte[] data = new byte[4096];
            int count = in.read(data);
            return count < 1 ? new JSONObject() : new JSONObject(new String(data, 0, count, StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }
}
