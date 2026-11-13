package app.autoturn.vpn;

/** Runs under the separate test APK UID, so split-routing is tested with real app traffic. */
public final class RoutingProbeActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("healthAudit", false)) {
            new Thread(this::auditHealthEndpoints, "health-endpoint-audit").start();
            return;
        }
        if (getIntent().getBooleanExtra("wake", false)) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        if (getIntent().getBooleanExtra("barrierWatch", false)) {
            new Thread(this::watchBarrier, "barrier-watch").start();
            return;
        }
        new Thread(() -> {
            org.json.JSONObject result = new org.json.JSONObject();
            try {
                android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
                android.net.Network active = cm.getActiveNetwork();
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                result.put("vpn", caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN));
                if (getIntent().getBooleanExtra("udpDns", false)) {
                    // A fresh wire query checks UDP independently, even when the subsequent HTTPS fails.
                    try (java.net.DatagramSocket dns = new java.net.DatagramSocket()) {
                        dns.setSoTimeout(3000);
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream wire = new java.io.DataOutputStream(bytes);
                        int id = new java.security.SecureRandom().nextInt(65536);
                        wire.writeShort(id); wire.writeShort(0x0100); wire.writeShort(1);
                        wire.writeShort(0); wire.writeShort(0); wire.writeShort(0);
                        for (String label : ("audit-" + java.util.UUID.randomUUID().toString().replace("-", "") +
                                ".example.com").split("\\.")) {
                            wire.writeByte(label.length()); wire.writeBytes(label);
                        }
                        wire.writeByte(0); wire.writeShort(1); wire.writeShort(1);
                        byte[] packet = bytes.toByteArray();
                        dns.connect(java.net.InetAddress.getByAddress(new byte[] {1,1,1,1}), 53);
                        dns.send(new java.net.DatagramPacket(packet, packet.length));
                        java.net.DatagramPacket reply = new java.net.DatagramPacket(new byte[2048], 2048);
                        dns.receive(reply);
                        byte[] body = reply.getData();
                        result.put("dnsPacketReply", reply.getLength() >= 12 && (body[2] & 0x80) != 0 &&
                                (((body[0] & 255) << 8) | (body[1] & 255)) == id);
                    } catch (Exception e) { result.put("dnsPacketReply", false); }
                }
                if (getIntent().getBooleanExtra("blocked", false)) {
                    result.put("tlsExternal", false);
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(
                                new byte[] {1,1,1,1}), 443), 3500);
                        socket.setSoTimeout(3500);
                        javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                        try (javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) factory.createSocket(socket, "one.one.one.one", 443, false)) {
                            tls.setSoTimeout(3500);
                            javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
                            parameters.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(parameters);
                            tls.startHandshake(); result.put("tlsExternal", true);
                        }
                    }
                } else {
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

    private void auditHealthEndpoints() {
        org.json.JSONObject result=new org.json.JSONObject();
        try {
            android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);
            android.net.Network vpn=null;
            for(android.net.Network n:cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                if(caps!=null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)){vpn=n;break;}
            }
            result.put("vpn",vpn!=null).put("network",String.valueOf(vpn));
            org.json.JSONArray checks=new org.json.JSONArray();result.put("checks",checks);
            for(String address:new String[]{"https://example.com/","https://www.wikipedia.org/",
                    "https://www.cloudflare.com/cdn-cgi/trace","https://www.gstatic.com/generate_204",
                    "https://1.1.1.1/cdn-cgi/trace"}) {
                org.json.JSONObject check=new org.json.JSONObject().put("host",new java.net.URL(address).getHost());
                checks.put(check);long started=android.os.SystemClock.elapsedRealtime();
                java.net.HttpURLConnection connection=null;
                try {
                    if(vpn==null)throw new java.io.IOException("No VPN");
                    connection=(java.net.HttpURLConnection)vpn.openConnection(new java.net.URL(address));
                    connection.setConnectTimeout(2500);connection.setReadTimeout(2500);
                    connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Connection","close");
                    check.put("http",connection.getResponseCode());
                } catch(Exception e){check.put("error",e.getClass().getSimpleName());}
                finally {if(connection!=null)connection.disconnect();}
                check.put("ms",android.os.SystemClock.elapsedRealtime()-started);
            }
        } catch(Exception e){try{result.put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}
        try {java.nio.file.Files.write(new java.io.File(getFilesDir(),"health-endpoints.json").toPath(),
                result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        runOnUiThread(this::finish);
    }

    private void watchBarrier() {
        int attempts = 0, connected = 0, dnsReplies = 0, dnsErrors = 0, ipv6 = 0;
        int requestedTimeout = getIntent().getIntExtra("probeTimeoutMillis", 0);
        int mobileTimeout = Math.max(100, Math.min(3000, requestedTimeout));
        int tcpTimeout = requestedTimeout > 0 ? mobileTimeout : 350;
        int dnsTimeout = requestedTimeout > 0 ? mobileTimeout : 150;
        int ipv6Timeout = requestedTimeout > 0 ? mobileTimeout : 150;
        long until = android.os.SystemClock.elapsedRealtime() +
                Math.max(1000, Math.min(60000, getIntent().getIntExtra("watchMillis", 45000)));
        while (android.os.SystemClock.elapsedRealtime() < until) {
            attempts++;
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(
                        new byte[] {1,1,1,1}), 443), tcpTimeout);
                socket.setSoTimeout(tcpTimeout);
                javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                try (javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) factory.createSocket(socket, "one.one.one.one", 443, false)) {
                    tls.setSoTimeout(tcpTimeout);
                    javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(parameters);
                    // gVisor may accept TCP locally even when the outbound is down.
                    // Only a verified external TLS handshake proves traffic escaped.
                    tls.startHandshake(); connected++;
                }
            } catch (Exception ignored) { }
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                socket.setSoTimeout(dnsTimeout);
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream wire = new java.io.DataOutputStream(bytes);
                int id = attempts & 65535;
                wire.writeShort(id); wire.writeShort(0x0100); wire.writeShort(1);
                wire.writeShort(0); wire.writeShort(0); wire.writeShort(0);
                for (String label : ("a" + java.util.UUID.randomUUID().toString().replace("-", "") +
                        ".example.com").split("\\.")) {
                    wire.writeByte(label.length()); wire.writeBytes(label);
                }
                wire.writeByte(0); wire.writeShort(1); wire.writeShort(1);
                byte[] query = bytes.toByteArray();
                socket.connect(java.net.InetAddress.getByAddress(new byte[] {1,1,1,1}), 53);
                socket.send(new java.net.DatagramPacket(query, query.length));
                java.net.DatagramPacket answer = new java.net.DatagramPacket(new byte[2048], 2048);
                socket.receive(answer);
                byte[] body = answer.getData();
                if (answer.getLength() >= 12 && (body[2] & 0x80) != 0 &&
                        (((body[0] & 255) << 8) | (body[1] & 255)) == id) {
                    int rcode = body[3] & 15;
                    if (rcode == 0 || rcode == 3) dnsReplies++;
                    else dnsErrors++; // Local SERVFAIL/REFUSED is not an upstream DNS answer.
                }
            } catch (Exception ignored) { }
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("2606:4700:4700::1111", 443), ipv6Timeout);
                ipv6++;
            } catch (Exception ignored) { }
            try {
                org.json.JSONObject result = new org.json.JSONObject().put("attempts", attempts)
                        .put("tlsConnected", connected).put("dnsReplies", dnsReplies).put("dnsErrors", dnsErrors).put("ipv6Connected", ipv6)
                        .put("complete", android.os.SystemClock.elapsedRealtime() >= until);
                java.nio.file.Files.write(new java.io.File(getFilesDir(), "barrier-watch.json").toPath(),
                        result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        try {
            org.json.JSONObject result = new org.json.JSONObject().put("attempts", attempts)
                    .put("tlsConnected", connected).put("dnsReplies", dnsReplies).put("dnsErrors", dnsErrors).put("ipv6Connected", ipv6)
                    .put("complete", true);
            java.nio.file.Files.write(new java.io.File(getFilesDir(), "barrier-watch.json").toPath(),
                    result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        runOnUiThread(this::finish);
    }
}
