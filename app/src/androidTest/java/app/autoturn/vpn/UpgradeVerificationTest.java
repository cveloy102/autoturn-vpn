package app.autoturn.vpn;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import java.io.File;
import java.lang.reflect.Method;

public final class UpgradeVerificationTest extends InstrumentationTestCase {
    public void testPersistMigrationPreservesAllUserSettings() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String path = ((InstrumentationTestRunner) getInstrumentation()).getArguments().getString("baselinePath");
        File before = path == null ? new File(context.getFilesDir(), "upgrade-baseline.enc") : new File(path);
        assertTrue("Supply the encrypted pre-upgrade snapshot", before.isFile());
        Method encode = SecureStore.class.getDeclaredMethod("encodeSettings");
        encode.setAccessible(true);
        String installed = encode.invoke(new SecureStore(context)).toString();
        try (SettingsFixture fixture = new SettingsFixture(context, before)) {
            SecureStore migrated = new SecureStore(fixture);
            assertEquals("Installed profiles and preferences match snapshot", installed, encode.invoke(migrated).toString());
            migrated.save();
            assertEquals("Writing migrated settings preserves all fields",
                    installed, encode.invoke(new SecureStore(fixture)).toString());
        }
    }
}
