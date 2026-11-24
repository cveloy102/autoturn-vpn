package app.autoturn.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The one persistent AutoTurn card in the notification shade. AutomationService and the tunnel
 * services (Xray, protected mode) all post it under the same id, so the shade shows a single
 * card: the status headline like on Home, the explanation line, the mode, and up to two actions.
 * Standard Android template only, so it looks right in HyperOS, OxygenOS and stock Android.
 */
final class StatusNotification {
    static final int ID = 100;
    static final String CHANNEL = "automation";
    static final String ACTION_TOGGLE = "app.autoturn.vpn.NOTIFICATION_TOGGLE";
    static final String ACTION_AUTO = "app.autoturn.vpn.NOTIFICATION_AUTO";

    private StatusNotification() {}

    /** What the card shows. Pure data, so the wording is unit-tested without Android. */
    static final class Content {
        final String title;
        final String text;
        final String mode;
        /** Action labels in order; the toggle (if any) is first. */
        final List<String> actions;
        final boolean showToggle;
        final boolean toggleTurnsOn;
        final boolean returnAuto;

        Content(String title, String text, String mode, boolean showToggle, boolean toggleTurnsOn, boolean returnAuto) {
            this.title = title; this.text = text; this.mode = mode;
            this.showToggle = showToggle; this.toggleTurnsOn = toggleTurnsOn; this.returnAuto = returnAuto;
            List<String> labels = new ArrayList<>();
            if (showToggle) labels.add(toggleTurnsOn ? "Включить VPN" : "Выключить VPN");
            if (returnAuto) labels.add("Вернуть Авто");
            this.actions = Collections.unmodifiableList(labels);
        }

        String key() { return title + "|" + text + "|" + mode + "|" + actions; }

        /**
         * @param manualUntil wall-clock end of the manual mode, 0 = none
         * @param hasProfile  false hides the VPN toggle (nothing to connect yet)
         */
        static Content of(String state, String detail, String manualMode, long manualUntil, boolean automation,
                          boolean protectedMode, boolean hasProfile, long now) {
            boolean running = StatusStore.ON.equals(state) || StatusStore.CONNECTING.equals(state) ||
                    StatusStore.BLOCKED.equals(state);
            boolean manualOff = SecureStore.MANUAL_OFF.equals(manualMode);
            // Same rule as the tile: a saved manual command wins while the service catches up.
            boolean turnsOn = SecureStore.MANUAL_AUTO.equals(manualMode) ? !running : manualOff;
            return new Content(title(state, detail), detail == null || detail.trim().isEmpty() ?
                    "Следим за домашней сетью" : detail.trim(),
                    mode(manualMode, manualUntil, automation, protectedMode, now),
                    hasProfile, turnsOn, !SecureStore.MANUAL_AUTO.equals(manualMode));
        }

        /** The Home headline without its cursor: «На связи», «Вы дома», … */
        static String title(String state, String detail) {
            if (StatusStore.BLOCKED.equals(state)) return "Защита активна";
            if (StatusStore.ON.equals(state)) return "На связи";
            if (StatusStore.CONNECTING.equals(state)) return "Подключаемся";
            if (StatusStore.ERROR.equals(state)) return "Нет связи";
            return detail != null && detail.startsWith("Домашний Wi-Fi") ? "Вы дома" : "VPN выключен";
        }

        static String mode(String manualMode, long manualUntil, boolean automation, boolean protectedMode, long now) {
            String mode = SecureStore.MANUAL_ON.equals(manualMode) ? "Включён вручную" :
                    SecureStore.MANUAL_OFF.equals(manualMode) ? "Выключен вручную" :
                    automation || protectedMode ? "Авто" : "Автоматика выключена";
            if (!SecureStore.MANUAL_AUTO.equals(manualMode) && manualUntil > 0) mode += " до " + until(manualUntil, now);
            return protectedMode ? mode + " · защита" : mode;
        }

        static String until(long when, long now) {
            Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
            a.setTimeInMillis(when); b.setTimeInMillis(now);
            boolean sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
            return new SimpleDateFormat(sameDay ? "HH:mm" : "dd.MM HH:mm", Locale.getDefault()).format(new Date(when));
        }
    }

    static Content current(Context context) {
        String manualMode = SecureStore.MANUAL_AUTO;
        long manualUntil = 0;
        boolean automation = true, protectedMode = false, hasProfile = true;
        try {
            SecureStore store = new SecureStore(context);
            manualMode = store.manualMode;
            manualUntil = store.manualUntil;
            automation = store.automation;
            protectedMode = store.protectedMode;
            hasProfile = store.firstConnectionProfile() != null;
        } catch (Exception ignored) {
            // Settings unreadable for a moment: still show the status, without actions.
            hasProfile = false;
        }
        return Content.of(StatusStore.state(context), StatusStore.detail(context), manualMode, manualUntil,
                automation, protectedMode, hasProfile, System.currentTimeMillis());
    }

    static void ensureChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Статус VPN", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Постоянная карточка AutoTurn: состояние VPN, режим и быстрые действия.");
        channel.setShowBadge(false);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static Notification build(Context context) {
        return build(context, current(context));
    }

    static Notification build(Context context, Content content) {
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_autoturn)
                .setColor(TerminalUi.NOTIFICATION_COLOR)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setSubText(content.mode)
                .setStyle(new Notification.BigTextStyle().bigText(content.text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(PendingIntent.getActivity(context, 300, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (android.os.Build.VERSION.SDK_INT >= 31)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Icon icon = Icon.createWithResource(context, R.drawable.ic_stat_autoturn);
        if (content.showToggle) {
            PendingIntent toggle = action(context, ACTION_TOGGLE, 301);
            try {
                if (content.toggleTurnsOn && VpnTileService.needsApp(context, new SecureStore(context)))
                    toggle = PendingIntent.getActivity(context, 303, VpnTileService.manualOnIntent(context),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } catch (Exception ignored) { /* Keep the broadcast; it reports errors itself. */ }
            builder.addAction(new Notification.Action.Builder(icon, content.actions.get(0), toggle).build());
        }
        if (content.returnAuto)
            builder.addAction(new Notification.Action.Builder(icon, "Вернуть Авто",
                    action(context, ACTION_AUTO, 302)).build());
        return builder.build();
    }

    private static PendingIntent action(Context context, String action, int code) {
        Intent intent = new Intent(context, NotificationActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** True while our card is on screen; a stopped tunnel service may have taken it down. */
    static boolean visible(Context context) {
        try {
            for (StatusBarNotification shown : context.getSystemService(NotificationManager.class).getActiveNotifications())
                if (shown.getId() == ID) return true;
        } catch (RuntimeException ignored) { }
        return false;
    }
}
