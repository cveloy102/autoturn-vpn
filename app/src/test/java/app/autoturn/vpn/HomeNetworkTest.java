package app.autoturn.vpn;

import static org.junit.Assert.*;
import org.junit.Test;

public class HomeNetworkTest {
    private static final long NOW = 1_000_000;

    @Test public void foreignWifiOrMobileIsAway() {
        assertEquals(HomeNetwork.State.AWAY, HomeNetwork.state(false, true, NOW, 0));
        assertEquals(HomeNetwork.State.AWAY, HomeNetwork.state(false, false, NOW, NOW - 1));
        assertFalse(HomeNetwork.atHome(HomeNetwork.State.AWAY));
    }

    @Test public void homeWifiCarryingTrafficIsHome() {
        assertEquals(HomeNetwork.State.HOME, HomeNetwork.state(true, true, NOW, 0));
        assertTrue(HomeNetwork.atHome(HomeNetwork.State.HOME));
    }

    @Test public void homeWifiWithoutInternetIsNotHome() {
        // The bug: home SSID still connected, Android routes everything over mobile data.
        HomeNetwork.State state = HomeNetwork.state(true, false, NOW, 0);
        assertEquals(HomeNetwork.State.BYPASSED, state);
        assertFalse(HomeNetwork.atHome(state));
    }

    @Test public void recoveredWifiWaitsBeforeReleasingVpn() {
        long bypassed = NOW - 5_000;
        HomeNetwork.State state = HomeNetwork.state(true, true, NOW, bypassed);
        assertEquals(HomeNetwork.State.SETTLING, state);
        assertFalse(HomeNetwork.atHome(state));
        assertEquals(HomeNetwork.SETTLE_MS - 5_000, HomeNetwork.settleRemaining(NOW, bypassed));
    }

    @Test public void stableWifiIsHomeAgain() {
        long bypassed = NOW - HomeNetwork.SETTLE_MS;
        assertEquals(HomeNetwork.State.HOME, HomeNetwork.state(true, true, NOW, bypassed));
        assertEquals(0, HomeNetwork.settleRemaining(NOW, bypassed));
    }

    @Test public void noSettlingWithoutAnEarlierBypass() {
        // A fresh start at home must not hold the VPN for the settle time.
        assertEquals(0, HomeNetwork.settleRemaining(NOW, 0));
        assertEquals(HomeNetwork.State.HOME, HomeNetwork.state(true, true, 10, 0));
    }
}
