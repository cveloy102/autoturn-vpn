package app.autoturn.vpn;

import android.net.Uri;

import org.amnezia.awg.config.Config;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Locale;

final class ProfileImporter {
    private ProfileImporter() {}

    static Profile parse(String nameHint, String input) throws Exception {
        String value = input.trim();
        if (value.length() > 1024 * 1024) throw new IllegalArgumentException("Файл слишком большой");
        if (value.startsWith("vless://")) return parseVless(value);
        if (value.startsWith("hysteria2://") || value.startsWith("hy2://"))
            return parseHysteria2(value);
        if (value.startsWith("{")) return parseXrayJson(nameHint, value);
        if (value.contains("[Interface]") && value.contains("[Peer]")) {
            Config.parse(new BufferedReader(new StringReader(value)));
            return new Profile(cleanName(nameHint, "AmneziaWG"), Profile.AMNEZIA, value);
        }
        throw new IllegalArgumentException("Нужна ссылка VLESS/Hysteria 2, Xray JSON или AmneziaWG .conf");
    }

    private static Profile parseVless(String value) {
        Uri uri = Uri.parse(value);
        if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getPort() < 1 ||
                uri.getUserInfo() == null || uri.getUserInfo().isEmpty()) {
            throw new IllegalArgumentException("В ссылке VLESS нет адреса, порта или ID");
        }
        if (!"reality".equalsIgnoreCase(uri.getQueryParameter("security")))
            throw new IllegalArgumentException("Поддерживается VLESS + REALITY");
        String transport = uri.getQueryParameter("type");
        if (transport != null && !"tcp".equalsIgnoreCase(transport))
            throw new IllegalArgumentException("Пока поддерживается транспорт TCP");
        String label = uri.getFragment();
        return new Profile(cleanName(label, "Reality"), Profile.VLESS, value);
    }

    private static Profile parseHysteria2(String value) {
        Uri uri = Uri.parse(value);
        try { Hysteria2Config.outbound(value); }
        catch (Exception e) { throw new IllegalArgumentException(e.getMessage()); }
        return new Profile(cleanName(uri.getFragment(), "Hysteria 2"), Profile.HYSTERIA2, value);
    }

    private static Profile parseXrayJson(String nameHint, String value) throws Exception {
        JSONObject json = new JSONObject(value);
        JSONArray outbounds = json.optJSONArray("outbounds");
        if (outbounds == null) throw new IllegalArgumentException("В JSON нет раздела outbounds");
        String type = null;
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject outbound = outbounds.getJSONObject(i);
            JSONObject stream = outbound.optJSONObject("streamSettings");
            if (stream == null) continue;
            if ("vless".equalsIgnoreCase(outbound.optString("protocol")) &&
                    "reality".equalsIgnoreCase(stream.optString("security"))) type = Profile.VLESS;
            if ("hysteria".equalsIgnoreCase(outbound.optString("protocol")) &&
                    "hysteria".equalsIgnoreCase(stream.optString("network")) &&
                    "tls".equalsIgnoreCase(stream.optString("security")) &&
                    outbound.optJSONObject("settings") != null &&
                    outbound.optJSONObject("settings").optInt("version") == 2) type = Profile.HYSTERIA2;
            if (type != null) break;
        }
        if (type == null) throw new IllegalArgumentException("В JSON нет VLESS + REALITY или Hysteria 2");
        return new Profile(cleanName(nameHint, Profile.HYSTERIA2.equals(type) ? "Hysteria 2 JSON" : "Reality JSON"), type, value);
    }

    private static String cleanName(String hint, String fallback) {
        String name = hint == null ? "" : hint.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".conf") || name.toLowerCase(Locale.ROOT).endsWith(".txt") ||
                name.toLowerCase(Locale.ROOT).endsWith(".json")) name = name.substring(0, name.lastIndexOf('.'));
        if (name.isEmpty()) name = fallback;
        return name.length() > 40 ? name.substring(0, 40) : name;
    }
}
