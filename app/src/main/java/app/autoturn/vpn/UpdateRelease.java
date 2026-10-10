package app.autoturn.vpn;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;

/** Stable release metadata. The APK's Android signature is the final trust anchor. */
final class UpdateRelease {
    static final String REPOSITORY = "cveloy102/autoturn-vpn";
    static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    final long code;
    final String version, notes, url, sha256;
    final long size;

    UpdateRelease(long code, String version, String notes, String url, String sha256, long size) {
        this.code = code; this.version = version; this.notes = notes;
        this.url = url; this.sha256 = sha256; this.size = size;
    }

    static String metadataUrl(JSONObject release) throws Exception {
        if (release.optBoolean("draft") || release.optBoolean("prerelease"))
            throw new IOException("Нужен стабильный релиз");
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if ("update.json".equals(asset.optString("name")))
                return checkedAssetUrl(asset.getString("browser_download_url"));
        }
        return null;
    }

    static UpdateRelease parse(JSONObject release, JSONObject metadata) throws Exception {
        metadataUrl(release);
        long code = metadata.getLong("versionCode");
        String version = metadata.getString("versionName");
        String name = metadata.getString("apkName");
        String hash = metadata.getString("sha256");
        if (code <= 0 || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
                !name.equals("AutoTurn-" + version + ".apk") || !hash.matches("[a-fA-F0-9]{64}") ||
                !release.getString("tag_name").equals("v" + version))
            throw new IOException("Некорректные данные релиза");
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!name.equals(asset.optString("name"))) continue;
            long size = asset.getLong("size");
            if (size <= 0 || size > AppUpdater.MAX_APK_BYTES)
                throw new IOException("Некорректный размер APK");
            String url = checkedAssetUrl(asset.getString("browser_download_url"));
            if (!url.equals("https://github.com/" + REPOSITORY + "/releases/download/v" + version + "/" + name))
                throw new IOException("Неверный адрес APK");
            String notes = release.optString("body", "");
            if (notes.length() > 12000) notes = notes.substring(0, 12000);
            return new UpdateRelease(code, version, notes, url, hash.toLowerCase(java.util.Locale.ROOT), size);
        }
        throw new IOException("APK отсутствует в релизе");
    }

    static String checkedAssetUrl(String address) throws Exception {
        URI uri = new URI(address);
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) ||
                uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null ||
                uri.getFragment() != null || !uri.getRawPath().startsWith("/" + REPOSITORY + "/releases/download/") ||
                uri.getRawPath().contains("..") || uri.getRawPath().contains("%"))
            throw new IOException("Недопустимый адрес релиза");
        return address;
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("code", code).put("version", version).put("notes", notes)
                .put("url", url).put("sha256", sha256).put("size", size);
    }

    static UpdateRelease restore(String json) throws Exception {
        JSONObject data = new JSONObject(json);
        String hash = data.getString("sha256");
        long size = data.getLong("size");
        if (!hash.matches("[a-f0-9]{64}") || size <= 0 || size > AppUpdater.MAX_APK_BYTES)
            throw new IOException("Повреждены данные обновления");
        return new UpdateRelease(data.getLong("code"), data.getString("version"), data.optString("notes"),
                checkedAssetUrl(data.getString("url")), hash, size);
    }
}
