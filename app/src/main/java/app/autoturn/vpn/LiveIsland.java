package app.autoturn.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Temporary connection statuses as an Android 16 Live Update. HyperOS and OxygenOS show these
 * in their island; stock Android shows a chip beside the clock. The system draws everything:
 * AutoTurn only hands over text, progress, a timer and one action.
 *
 * Opt-in (Settings → «Статусы в острове»). Only transitions with an end are shown, never the
 * permanent "VPN on" state. Every method runs on AutomationService's worker thread.
 */
final class LiveIsland {
    static final String CHANNEL = "live-status";
    static final int ID = 101;
    // Long enough to read where AutoTurn connected; then the island leaves by itself.
    static final long DONE_VISIBLE_MS = 10_000;
    // Profile names are user text of any length: keep the title and the island chip short.
    static final int TITLE_NAME_MAX = 20;
    static final int CHIP_MAX = 8;
    // Long waits (no network for hours) must not turn into a permanent island.
    static final long WAIT_VISIBLE_MS = 10 * 60_000;
    static final long CAPTCHA_VISIBLE_MS = 2 * 60_000;
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";
    private static final int RED = 0xFFE5453F;
    private static final int GREEN = 0xFF3FA06A;

    enum Availability { READY, NOTIFICATIONS_OFF, CHANNEL_OFF, PROMOTION_OFF, UNSUPPORTED }
    enum Kind { CONNECTING, CAPTCHA, PAUSE, NO_NETWORK, PERMISSION, DONE }

    /** What one island state shows. Pure data, so the wording is unit-tested without Android. */
    static final class Content {
        final Kind kind;
        final String title;
        final String text;
        /** Short text for the collapsed island / status chip; null when a countdown shows instead. */
        final String chip;
        /** One entry per profile: true = this profile already failed in the current attempt. */
        final List<Boolean> failedSegments;
        /** Index of the profile being tried now, -1 when there is no segmented progress. */
        final int current;
        final boolean indeterminate;
        /** Wall-clock end of a countdown, 0 when there is none. */
        final long countdownTo;
        final long timeoutMs;

        Content(Kind kind, String title, String text, String chip, List<Boolean> failedSegments,
                int current, boolean indeterminate, long countdownTo, long timeoutMs) {
            this.kind = kind; this.title = title; this.text = text; this.chip = chip;
            this.failedSegments = Collections.unmodifiableList(new ArrayList<>(failedSegments));
            this.current = current; this.indeterminate = indeterminate;
            this.countdownTo = countdownTo; this.timeoutMs = timeoutMs;
        }

        /** Same key = nothing visible changed, so the notification is not reposted. */
        String key() {
            return kind + "|" + title + "|" + text + "|" + chip + "|" + failedSegments + "|" + current +
                    "|" + indeterminate + "|" + countdownTo;
        }

        static Content connecting(String profileName, String protocol, List<Boolean> failed, int current) {
            int total = failed.size();
            boolean single = total < 2 || current < 0;
            int failedCount = 0;
            for (boolean f : failed) if (f) failedCount++;
            String text = (single ? "" : "Профиль " + (current + 1) + " из " + total + " · ") + protocol +
                    (failedCount > 0 ? " · не ответили: " + failedCount : "");
            return new Content(Kind.CONNECTING, "Подключаемся · " + shorten(profileName, TITLE_NAME_MAX), text,
                    single ? "VPN" : (current + 1) + "/" + total, failed, single ? -1 : current, single, 0, 0);
        }

        static Content captcha() {
            return new Content(Kind.CAPTCHA, "Нужна капча", "qWDTT · VK просит подтвердить вход",
                    "Капча", Collections.emptyList(), -1, false, 0, CAPTCHA_VISIBLE_MS);
        }

        static Content pause(long retryAt) {
            return new Content(Kind.PAUSE, "Нет связи", "Профили недоступны · повторим сами",
                    null, Collections.emptyList(), -1, false, retryAt, 0);
        }

        static Content noNetwork() {
            return new Content(Kind.NO_NETWORK, "Ждём сеть", "Нет сети · подключимся, когда она появится",
                    "Сеть…", Collections.emptyList(), -1, true, 0, WAIT_VISIBLE_MS);
        }

