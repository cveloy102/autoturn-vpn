package app.autoturn.vpn;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

final class Hysteria2Config {
    private Hysteria2Config() { }

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
