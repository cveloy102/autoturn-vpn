package app.autoturn.vpn;

import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.net.VpnService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class VpnTileService extends TileService {
    private boolean receiverRegistered;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateTile(); }
    };

    @Override public void onStartListening() {
        super.onStartListening();
        if (!receiverRegistered) {
            registerReceiver(statusReceiver, new IntentFilter(StatusStore.ACTION_CHANGED), RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        updateTile();
    }

    @Override public void onStopListening() {
        stopStatusListening();
        super.onStopListening();
    }

    @Override public void onDestroy() {
        stopStatusListening();
        super.onDestroy();
    }

    private void stopStatusListening() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
    }

    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::toggleVpn);
        else toggleVpn();
    }

    private void toggleVpn() {
        try {
            SecureStore store = new SecureStore(this);
            if (SecureStore.expireManualMode(this, System.currentTimeMillis())) store = new SecureStore(this);
            String next = nextManualMode(store.manualMode, StatusStore.state(this));
            if (SecureStore.MANUAL_ON.equals(next) &&
                    (store.activeProfile() == null || VpnService.prepare(this) != null)) {
                Intent open = new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_MANUAL_ON, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                openApp(open);
                return;
            }
            store.setControlMode(next);
            AutoReturnReceiver.sync(this);
            startForegroundService(new Intent(this, AutomationService.class)
                    .setAction(AutomationService.ACTION_REFRESH));
            updateTile(SecureStore.MANUAL_ON.equals(next));
        } catch (Exception e) {
            StatusStore.set(this, StatusStore.ERROR, "Не удалось переключить VPN. Откройте AutoTurn.");
            updateTile();
        }
    }

    static String nextManualMode(String mode, String state) {
        // Saved commands take precedence while the VPN service is still catching up.
        if (SecureStore.MANUAL_OFF.equals(mode)) return SecureStore.MANUAL_ON;
        if (SecureStore.MANUAL_ON.equals(mode))
            return SecureStore.MANUAL_OFF;
        return StatusStore.ON.equals(state) || StatusStore.CONNECTING.equals(state) ?
                SecureStore.MANUAL_OFF : SecureStore.MANUAL_ON;
    }

    // The Intent overload is used only on Android 10–13; Android 14+ uses PendingIntent.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openApp(Intent intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pending);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void updateTile() {
        updateTile(null);
    }

    private void updateTile(Boolean pendingOn) {
        Tile tile = getQsTile();
        if (tile == null) return;
        String state = StatusStore.state(this);
        boolean automation = false;
        String manualMode = SecureStore.MANUAL_AUTO;
        Profile profile = null;
        try {
            SecureStore store = new SecureStore(this);
            automation = store.automation;
            manualMode = store.manualMode;
            profile = store.activeProfile();
        } catch (Exception ignored) { }
        String mode = SecureStore.MANUAL_AUTO.equals(manualMode) ?
                "Авто" : "Ручной";
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_vpn_tile));
        String connection = profile == null ? "VPN" : Profile.HYSTERIA2.equals(profile.type) ?
                "Hysteria 2" : Profile.AMNEZIA.equals(profile.type) ? "AmneziaWG" : "VLESS";
        // HyperOS hides the subtitle on small tiles. Keep the connection in the visible label.
        tile.setLabel("AutoTurn\n" + connection);
        boolean active = !SecureStore.MANUAL_OFF.equals(manualMode) &&
                (StatusStore.ON.equals(state) || StatusStore.CONNECTING.equals(state));
        if (pendingOn != null) active = pendingOn;
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        String description = pendingOn != null ? mode + (pendingOn ? " · подключается" : " · выключается") :
                StatusStore.ERROR.equals(state) ? mode + " · ошибка" :
                StatusStore.CONNECTING.equals(state) ? mode + " · подключается" :
                StatusStore.ON.equals(state) ? mode + " · VPN включён" :
                !automation && SecureStore.MANUAL_AUTO.equals(manualMode) ?
                        "Автоматика выключена" : mode + " · VPN выключен";
        tile.setSubtitle(profile == null ? description : profile.name + " · " + description);
        tile.setContentDescription("AutoTurn VPN · " + connection +
                (profile == null ? "" : " · " + profile.name) + " · " + description);
        if (Build.VERSION.SDK_INT >= 30) tile.setStateDescription(description);
        tile.updateTile();
    }

    static void refresh(android.content.Context context) {
        try {
            requestListeningState(context, new ComponentName(context, VpnTileService.class));
        } catch (RuntimeException ignored) {
            // The tile may not have been added to Quick Settings yet.
        }
    }
}
