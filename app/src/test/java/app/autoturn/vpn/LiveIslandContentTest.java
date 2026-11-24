package app.autoturn.vpn;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LiveIslandContentTest {
    @Test public void queueShowsPositionAndFailedProfiles() {
        LiveIsland.Content content = LiveIsland.Content.connecting("amneziawg", Arrays.asList(true, false, false), 1);
        assertEquals("2/3", content.chip);
        assertEquals("Пробуем amneziawg", content.text);
        assertEquals(1, content.current);
        assertFalse(content.indeterminate);
        assertEquals(Arrays.asList(true, false, false), content.failedSegments);
    }

    @Test public void singleProfileHasNoCounter() {
        LiveIsland.Content content = LiveIsland.Content.connecting("hysteria", Collections.singletonList(false), 0);
        assertEquals("VPN", content.chip);
        assertTrue(content.indeterminate);
        assertEquals(-1, content.current);
    }

    @Test public void unknownProfilePositionFallsBackToSpinner() {
        LiveIsland.Content content = LiveIsland.Content.connecting("x", Arrays.asList(false, false), -1);
        assertEquals("VPN", content.chip);
        assertTrue(content.indeterminate);
    }

    @Test public void chipTextsFitTheIsland() {
        // Android recommends at most 7 characters for the short critical text.
        for (LiveIsland.Content content : new LiveIsland.Content[]{
                LiveIsland.Content.connecting("a", Arrays.asList(false, false, false, false, false, false, false, false, false, false), 9),
                LiveIsland.Content.captcha(), LiveIsland.Content.noNetwork(),
                LiveIsland.Content.permissionLost(), LiveIsland.Content.done("VPN проверен · a")})
            assertTrue(content.chip, content.chip.length() <= 7);
    }

    @Test public void pauseCountsDownInsteadOfChipText() {
        LiveIsland.Content content = LiveIsland.Content.pause(123_000);
        assertNull(content.chip);
        assertEquals(123_000, content.countdownTo);
    }

    @Test public void doneLeavesByItself() {
        LiveIsland.Content content = LiveIsland.Content.done("");
        assertEquals("VPN проверен", content.text);
        assertEquals(LiveIsland.DONE_VISIBLE_MS, content.timeoutMs);
    }

    @Test public void longWaitsDoNotBecomePermanent() {
        assertTrue(LiveIsland.Content.noNetwork().timeoutMs > 0);
        assertTrue(LiveIsland.Content.captcha().timeoutMs > 0);
        assertTrue(LiveIsland.Content.permissionLost().timeoutMs > 0);
    }

    @Test public void keyChangesOnlyWithVisibleContent() {
        assertEquals(LiveIsland.Content.captcha().key(), LiveIsland.Content.captcha().key());
        assertNotEquals(LiveIsland.Content.connecting("a", Arrays.asList(false, false), 0).key(),
                LiveIsland.Content.connecting("b", Arrays.asList(true, false), 1).key());
    }
}
