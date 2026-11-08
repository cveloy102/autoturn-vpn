package app.autoturn.vpn;

/** Runs under the separate test APK UID, so split-routing is tested with real app traffic. */
public final class RoutingProbeActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("wake", false)) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
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
                if (getIntent().getBooleanExtra("audit", false)) {
                    long started = android.os.SystemClock.elapsedRealtime();
                    java.net.HttpURLConnection ip = (java.net.HttpURLConnection)
                            new java.net.URL("https://api.ipify.org?format=json").openConnection();
                    ip.setConnectTimeout(10000); ip.setReadTimeout(10000); ip.setUseCaches(false);
                    try (java.io.InputStream in = ip.getInputStream()) {
                        result.put("egress", new org.json.JSONObject(new String(in.readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)).getString("ip"));
                    } finally { ip.disconnect(); }
                    java.net.HttpURLConnection payload = (java.net.HttpURLConnection)
                            new java.net.URL("https://speed.cloudflare.com/__down?bytes=262144").openConnection();
                    payload.setConnectTimeout(10000); payload.setReadTimeout(10000); payload.setUseCaches(false);
                    payload.setRequestProperty("Accept-Encoding", "identity");
                    try (java.io.InputStream in = payload.getInputStream()) {
                        byte[] block = new byte[16384]; int count, bytes = 0;
                        while ((count = in.read(block)) != -1) {
                            bytes += count;
                            if (bytes > 1048576) throw new java.io.IOException("Unexpected payload size");
                        }
                        result.put("payloadBytes", bytes);
                    } finally { payload.disconnect(); }
                    result.put("auditMs", android.os.SystemClock.elapsedRealtime() - started);
                }
                if (getIntent().getBooleanExtra("udp", false)) {
                    try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                        socket.setSoTimeout(6000);
                        byte[] request = new byte[20];
                        request[1] = 1; request[4] = 0x21; request[5] = 0x12;
                        request[6] = (byte) 0xa4; request[7] = 0x42;
                        byte[] transaction = new byte[12]; new java.security.SecureRandom().nextBytes(transaction);
                        System.arraycopy(transaction, 0, request, 8, 12);
                        java.net.InetAddress destination = null;
                        for (java.net.InetAddress candidate : java.net.InetAddress.getAllByName("stun.l.google.com"))
                            if (candidate instanceof java.net.Inet4Address) { destination = candidate; break; }
                        if (destination == null) throw new java.io.IOException("No IPv4 STUN address");
                        socket.connect(destination, 19302);
                        socket.send(new java.net.DatagramPacket(request, request.length));
                        byte[] response = new byte[2048];
                        java.net.DatagramPacket packet = new java.net.DatagramPacket(response, response.length);
                        socket.receive(packet);
                        boolean matched = packet.getLength() >= 20 && response[0] == 1 && response[1] == 1;
                        for (int i = 4; i < 20 && matched; i++) matched = response[i] == request[i];
                        result.put("udp", matched);
                    }
                }
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
