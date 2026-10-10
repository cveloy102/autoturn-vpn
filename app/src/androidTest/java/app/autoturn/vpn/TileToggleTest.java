package app.autoturn.vpn;

import android.test.InstrumentationTestCase;

public final class TileToggleTest extends InstrumentationTestCase {
    public void testAutomaticModeTogglesActualConnection() {
        assertEquals(SecureStore.MANUAL_OFF, VpnTileService.nextManualMode(SecureStore.MANUAL_AUTO, StatusStore.ON));
        assertEquals(SecureStore.MANUAL_OFF, VpnTileService.nextManualMode(SecureStore.MANUAL_AUTO, StatusStore.CONNECTING));
        assertEquals(SecureStore.MANUAL_ON, VpnTileService.nextManualMode(SecureStore.MANUAL_AUTO, StatusStore.OFF));
        assertEquals(SecureStore.MANUAL_ON, VpnTileService.nextManualMode(SecureStore.MANUAL_AUTO, StatusStore.ERROR));
    }

    public void testRapidClicksReversePendingCommand() {
        assertEquals(SecureStore.MANUAL_OFF, VpnTileService.nextManualMode(SecureStore.MANUAL_ON, StatusStore.OFF));
        assertEquals(SecureStore.MANUAL_OFF, VpnTileService.nextManualMode(SecureStore.MANUAL_ON, StatusStore.CONNECTING));
        assertEquals(SecureStore.MANUAL_ON, VpnTileService.nextManualMode(SecureStore.MANUAL_OFF, StatusStore.ON));
        assertEquals(SecureStore.MANUAL_ON, VpnTileService.nextManualMode(SecureStore.MANUAL_OFF, StatusStore.CONNECTING));
    }

    public void testFailedStartCanBeStopped() {
        assertEquals(SecureStore.MANUAL_OFF, VpnTileService.nextManualMode(SecureStore.MANUAL_ON, StatusStore.ERROR));
    }
}
