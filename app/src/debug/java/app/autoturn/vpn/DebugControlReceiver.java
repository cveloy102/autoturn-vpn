package app.autoturn.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Debug APK only: lets the Android shell restore the phone after a connection test. */
public final class DebugControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"app.autoturn.vpn.DEBUG_DISABLE".equals(intent.getAction())) return;
        try {
            SecureStore store = new SecureStore(context);
            store.automation = false;
            store.keepAtHome = false;
            store.save();
            context.stopService(new Intent(context, AutomationService.class));
            context.stopService(new Intent(context, XrayVpnService.class));
            StatusStore.set(context, StatusStore.OFF, "Автоматизация выключена после проверки");
        } catch (Exception ignored) {
            StatusStore.set(context, StatusStore.ERROR, "Не удалось завершить проверку VPN");
        }
    }
}
