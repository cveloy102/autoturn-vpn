package app.autoturn.vpn;

import static org.junit.Assert.*;

import org.junit.Test;

/** When the island says «Снова Авто»: only when the manual mode's timer ran out. */
public class AutoReturnDecisionTest {
    private static final long DEADLINE = 1_000_000;

    @Test public void timerSeenByPreviousDecisionHasPassed() {
        assertTrue(AutomationService.autoReturned(SecureStore.MANUAL_ON, DEADLINE, SecureStore.MANUAL_AUTO, false, DEADLINE + 5));
        assertTrue(AutomationService.autoReturned(SecureStore.MANUAL_OFF, DEADLINE, SecureStore.MANUAL_AUTO, false, DEADLINE));
    }

    @Test public void storeOrAlarmJustExpiredIt() {
        // The alarm receiver may expire the mode before the service ever saw the deadline.
        assertTrue(AutomationService.autoReturned("", 0, SecureStore.MANUAL_AUTO, true, DEADLINE));
    }

    @Test public void userChoosingAutoEarlyIsNotATimer() {
        assertFalse(AutomationService.autoReturned(SecureStore.MANUAL_ON, DEADLINE, SecureStore.MANUAL_AUTO, false, DEADLINE - 1));
    }

    @Test public void manualWithoutDeadlineNeverReturnsByItself() {
        assertFalse(AutomationService.autoReturned(SecureStore.MANUAL_ON, 0, SecureStore.MANUAL_AUTO, false, DEADLINE));
    }

    @Test public void stillManualOrAlreadyAuto() {
        assertFalse(AutomationService.autoReturned(SecureStore.MANUAL_ON, DEADLINE, SecureStore.MANUAL_ON, true, DEADLINE + 5));
        assertFalse(AutomationService.autoReturned(SecureStore.MANUAL_AUTO, 0, SecureStore.MANUAL_AUTO, false, DEADLINE));
        // A fresh service start (nothing seen yet) in Auto is not a return.
        assertFalse(AutomationService.autoReturned("", 0, SecureStore.MANUAL_AUTO, false, DEADLINE));
    }
}
