package app.autoturn.vpn;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LiveIslandContentTest {
    @Test public void queueShowsPositionAndFailedProfiles() {
        LiveIsland.Content content = LiveIsland.Content.connecting("дом", "AmneziaWG", Arrays.asList(true, false, false), 1);
        assertEquals("2/3", content.chip);
        assertEquals("Подключаемся · дом", content.title);
        assertEquals("Профиль 2 из 3 · AmneziaWG · не ответили: 1", content.text);
        assertEquals(1, content.current);
        assertFalse(content.indeterminate);
        assertEquals(Arrays.asList(true, false, false), content.failedSegments);
    }

    @Test public void singleProfileHasNoCounter() {
        LiveIsland.Content content = LiveIsland.Content.connecting("hysteria", "Hysteria 2", Collections.singletonList(false), 0);
        assertEquals("VPN", content.chip);
        assertEquals("Hysteria 2", content.text);
        assertTrue(content.indeterminate);
        assertEquals(-1, content.current);
    }

    @Test public void unknownProfilePositionFallsBackToSpinner() {
        LiveIsland.Content content = LiveIsland.Content.connecting("x", "Hysteria 2", Arrays.asList(false, false), -1);
        assertEquals("VPN", content.chip);
        assertTrue(content.indeterminate);
    }

    @Test public void chipTextsFitTheIsland() {
        // The island chip has room for a few characters only; longer names are cut with an ellipsis.
        for (LiveIsland.Content content : new LiveIsland.Content[]{
                LiveIsland.Content.connecting("a", "", Arrays.asList(false, false, false, false, false, false, false, false, false, false), 9),
                LiveIsland.Content.captcha(), LiveIsland.Content.noNetwork(),
                LiveIsland.Content.permissionLost(),
                LiveIsland.Content.done("Очень длинное имя профиля для Нидерландов", "Hysteria 2")})
            assertTrue(content.chip, content.chip.length() <= LiveIsland.CHIP_MAX);
    }

    @Test public void pauseCountsDownInsteadOfChipText() {
        LiveIsland.Content content = LiveIsland.Content.pause(123_000);
        assertNull(content.chip);
        assertEquals(123_000, content.countdownTo);
    }

    @Test public void doneNamesTheProfileAndLeavesByItself() {
        LiveIsland.Content content = LiveIsland.Content.done("hysteria", "Hysteria 2");
        assertEquals("На связи · hysteria", content.title);
        assertEquals("Доступ проверен · Hysteria 2", content.text);
        assertEquals("hysteria", content.chip);
        assertEquals(LiveIsland.DONE_VISIBLE_MS, content.timeoutMs);
        assertEquals("Готово", LiveIsland.Content.done(null, "").chip);
    }

    @Test public void longProfileNamesAreCutToOneShortLine() {
        LiveIsland.Content content = LiveIsland.Content.done("Нидерланды  Амстердам\nрезервный сервер №2", "VLESS · Reality");
        assertEquals("На связи · Нидерланды Амстерда…", content.title);
        assertEquals("Нидерла…", content.chip);
        assertEquals("abc", LiveIsland.Content.shorten("abc", 3));
        assertEquals("ab…", LiveIsland.Content.shorten("abcd", 3));
    }

    @Test public void longWaitsDoNotBecomePermanent() {
        assertTrue(LiveIsland.Content.noNetwork().timeoutMs > 0);
        assertTrue(LiveIsland.Content.captcha().timeoutMs > 0);
        assertTrue(LiveIsland.Content.permissionLost().timeoutMs > 0);
    }

    @Test public void keyChangesOnlyWithVisibleContent() {
        assertEquals(LiveIsland.Content.captcha().key(), LiveIsland.Content.captcha().key());
        assertNotEquals(LiveIsland.Content.connecting("a", "", Arrays.asList(false, false), 0).key(),
                LiveIsland.Content.connecting("b", "", Arrays.asList(true, false), 1).key());
    }
}
