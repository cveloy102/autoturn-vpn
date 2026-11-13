package app.autoturn.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Debug APK only: lets the Android shell restore the phone after a connection test. */
public final class DebugControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ("app.autoturn.vpn.DEBUG_NETWORK_SNAPSHOT".equals(intent.getAction())) {
            try {
                android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
                org.json.JSONObject snapshot = new org.json.JSONObject();
                snapshot.put("uid", android.os.Process.myUid()).put("active", String.valueOf(cm.getActiveNetwork()))
                        .put("state", StatusStore.state(context)).put("detail", StatusStore.detail(context));
                SecureStore store = new SecureStore(context);
                snapshot.put("routing", store.appMode).put("protected", store.protectedMode)
                        .put("profileType", store.activeProfile() == null ? "none" : store.activeProfile().type);
                org.json.JSONArray networks = new org.json.JSONArray();
                for (android.net.Network network : cm.getAllNetworks()) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    android.net.LinkProperties links = cm.getLinkProperties(network);
                    org.json.JSONObject item = new org.json.JSONObject().put("network", network.toString());
                    item.put("capsPresent", caps != null).put("linksPresent", links != null)
                            .put("addressCount", links == null ? -1 : links.getLinkAddresses().size())
                            .put("usableAddress", ConnectionProbe.hasUsableAddress(links));
                    if (caps != null) item.put("wifi", caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                            .put("cell", caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))
                            .put("vpn", caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
                            .put("internet", caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))
                            .put("eligibleCaps", ConnectionProbe.isUnderlyingInternet(context, caps));
                    item.put("eligible", ConnectionProbe.isUnderlyingInternet(context, cm, network));
                    networks.put(item);
                }
                snapshot.put("networks", networks);
                java.nio.file.Files.write(new java.io.File(context.getFilesDir(), "network-debug.json").toPath(),
                        snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) { android.util.Log.w("AutoTurnNetwork", "Snapshot failed: " + e.getClass().getSimpleName()); }
            return;
        }
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
