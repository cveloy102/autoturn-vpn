package app.autoturn.vpn;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Called only from the protected owner's worker, never Android's main thread. */
final class ProtectedAwgClient implements AutoCloseable {
    private final Context context;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile IProtectedAwg engine;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            engine = IProtectedAwg.Stub.asInterface(binder); ready.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { engine = null; }
        @Override public void onNullBinding(ComponentName name) { ready.countDown(); }
    };
    ProtectedAwgClient(Context context) {
        // Service-context bindings are automatically forgotten by Android during
        // onDestroy(), before the owner's queued native cleanup necessarily runs.
        this.context = context.getApplicationContext();
    }
    void start(ParcelFileDescriptor master, String config) throws Exception {
        if (!bound) bound = context.bindService(new Intent(context, ProtectedAwgService.class),
                connection, Context.BIND_AUTO_CREATE);
        if (!bound || !ready.await(5, TimeUnit.SECONDS) || engine == null)
            throw new IOException("AWG process unavailable");
        try (ParcelFileDescriptor copy = ParcelFileDescriptor.dup(master.getFileDescriptor())) {
            if (!engine.start(copy, config)) throw new IOException("AWG activation failed");
        }
    }
    void stop() {
        IProtectedAwg current = engine;
        if (current != null) try { current.stop(); } catch (Exception ignored) { }
    }
    @Override public void close() {
        stop();
        if (bound) {
            bound = false;
            try { context.unbindService(connection); }
            catch (IllegalArgumentException alreadyReleased) { /* Idempotent lifecycle cleanup. */ }
        }
        engine = null;
    }
}
