package app.autoturn.vpn;

import static org.junit.Assert.*;

import org.junit.Test;

public class HealthScheduleTest {
    @Test public void freshTunnelIsCheckedFast() {
        assertEquals(5_000, HealthSchedule.healthyInterval(1));
        assertEquals(5_000, HealthSchedule.healthyInterval(3));
    }

    @Test public void stableTunnelSlowsDownToOneMinute() {
        assertEquals(15_000, HealthSchedule.healthyInterval(4));
        assertEquals(30_000, HealthSchedule.healthyInterval(10));
        assertEquals(60_000, HealthSchedule.healthyInterval(11));
        assertEquals(60_000, HealthSchedule.healthyInterval(10_000));
    }

    @Test public void neverSlowerThanOneMinute() {
        for (int streak = 1; streak < 200; streak++)
            assertTrue(HealthSchedule.healthyInterval(streak) <= 60_000);
    }
}
