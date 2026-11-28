package app.autoturn.vpn;

/**
 * "At home" means more than the home Wi-Fi name: the home Wi-Fi must also be the network Android
 * actually sends traffic through. When that Wi-Fi loses internet (or a vendor "Wi-Fi assistant"
 * prefers mobile data), the phone stays on the home SSID while traffic goes out over the carrier.
 * Treating that as home turned the VPN off and leaked everything.
 */
final class HomeNetwork {
    /** After home Wi-Fi carried no traffic, it must carry it this long before the VPN is released. */
    static final long SETTLE_MS = 15_000;

    enum State {
        /** Not on a home network at all. */
        AWAY,
        /** On the home Wi-Fi, and it carries the traffic: the VPN is not needed. */
        HOME,
        /** On the home Wi-Fi, but traffic goes through another network (usually mobile data). */
        BYPASSED,
        /** The home Wi-Fi carries traffic again, but only recently: keep the VPN for now. */
        SETTLING
    }

    private HomeNetwork() { }

    /**
     * @param homeSsid      the connected Wi-Fi is one of the user's home networks
     * @param carriesTraffic that Wi-Fi is the network Android routes traffic through (or nothing is
     *                      known yet: then the old SSID-only answer stands)
     * @param lastBypassedAt elapsed-realtime of the last moment the home Wi-Fi was bypassed, 0 if never
     */
    static State state(boolean homeSsid, boolean carriesTraffic, long now, long lastBypassedAt) {
        if (!homeSsid) return State.AWAY;
        if (!carriesTraffic) return State.BYPASSED;
        return settleRemaining(now, lastBypassedAt) > 0 ? State.SETTLING : State.HOME;
    }

    /** Milliseconds until a SETTLING home network counts as home; 0 when it already does. */
    static long settleRemaining(long now, long lastBypassedAt) {
        if (lastBypassedAt <= 0) return 0;
        return Math.max(0, lastBypassedAt + SETTLE_MS - now);
    }

    static boolean atHome(State state) { return state == State.HOME; }

    private static final String BYPASS_FILE = "home-bypassed";

    /**
     * AutomationService (main process) knows precisely where traffic goes; the protected VPN runs in
     * its own process and re-checks "home" before every step. This file tells it the home Wi-Fi is
     * not to be trusted right now. A stale file only keeps protection on, never off.
     */
    static void setBypassed(android.content.Context context, boolean bypassed) {
        java.io.File file = new java.io.File(context.getFilesDir(), BYPASS_FILE);
        try {
            if (bypassed) { if (!file.exists()) file.createNewFile(); }
            else if (file.exists()) file.delete();
        } catch (java.io.IOException ignored) { }
    }

    static boolean bypassed(android.content.Context context) {
        return new java.io.File(context.getFilesDir(), BYPASS_FILE).exists();
    }

    /**
     * Without a network callback: does the connected Wi-Fi carry traffic? Android prefers a Wi-Fi
     * with working internet; a Wi-Fi without it loses to any other network that has it.
     */
    static boolean wifiCarriesTraffic(android.net.ConnectivityManager connectivity) {
        boolean wifiValidated = false, otherValidated = false;
        for (android.net.Network network : connectivity.getAllNetworks()) {
            android.net.NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            boolean validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) wifiValidated |= validated;
            else otherValidated |= validated;
        }
        return wifiValidated || !otherValidated;
    }
}
