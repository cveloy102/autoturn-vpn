package app.autoturn.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) &&
                !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        try {
            SecureStore.expireManualMode(context, System.currentTimeMillis());
            AutoReturnReceiver.sync(context);
            SecureStore store = new SecureStore(context);
            if (store.automation || SecureStore.MANUAL_ON.equals(store.manualMode))
                context.startForegroundService(new Intent(context, AutomationService.class));
        } catch (Exception ignored) {
            StatusStore.set(context, StatusStore.ERROR, "Откройте AutoTurn после перезагрузки");
        }
    }
}
