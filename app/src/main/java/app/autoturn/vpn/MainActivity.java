package app.autoturn.vpn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    static final String EXTRA_MANUAL_ON = "app.autoturn.vpn.MANUAL_ON";
    private static final int PAGE_HOME = 0;
    private static final int PAGE_PROFILES = 1;
    private static final int PAGE_SETTINGS = 2;
    private static final int REQUEST_FILE = 10;
    private static final int REQUEST_VPN = 11;
    private static final int REQUEST_VPN_MANUAL = 13;
    private static final int REQUEST_PROTECTION = 14;
    private static final int REQUEST_LOCATION = 12;
    private int BG;
    private int CARD;
    private int NEON;
    private int TEXT;
    private int MUTED;
    private ThemePalette palette;
    private SecureStore store;
    private boolean pendingAutomation;
    private boolean pendingHome;
    private boolean receiverRegistered;
    private int page = PAGE_HOME;
    private int slideDirection;
    private OnBackInvokedCallback backCallback;
    private AlertDialog releaseNotesDialog;
    private UpdateUi updateUi;
    private AlertDialog controlDialog;
    private AlertDialog profileDialog;
    private AlertDialog wifiDialog;
    private LinearLayout shell;
    private FrameLayout pageHost;
    private ScrollView currentScroll;
    private View navigation;
    private int renderedPage = -1;
    private String renderedKey = "";
    private final int[] scrollPositions = new int[3];
    private final ScrollView[] cachedPages = new ScrollView[3];
    private final String[] cachedKeys = new String[3];
    private long lastScrollTime;
    private boolean touching;
    private boolean profileDragging;
    private final Runnable deferredRender = () -> render();
    private TextView terminalHeadline;
    private Button homeUpdate;
    private TextView terminalDescription;
    private TextView terminalBadge;
    private TextView terminalFoot;
    private TextView terminalNetwork;
    private Button terminalPower;
    private TerminalUi.Signal terminalSignal;
    private int systemTopInset;

    private final android.content.BroadcastReceiver statusReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            try { store = new SecureStore(MainActivity.this); }
            catch (Exception ignored) { }
            render();
        }
    };

    @Override public void onCreate(Bundle state) {
        try {
            SecureStore.expireManualMode(this, System.currentTimeMillis());
            store = new SecureStore(this);
        }
        catch (Exception ignored) { }
        ThemePalette.applyActivityTheme(this, store == null ? SecureStore.THEME_DARK : store.theme);
        super.onCreate(state);
        if (state != null) {
            page = state.getInt("page", PAGE_HOME);
            int[] positions = state.getIntArray("scroll_positions");
            if (positions != null && positions.length == 3)
                System.arraycopy(positions, 0, scrollPositions, 0, 3);
        }
        if (store == null) {
            new AlertDialog.Builder(this).setTitle("Не удалось открыть настройки")
                    .setMessage("Зашифрованные настройки недоступны. Приложение не будет перезаписывать их автоматически.")
                    .setPositiveButton("Закрыть", (d, w) -> finish()).show();
            return;
        }
        applyTheme();
        updateUi = new UpdateUi(this);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        if (store != null && !receiverRegistered) {
            registerReceiver(statusReceiver, new IntentFilter(StatusStore.ACTION_CHANGED), RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    @Override protected void onStop() {
        if (currentScroll != null) {
            scrollPositions[renderedPage] = currentScroll.getScrollY();
            currentScroll.removeCallbacks(deferredRender);
        }
        if (receiverRegistered) { unregisterReceiver(statusReceiver); receiverRegistered = false; }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (updateUi != null) updateUi.destroy();
        if (releaseNotesDialog != null) releaseNotesDialog.dismiss();
        if (controlDialog != null) controlDialog.dismiss();
        if (profileDialog != null) profileDialog.dismiss();
        if (wifiDialog != null) wifiDialog.dismiss();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (currentScroll != null) scrollPositions[renderedPage] = currentScroll.getScrollY();
        outState.putInt("page", page);
        outState.putIntArray("scroll_positions", scrollPositions);
        super.onSaveInstanceState(outState);
    }

    // Android 13+ gestures use updateBackCallback(); this fallback serves Android 10–12.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (page != PAGE_HOME) navigateTo(PAGE_HOME);
        else super.onBackPressed();
    }

    private void navigateTo(int destination) {
        if (page == destination) return;
        slideDirection = destination > page ? 1 : -1;
        page = destination;
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (store == null) return;
        try { store = new SecureStore(this); }
        catch (Exception e) { error("Не удалось обновить настройки"); return; }
        applyTheme();
        if (pendingHome && hasLocation()) { pendingHome = false; saveCurrentWifi(); }
        if (pendingAutomation && hasBackgroundLocation()) {
            pendingAutomation = false;
            requestVpnThenStart();
        }
        if (getIntent().getBooleanExtra(EXTRA_MANUAL_ON, false)) {
            getIntent().removeExtra(EXTRA_MANUAL_ON);
            requestManualOn();
        }
        if (store.protectedMode || store.automation || !SecureStore.MANUAL_AUTO.equals(store.manualMode)) refreshAutomation();
        render();
        showReleaseNotesIfNeeded();
        if (updateUi != null) updateUi.resume();
    }

    @Override protected void onPause() {
        if (updateUi != null) updateUi.pause();
        super.onPause();
    }

    boolean updateNotesVisible() { return releaseNotesDialog != null; }

    private void render() {
        if (store == null) return;
        updateBackCallback();
        String key = contentKey();
        if (currentScroll != null && page == renderedPage && key.equals(renderedKey)) {
            if (page == PAGE_HOME) updateTerminalStatus();
            return;
        }
        if (currentScroll != null && page == renderedPage &&
                (touching || profileDragging || SystemClock.uptimeMillis() - lastScrollTime < 180)) {
            currentScroll.removeCallbacks(deferredRender);
            currentScroll.postDelayed(deferredRender, 180);
            return;
        }
        if (currentScroll != null) {
            currentScroll.removeCallbacks(deferredRender);
            scrollPositions[renderedPage] = currentScroll.getScrollY();
        }
        if (shell == null) {
            shell = new LinearLayout(this);
            shell.setOrientation(LinearLayout.VERTICAL);
            pageHost = new FrameLayout(this);
            shell.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
            shell.setOnApplyWindowInsetsListener((view, insets) -> {
                systemTopInset = Build.VERSION.SDK_INT >= 30 ?
                        insets.getInsets(WindowInsets.Type.systemBars()).top : insets.getSystemWindowInsetTop();
                int bottom = Build.VERSION.SDK_INT >= 30 ?
                        insets.getInsets(WindowInsets.Type.systemBars()).bottom :
                        insets.getSystemWindowInsetBottom();
                shell.setPadding(0, systemTopInset, 0, bottom);
                return insets;
            });
            setContentView(shell);
        }
        shell.setBackgroundColor(BG);
        pageHost.setBackgroundColor(BG);
        if (navigation != null) shell.removeView(navigation);
        navigation = createTerminalNav();
        if (navigation != null) shell.addView(navigation);
        ScrollView scroll;
        if (cachedPages[page] != null && key.equals(cachedKeys[page])) {
            scroll = cachedPages[page];
            if (page == PAGE_HOME) updateTerminalStatus();
        } else {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> lastScrollTime = SystemClock.uptimeMillis());
        scroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touching = true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                touching = false;
            return false;
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(dp(24), dp(20), dp(24), dp(28));
        scroll.addView(root);
        renderTerminal(root);
        cachedPages[page] = scroll;
        cachedKeys[page] = key;
        }
        ScrollView previous = currentScroll;
        boolean animate = previous != null && page != renderedPage && ValueAnimator.areAnimatorsEnabled();
        int direction = slideDirection;
        slideDirection = 0;
        for (int i = pageHost.getChildCount() - 1; i >= 0; i--) {
            View child = pageHost.getChildAt(i);
            child.animate().cancel();
            child.setLayerType(View.LAYER_TYPE_NONE, null);
            if (child != previous) pageHost.removeView(child);
        }
        if (previous != null && !animate) pageHost.removeView(previous);
        currentScroll = scroll;
        renderedPage = page;
        renderedKey = key;
        touching = false;
        scroll.setAlpha(1);
        scroll.setTranslationX(0);
        pageHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.requestApplyInsets();
        int savedY = scrollPositions[page];
        scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                scroll.scrollTo(0, savedY);
                return true;
            }
        });
        if (animate) {
            scroll.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            previous.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            scroll.setAlpha(0);
            scroll.setTranslationX(dp(24) * direction);
            previous.animate().alpha(0).translationX(-dp(12) * direction).setDuration(160)
                    .withEndAction(() -> {
                        previous.setLayerType(View.LAYER_TYPE_NONE, null);
                        pageHost.removeView(previous);
                    }).start();
            scroll.animate().alpha(1).translationX(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(() -> scroll.setLayerType(View.LAYER_TYPE_NONE, null)).start();
        }
    }

    private String contentKey() {
        StringBuilder key = new StringBuilder().append(page).append('|').append(store.theme)
                .append('|').append(palette.light).append('|').append(store.activeId).append('|').append(store.automation)
                .append('|').append(store.primaryId)
                .append('|').append(store.keepAtHome).append('|').append(store.manualMode)
                .append('|').append(store.autoReturnHours).append('|').append(store.manualUntil)
                .append('|').append(store.connectionGuard).append('|').append(store.protectedMode).append('|').append(store.homeSsids)
                .append('|').append(store.appMode).append('|').append(store.selectedPackages);
        if (page == PAGE_PROFILES) key.append('|').append(StatusStore.state(this));
        Profile current = store.activeProfile();
        if (page == PAGE_HOME && current != null && Profile.QWDTT.equals(current.type))
            key.append('|').append(QwdttState.pending(this, current.id));
        for (Profile profile : store.profiles)
            key.append('|').append(profile.id).append(':').append(profile.name).append(':').append(profile.type)
                    .append(':').append(profile.lastResort);
        return key.toString();
    }



    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (page != PAGE_HOME && backCallback == null) {
            backCallback = () -> navigateTo(PAGE_HOME);
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else if (page == PAGE_HOME && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
    }



    private void terminalEyebrow(LinearLayout root, String text) {
        TextView eyebrow = label(text, 11, NEON, false);
        eyebrow.setLetterSpacing(.10f);
        root.addView(eyebrow);
        gap(root, 16);
    }

    private void renderTerminalHome(LinearLayout root) {
        homeUpdate = action("", false, v -> updateUi.openUpdate());
        homeUpdate.setTextSize(12);
        root.addView(homeUpdate, new LinearLayout.LayoutParams(-1, dp(48)));
        gap(root, 16);
        updateHomeUpdate();
        String state = StatusStore.state(this);
        String detail = StatusStore.detail(this);
        boolean on = StatusStore.ON.equals(state);
        boolean connecting = StatusStore.CONNECTING.equals(state);
        boolean blocked = StatusStore.BLOCKED.equals(state);
        boolean atHome = StatusStore.OFF.equals(state) && detail.startsWith("Домашний Wi‑Fi");
        String headline = blocked ? "Защита активна." : on ? "На связи." : atHome ? "Вы дома." : connecting ?
                "Подключаемся." : StatusStore.ERROR.equals(state) ? "Нет связи." : "VPN выключен.";
        terminalEyebrow(root, "[ 01 / CONNECTION ]");
        terminalHeadline = TerminalUi.title(root, palette, headline, 36);
        terminalHeadline.setMaxLines(1);
        terminalHeadline.setAutoSizeTextTypeUniformWithConfiguration(26, 36, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        terminalHeadline.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(56)));
        gap(root, 12);
        if (detail.isEmpty()) detail = store.automation ?
                "Ожидаем подключения к сети" : "Автоматика выключена";
        TextView description = label(detail, 13, MUTED, false);
        terminalDescription = description;
        description.setMinLines(2);
        description.setMaxLines(2);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        description.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(description);
        gap(root, 24);

        Profile active = store.activeProfile();
        LinearLayout signal = new LinearLayout(this);
        signal.setOrientation(LinearLayout.VERTICAL);
        signal.setPadding(dp(14), dp(12), dp(14), dp(12));
        signal.setBackground(TerminalUi.surface(this, BG, palette.border));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("LINK / " + protocolName(active), 10, MUTED, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        terminalBadge = label(blocked ? "■ BLOCKED" : on ? "● ONLINE" : connecting ? "◌ WAIT" :
                StatusStore.ERROR.equals(state) ? "! ERROR" : "■ OFFLINE", 10,
                on ? palette.online : StatusStore.ERROR.equals(state) ? NEON : MUTED, false);
        terminalBadge.setGravity(Gravity.CENTER_VERTICAL);
        terminalBadge.setMaxLines(1);
        heading.addView(terminalBadge, new LinearLayout.LayoutParams(-2, dp(18)));
        signal.addView(heading);
        terminalSignal = new TerminalUi.Signal(this, palette, state);
        signal.addView(terminalSignal,
                new LinearLayout.LayoutParams(-1, dp(58)));
        terminalFoot = label(blocked ? "■ ТРАФИК ЗАБЛОКИРОВАН" : on ? ((store.connectionGuard || store.protectedMode) && detail.startsWith("VPN проверен") ?
                "● ДОСТУП ПРОВЕРЕН" : "● VPN ЗАПУЩЕН") : atHome ? "■ ДОМАШНЯЯ СЕТЬ" :
                connecting ? "◌ УСТАНАВЛИВАЕМ СВЯЗЬ" : StatusStore.ERROR.equals(state) ?
                "! НЕТ СОЕДИНЕНИЯ" : "■ VPN ВЫКЛЮЧЕН", 11, MUTED, false);
        terminalFoot.setGravity(Gravity.CENTER_VERTICAL);
        terminalFoot.setMaxLines(1);
        terminalFoot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        signal.addView(terminalFoot, new LinearLayout.LayoutParams(-1, dp(20)));
        root.addView(signal);
        if (active != null && Profile.QWDTT.equals(active.type) && QwdttState.pending(this, active.id)) {
            gap(root, 12);
            root.addView(action("Пройти капчу VK →", false, v -> {
                org.json.JSONObject pending = QwdttState.read(this);
                startActivity(new Intent(this, QwdttCaptchaActivity.class)
                        .putExtra("session", pending.optString("session"))
                        .putExtra("request", pending.optString("captchaRequest")));
            }));
        }
        gap(root, 20);
        terminalInfo(root, "Основной", store.primaryProfile() == null ? "Не выбран →" : store.primaryProfile().name + " →",
                v -> navigateTo(PAGE_PROFILES));
        terminalNetwork = terminalInfo(root, "Сеть", atHome ? currentWifiSsid() : networkLabel(), null);
        boolean manual = !SecureStore.MANUAL_AUTO.equals(store.manualMode);
        terminalInfo(root, "Режим", modeLabel() + " →", v -> showModeChooser());
        if (manual) terminalInfo(root, "Возврат в Авто", returnLabel(), null);
        gap(root, 22);
        boolean running = on || connecting || (blocked && !SecureStore.MANUAL_OFF.equals(store.manualMode));
        terminalPower = action(running ? "Выключить VPN  ↗" : "Включить VPN  ↗", true,
                v -> {
                    String next = VpnTileService.nextManualMode(store.manualMode, StatusStore.state(this));
                    if (SecureStore.MANUAL_OFF.equals(next))
                        setManualMode(SecureStore.MANUAL_OFF);
                    else requestManualOn();
                });
        root.addView(terminalPower);
        gap(root, 8);
        Button secondary = action(manual ? "Вернуть Авто сейчас →" : "Выбрать режим →",
                false, v -> { if (manual) chooseAutomaticMode(); else showModeChooser(); });
        secondary.setBackgroundColor(BG);
        secondary.setTextColor(MUTED);
        secondary.setTextSize(12);
        root.addView(secondary);
        gap(root, 20);
        root.addView(label(manual ? "Ручной режим действует до возврата в Авто." :
                "Авто: дома — напрямую.\nВне дома — через VPN.", 12, MUTED, false));
    }

    private String protocolName(Profile profile) {
        if (profile == null) return "—";
        return Profile.QWDTT.equals(profile.type) ? "qWDTT · VK TURN" : Profile.VLESS.equals(profile.type) ? "VLESS · REALITY" :
                Profile.HYSTERIA2.equals(profile.type) ? "HYSTERIA 2" : "AMNEZIAWG";
    }

    private String networkLabel() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return "Не определена";
        // Inspect physical networks too: the active network may be this app's VPN.
        boolean wifi = false;
        boolean cellular = false;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            wifi |= caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            cellular |= caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        }
        if (wifi && cellular) return "Wi-Fi / мобильная";
        if (wifi) return "Wi-Fi";
        if (cellular) return "Мобильная сеть";
        return manager.getActiveNetwork() == null ? "Нет сети" : "Другая сеть";
    }

    private TextView terminalInfo(LinearLayout root, String caption, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(13));
        row.addView(label(caption, 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView text = label(value, 13, TEXT, false);
        text.setGravity(Gravity.END);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 2));
        if (click != null) {
            row.setOnClickListener(click);
            row.setFocusable(true);
            row.setContentDescription(caption + ": " + value);
        }
        root.addView(row);
        TerminalUi.divider(root, palette.divider);
        return text;
    }

    private void updateTerminalStatus() {
        updateHomeUpdate();
        if (terminalHeadline == null) return;
        String state = StatusStore.state(this);
        String detail = StatusStore.detail(this);
        boolean on = StatusStore.ON.equals(state);
        boolean connecting = StatusStore.CONNECTING.equals(state);
        boolean error = StatusStore.ERROR.equals(state);
        boolean blocked = StatusStore.BLOCKED.equals(state);
        boolean atHome = StatusStore.OFF.equals(state) && detail.startsWith("Домашний Wi‑Fi");
        String headline = blocked ? "Защита активна." : on ? "На связи." : atHome ? "Вы дома." : connecting ?
                "Подключаемся." : error ? "Нет связи." : "VPN выключен.";
        if (!terminalHeadline.getText().toString().equals(headline + "_"))
            TerminalUi.setTitle(terminalHeadline, palette, headline);
        if (detail.isEmpty()) detail = store.automation ? "Ожидаем подключения к сети" : "Автоматика выключена";
        setTextIfChanged(terminalDescription, detail);
        setTextIfChanged(terminalBadge, blocked ? "■ BLOCKED" : on ? "● ONLINE" : connecting ? "◌ WAIT" : error ? "! ERROR" : "■ OFFLINE");
        terminalBadge.setTextColor(on ? palette.online : error ? NEON : MUTED);
        setTextIfChanged(terminalFoot, blocked ? "■ ТРАФИК ЗАБЛОКИРОВАН" : on ? ((store.connectionGuard || store.protectedMode) && detail.startsWith("VPN проверен") ?
                "● ДОСТУП ПРОВЕРЕН" : "● VPN ЗАПУЩЕН") : atHome ? "■ ДОМАШНЯЯ СЕТЬ" :
                connecting ? "◌ УСТАНАВЛИВАЕМ СВЯЗЬ" : error ? "! НЕТ СОЕДИНЕНИЯ" : "■ VPN ВЫКЛЮЧЕН");
        setTextIfChanged(terminalNetwork, atHome ? currentWifiSsid() : networkLabel());
        setTextIfChanged(terminalPower, on || connecting || (blocked && !SecureStore.MANUAL_OFF.equals(store.manualMode)) ? "Выключить VPN  ↗" : "Включить VPN  ↗");
        terminalSignal.setState(state);
    }

    private void setTextIfChanged(TextView view, String text) {
        if (!view.getText().toString().equals(text)) view.setText(text);
    }

    void updateHomeUpdate() {
        if (homeUpdate == null) return;
        AppUpdater updater = AppUpdater.get(this);
        UpdateRelease release = updater.availableRelease();
        AppUpdater.State state = updater.state();
        String text = "AutoTurn " + BuildConfig.VERSION_NAME;
        if (release != null) {
            if (state.phase == AppUpdater.Phase.DOWNLOADING)
                text = "Скачивание обновления" + (state.progress >= 0 ? " · " + state.progress + "%" : "…") + " →";
            else if (state.phase == AppUpdater.Phase.VERIFYING) text = "Проверяем обновление… →";
            else if (state.phase == AppUpdater.Phase.READY) text = "Установить " + release.version + " →";
            else text = "Доступна версия " + release.version + " →";
        }
        setTextIfChanged(homeUpdate, text);
        homeUpdate.setEnabled(release != null);
        homeUpdate.setTextColor(release != null ? NEON : MUTED);
        homeUpdate.setContentDescription(text);
    }

    private void terminalToggle(LinearLayout root, String title, boolean checked, View.OnClickListener click) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextColor(TEXT);
        control.setTextSize(13);
        control.setTypeface(Typeface.MONOSPACE);
        control.setMinHeight(dp(48));
        control.setSwitchMinWidth(dp(60));
        control.setSwitchPadding(dp(16));
        control.setShowText(false);
        control.setSplitTrack(false);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(TEXT);
        thumb.setCornerRadius(dp(3));
        thumb.setSize(dp(22), dp(22));
        control.setThumbDrawable(new android.graphics.drawable.InsetDrawable(thumb, dp(4)));
        android.graphics.drawable.StateListDrawable tracks = new android.graphics.drawable.StateListDrawable();
        for (boolean enabled : new boolean[]{true, false}) {
            GradientDrawable track = new GradientDrawable();
            track.setColor(enabled ? NEON : palette.switchOff);
            track.setStroke(dp(1), enabled ? NEON : palette.switchBorder);
            track.setCornerRadius(dp(5));
            track.setSize(dp(60), dp(30));
            tracks.addState(enabled ? new int[]{android.R.attr.state_checked} : new int[]{}, track);
        }
        control.setTrackDrawable(tracks);
        control.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{palette.light ? Color.WHITE : TEXT, palette.thumbOff}));
        control.setTrackTintList(null);
        control.setChecked(checked);
        control.setOnClickListener(v -> {
            click.onClick(v);
            // Permission requests may defer the action; reflect the saved setting meanwhile.
            control.setChecked("Автоматика".equals(title) ? store.automation :
                    "Защищённый режим".equals(title) ? store.protectedMode : store.connectionGuard || store.protectedMode);
        });
        root.addView(control, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderTerminalSettings(LinearLayout root) {
        terminalEyebrow(root, "[ 03 / SETTINGS ]");
        TerminalUi.title(root, palette, "По вашим\nправилам", 27);
        gap(root, 22);
        renderThemeChooser(root);
        gap(root, 18);
        TerminalUi.divider(root, palette.divider);
        gap(root, 18);
        root.addView(label("// Домашние Wi-Fi сети", 13, TEXT, false));
        gap(root, 7);
        root.addView(label("В режиме Авто VPN выключается в любой из этих сетей.", 12, MUTED, false));
        gap(root, 12);
        if (store.homeSsids.isEmpty()) root.addView(label("Пока нет домашних сетей.", 13, MUTED, false));
        String currentSsid = currentWifiSsid();
        for (String ssid : store.homeSsids) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(4), dp(4), dp(4));
            row.setBackground(TerminalUi.surface(this, CARD, palette.border));
            LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(ssid, 14, TEXT, false);
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(name);
            if (ssid.equals(currentSsid)) text.addView(label("● ТЕКУЩАЯ СЕТЬ", 10, palette.online, false));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            Button remove = iconAction("×", "Удалить домашнюю сеть " + ssid, v -> removeHomeWifi(ssid));
            row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
            root.addView(row); gap(root, 6);
        }
        gap(root, 6);
        root.addView(action("+ Добавить текущую сеть", false, v -> chooseHome()));
        gap(root, 8);
        root.addView(action("Добавить по имени →", false, v -> addHomeWifiByName()));
        gap(root, 18);
        TerminalUi.divider(root, palette.divider);
        gap(root, 18);
        root.addView(label("// Приложения в VPN", 13, TEXT, false));
        gap(root, 6);
        root.addView(label(AppPolicy.describe(store.appMode, store.selectedPackages.size()), 12, MUTED, false));
        gap(root, 12);
        root.addView(action("Настроить приложения →", false,
                v -> startActivity(new Intent(this, AppRoutingActivity.class))));
        gap(root, 18);
        TerminalUi.divider(root, palette.divider);
        {
            gap(root, 10);
            terminalToggle(root, "Защищённый режим", store.protectedMode, v -> toggleProtectedMode());
            root.addView(label("При обрыве и смене профиля приложения в VPN остаются без интернета.\n" +
                    "Ручное выключение VPN сохраняет блокировку вне дома. Дома — обычное подключение.\n" +
                    "Не защищает при принудительной остановке приложения или отзыве разрешения VPN.", 12, MUTED, false));
            gap(root, 18);
            terminalToggle(root, "Контроль соединения", store.connectionGuard || store.protectedMode, v -> toggleConnectionGuard());
            root.addView(label("Проверяет интернет через VPN.\nЕсли трафик не проходит, перепроверяет\nи переключается на резервный профиль.", 12, MUTED, false));
            gap(root, 18);
            TerminalUi.divider(root, palette.divider);
        }
        gap(root, 10);
        root.addView(action("Режим: " + modeLabel() + " →", false, v -> showModeChooser()));
        gap(root, 16);
        root.addView(label("// Автоматически вернуть Авто", 13, TEXT, false));
        gap(root, 8);
        root.addView(action(store.autoReturnHours == 0 ? "Выкл. →" :
                "Через " + store.autoReturnHours + " ч →", false, v -> showAutoReturnChooser()));
        gap(root, 8);
        root.addView(label("После ручного включения или выключения.\nНовая команда начинает отсчёт заново.\nИзменение времени тоже перезапускает отсчёт.\nПри энергосбережении возврат может задержаться.", 12, MUTED, false));
        gap(root, 18);
        TerminalUi.divider(root, palette.divider);
        gap(root, 18);
        root.addView(label("// Обновления", 13, TEXT, false));
        gap(root, 8);
        root.addView(label("AutoTurn " + BuildConfig.VERSION_NAME + "\nПроверка при открытии — раз в сутки.\nСкачивание из GitHub Releases.", 12, MUTED, false));
        gap(root, 12);
        root.addView(action("Проверить обновления →", false, v -> updateUi.manualCheck()));
    }

    private void renderTerminalProfiles(LinearLayout root) {
        terminalEyebrow(root, "[ 02 / PROFILES ]");
        TerminalUi.title(root, palette, "Свои маршруты", 27);
        gap(root, 12);
        root.addView(label(store.profiles.isEmpty() ? "Добавьте первое подключение." :
                "Каждый запуск — с основного.\nРезервы — сверху вниз.", 13, MUTED, false));
        if (!store.connectionGuard && !store.protectedMode && !store.profiles.isEmpty()) {
            gap(root, 6);
            root.addView(label("Для автопереключения включите контроль соединения.", 12, MUTED, false));
        }
        gap(root, 18);
        root.addView(label("Порядок — за ≡. Действия — в ⋮.", 11, MUTED, false));
        renderProfileGroup(root, "01 / ОСНОВНОЙ", null);
        renderProfileGroup(root, "02 / ОБЫЧНЫЕ РЕЗЕРВЫ", false);
        renderProfileGroup(root, "03 / ПОСЛЕДНИЕ РЕЗЕРВЫ", true);
        gap(root, 8);
        root.addView(action("+ Добавить профиль  ↗", true, v -> openFile()));
        gap(root, 10);
        root.addView(action("Вставить ссылку →", false, v -> importClipboard()));
        gap(root, 12);
        root.addView(label(".conf / .json / .txt\nVLESS / Hysteria 2 / AmneziaWG / qWDTT\nqWDTT добавляется как последний резерв.", 12, MUTED, false));
    }



    private void renderProfileGroup(LinearLayout root, String heading, Boolean lastResort) {
        gap(root, 20);
        root.addView(label(heading, 11, NEON, false));
        gap(root, 9);
        java.util.List<Profile> profiles = new java.util.ArrayList<>();
        for (Profile p : store.orderedProfiles()) {
            boolean primary = p.id.equals(store.primaryId);
            if (lastResort == null ? primary : !primary && p.lastResort == lastResort) profiles.add(p);
        }
        if (profiles.isEmpty()) {
            root.addView(label(lastResort == null ? "Добавьте основной профиль." : "Нет профилей", 12, MUTED, false));
            return;
        }
        ProfileOrderLayout group = new ProfileOrderLayout(this, (ScrollView) root.getParent(), new ProfileOrderLayout.Listener() {
            @Override public void dragging(boolean active) {
                profileDragging = active;
                if (!active) { touching = false; root.postDelayed(() -> render(), 200); }
            }
            @Override public void reordered(java.util.List<String> ids) {
                try {
                    store.reorderProfiles(Boolean.TRUE.equals(lastResort), ids);
                    root.announceForAccessibility("Порядок профилей сохранён");
                } catch (Exception e) {
                    try { store = new SecureStore(MainActivity.this); } catch (Exception ignored) { }
                    cachedKeys[PAGE_PROFILES] = null;
                    renderedKey = "";
                    error("Не удалось сохранить порядок. Список обновлён; повторите перемещение.");
                }
            }
        });
        group.setContentDescription(heading);
        for (Profile p : profiles) {
            LinearLayout row = new LinearLayout(this);
            row.setTag(p.id); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(6), dp(4), dp(6));
            row.setMinimumHeight(dp(68));
            row.setBackground(TerminalUi.surface(this, CARD, lastResort == null ? NEON : palette.border));
            TextView handle = label(lastResort == null ? "■" : "≡", 23, lastResort == null ? NEON : MUTED, false);
            handle.setGravity(Gravity.CENTER);
            handle.setContentDescription((lastResort == null ? "Основной профиль " : "Переместить профиль ") + p.name);
            row.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(48)));
            if (lastResort != null) group.attachHandle(handle, row);
            LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(p.name, 15, TEXT, false);
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(name);
            String state = StatusStore.state(this);
            boolean current = p.id.equals(store.activeId);
            String detail = protocolName(p);
            if (current && (StatusStore.ON.equals(state) || StatusStore.CONNECTING.equals(state)))
                detail += StatusStore.ON.equals(state) ? " · ● Подключён" : " · ◌ Подключаем";
            TextView subtitle = label(detail, 10, current && StatusStore.ON.equals(state) ? palette.online : MUTED, false);
            subtitle.setMaxLines(2); text.addView(subtitle);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(iconAction("⋮", "Действия профиля " + p.name, v -> showProfileMenu(p)),
                    new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(6)); group.addView(row, params);
        }
        root.addView(group);
    }

    private Button iconAction(String symbol, String description, View.OnClickListener click) {
        Button button = action(symbol, false, click);
        button.setTextSize(23); button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x18000000 | (TEXT & 0x00ffffff)), null, null));
        button.setContentDescription(description); return button;
    }

    private void showProfileMenu(Profile profile) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        if (!profile.id.equals(store.primaryId)) {
            labels.add("Сделать основным"); actions.add(() -> selectProfile(profile));
            labels.add(profile.lastResort ? "В обычные резервы" : "В последние резервы");
            actions.add(() -> {
                try { store.setLastResort(profile.id, !profile.lastResort); render(); }
                catch (Exception e) { error("Не удалось изменить группу профиля"); }
            });
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Profile p : store.orderedProfiles())
                if (!p.id.equals(store.primaryId) && p.lastResort == profile.lastResort) ids.add(p.id);
            int index = ids.indexOf(profile.id);
            if (index > 0) {
                labels.add("Выше в очереди"); actions.add(() -> moveProfile(profile, -1));
            }
            if (index >= 0 && index < ids.size() - 1) {
                labels.add("Ниже в очереди"); actions.add(() -> moveProfile(profile, 1));
            }
        }
        labels.add("Переименовать"); actions.add(() -> renameProfile(profile));
        labels.add("Удалить"); actions.add(() -> deleteProfile(profile));
        AlertDialog menu = new AlertDialog.Builder(this).setTitle(profile.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Закрыть", null).show();
        profileDialog = menu;
        TerminalUi.styleDialog(menu, palette);
    }

    private void moveProfile(Profile profile, int delta) {
        try {
            store = new SecureStore(this);
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Profile p : store.orderedProfiles())
                if (!p.id.equals(store.primaryId) && p.lastResort == profile.lastResort) ids.add(p.id);
            int index = ids.indexOf(profile.id), next = index + delta;
            if (index >= 0 && next >= 0 && next < ids.size()) {
                java.util.Collections.swap(ids, index, next);
                store.reorderProfiles(profile.lastResort, ids); render();
            }
        } catch (Exception e) { error("Не удалось изменить порядок"); }
    }

    private void applyTheme() {
        palette = ThemePalette.of(store.theme, getResources().getConfiguration());
        BG = palette.bg;
        CARD = palette.card;
        NEON = palette.accent;
        TEXT = palette.text;
        MUTED = palette.muted;
        ThemePalette.applyActivityTheme(this, store.theme);
        palette.applySystemBars(this);
    }

    private void showReleaseNotesIfNeeded() {
        if (releaseNotesDialog != null ||
                getSharedPreferences("release_notes", MODE_PRIVATE)
                        .getInt("shown_version", 0) >= BuildConfig.VERSION_CODE) return;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(22));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(palette.radius));
        background.setStroke(dp(1), palette.border);
        content.setBackground(background);

        TextView edition = label("ОБНОВЛЕНИЕ · " + BuildConfig.VERSION_NAME, 11, NEON, true);
        edition.setLetterSpacing(.12f);
        content.addView(edition);
        gap(content, 13);
        content.addView(label("Что нового", 27, TEXT, true));
        gap(content, 17);
        TextView changes = label("Тестовая версия. Не опубликована как стабильный релиз.\n\n" +
                "• В настройках появился отдельный защищённый режим. По умолчанию выключен.\n\n" +
                "• При обрыве, смене профиля и недоступности всех серверов сохраняет блокирующий VPN-интерфейс.\n\n" +
                "• Ручное выключение VPN вне дома сохраняет блокировку. Прямой доступ возвращается снятием галочки.\n\n" +
                "• Дома в режиме Авто VPN выключается как прежде. При выключенной защите работает прежний движок.\n\n" +
                "Требуется проверка на телефоне. Не защищает при принудительной остановке приложения, " +
                "отзыве VPN-разрешения и до первоначального запуска защиты после загрузки телефона.", 14, MUTED, false);
        changes.setLineSpacing(dp(2), 1f);
        content.addView(changes);
        gap(content, 22);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        dialog.setCancelable(false);
        dialog.setOnDismissListener(ignored -> {
            releaseNotesDialog = null;
            if (updateUi != null && !isFinishing() && !isDestroyed()) updateUi.notesClosed();
        });
        content.addView(action("Понятно", true, v -> {
            getSharedPreferences("release_notes", MODE_PRIVATE).edit()
                    .putInt("shown_version", BuildConfig.VERSION_CODE).apply();
            dialog.dismiss();
        }));
        releaseNotesDialog = dialog;
        dialog.show();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    private void renderThemeChooser(LinearLayout root) {
        root.addView(label("// Оформление", 13, TEXT, false));
        gap(root, 12);
        themeOption(root, "Тёмная", SecureStore.THEME_DARK);
        gap(root, 7);
        themeOption(root, "Светлая", SecureStore.THEME_LIGHT);
        gap(root, 7);
        themeOption(root, "Как в системе", SecureStore.THEME_SYSTEM);
        gap(root, 9);
        root.addView(label(SecureStore.THEME_SYSTEM.equals(store.theme)
                ? "Сейчас в системе: " + (palette.light ? "светлая" : "тёмная")
                : "Выбранная тема действует независимо от системы.", 11, MUTED, false));
    }

    private void themeOption(LinearLayout chooser, String title, String theme) {
        boolean selected = theme.equals(store.theme);
        android.widget.RadioButton option = new android.widget.RadioButton(this);
        option.setText(title);
        option.setTextColor(TEXT);
        option.setTextSize(13);
        option.setTypeface(Typeface.MONOSPACE);
        option.setPadding(dp(14), 0, dp(14), 0);
        option.setCompoundDrawablePadding(dp(12));
        option.setBackground(TerminalUi.surface(this, selected ? CARD : BG,
                selected ? NEON : palette.border));
        android.graphics.drawable.StateListDrawable marks = new android.graphics.drawable.StateListDrawable();
        for (boolean checked : new boolean[]{true, false}) {
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(checked ? NEON : BG);
            ring.setStroke(dp(1), checked ? NEON : MUTED);
            ring.setSize(dp(16), dp(16));
            if (checked) {
                GradientDrawable center = new GradientDrawable();
                center.setColor(NEON);
                GradientDrawable base = new GradientDrawable();
                base.setColor(BG);
                base.setStroke(dp(1), NEON);
                base.setSize(dp(16), dp(16));
                android.graphics.drawable.LayerDrawable mark = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{base,
                                new android.graphics.drawable.InsetDrawable(center, dp(4))});
                marks.addState(new int[]{android.R.attr.state_checked}, mark);
            } else marks.addState(new int[]{}, ring);
        }
        option.setButtonDrawable(new android.graphics.drawable.InsetDrawable(marks, dp(14), 0, dp(10), 0));
        option.setButtonTintList(null);
        option.setChecked(selected);
        chooser.addView(option, new LinearLayout.LayoutParams(-1, dp(48)));
        option.setOnClickListener(v -> {
                    if (theme.equals(store.theme)) return;
                    String previous = store.theme;
                    try {
                        store.theme = theme;
                        store.save();
                        applyTheme();
                        render();
                    } catch (Exception e) {
                        store.theme = previous;
                        option.setChecked(selected);
                        error("Не удалось сохранить оформление");
                    }
                });
    }

    private void renderTerminal(LinearLayout root) {
        TerminalUi.header(root, palette);
        if (page == PAGE_HOME) renderTerminalHome(root);
        else if (page == PAGE_SETTINGS) renderTerminalSettings(root);
        else renderTerminalProfiles(root);
    }







    private LinearLayout createTerminalNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(14), 0, dp(14), dp(8));
        nav.setBackgroundColor(BG);
        String[] names = {"Главная", "Профили", "Настройки"};
        for (int i = 0; i < names.length; i++) {
            final int destination = i;
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            View line = new View(this);
            line.setBackgroundColor(page == i ? NEON : palette.divider);
            column.addView(line, new LinearLayout.LayoutParams(-1, dp(page == i ? 2 : 1)));
            Button item = TerminalUi.button(this, palette, names[i], false, v -> navigateTo(destination));
            item.setBackgroundColor(BG);
            item.setTextColor(page == i ? TEXT : MUTED);
            item.setTextSize(12);
            item.setSelected(page == i);
            item.setContentDescription(names[i] + (page == i ? ", выбран" : ""));
            column.addView(item, new LinearLayout.LayoutParams(-1, -2));
            nav.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return nav;
    }

    private void toggleConnectionGuard() {
        if (store.protectedMode) { error("В защищённом режиме контроль соединения включён обязательно."); return; }
        try {
            store.connectionGuard = !store.connectionGuard;
            store.save();
            refreshAutomation();
            render();
        } catch (Exception e) { error("Не удалось изменить проверку соединения"); }
    }

    private void toggleProtectedMode() {
        if (store.protectedMode) {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Отключить защищённый режим?")
                    .setMessage("При остановленном или недоступном VPN приложения смогут выйти напрямую. " +
                            "AutoTurn вернётся к обычному режиму работы.")
                    .setPositiveButton("Отключить", (d, w) -> setProtectedMode(false))
                    .setNegativeButton("Отмена", null).show();
            TerminalUi.styleDialog(dialog, palette);
        } else {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) startActivityForResult(prepare, REQUEST_PROTECTION);
            else setProtectedMode(true);
        }
    }

    private void setProtectedMode(boolean enabled) {
        try {
            store.protectedMode = enabled;
            store.save("protectedMode");
            startForegroundService(new Intent(this, AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
            render();
        } catch (Exception e) {
            try { store = new SecureStore(this); } catch (Exception ignored) { }
            StatusStore.set(this, StatusStore.ERROR, "Не удалось применить защищённый режим · откройте настройки");
            render(); error("Не удалось изменить защищённый режим");
        }
    }



    private void selectProfile(Profile profile) {
        if (profile.id.equals(store.primaryId)) return;
        try { store.selectPrimary(profile.id); refreshAutomation(); render(); }
        catch (Exception e) { error("Не удалось выбрать профиль"); }
    }

    private void renameProfile(Profile profile) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(profile.name);
        input.setSelection(profile.name.length());
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.DKGRAY);
        input.setPadding(dp(18), dp(8), dp(18), dp(8));
        {
            input.setTextColor(TEXT);
            input.setHintTextColor(MUTED);
            input.setTypeface(Typeface.MONOSPACE);
            input.setBackground(TerminalUi.surface(this, CARD, palette.border));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Название профиля")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty() || name.length() > 40) {
                input.setError("Введите название до 40 символов");
                return;
            }
            try { store.rename(profile.id, name); VpnTileService.refresh(this); dialog.dismiss(); render(); }
            catch (Exception e) { error("Не удалось переименовать профиль"); }
        }));
        dialog.show();
        TerminalUi.styleDialog(dialog, palette);
        profileDialog = dialog;
    }

    private void deleteProfile(Profile profile) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Удалить профиль?")
                .setMessage("«" + profile.name + "» будет удалён с этого телефона.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (confirmation, which) -> {
                    try {
                        boolean wasActive = store.delete(profile.id);
                        VpnTileService.refresh(this);
                        if (wasActive && store.activeProfile() == null) {
                            stopAutomation();
                            StatusStore.set(this, StatusStore.OFF, "Профиль удалён · VPN выключен");
                        } else refreshAutomation();
                        render();
                    } catch (Exception e) { error("Не удалось удалить профиль"); }
                }).show();
        TerminalUi.styleDialog(dialog, palette);
        profileDialog = dialog;
    }

    private void chooseHome() {
        if (!hasLocation()) {
            pendingHome = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        saveCurrentWifi();
    }

    private String currentWifiSsid() {
        try {
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info = manager == null ? null : manager.getConnectionInfo();
        String ssid = info == null ? WifiManager.UNKNOWN_SSID : info.getSSID();
        if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.isEmpty()) {
            return "";
        }
        if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\""))
            ssid = ssid.substring(1, ssid.length() - 1);
        return ssid;
        } catch (SecurityException denied) { return ""; }
    }

    private void saveCurrentWifi() {
        String ssid = currentWifiSsid();
        if (ssid.isEmpty()) { error("Подключитесь к домашнему Wi-Fi и включите геолокацию телефона"); return; }
        try {
            boolean added = store.addHomeSsid(ssid); refreshAutomation(); render();
            if (!added) android.widget.Toast.makeText(this, "Эта сеть уже добавлена", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) { error("Не удалось сохранить сеть"); }
    }

    private void addHomeWifiByName() {
        EditText input = new EditText(this); input.setSingleLine(true);
        input.setHint("Имя Wi-Fi (SSID)"); input.setTextColor(TEXT); input.setHintTextColor(MUTED);
        input.setTypeface(Typeface.MONOSPACE); input.setPadding(dp(18), dp(12), dp(18), dp(12));
        input.setBackground(TerminalUi.surface(this, CARD, palette.border));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Добавить домашнюю сеть")
                .setMessage("Введите точное имя сети с учётом регистра.")
                .setView(input).setNegativeButton("Отмена", null).setPositiveButton("Добавить", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ssid = input.getText().toString();
            try {
                boolean added = store.addHomeSsid(ssid); dialog.dismiss(); refreshAutomation(); render();
                if (!added) android.widget.Toast.makeText(this, "Эта сеть уже добавлена", android.widget.Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
            catch (Exception e) { error("Не удалось сохранить сеть"); }
        }));
        dialog.show(); TerminalUi.styleDialog(dialog, palette);
        wifiDialog = dialog;
    }

    private void removeHomeWifi(String ssid) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Удалить домашнюю сеть?")
                .setMessage("«" + ssid + "» больше не будет считаться домашней." +
                        (store.homeSsids.size() == 1 ? "\nДля режима Авто потребуется добавить домашнюю сеть." : ""))
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> {
                    try { store.removeHomeSsid(ssid); refreshAutomation(); render(); }
                    catch (Exception e) { error("Не удалось удалить сеть"); }
                }).show();
        TerminalUi.styleDialog(dialog, palette);
        wifiDialog = dialog;
    }

    private void chooseAutomaticMode() {
        if (store.activeProfile() == null || store.homeSsids.isEmpty()) {
            error("Сначала выберите профиль и домашнюю сеть");
            return;
        }
        if (!hasLocation()) {
            pendingAutomation = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        if (!hasBackgroundLocation()) {
            pendingAutomation = true;
            new AlertDialog.Builder(this).setTitle("Доступ к домашней сети")
                    .setMessage("Для переключения VPN при закрытом приложении разрешите доступ к местоположению «Всегда» в настройках Android. Сами координаты приложение не читает.")
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(settings);
                    }).setNegativeButton("Позже", null).show();
            return;
        }
        requestVpnThenStart();
    }

    private void requestVpnThenStart() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, REQUEST_VPN);
        else enableAutomation();
    }

    private void enableAutomation() {
        try {
            store.setControlMode(SecureStore.MANUAL_AUTO);
            AutoReturnReceiver.sync(this);
            Intent service = new Intent(this, AutomationService.class);
            startForegroundService(service);
            render();
        } catch (Exception e) { error("Не удалось включить автоматизацию"); }
    }

    private String modeLabel() {
        return SecureStore.MANUAL_ON.equals(store.manualMode) ? "Включён вручную" :
                SecureStore.MANUAL_OFF.equals(store.manualMode) ? "Выключен" : "Авто";
    }

    private String returnLabel() {
        return store.manualUntil <= 0 ? "Автоматический возврат выключен" :
                new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(store.manualUntil));
    }

    private void showModeChooser() {
        String[] modes = {"Авто — дома выкл., вне дома вкл.", "Включён вручную", "Выключен"};
        int selected = SecureStore.MANUAL_ON.equals(store.manualMode) ? 1 :
                SecureStore.MANUAL_OFF.equals(store.manualMode) ? 2 : 0;
        if (controlDialog != null) controlDialog.dismiss();
        controlDialog = new AlertDialog.Builder(this).setTitle("Режим VPN")
                .setSingleChoiceItems(controlChoices(modes), selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) chooseAutomaticMode();
                    else if (which == 1) requestManualOn();
                    else setManualMode(SecureStore.MANUAL_OFF);
                }).setNegativeButton("Отмена", null).create();
        controlDialog.setOnDismissListener(dialog -> controlDialog = null);
        controlDialog.show();
        styleControlDialog();
    }

    private void showAutoReturnChooser() {
        int[] hours = {0, 2, 4, 6, 24};
        String[] labels = {"Выкл. — возвращать вручную", "Через 2 часа", "Через 4 часа", "Через 6 часов", "Через 24 часа"};
        int selected = 1;
        for (int i = 0; i < hours.length; i++) if (hours[i] == store.autoReturnHours) selected = i;
        if (controlDialog != null) controlDialog.dismiss();
        controlDialog = new AlertDialog.Builder(this).setTitle("Вернуть Авто автоматически")
                .setSingleChoiceItems(controlChoices(labels), selected, (dialog, which) -> {
                    dialog.dismiss();
                    try {
                        store.setAutoReturnHours(hours[which]);
                        AutoReturnReceiver.sync(this); refreshAutomation(); render();
                    } catch (Exception e) { error("Не удалось изменить время возврата"); }
                }).setNegativeButton("Отмена", null).create();
        controlDialog.setOnDismissListener(dialog -> controlDialog = null);
        controlDialog.show();
        styleControlDialog();
    }

    private android.widget.ListAdapter controlChoices(String[] labels) {
        return new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View reusable, android.view.ViewGroup parent) {
                android.widget.CheckedTextView choice = (android.widget.CheckedTextView) super.getView(position, reusable, parent);
                choice.setTextColor(TEXT); choice.setTypeface(Typeface.MONOSPACE); choice.setTextSize(14);
                choice.setCheckMarkTintList(new android.content.res.ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{NEON, MUTED}));
                return choice;
            }
        };
    }

    private void styleControlDialog() {
        TerminalUi.styleDialog(controlDialog, palette);
        int titleId = getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = controlDialog.findViewById(titleId);
        if (title != null) { title.setTextColor(TEXT); title.setTypeface(Typeface.MONOSPACE); }
    }

    private void requestManualOn() {
        if (store.activeProfile() == null) { error("Сначала выберите профиль VPN"); return; }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, REQUEST_VPN_MANUAL);
        else setManualMode(SecureStore.MANUAL_ON);
    }

    private void setManualMode(String mode) {
        try {
            store.setControlMode(mode);
            AutoReturnReceiver.sync(this);
            if (SecureStore.MANUAL_AUTO.equals(mode) && !store.automation) {
                stopAutomation();
                StatusStore.set(this, StatusStore.OFF, "VPN выключен");
            } else refreshAutomation();
            render();
        } catch (Exception e) { error("Не удалось изменить режим VPN"); }
    }

    private void refreshAutomation() {
        VpnTileService.refresh(this);
        if (store.protectedMode || store.automation || !SecureStore.MANUAL_AUTO.equals(store.manualMode)) {
            Intent intent = new Intent(this, AutomationService.class).setAction(AutomationService.ACTION_REFRESH);
            startForegroundService(intent);
        }
    }

    private void stopAutomation() {
        // Let the service acknowledge any pending foreground start before stopping itself.
        startForegroundService(new Intent(this, AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
    }

    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_FILE);
    }

    private void importClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) { error("Буфер обмена пуст"); return; }
        CharSequence value = data.getItemAt(0).coerceToText(this);
        importText("", value.toString());
    }

    private void importText(String name, String content) {
        try {
            Profile profile = ProfileImporter.parse(name, content);
            store.add(profile);
            refreshAutomation();
            render();
        } catch (Exception e) { error("Не удалось прочитать профиль. Проверьте формат файла."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROTECTION && resultCode == RESULT_OK) setProtectedMode(true);
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) enableAutomation();
        if (requestCode == REQUEST_VPN_MANUAL && resultCode == RESULT_OK)
            setManualMode(SecureStore.MANUAL_ON);
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 1024 * 1024) throw new IllegalArgumentException();
                    out.write(buffer, 0, n);
                }
                String name = uri.getLastPathSegment();
                importText(name == null ? "Импорт" : name, out.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception e) { error("Не удалось прочитать файл или он слишком большой"); }
        }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void error(String message) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("AutoTurn").setMessage(message)
                .setPositiveButton("Понятно", null).show();
        TerminalUi.styleDialog(dialog, palette);
    }

    private LinearLayout block() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD); shape.setCornerRadius(dp(palette.radius));
        shape.setStroke(dp(1), palette.border);
        box.setBackground(shape);
        return box;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextColor(color); view.setTextSize(sp);
        view.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        return view;
    }

    private Button action(String text, boolean primary, View.OnClickListener listener) {
        return TerminalUi.button(this, palette, text, primary, listener);
    }

    private void gap(LinearLayout root, int dp) {
        View view = new View(this);
        root.addView(view, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}

