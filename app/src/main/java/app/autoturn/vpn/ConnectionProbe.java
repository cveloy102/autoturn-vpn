package app.autoturn.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.telephony.TelephonyManager;

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
            "https://www.wikipedia.org/",
            "https://www.gstatic.com/generate_204",
            // Confirm the data path independently of Android's DNS cache/resolver.
            // HTTPS still validates the destination certificate, including its IP identity.
            "https://1.1.1.1/cdn-cgi/trace"
    };

    private ConnectionProbe() { }

    static Result checkQwdtt(Context context, ConnectivityManager connectivity, String profileId) {
        return checkQwdtt(context, connectivity, profileId, false);
    }

    static Result checkQwdtt(Context context, ConnectivityManager connectivity, String profileId, boolean confirmation) {
        if (VpnService.prepare(context) != null) return Result.VPN_PERMISSION_LOST;
        boolean vpn = false, underlying = false;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
            if (isUnderlyingInternet(context, connectivity, network)) underlying = true;
        }
        if (!underlying) return Result.NO_NETWORK;
        if (!vpn) return Result.VPN_MISSING;
        org.json.JSONObject state = QwdttState.read(context);
        if (!profileId.equals(state.optString("profileId")) || !"active".equals(state.optString("phase")))
            return Result.FAILED;
        // AutoTurn's UID is excluded to keep the external TURN process outside its own TUN.
        // Authenticate to Xray's probe inbound, which uses the same qWDTT outbound as the TUN.
        // A successful request through the physical network alone cannot pass this check.
        boolean healthy = probeAny(address -> canReachQwdtt(state, address, confirmation ? 8000 : 2500), confirmation);
        if (healthy) return Result.HEALTHY;
        boolean physical = false;
        for (Network network : connectivity.getAllNetworks())
            if (isUnderlyingInternet(context, connectivity, network)) { physical = true; break; }
        return classify(false, true, physical);
    }

    private static boolean canReachQwdtt(org.json.JSONObject state, String address, int timeout) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", state.getInt("port")), 1500);
            socket.setSoTimeout(timeout);
            java.io.DataInputStream input = new java.io.DataInputStream(socket.getInputStream());
            java.io.OutputStream output = socket.getOutputStream();
            output.write(new byte[]{5, 1, 2}); output.flush();
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) return false;
            byte[] user = state.getString("user").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] pass = state.getString("password").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            output.write(1); output.write(user.length); output.write(user);
            output.write(pass.length); output.write(pass); output.flush();
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) return false;
            URL url = new URL(address);
            byte[] host = url.getHost().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            output.write(new byte[]{5, 1, 0, 3}); output.write(host.length); output.write(host);
            output.write(new byte[]{1, (byte) 187}); output.flush(); // TCP 443
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0)
                return false;
            int type = input.readUnsignedByte();
            int length = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? input.readUnsignedByte() : -1;
            if (length < 0) return false;
            byte[] bound = new byte[length + 2]; input.readFully(bound);
            javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            try (javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) factory.createSocket(socket, url.getHost(), 443, false)) {
                tls.setSoTimeout(timeout);
                javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                tls.getOutputStream().write(("HEAD / HTTP/1.1\r\nHost: " + url.getHost() +
                        "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                tls.getOutputStream().flush();
                StringBuilder status = new StringBuilder();
                for (int i = 0; i < 256; i++) {
                    int b = tls.getInputStream().read();
                    if (b == -1 || b == '\n') break;
                    status.append((char) b);
                }
                return successfulHttpStatus(status.toString());
            }
        } catch (Exception e) {
            android.util.Log.d("AutoTurnProbe", address + " via qWDTT: " + e.getClass().getSimpleName());
            return false;
        }
    }

    static boolean successfulHttpStatus(String line) {
        // An authenticated HTTPS response proves the tunnel carried traffic even if
        // this particular site rate-limits an exit IP or has a server error.
        return line != null && line.trim().matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?");
    }

    static boolean isHttpResponse(int code) { return code >= 100 && code <= 599; }

    interface EndpointProbe { boolean reach(String address); }

    static boolean probeAny(EndpointProbe probe, boolean confirmation) {
        int count = confirmation ? URLS.length : 2;
        CountDownLatch settled = new CountDownLatch(1);
        AtomicBoolean healthy = new AtomicBoolean();
        AtomicInteger pending = new AtomicInteger(count);
        for (int i = 0; i < count; i++) {
            final String address = URLS[i];
            Thread request = new Thread(() -> {
                try { if (probe.reach(address)) healthy.set(true); }
                finally { if (pending.decrementAndGet() == 0 || healthy.get()) settled.countDown(); }
            }, "vpn-probe");
            request.setDaemon(true); request.start();
        }
        try { settled.await(confirmation ? 16500 : 5500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return healthy.get();
    }

    static Result check(Context context, ConnectivityManager connectivity) {
        return check(context, connectivity, false);
    }

    static Result check(Context context, ConnectivityManager connectivity, boolean confirmation) {
        if (VpnService.prepare(context) != null) return Result.VPN_PERMISSION_LOST;
        Network vpn = null;
        boolean hasUnderlying = false;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null) continue;
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpn = network;
            } else if (isUnderlyingInternet(context, connectivity, network)) {
                hasUnderlying = true;
            }
        }
        if (vpn == null) return classify(false, false, hasUnderlying);
        Network activeVpn = vpn;
        if (probeAny(address -> canReach(activeVpn, address, confirmation ? 8000 : 2500), confirmation))
            return Result.HEALTHY;
        // Re-read after the request: mobile/Wi-Fi handoffs can change the network meanwhile.
        hasUnderlying = false;
        for (Network network : connectivity.getAllNetworks()) {
            if (isUnderlyingInternet(context, connectivity, network)) {
                hasUnderlying = true;
                break;
            }
        }
        return classify(false, true, hasUnderlying);
    }

    static boolean isUnderlyingInternet(Context context, ConnectivityManager connectivity, Network network) {
        return network != null && isUnderlyingInternet(context, connectivity.getNetworkCapabilities(network))
                && hasUsableAddress(connectivity.getLinkProperties(network));
    }

    static boolean hasUsableAddress(LinkProperties properties) {
        if (properties == null) return false;
        // A CONNECTING network can advertise INTERNET before it has an IP configuration.
        for (android.net.LinkAddress link : properties.getLinkAddresses()) {
            if (isUsableAddress(link.getAddress())) return true;
        }
        return false;
    }

    static boolean isUsableAddress(java.net.InetAddress address) {
        return address != null && !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress();
    }

    static boolean isUnderlyingInternet(Context context, NetworkCapabilities capabilities) {
        if (!isUnderlyingInternet(capabilities)) return false;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            // Android may retain an INTERNET cellular network after the user turns data off.
            // It must not consume the profile queue while both physical transports are off.
            try {
                TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
                if (telephony != null && !telephony.isDataEnabled()) return false;
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // Keep the existing capability/probe behavior if the platform cannot expose this setting.
            }
        }
        return true;
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

    private static boolean canReach(Network vpn, String address, int timeout) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) vpn.openConnection(new URL(address));
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode();
            return isHttpResponse(code);
        } catch (Exception e) {
            // Only fixed public probe URLs and exception types; never profile/transport secrets.
            android.util.Log.d("AutoTurnProbe", address + ": " + e.getClass().getSimpleName());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