        static Content permissionLost() {
            return new Content(Kind.PERMISSION, "Нужно разрешение VPN", "Android отозвал разрешение · откройте AutoTurn",
                    "VPN!", Collections.emptyList(), -1, false, 0, WAIT_VISIBLE_MS);
        }

        static Content done(String profileName, String protocol) {
            boolean named = profileName != null && !profileName.trim().isEmpty();
            return new Content(Kind.DONE, named ? "На связи · " + shorten(profileName, TITLE_NAME_MAX) : "На связи",
                    "Доступ проверен" + (protocol == null || protocol.isEmpty() ? "" : " · " + protocol),
                    named ? shorten(profileName, CHIP_MAX) : "Готово", Collections.emptyList(), -1, false, 0, DONE_VISIBLE_MS);
        }

        /** One line, at most max characters (code points), with an ellipsis when cut. */
        static String shorten(String value, int max) {
            String line = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            if (line.codePointCount(0, line.length()) <= max) return line;
            return line.substring(0, line.offsetByCodePoints(0, max - 1)).trim() + "…";
        }
    }

    static String protocol(Profile profile) {
        if (profile == null) return "";
        return Profile.QWDTT.equals(profile.type) ? "qWDTT · VK TURN" : Profile.VLESS.equals(profile.type) ? "VLESS · Reality" :
                Profile.HYSTERIA2.equals(profile.type) ? "Hysteria 2" : "AmneziaWG";
    }

    private final Context context;
    private final NotificationManager manager;
    private boolean enabled;
    private String shownKey = "";
    private Kind shownKind;
    // When the system removes the shown notification by itself (setTimeoutAfter); 0 = never.
    private long shownExpires;

    LiveIsland(Context context) {
        this.context = context;
        this.manager = context.getSystemService(NotificationManager.class);
    }

