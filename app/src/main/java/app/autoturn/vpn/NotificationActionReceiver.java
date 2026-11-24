package app.autoturn.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Buttons of the persistent status card: «Включить/Выключить VPN» and «Вернуть Авто». */
public final class NotificationActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        try {
            if (StatusNotification.ACTION_TOGGLE.equals(action)) {
                // The card only offers this broadcast when no VPN permission prompt is needed;
                // otherwise its button opens AutoTurn directly (Android blocks activity starts here).
                VpnTileService.applyToggle(context);
            } else if (StatusNotification.ACTION_AUTO.equals(action)) {
                new SecureStore(context).setControlMode(SecureStore.MANUAL_AUTO);
                AutoReturnReceiver.sync(context);
                context.startForegroundService(new Intent(context, AutomationService.class)
                        .setAction(AutomationService.ACTION_REFRESH));
            }
        } catch (Exception e) {
            StatusStore.set(context, StatusStore.ERROR, "Не удалось переключить VPN. Откройте AutoTurn.");
        }
    }
}
