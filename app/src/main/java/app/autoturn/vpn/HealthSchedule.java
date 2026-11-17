package app.autoturn.vpn;

/**
 * Interval between connection checks while the VPN keeps passing them. A fresh or recently
 * failing tunnel is checked every 5 s; a long-stable one slows down to once a minute.
 * Network changes, a lost VPN network and screen-on still trigger an immediate check.
 */
final class HealthSchedule {
    static final long FAST_MS = 5_000;

    private HealthSchedule() { }

    /** @param healthyStreak consecutive successful checks, starting at 1. */
    static long healthyInterval(int healthyStreak) {
        if (healthyStreak <= 3) return FAST_MS;
        if (healthyStreak <= 6) return 15_000;
        if (healthyStreak <= 10) return 30_000;
        return 60_000;
    }
}
