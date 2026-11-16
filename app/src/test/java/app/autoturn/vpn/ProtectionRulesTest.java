package app.autoturn.vpn;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProtectionRulesTest {
    private static final String[] MODES = {"auto", "on", "off"};

    @Test public void disabledProtectionNeverOwnsBarrier() {
        for (String mode : MODES) for (boolean home : new boolean[]{false, true}) {
            assertFalse(ProtectionRules.needsBarrier(false, mode, home));
            assertFalse(ProtectionRules.shouldConnect(false, mode, home, true));
        }
    }

    @Test public void barrierFollowsModeAndLocation() {
        for (String mode : MODES) for (boolean home : new boolean[]{false, true}) {
            boolean barrier = !home || "on".equals(mode);
            assertEquals(mode + home, barrier, ProtectionRules.needsBarrier(true, mode, home));
            assertFalse("no profiles must not open traffic", ProtectionRules.shouldConnect(true, mode, home, false));
            assertEquals(barrier && !"off".equals(mode), ProtectionRules.shouldConnect(true, mode, home, true));
        }
    }

    @Test public void documentedBehaviour() {
        assertTrue("manual OFF outside home keeps protection", ProtectionRules.needsBarrier(true, "off", false));
        assertFalse("home AUTO releases protection", ProtectionRules.needsBarrier(true, "auto", true));
        assertTrue("manual ON protects even at home", ProtectionRules.needsBarrier(true, "on", true));
        assertTrue("unknown SSID counts as outside home", ProtectionRules.needsBarrier(true, "auto", false));
    }
}