    static void ensureChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Статусы подключения",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Подключение, капча VK и пауза перед повтором. Видны, пока идёт переход.");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        // Creating an existing channel keeps the user's own changes to it.
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    /** What stops the island from appearing, in the order the user has to fix it. */
    static Availability availability(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return Availability.NOTIFICATIONS_OFF;
        ensureChannel(context);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return Availability.CHANNEL_OFF;
        if (Build.VERSION.SDK_INT < 36) return Availability.UNSUPPORTED;
        if (!manager.canPostPromotedNotifications()) return Availability.PROMOTION_OFF;
        return Availability.READY;
    }

    void setEnabled(boolean on) {
        if (enabled && !on) clear();
        enabled = on;
    }

    /** True while a notification of ours is on screen (not removed by its own timeout). */
    boolean showing() {
        return !shownKey.isEmpty() && (shownExpires == 0 || android.os.SystemClock.elapsedRealtime() < shownExpires);
    }

    void show(Content content) {
        if (!enabled) return;
        String key = content.key();
        if (key.equals(shownKey)) return;
        try {
            ensureChannel(context);
            manager.notify(ID, build(content));
            shownKey = key;
            shownKind = content.kind;
            shownExpires = content.timeoutMs > 0 ? android.os.SystemClock.elapsedRealtime() + content.timeoutMs : 0;
        } catch (RuntimeException ignored) {
            // A notification is never worth breaking the connection logic.
        }
    }

    /** Ends a transition with «На связи»; ignored when nothing was shown (routine health checks). */
    void done(Profile profile) {
        if (!showing() || shownKind == Kind.DONE) return;
        show(Content.done(profile == null ? null : profile.name, protocol(profile)));
    }

    void clear() {
        if (shownKey.isEmpty()) return;
        shownKey = "";
        shownKind = null;
        try { manager.cancel(ID); } catch (RuntimeException ignored) { }
    }

    private Notification build(Content content) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(ring(content))
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setOngoing(true)  // Live Updates must be ongoing; DONE leaves by timeout
                .setOnlyAlertOnce(true)
                .setCategory(content.kind == Kind.CONNECTING ? Notification.CATEGORY_PROGRESS : Notification.CATEGORY_STATUS)
                .setContentIntent(PendingIntent.getActivity(context, 201, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (content.timeoutMs > 0) builder.setTimeoutAfter(content.timeoutMs);
        if (content.countdownTo > 0) {
            builder.setWhen(content.countdownTo).setShowWhen(true)
                    .setUsesChronometer(true).setChronometerCountDown(true);
        } else builder.setShowWhen(false);
        if (content.kind == Kind.PAUSE) {
            Intent retry = new Intent(context, AutomationService.class).setAction(AutomationService.ACTION_RESTART_QUEUE);
            builder.addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_vpn_tile),
                    "Повторить сейчас", PendingIntent.getForegroundService(context, 202, retry,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        } else if (content.kind == Kind.CAPTCHA) {
            builder.addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_vpn_tile),
                    "Открыть капчу", PendingIntent.getActivity(context, 203, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        // Same extra as setRequestPromotedOngoing(), which the SDK only exposes from Android 16 QPR2;
        // earlier Android 16 builds and vendor islands read the extra directly.
        android.os.Bundle promoted = new android.os.Bundle();
        promoted.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        builder.addExtras(promoted);
        if (Build.VERSION.SDK_INT >= 36) {
            if (content.chip != null) builder.setShortCriticalText(content.chip);
            boolean hasProgress = content.kind == Kind.DONE || content.indeterminate || content.current >= 0;
            builder.setStyle(hasProgress ? progress(content) : new Notification.BigTextStyle().bigText(content.text));
        }
        return builder.build();
    }

    /**
     * The island's left icon: the A monogram inside a ring with one segment per profile
     * (failed red, current white, waiting dim), like the mockup. The system may tint it.
     */
    private Icon ring(Content content) {
        try {
            int size = 96;
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(9);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            android.graphics.RectF box = new android.graphics.RectF(6, 6, size - 6, size - 6);
            int dim = 0x59FFFFFF;
            if (content.kind == Kind.CONNECTING && content.current >= 0) {
                int n = content.failedSegments.size();
                float gap = n > 1 ? 22f : 0f, sweep = 360f / n - gap;
                for (int i = 0; i < n; i++) {
                    paint.setColor(content.failedSegments.get(i) ? RED : i == content.current ? 0xFFFFFFFF :
                            i < content.current ? GREEN : dim);
                    canvas.drawArc(box, -90 + i * 360f / n + gap / 2, sweep, false, paint);
                }
            } else if (content.kind == Kind.CONNECTING || content.kind == Kind.NO_NETWORK) {
                paint.setColor(dim);
                canvas.drawArc(box, 0, 360, false, paint);
                paint.setColor(0xFFFFFFFF);
                canvas.drawArc(box, -90, 100, false, paint);
            } else {
                paint.setColor(content.kind == Kind.DONE ? GREEN :
                        content.kind == Kind.CAPTCHA || content.kind == Kind.PERMISSION ? RED : dim);
                canvas.drawArc(box, 0, 360, false, paint);
            }
            android.graphics.drawable.Drawable mark = context.getDrawable(R.drawable.ic_vpn_tile);
            if (mark != null) {
                mark.setBounds(28, 28, size - 28, size - 28);
                mark.draw(canvas);
            }
            return Icon.createWithBitmap(bitmap);
        } catch (RuntimeException e) {
            return Icon.createWithResource(context, R.drawable.ic_vpn_tile);
        }
    }

    @android.annotation.TargetApi(36)
    private static Notification.ProgressStyle progress(Content content) {
        Notification.ProgressStyle style = new Notification.ProgressStyle();
        if (content.kind == Kind.DONE) {
            style.addProgressSegment(new Notification.ProgressStyle.Segment(1).setColor(GREEN)).setProgress(1);
        } else if (content.indeterminate) {
            style.setProgressIndeterminate(true);
        } else if (content.current >= 0) {
            // Two units per profile, so the current profile reads as "halfway through".
            for (boolean failed : content.failedSegments)
                style.addProgressSegment(new Notification.ProgressStyle.Segment(2).setColor(failed ? RED : GREEN));
            style.setProgress(content.current * 2 + 1);
        }
        return style;
    }
}
