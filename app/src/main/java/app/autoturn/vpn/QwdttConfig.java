package app.autoturn.vpn;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict parser; parsing never contacts the server or VK. */
final class QwdttConfig {
    final String name, peer, hashes, password;
    final int port, workers;

    private QwdttConfig(String name, String peer, String hashes, String password, int port, int workers) {
        this.name = name; this.peer = peer; this.hashes = hashes; this.password = password;
        this.port = port; this.workers = workers;
    }

    static QwdttConfig parse(String source) throws Exception {
        if (source == null || source.length() > 16384 || source.matches("(?s).*[\\r\\n\\x00].*"))
            throw invalid("Некорректная ссылка");
        URI uri = new URI(source);
        if (!"qwdtt".equals(uri.getScheme()) || !"config".equals(uri.getHost()) ||
                uri.getUserInfo() != null || uri.getPort() != -1 ||
                !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw invalid("Ожидается qwdtt://config?…");
        if (uri.getFragment() != null) throw invalid("Некорректный формат ссылки");
        Map<String, String> query = new LinkedHashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) for (String item : rawQuery.split("&", -1)) {
            String[] pair = item.split("=", 2);
            String key = URLDecoder.decode(pair[0], "UTF-8");
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], "UTF-8") : "";
            if (key.isEmpty() || query.containsKey(key) || value.matches("(?s).*[\\r\\n\\x00].*"))
                throw invalid("Повтор или повреждение параметра");
            query.put(key, value);
        }
        String peer = required(query, "peer");
        URI endpoint = new URI("udp://" + peer);
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null ||
                !(endpoint.getPath() == null || endpoint.getPath().isEmpty()) ||
                endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw invalid("Некорректный адрес сервера");
        int remotePort = endpoint.getPort();
        if (remotePort == -1) {
            remotePort = number(query, "dtls_port", 56000, 1, 65535);
            peer += ":" + remotePort;
        }
        if (remotePort < 1 || remotePort > 65535) throw invalid("Некорректный порт сервера");
        String hashes = required(query, "hashes");
        String[] items = hashes.split(",", -1);
        if (items.length > 4) throw invalid("Допускается до четырёх VK-хешей");
        HashSet<String> seen = new HashSet<>();
        for (String hash : items)
            if (!hash.matches("[A-Za-z0-9_-]{8,512}") || !seen.add(hash))
                throw invalid("Некорректный или повторяющийся VK-хеш");
        String password = required(query, "pass");
        if (password.length() > 256) throw invalid("Слишком длинный пароль");
        String name = query.get("name");
        return new QwdttConfig(name == null ? "qWDTT" : name, peer, hashes, password,
                number(query, "port", 9000, 1024, 65535), number(query, "workers", 9, 1, 108));
    }

    private static String required(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isEmpty() || value.matches("(?s).*[\\r\\n\\x00].*"))
            throw invalid("Отсутствует или повреждён параметр " + key);
        return value;
    }

    private static int number(Map<String, String> query, String key, int fallback, int min, int max) {
        String value = query.get(key);
        if (value == null) return fallback;
        try {
            if (!value.matches("[0-9]{1,5}")) throw new NumberFormatException();
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) { throw invalid("Некорректный параметр " + key); }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("qWDTT: " + message);
    }
}
