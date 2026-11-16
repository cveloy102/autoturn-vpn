package app.autoturn.vpn;

import static org.junit.Assert.*;
import org.junit.Test;

public class SecureStoreLogicTest {
    @Test public void manualDeadline() {
        assertEquals(0, SecureStore.deadline(SecureStore.MANUAL_AUTO, 2, 1000));
        assertEquals(0, SecureStore.deadline(SecureStore.MANUAL_ON, 0, 1000));
        assertEquals(1000 + 2 * 3_600_000L, SecureStore.deadline(SecureStore.MANUAL_ON, 2, 1000));
        assertEquals(1000 + 24 * 3_600_000L, SecureStore.deadline(SecureStore.MANUAL_OFF, 24, 1000));
    }

    @Test public void themeNormalization() {
        assertEquals(SecureStore.THEME_LIGHT, SecureStore.normalizeTheme("light"));
        assertEquals(SecureStore.THEME_SYSTEM, SecureStore.normalizeTheme("system"));
        assertEquals(SecureStore.THEME_DARK, SecureStore.normalizeTheme("neon"));
        assertEquals(SecureStore.THEME_DARK, SecureStore.normalizeTheme(null));
    }
}
