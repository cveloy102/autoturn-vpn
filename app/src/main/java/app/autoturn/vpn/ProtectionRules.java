package app.autoturn.vpn;

/** The user's protection intent is independent of engine availability and retry timers. */
final class ProtectionRules {
    private ProtectionRules() { }

    static boolean needsBarrier(boolean enabled, String mode, boolean atHome) {
        return enabled && ("on".equals(mode) || !atHome);
    }

    static boolean shouldConnect(boolean enabled, String mode, boolean atHome, boolean hasProfile) {
        return needsBarrier(enabled, mode, atHome) && !"off".equals(mode) && hasProfile;
    }
}
