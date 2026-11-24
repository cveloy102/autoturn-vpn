package app.autoturn.vpn;

import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;

import java.util.Arrays;

/**
 * Visual walk through every island state, for checking how HyperOS / OxygenOS draw them.
 * Posts real notifications from the app for a few seconds each; skipped unless asked:
 *   adb shell am instrument -w -e islandDemo 1 -e class app.autoturn.vpn.LiveIslandDemoTest \
 *     app.autoturn.vpn.test/android.test.InstrumentationTestRunner
 * Optional: -e holdMs 6000 (time per state). Leave the phone on the home screen while it runs.
 */
public final class LiveIslandDemoTest extends InstrumentationTestCase {
    public void testWalkThroughStates() throws Exception {
        Bundle args = getInstrumentation() instanceof InstrumentationTestRunner ?
                ((InstrumentationTestRunner) getInstrumentation()).getArguments() : null;
        if (args == null || args.getString("islandDemo") == null) return;
        long hold = Long.parseLong(args.getString("holdMs", "6000"));
        LiveIsland island = new LiveIsland(getInstrumentation().getTargetContext());
        island.setEnabled(true);
        try {
            island.show(LiveIsland.Content.connecting("hysteria", "Hysteria 2", Arrays.asList(false, false, false, false), 0));
            Thread.sleep(hold);
            island.show(LiveIsland.Content.connecting("amnezia дом", "AmneziaWG", Arrays.asList(true, false, false, false), 1));
            Thread.sleep(hold);
            island.show(LiveIsland.Content.captcha());
            Thread.sleep(hold);
            island.show(LiveIsland.Content.pause(System.currentTimeMillis() + 15_000));
            Thread.sleep(hold);
            island.show(LiveIsland.Content.noNetwork());
            Thread.sleep(hold);
            island.show(LiveIsland.Content.permissionLost());
            Thread.sleep(hold);
            island.show(LiveIsland.Content.done("Нидерланды Амстердам резервный сервер", "VLESS · Reality"));
            Thread.sleep(hold);
            island.show(LiveIsland.Content.home("Keen_5G"));
            Thread.sleep(hold);
            island.show(LiveIsland.Content.manualOff(false, "04:10"));
            Thread.sleep(hold);
            island.show(LiveIsland.Content.autoReturn("Дома · VPN выключен"));
            Thread.sleep(LiveIsland.STATUS_VISIBLE_MS + 1000);
        } finally {
            island.clear();
        }
    }
}
