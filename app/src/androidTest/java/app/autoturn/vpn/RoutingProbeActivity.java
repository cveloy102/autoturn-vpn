package app.autoturn.vpn;

/** Runs under the separate test APK UID, so split-routing is tested with real app traffic. */
public final class RoutingProbeActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            org.json.JSONObject result = new org.json.JSONObject();
            try {
                android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
                android.net.Network active = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                result.put("vpn", caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN));
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                        new java.net.URL("https://example.com/").openConnection();
                try {
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(6000);
                    connection.setUseCaches(false);
                    result.put("http", connection.getResponseCode());
                } finally { connection.disconnect(); }
            } catch (Exception e) {
                try { result.put("error", e.getClass().getSimpleName()); } catch (Exception ignored) { }
            }
            try {
                java.nio.file.Files.write(new java.io.File(getFilesDir(), "routing-probe.json").toPath(),
                        result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            runOnUiThread(this::finish);
        }, "routing-probe").start();
    }
}
