package app.autoturn.vpn;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;

public class StatusNotificationContentTest {
    private static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static final long NOW = at(25, 1, 30);

    @Test public void headlinesMatchHome() {
        assertEquals("На связи", StatusNotification.Content.title(StatusStore.ON, "VPN проверен · hysteria"));
        assertEquals("Подключаемся", StatusNotification.Content.title(StatusStore.CONNECTING, "Пробуем профиль b"));
        assertEquals("Нет связи", StatusNotification.Content.title(StatusStore.ERROR, "Профили недоступны"));
        assertEquals("Защита активна", StatusNotification.Content.title(StatusStore.BLOCKED, "Трафик заблокирован"));
        assertEquals("Вы дома", StatusNotification.Content.title(StatusStore.OFF, "Домашний Wi-Fi · VPN выключен"));
        assertEquals("VPN выключен", StatusNotification.Content.title(StatusStore.OFF, "VPN выключен вручную"));
    }

    @Test public void homeInAutoOffersToTurnOn() {
        StatusNotification.Content c = StatusNotification.Content.of(StatusStore.OFF, "Домашний Wi-Fi · VPN выключен",
                SecureStore.MANUAL_AUTO, 0, true, false, true, NOW);
        assertEquals("Вы дома", c.title);
        assertEquals("Домашний Wi-Fi · VPN выключен", c.text);
        assertEquals("Авто", c.mode);
        assertEquals(Collections.singletonList("Включить VPN"), c.actions);
        assertTrue(c.toggleTurnsOn);
    }

    @Test public void manualOnOffersOffAndReturnToAuto() {
        StatusNotification.Content c = StatusNotification.Content.of(StatusStore.ON, "VPN проверен · hysteria",
                SecureStore.MANUAL_ON, at(25, 3, 43), true, false, true, NOW);
        assertEquals("Вручную до 03:43", c.mode);
        assertEquals(Arrays.asList("Выключить VPN", "Вернуть Авто"), c.actions);
        assertFalse(c.toggleTurnsOn);
    }

    @Test public void savedManualCommandWinsWhileServiceCatchesUp() {
        // Just turned off by hand, the tunnel still reports ON for a moment: offer "on", like the tile.
        StatusNotification.Content c = StatusNotification.Content.of(StatusStore.ON, "VPN проверен · a",
                SecureStore.MANUAL_OFF, 0, true, false, true, NOW);
        assertTrue(c.toggleTurnsOn);
        assertEquals("Выключен", c.mode);
    }

    @Test public void deadlineOnAnotherDayShowsTheDate() {
        assertEquals("26.11 09:00", StatusNotification.Content.until(at(26, 9, 0), NOW));
    }

    @Test public void protectedModeAndNoProfile() {
        StatusNotification.Content c = StatusNotification.Content.of(StatusStore.BLOCKED, "", SecureStore.MANUAL_AUTO,
                0, true, true, false, NOW);
        assertEquals("Авто · защита", c.mode);
        assertEquals("Следим за домашней сетью", c.text);
        assertTrue(c.actions.isEmpty());
    }

    @Test public void headerStaysShort() {
        // Longest case: protected mode with a deadline on another day. The shield is dropped there.
        String mode = StatusNotification.Content.mode(SecureStore.MANUAL_OFF, at(26, 9, 0), true, true, NOW);
        assertEquals("Выключен до 26.11 09:00", mode);
        assertTrue(mode, mode.length() <= 25);
    }

    @Test public void automationOffIsNamed() {
        assertEquals("Автоматика выключена", StatusNotification.Content.mode(SecureStore.MANUAL_AUTO, 0, false, false, NOW));
    }
}
