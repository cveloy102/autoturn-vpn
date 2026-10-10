package app.autoturn.vpn;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Persistent deadline; every delivery rechecks the latest command before changing mode. */
public final class AutoReturnReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            SecureStore.expireManualMode(context, System.currentTimeMillis());
            sync(context);
            context.startForegroundService(new Intent(context, AutomationService.class)
                    .setAction(AutomationService.ACTION_REFRESH));
        } catch (Exception ignored) {
            // The foreground service, screen wake, and next app open also check the deadline.
        }
    }

    static void sync(Context context) throws Exception {
        SecureStore store = new SecureStore(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent alarm = PendingIntent.getBroadcast(context, 10,
                new Intent(context, AutoReturnReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (SecureStore.MANUAL_AUTO.equals(store.manualMode) || store.manualUntil <= 0) alarms.cancel(alarm);
        else {
            // Use precise delivery where Android already permits it; no extra permission prompt.
            if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, store.manualUntil, alarm);
                    return;
                } catch (SecurityException ignored) { /* Permission/exemption changed meanwhile. */ }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, store.manualUntil, alarm);
        }
    }
}
