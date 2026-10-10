package app.autoturn.vpn;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;

final class ThemePalette {
    final int bg, card, accent, text, muted, divider, secondaryButton, border;
    final int radius = 0;
    final int onAccent, online, switchOff, switchBorder, thumbOff;
    final boolean light;

    private ThemePalette(boolean light) {
        this.light = light;
        bg = light ? 0xFFF5F2EB : 0xFF101010;
        card = light ? 0xFFEEEBE4 : 0xFF181818;
        accent = light ? 0xFFCF332D : 0xFFF0443E;
        text = light ? 0xFF191918 : 0xFFE8E4D9;
        muted = light ? 0xFF68645D : 0xFFAAA79F;
        divider = light ? 0xFFD5D0C7 : 0xFF353535;
        secondaryButton = card;
        border = divider;
        onAccent = light ? 0xFFFFFFFF : bg;
        online = light ? 0xFF296744 : 0xFF8CDBAD;
        switchOff = light ? divider : 0xFF303030;
        switchBorder = light ? 0xFF999389 : 0xFF79766E;
        thumbOff = light ? muted : text;
    }

    static ThemePalette of(String theme, Configuration configuration) {
        return of(theme, (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    static ThemePalette of(String theme, boolean systemDark) {
        String mode = SecureStore.normalizeTheme(theme);
        return new ThemePalette(SecureStore.THEME_LIGHT.equals(mode) ||
                (SecureStore.THEME_SYSTEM.equals(mode) && !systemDark));
    }

    static ThemePalette of(String theme) { return of(theme, true); }

    static void applyActivityTheme(Activity activity, String mode) {
        ThemePalette palette = of(mode, activity.getResources().getConfiguration());
        activity.setTheme(palette.light ? android.R.style.Theme_Material_Light_NoActionBar :
                android.R.style.Theme_Material_NoActionBar);
    }

    void applySystemBars(Activity activity) {
        activity.getWindow().setStatusBarColor(bg);
        activity.getWindow().setNavigationBarColor(bg);
        View decor = activity.getWindow().getDecorView();
        int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(light ? flags | mask : flags & ~mask);
    }
}
