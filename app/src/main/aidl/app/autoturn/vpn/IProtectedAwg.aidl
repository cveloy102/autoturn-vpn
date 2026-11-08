package app.autoturn.vpn;
import android.os.ParcelFileDescriptor;

// Private same-UID service. The VPN owner keeps its master descriptor throughout.
interface IProtectedAwg {
    boolean start(in ParcelFileDescriptor tunnel, String config);
    void stop();
}
