package app.autoturn.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests actual HTTPS traffic on Android's VPN network, never the direct connection. */
final class ConnectionProbe {
    enum Result { HEALTHY, FAILED, VPN_MISSING, NO_NETWORK, VPN_PERMISSION_LOST }

    private static final String[] URLS = {
            "https://example.com/",
            "https://www.wikipedia.org/"
    };

    private ConnectionProbe() { }

    static Result check(Context context, ConnectivityManager connectivity) {
        if (VpnService.prepare(context) != null) return Result.VPN_PERMISSION_LOST;
        Network vpn = null;
        boolean hasUnderlying = false;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null) continue;
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpn = network;
            } else if (isUnderlyingInternet(capabilities)) {
                hasUnderlying = true;
            }
        }
        if (vpn == null) return classify(false, false, hasUnderlying);
        Network activeVpn = vpn;
        CountDownLatch settled = new CountDownLatch(1);
        AtomicBoolean healthy = new AtomicBoolean();
        AtomicInteger pending = new AtomicInteger(URLS.length);
        for (String address : URLS) {
            Thread probe = new Thread(() -> {
                if (canReach(activeVpn, address)) healthy.set(true);
                if (pending.decrementAndGet() == 0 || healthy.get()) settled.countDown();
            }, "vpn-probe");
            probe.setDaemon(true);
            probe.start();
        }
        try { settled.await(5500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (healthy.get()) return Result.HEALTHY;
        // Re-read after the request: mobile/Wi-Fi handoffs can change the network meanwhile.
        hasUnderlying = false;
        for (Network network : connectivity.getAllNetworks()) {
            if (isUnderlyingInternet(connectivity.getNetworkCapabilities(network))) {
                hasUnderlying = true;
                break;
            }
        }
        return classify(false, true, hasUnderlying);
    }

    static boolean isUnderlyingInternet(NetworkCapabilities capabilities) {
        // VALIDATED describes Android's last probe, not whether our VPN can pass traffic.
        return capabilities != null && isUnderlyingInternet(
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
    }

    static boolean isUnderlyingInternet(boolean vpn, boolean internet) {
        return !vpn && internet;
    }

    static Result classify(boolean healthy, boolean vpnPresent, boolean hasUnderlying) {
        if (healthy) return Result.HEALTHY;
        if (!hasUnderlying) return Result.NO_NETWORK;
        return vpnPresent ? Result.FAILED : Result.VPN_MISSING;
    }

    private static boolean canReach(Network vpn, String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(new URL(address));
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
