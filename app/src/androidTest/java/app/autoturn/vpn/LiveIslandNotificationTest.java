package app.autoturn.vpn;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;
import java.util.Arrays;

/** Checks what Android actually posts, replaces and removes without starting a VPN. */
public final class LiveIslandNotificationTest extends InstrumentationTestCase {
    private NotificationManager manager;
    private LiveIsland island;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context context = getInstrumentation().getTargetContext();
        manager = context.getSystemService(NotificationManager.class);
        assertTrue("Allow AutoTurn notifications for this device test", manager.areNotificationsEnabled());
        manager.cancel(LiveIsland.ID);
        island = new LiveIsland(context);
    }

    private Notification awaitTitle(String expected) {
        long end = SystemClock.elapsedRealtime() + 5_000;
        do {
            Notification found = null;
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() == LiveIsland.ID) found = posted.getNotification();
            }
            if (expected == null && found == null) return null;
            if (found != null && expected != null && expected.contentEquals(
                    found.extras.getCharSequence(Notification.EXTRA_TITLE, ""))) return found;
            SystemClock.sleep(60);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Expected island notification title: " + expected);
    }

    public void testReplacesProgressAndClearsWhenDisabled() {
        LiveIsland.Content progress = LiveIsland.Content.connecting("QA", "AmneziaWG", Arrays.asList(true, false), 1);
        island.show(progress);
        awaitTitle(null);
        island.setEnabled(true);
        island.show(progress);
        Notification posted = awaitTitle(progress.title);
        assertEquals(LiveIsland.CHANNEL, posted.getChannelId());
        assertTrue(posted.extras.getBoolean("android.requestPromotedOngoing"));
        assertTrue((posted.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertTrue(posted.contentIntent.isActivity());
        LiveIsland.Content pause = LiveIsland.Content.pause(System.currentTimeMillis() + 15_000);
        island.show(pause);
        posted = awaitTitle(pause.title);
        assertEquals(pause.countdownTo, posted.when);
        assertEquals("Повторить сейчас", posted.actions[0].title.toString());
        assertTrue(posted.actions[0].actionIntent.isForegroundService());
        island.setEnabled(false);
        awaitTitle(null);
        assertFalse(island.showing());
    }

    public void testConfirmationExpiresAndCanBeShownAgain() {
        island.setEnabled(true);
        LiveIsland.Content home = LiveIsland.Content.home("QA");
        island.show(home);
        island.clearProgress();
        Notification posted = awaitTitle(home.title);
        assertEquals(LiveIsland.STATUS_VISIBLE_MS, posted.getTimeoutAfter());
        SystemClock.sleep(LiveIsland.STATUS_VISIBLE_MS);
        awaitTitle(null);
        island.show(home);
        awaitTitle(home.title);
    }

    @Override protected void tearDown() throws Exception {
        try { manager.cancel(LiveIsland.ID); } finally { super.tearDown(); }
    }
}
