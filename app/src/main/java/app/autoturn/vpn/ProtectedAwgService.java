package app.autoturn.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import static org.amnezia.awg.GoBackend.*;

/** AWG's Go runtime must never share a process with libXray's Go runtime. */
public final class ProtectedAwgService extends VpnService {
    private int handle = -1;
    private final IProtectedAwg.Stub engine = new IProtectedAwg.Stub() {
        @Override public boolean start(ParcelFileDescriptor tunnel, String config) {
            synchronized (ProtectedAwgService.this) {
                stopEngine();
                try {
                    new org.amnezia.awg.backend.GoBackend(ProtectedAwgService.this);
                    handle = awgTurnOn("AutoTurnSecure", tunnel.detachFd(), config);
                    if (handle < 0) return false;
                    int v4 = awgGetSocketV4(handle), v6 = awgGetSocketV6(handle);
                    if ((v4 >= 0 && !protect(v4)) || (v6 >= 0 && !protect(v6))) {
                        stopEngine(); return false;
                    }
                    return true;
                } finally {
                    try { tunnel.close(); } catch (Exception ignored) { }
                }
            }
        }
        @Override public void stop() { stopEngine(); }
    };

    private synchronized void stopEngine() {
        if (handle >= 0) { int previous = handle; handle = -1; awgTurnOff(previous); }
    }
    @Override public IBinder onBind(Intent intent) { return engine; }
    @Override public void onDestroy() { stopEngine(); super.onDestroy(); }
}
