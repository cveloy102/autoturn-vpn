package app.autoturn.vpn;

import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs the independent traffic probe under the instrumentation APK's own UID. */
abstract class RoutingProbeTestCase extends InstrumentationTestCase {
    final String probePackage() {
        return getInstrumentation().getContext().getPackageName();
    }

    final String shell(String command) throws Exception {
        ParcelFileDescriptor fd = getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            return new String(TestIo.bytes(in), StandardCharsets.UTF_8);
        }
    }
}
