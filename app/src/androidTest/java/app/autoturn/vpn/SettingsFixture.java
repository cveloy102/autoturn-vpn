package app.autoturn.vpn;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** A private copy of encrypted settings that uses the installed app's existing Keystore key. */
final class SettingsFixture extends ContextWrapper implements AutoCloseable {
    private final File directory;

    SettingsFixture(Context base, File snapshot) throws IOException {
        super(base);
        directory = Files.createTempDirectory(base.getCacheDir().toPath(), "qa-settings-").toFile();
        try {
            Files.copy(snapshot.toPath(), new File(directory, "settings.enc").toPath());
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    @Override public File getFilesDir() { return directory; }
    @Override public Context getApplicationContext() { return this; }

    @Override public void close() throws IOException {
        File[] contents = directory.listFiles();
        if (contents != null) for (File file : contents) Files.delete(file.toPath());
        Files.delete(directory.toPath());
    }
}
