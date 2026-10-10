package app.autoturn.vpn;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStore {
    static final String MANUAL_AUTO = "auto";
    static final String MANUAL_ON = "on";
    static final String MANUAL_OFF = "off";
    static final String THEME_DARK = "dark";
    static final String THEME_LIGHT = "light";
    static final String THEME_SYSTEM = "system";
    private static final String KEY_ALIAS = "autoturn_profiles_v1";
    private static final String FILE_NAME = "settings.enc";
    private static final Object WRITE_LOCK = new Object();
    private final Context context;
    private JSONObject originalState;
    private boolean migrateControl;
    final List<Profile> profiles = new ArrayList<>();
    String activeId = "";
    String homeSsid = "";
    boolean automation = true;
    boolean keepAtHome = false;
    boolean connectionGuard = true;
    String theme = THEME_DARK;
    String manualMode = MANUAL_AUTO;
    int autoReturnHours = 2;
    long manualUntil = 0;
    String appMode = AppPolicy.ALL;
    final Set<String> selectedPackages = new LinkedHashSet<>();
    final Set<String> onlyPackages = new LinkedHashSet<>();
    final Set<String> excludedPackages = new LinkedHashSet<>();

    SecureStore(Context context) throws Exception {
        this.context = context.getApplicationContext();
        synchronized (WRITE_LOCK) {
            load();
            originalState = encodeSettings();
            if (migrateControl) writeSettings(originalState);
        }
    }

    synchronized Profile activeProfile() {
        for (Profile profile : profiles) if (profile.id.equals(activeId)) return profile;
        return null;
    }

    void setControlMode(String mode) throws Exception {
        if (!MANUAL_AUTO.equals(mode) && !MANUAL_ON.equals(mode) && !MANUAL_OFF.equals(mode))
            throw new IllegalArgumentException("Неизвестный режим VPN");
        synchronized (WRITE_LOCK) {
            SecureStore latest = new SecureStore(context);
            latest.manualMode = mode;
            latest.automation = true;
            latest.keepAtHome = false;
            latest.manualUntil = deadline(mode, latest.autoReturnHours, System.currentTimeMillis());
            latest.save("manualMode", "manualUntil", "automation", "keepAtHome");
            load(); originalState = encodeSettings();
        }
    }

    void setAutoReturnHours(int hours) throws Exception {
        if (hours != 0 && hours != 2 && hours != 4 && hours != 6 && hours != 24)
            throw new IllegalArgumentException("Неизвестное время возврата");
        synchronized (WRITE_LOCK) {
            SecureStore latest = new SecureStore(context);
            latest.autoReturnHours = hours;
            latest.manualUntil = deadline(latest.manualMode, hours, System.currentTimeMillis());
            latest.save("autoReturnHours", "manualUntil");
            load(); originalState = encodeSettings();
        }
    }

    static long deadline(String mode, int hours, long now) {
        return MANUAL_AUTO.equals(mode) || hours == 0 ? 0 : now + hours * 3_600_000L;
    }

    static boolean expireManualMode(Context context, long now) throws Exception {
        synchronized (WRITE_LOCK) {
            SecureStore latest = new SecureStore(context);
            if (MANUAL_AUTO.equals(latest.manualMode) || latest.manualUntil <= 0 || now < latest.manualUntil)
                return false;
            latest.manualMode = MANUAL_AUTO; latest.manualUntil = 0;
            latest.automation = true; latest.keepAtHome = false;
            latest.save("manualMode", "manualUntil", "automation", "keepAtHome");
            return true;
        }
    }

    synchronized void add(Profile profile) throws Exception {
        profiles.add(profile);
        if (activeId.isEmpty()) activeId = profile.id;
        save();
    }

    synchronized void rename(String id, String name) throws Exception {
        for (int i = 0; i < profiles.size(); i++) {
            Profile old = profiles.get(i);
            if (!old.id.equals(id)) continue;
            profiles.set(i, new Profile(old.id, name, old.type, old.config));
            try { save(); }
            catch (Exception e) { profiles.set(i, old); throw e; }
            return;
        }
        throw new IllegalArgumentException("Профиль не найден");
    }

    synchronized boolean delete(String id) throws Exception {
        for (int i = 0; i < profiles.size(); i++) {
            Profile old = profiles.get(i);
            if (!old.id.equals(id)) continue;
            String previousActive = activeId;
            boolean previousAutomation = automation;
            boolean previousKeepAtHome = keepAtHome;
            String previousManualMode = manualMode;
            long previousManualUntil = manualUntil;
            boolean wasActive = id.equals(activeId);
            profiles.remove(i);
            if (wasActive) {
                activeId = profiles.isEmpty() ? "" : profiles.get(0).id;
                if (activeId.isEmpty()) {
                    automation = true;
                    keepAtHome = false;
                    manualMode = MANUAL_OFF;
                    manualUntil = 0;
                }
            }
            try { save(); }
            catch (Exception e) {
                profiles.add(i, old);
                activeId = previousActive;
                automation = previousAutomation;
                keepAtHome = previousKeepAtHome;
                manualMode = previousManualMode;
                manualUntil = previousManualUntil;
                throw e;
            }
            return wasActive;
        }
        throw new IllegalArgumentException("Профиль не найден");
    }

    synchronized void setAppPolicy(String mode, Set<String> only, Set<String> excluded) throws Exception {
        if (!AppPolicy.ALL.equals(mode) && !AppPolicy.ONLY.equals(mode) && !AppPolicy.EXCLUDE.equals(mode))
            throw new IllegalArgumentException("Неизвестный режим приложений");
        if (AppPolicy.ONLY.equals(mode) && only.isEmpty())
            throw new IllegalArgumentException("Выберите хотя бы одно приложение");
        String previousMode = appMode;
        Set<String> previousPackages = new LinkedHashSet<>(selectedPackages);
        Set<String> previousOnly = new LinkedHashSet<>(onlyPackages);
        Set<String> previousExcluded = new LinkedHashSet<>(excludedPackages);
        appMode = mode;
        onlyPackages.clear();
        onlyPackages.addAll(only);
        excludedPackages.clear();
        excludedPackages.addAll(excluded);
        selectedPackages.clear();
        if (AppPolicy.ONLY.equals(mode)) selectedPackages.addAll(onlyPackages);
        if (AppPolicy.EXCLUDE.equals(mode)) selectedPackages.addAll(excludedPackages);
        try { save(); }
        catch (Exception e) {
            appMode = previousMode;
            selectedPackages.clear();
            selectedPackages.addAll(previousPackages);
            onlyPackages.clear();
            onlyPackages.addAll(previousOnly);
            excludedPackages.clear();
            excludedPackages.addAll(previousExcluded);
            throw e;
        }
    }

    private JSONObject encodeSettings() throws Exception {
        JSONObject json = new JSONObject();
        JSONArray items = new JSONArray();
        for (Profile profile : profiles) items.put(profile.toJson());
        json.put("profiles", items);
        json.put("activeId", activeId);
        json.put("homeSsid", homeSsid);
        json.put("automation", automation);
        json.put("keepAtHome", keepAtHome);
        json.put("connectionGuard", connectionGuard);
        json.put("theme", theme);
        json.put("manualMode", manualMode);
        json.put("controlSchema", 1);
        json.put("autoReturnHours", autoReturnHours);
        json.put("manualUntil", manualUntil);
        json.put("appMode", appMode);
        JSONArray apps = new JSONArray();
        for (String name : selectedPackages) apps.put(name);
        json.put("selectedPackages", apps);
        JSONArray onlyApps = new JSONArray();
        for (String name : onlyPackages) onlyApps.put(name);
        json.put("onlyPackages", onlyApps);
        JSONArray excludedApps = new JSONArray();
        for (String name : excludedPackages) excludedApps.put(name);
        json.put("excludedPackages", excludedApps);
        return json;
    }

    synchronized void save() throws Exception {
        save(new String[0]);
    }

    synchronized void save(String... explicitFields) throws Exception {
        synchronized (WRITE_LOCK) {
            JSONObject desired = encodeSettings();
            JSONObject json = new File(context.getFilesDir(), FILE_NAME).exists()
                    ? new SecureStore(context).encodeSettings() : desired;
            // An open editor owns only its edits. Preserve newer tile and service decisions.
            java.util.Iterator<String> keys = desired.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (java.util.Arrays.asList(explicitFields).contains(name) ||
                        !desired.get(name).toString().equals(originalState.get(name).toString())) {
                    if ("profiles".equals(name)) {
                        json.put(name, mergeProfiles(originalState.getJSONArray(name),
                                desired.getJSONArray(name), json.getJSONArray(name)));
                    } else json.put(name, desired.get(name));
                }
            }
            JSONArray savedProfiles = json.getJSONArray("profiles");
            boolean profilesEdited = !desired.getJSONArray("profiles").toString()
                    .equals(originalState.getJSONArray("profiles").toString());
            if (profilesEdited || !desired.getString("activeId").equals(originalState.getString("activeId"))) {
                boolean activeExists = false;
                for (int i = 0; i < savedProfiles.length(); i++) {
                    if (savedProfiles.getJSONObject(i).getString("id").equals(json.getString("activeId"))) activeExists = true;
                }
                if (!activeExists) json.put("activeId", savedProfiles.length() == 0 ? "" : savedProfiles.getJSONObject(0).getString("id"));
                if (profilesEdited && savedProfiles.length() == 0) {
                    json.put("automation", true);
                    json.put("keepAtHome", false);
                    json.put("manualMode", MANUAL_OFF);
                    json.put("manualUntil", 0);
                }
            }
            JSONArray routedApps = AppPolicy.ONLY.equals(json.getString("appMode")) ? json.getJSONArray("onlyPackages")
                    : AppPolicy.EXCLUDE.equals(json.getString("appMode")) ? json.getJSONArray("excludedPackages") : new JSONArray();
            json.put("selectedPackages", routedApps);

            writeSettings(json);
            load();
            originalState = encodeSettings();
        }
    }

    private void writeSettings(JSONObject json) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject envelope = new JSONObject();
            envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            envelope.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));

            File file = new File(context.getFilesDir(), FILE_NAME);
            File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) {
                // Android's rename implementation replaces the destination atomically on supported filesystems.
                throw new IllegalStateException("Не удалось сохранить настройки");
            }
    }

    private static JSONArray mergeProfiles(JSONArray original, JSONArray desired, JSONArray latest) throws Exception {
        java.util.LinkedHashMap<String, JSONObject> before = profileMap(original);
        java.util.LinkedHashMap<String, JSONObject> after = profileMap(desired);
        java.util.LinkedHashMap<String, JSONObject> merged = profileMap(latest);
        for (String id : before.keySet()) if (!after.containsKey(id)) merged.remove(id);
        for (String id : after.keySet()) {
            JSONObject old = before.get(id), edited = after.get(id);
            if (old == null || !old.toString().equals(edited.toString())) {
                // A rename from an old screen must not resurrect a deleted profile.
                if (old == null || merged.containsKey(id)) merged.put(id, edited);
            }
        }
        JSONArray result = new JSONArray();
        for (JSONObject profile : merged.values()) result.put(profile);
        return result;
    }

    private static java.util.LinkedHashMap<String, JSONObject> profileMap(JSONArray profiles) throws Exception {
        java.util.LinkedHashMap<String, JSONObject> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            result.put(profile.getString("id"), profile);
        }
        return result;
    }

    private void load() throws Exception {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return;
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file)) {
            // Read the size from the same opened file, even if another process replaces the path.
            bytes = new byte[(int) in.getChannel().size()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IllegalStateException("Файл настроек повреждён");
                offset += count;
            }
        }
        JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(envelope.getString("data"), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        JSONObject json = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        JSONArray items = json.getJSONArray("profiles");
        profiles.clear();
        for (int i = 0; i < items.length(); i++) profiles.add(Profile.fromJson(items.getJSONObject(i)));
        activeId = json.optString("activeId", "");
        homeSsid = json.optString("homeSsid", "");
        automation = json.optBoolean("automation", false);
        keepAtHome = json.optBoolean("keepAtHome", false);
        connectionGuard = json.optBoolean("connectionGuard", true);
        theme = normalizeTheme(json.optString("theme", THEME_DARK));
        manualMode = json.optString("manualMode", MANUAL_AUTO);
        if (!MANUAL_ON.equals(manualMode) && !MANUAL_OFF.equals(manualMode)) manualMode = MANUAL_AUTO;
        autoReturnHours = json.optInt("autoReturnHours", 2);
        if (autoReturnHours != 0 && autoReturnHours != 2 && autoReturnHours != 4 &&
                autoReturnHours != 6 && autoReturnHours != 24) autoReturnHours = 2;
        manualUntil = json.optLong("manualUntil", 0);
        migrateControl = !json.has("controlSchema");
        if (migrateControl) {
            if (MANUAL_AUTO.equals(manualMode)) {
                if (!automation) manualMode = MANUAL_OFF;
                else if (keepAtHome) manualMode = MANUAL_ON;
            }
            automation = true; keepAtHome = false;
            manualUntil = deadline(manualMode, autoReturnHours, System.currentTimeMillis());
        }
        appMode = json.optString("appMode", AppPolicy.ALL);
        selectedPackages.clear();
        JSONArray apps = json.optJSONArray("selectedPackages");
        if (apps != null) for (int i = 0; i < apps.length(); i++) selectedPackages.add(apps.getString(i));
        onlyPackages.clear();
        excludedPackages.clear();
        JSONArray onlyApps = json.optJSONArray("onlyPackages");
        JSONArray excludedApps = json.optJSONArray("excludedPackages");
        if (onlyApps != null) for (int i = 0; i < onlyApps.length(); i++) onlyPackages.add(onlyApps.getString(i));
        if (excludedApps != null) for (int i = 0; i < excludedApps.length(); i++) excludedPackages.add(excludedApps.getString(i));
        if (onlyApps == null && AppPolicy.ONLY.equals(appMode)) onlyPackages.addAll(selectedPackages);
        if (excludedApps == null && AppPolicy.EXCLUDE.equals(appMode)) excludedPackages.addAll(selectedPackages);
        selectedPackages.clear();
        if (AppPolicy.ONLY.equals(appMode)) selectedPackages.addAll(onlyPackages);
        if (AppPolicy.EXCLUDE.equals(appMode)) selectedPackages.addAll(excludedPackages);
    }

    static String normalizeTheme(String value) {
        // All previous appearances were dark. Unknown or removed values migrate to dark.
        return THEME_LIGHT.equals(value) || THEME_SYSTEM.equals(value) ? value : THEME_DARK;
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }
}
