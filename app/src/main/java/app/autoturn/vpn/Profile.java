package app.autoturn.vpn;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class Profile {
    static final String VLESS = "vless";
    static final String HYSTERIA2 = "hysteria2";
    static final String AMNEZIA = "amneziawg";
    static final String QWDTT = "qwdtt";

    final String id;
    final String name;
    final String type;
    final String config;
    final boolean lastResort;

    Profile(String name, String type, String config) {
        this(UUID.randomUUID().toString(), name, type, config);
    }

    Profile(String id, String name, String type, String config) {
        this(id, name, type, config, QWDTT.equals(type));
    }

    Profile(String id, String name, String type, String config, boolean lastResort) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.config = config;
        this.lastResort = lastResort;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("type", type);
        json.put("config", config);
        json.put("lastResort", lastResort);
        return json;
    }

    static Profile fromJson(JSONObject json) throws JSONException {
        return new Profile(json.getString("id"), json.getString("name"),
                json.getString("type"), json.getString("config"), json.optBoolean("lastResort", false));
    }
}
