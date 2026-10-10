package app.autoturn.vpn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.VpnService;

import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.Interface;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AppPolicy {
    static final String ALL = "all";
    static final String ONLY = "only";
    static final String EXCLUDE = "exclude";

    private AppPolicy() { }

    static String describe(String mode, int count) {
        if (ONLY.equals(mode)) return "Только выбранные · " + count;
        if (EXCLUDE.equals(mode)) return "Без выбранных · " + count;
        return "Все приложения";
    }

    static Set<String> installed(Context context, Collection<String> packages) {
        Set<String> result = new LinkedHashSet<>();
        PackageManager manager = context.getPackageManager();
        for (String name : packages) {
            if (name == null || name.isEmpty() || name.equals(context.getPackageName())) continue;
            try { manager.getApplicationInfo(name, 0); result.add(name); }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        return result;
    }

    static Set<String> routed(Context context, SecureStore store) {
        Set<String> result = installed(context, store.selectedPackages);
        if (store.connectionGuard && ONLY.equals(store.appMode))
            result.add(context.getPackageName());
        return result;
    }

    static void apply(VpnService.Builder builder, String mode, Set<String> packages) throws Exception {
        if (ONLY.equals(mode)) {
            if (packages.isEmpty()) throw new IllegalStateException("Нет выбранных приложений");
            for (String name : packages) builder.addAllowedApplication(name);
        } else if (EXCLUDE.equals(mode)) {
            for (String name : packages) builder.addDisallowedApplication(name);
        }
    }

    static Config forAmnezia(Config source, String mode, Set<String> packages) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String line : source.getInterface().toAwgQuickString().split("\\r?\\n")) {
            String trimmed = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (trimmed.startsWith("includedapplications") || trimmed.startsWith("excludedapplications")) continue;
            if (!trimmed.isEmpty()) lines.add(line);
        }
        if (ONLY.equals(mode)) {
            if (packages.isEmpty()) throw new IllegalStateException("Нет выбранных приложений");
            lines.add("IncludedApplications = " + String.join(", ", packages));
        } else if (EXCLUDE.equals(mode) && !packages.isEmpty()) {
            lines.add("ExcludedApplications = " + String.join(", ", packages));
        }
        return new Config.Builder().setInterface(Interface.parse(lines))
                .addPeers(source.getPeers()).build();
    }
}
