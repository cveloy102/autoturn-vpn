package app.autoturn.vpn;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

final class Hysteria2Config {
    private Hysteria2Config() { }

    /**
     * True when a Hysteria 2 link turns off the server certificate check (insecure=1)
     * and gives no certificate fingerprint (pinSHA256) to check instead.
     * Plain string parsing so it runs in local unit tests without Android.
     */
    static boolean skipsCertificateCheck(String link) {
        if (link == null) return false;
        String value = link.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("hy2://") && !lower.startsWith("hysteria2://")) return false;
        int start = value.indexOf('?');
        if (start < 0) return false;
        int end = value.indexOf('#', start);
        String query = end < 0 ? value.substring(start + 1) : value.substring(start + 1, end);
        boolean insecure = false, pinned = false;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? "" : pair.substring(eq + 1);
            if ("insecure".equals(key)) insecure = "1".equals(val);
            if ("pinSHA256".equals(key) && !val.isEmpty()) pinned = true;
        }
        return insecure && !pinned;
    }

    static JSONObject outbound(String link) throws Exception {
        Uri uri = Uri.parse(link.trim());
        String scheme = uri.getScheme();
        if (!"hy2".equalsIgnoreCase(scheme) && !"hysteria2".equalsIgnoreCase(scheme))
            throw new IllegalArgumentException("Нужна ссылка Hysteria 2");
        String host = uri.getHost();
        String auth = uri.getUserInfo();
        int port = uri.getPort();
        if (host == null || host.isEmpty() || auth == null || auth.isEmpty() ||
                port == 0 || port > 65535)
            throw new IllegalArgumentException("В ссылке Hysteria 2 нет адреса или ключа");
        if (port < 0) port = 443;

        String obfs = uri.getQueryParameter("obfs");
        String obfsPassword = uri.getQueryParameter("obfs-password");
        if (obfs != null && !obfs.isEmpty() && !"salamander".equalsIgnoreCase(obfs))
            throw new IllegalArgumentException("Поддерживается обфускация Salamander");
        if ("salamander".equalsIgnoreCase(obfs) &&
                (obfsPassword == null || obfsPassword.isEmpty()))
            throw new IllegalArgumentException("Для Salamander нужен пароль обфускации");
        if (uri.getQueryParameter("ech") != null)
            throw new IllegalArgumentException("Ссылка с ECH пока не поддерживается");

        String sni = uri.getQueryParameter("sni");
        JSONObject tls = new JSONObject()
                .put("serverName", sni == null || sni.isEmpty() ? host : sni)
                .put("alpn", new JSONArray().put("h3"));
        String insecure = uri.getQueryParameter("insecure");
        if (insecure != null) {
            if (!"0".equals(insecure) && !"1".equals(insecure))
                throw new IllegalArgumentException("Некорректная настройка TLS");
            if ("1".equals(insecure)) tls.put("allowInsecure", true);
        }
        String pin = uri.getQueryParameter("pinSHA256");
        if (pin != null && !pin.isEmpty()) tls.put("pinnedPeerCertSha256", pin);

        JSONObject stream = new JSONObject()
                .put("network", "hysteria")
                .put("security", "tls")
                .put("tlsSettings", tls)
                .put("hysteriaSettings", new JSONObject().put("version", 2).put("auth", auth));
        if ("salamander".equalsIgnoreCase(obfs)) {
            JSONObject mask = new JSONObject().put("type", "salamander")
                    .put("settings", new JSONObject().put("password", obfsPassword));
            stream.put("finalmask", new JSONObject().put("udp", new JSONArray().put(mask)));
        }
        return new JSONObject().put("protocol", "hysteria")
                .put("settings", new JSONObject().put("version", 2)
                        .put("address", host).put("port", port))
                .put("streamSettings", stream);
    }
}
