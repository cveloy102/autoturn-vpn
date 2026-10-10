package app.autoturn.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AppRoutingActivity extends Activity {
    private int BG;
    private int CARD;
    private int NEON;
    private int TEXT;
    private int MUTED;
    private ThemePalette palette;

    private final Set<String> selected = new HashSet<>();
    private final Set<String> onlySelected = new HashSet<>();
    private final Set<String> excludedSelected = new HashSet<>();
    private final List<AppEntry> installed = new ArrayList<>();
    private final List<AppEntry> visible = new ArrayList<>();
    private final AppAdapter adapter = new AppAdapter();
    private SecureStore store;
    private String mode;
    private String searchText = "";
    private boolean showSystem;
    private boolean appsLoaded;
    private LinearLayout appSection;
    private TextView count;
    private TextView loading;
    private ListView list;
    private EditText search;
    private int restoredRow = -1;
    private int restoredTop;
    private final Runnable searchFilter = () -> filter();

    @Override public void onCreate(Bundle state) {
        try { store = new SecureStore(this); }
        catch (Exception ignored) { }
        ThemePalette.applyActivityTheme(this, store == null ? SecureStore.THEME_DARK : store.theme);
        super.onCreate(state);
        if (store == null) {
            new AlertDialog.Builder(this).setMessage("Не удалось открыть настройки приложений")
                    .setPositiveButton("Закрыть", (d, w) -> finish()).show();
            return;
        }
        palette = ThemePalette.of(store.theme, getResources().getConfiguration());
        BG = palette.bg;
        CARD = palette.card;
        NEON = palette.accent;
        TEXT = palette.text;
        MUTED = palette.muted;
        palette.applySystemBars(this);
        mode = store.appMode;
        onlySelected.addAll(store.onlyPackages);
        excludedSelected.addAll(store.excludedPackages);
        if (state != null) {
            mode = state.getString("routing_mode", mode);
            onlySelected.clear();
            excludedSelected.clear();
            onlySelected.addAll(state.getStringArrayList("routing_only"));
            excludedSelected.addAll(state.getStringArrayList("routing_excluded"));
            searchText = state.getString("routing_search", "");
            showSystem = state.getBoolean("routing_system");
            restoredRow = state.getInt("routing_row", -1);
            restoredTop = state.getInt("routing_top");
        }
        loadSelectionForMode();
        buildScreen();
        loadAppsInBackground();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(WindowInsets.Type.systemBars()).top :
                    insets.getSystemWindowInsetTop();
            int bottom = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(WindowInsets.Type.systemBars()).bottom :
                    insets.getSystemWindowInsetBottom();
            root.setPadding(dp(24), top + dp(20), dp(24), bottom + dp(20));
            return insets;
        });
        setContentView(root);

        {
            TerminalUi.header(root, palette);
            root.addView(label("[ 03 / APP ROUTING ]", 11, NEON, false));
            gap(root, 12);
            TerminalUi.title(root, palette, "Ваш трафик.\nВаш выбор", 26);
            gap(root, 16);
        }

        LinearLayout modes = block();
        modes.addView(label("РЕЖИМ", 11, NEON, true));
        {
            Spinner picker = new Spinner(this);
            String[] titles = {"Все приложения", "Только выбранные", "Все, кроме выбранных"};
            String[] values = {AppPolicy.ALL, AppPolicy.ONLY, AppPolicy.EXCLUDE};
            ArrayAdapter<String> options = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, titles) {
                private View style(View view) {
                    TextView text = (TextView) view;
                    text.setTextColor(TEXT);
                    text.setTypeface(Typeface.MONOSPACE);
                    text.setTextSize(13);
                    text.setPadding(dp(8), dp(12), dp(8), dp(12));
                    text.setBackgroundColor(CARD);
                    text.setMinHeight(dp(48));
                    return view;
                }
                @Override public View getView(int position, View reuse, ViewGroup parent) {
                    return style(super.getView(position, reuse, parent));
                }
                @Override public View getDropDownView(int position, View reuse, ViewGroup parent) {
                    return style(super.getDropDownView(position, reuse, parent));
                }
            };
            options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            picker.setAdapter(options);
            picker.setContentDescription("Маршрут приложений");
            picker.setSelection(AppPolicy.ONLY.equals(mode) ? 1 : AppPolicy.EXCLUDE.equals(mode) ? 2 : 0);
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!values[position].equals(mode)) changeMode(values[position]);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            modes.addView(picker);
        }
        root.addView(modes);
        gap(root, 12);

        count = label("", 13, MUTED, false);
        root.addView(count);
        updateCount();
        gap(root, 10);
        gap(root, 15);

        appSection = new LinearLayout(this);
        appSection.setOrientation(LinearLayout.VERTICAL);
        appSection.addView(label("ВЫБРАТЬ ПРИЛОЖЕНИЯ", 11, NEON, true));
        gap(appSection, 8);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Поиск приложения");
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setBackgroundColor(CARD);
        search.setPadding(dp(14), dp(9), dp(14), dp(9));
        {
            search.setTypeface(Typeface.MONOSPACE);
            search.setTextSize(14);
            search.setBackground(TerminalUi.surface(this, CARD, palette.border));
        }
        search.setText(searchText);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchText = s.toString();
                search.removeCallbacks(searchFilter);
                search.postDelayed(searchFilter, 120);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        appSection.addView(search);
        CheckBox system = new CheckBox(this);
        system.setText("Показать системные");
        system.setTextSize(13);
        system.setTextColor(MUTED);
        system.setTypeface(Typeface.MONOSPACE);
        system.setButtonTintList(ColorStateList.valueOf(NEON));
        system.setChecked(showSystem);
        system.setOnCheckedChangeListener((button, checked) -> {
            showSystem = checked;
            filter();
        });
        appSection.addView(system);
        loading = label("Загружаем приложения…", 13, MUTED, false);
        appSection.addView(loading);
        list = new ListView(this);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setDivider(null);
        list.setDividerHeight(dp(8));
        list.setCacheColorHint(BG);
        list.setAdapter(adapter);
        appSection.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(appSection, new LinearLayout.LayoutParams(-1, 0, 1));
        appSection.setVisibility(AppPolicy.ALL.equals(mode) ? View.GONE : View.VISIBLE);

        TextView note = label("В режиме обхода выбранные приложения идут напрямую. Системная блокировка соединений без VPN может перекрыть им интернет.", 11, MUTED, false);
        root.addView(note);
        {
            gap(root, 12);
            root.addView(action("Сохранить выбор  ↗", v -> save()));
        }
    }

    private void loadAppsInBackground() {
        new Thread(() -> {
            PackageManager manager = getPackageManager();
            List<AppEntry> found = new ArrayList<>();
            try {
                for (ApplicationInfo info : manager.getInstalledApplications(0)) {
                    if (getPackageName().equals(info.packageName)) continue;
                    CharSequence title = info.loadLabel(manager);
                    String name = title == null || title.length() == 0 ? info.packageName : title.toString();
                    // Rasterize icons on the worker, so scrolling never calls PackageManager.
                    Drawable icon = info.loadIcon(manager);
                    int size = dp(34);
                    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    icon.setBounds(0, 0, size, size);
                    icon.draw(new Canvas(bitmap));
                    found.add(new AppEntry(info, name, new BitmapDrawable(getResources(), bitmap)));
                }
                Collator collator = Collator.getInstance(new Locale("ru"));
                found.sort(Comparator.comparing(entry -> entry.name, collator));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installed.clear();
                    installed.addAll(found);
                    Set<String> present = new HashSet<>();
                    for (AppEntry entry : installed) present.add(entry.pkg);
                    onlySelected.retainAll(present);
                    excludedSelected.retainAll(present);
                    loadSelectionForMode();
                    appsLoaded = true;
                    loading.setVisibility(View.GONE);
                    updateCount();
                    filter();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) loading.setText("Не удалось загрузить список приложений");
                });
            }
        }, "AutoTurn-app-list").start();
    }



    private void filter() {
        if (!appsLoaded) return;
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (AppEntry entry : installed) {
            if ((showSystem || !entry.system || selected.contains(entry.pkg)) &&
                    entry.search.contains(query)) visible.add(entry);
        }
        adapter.notifyDataSetChanged();
        if (restoredRow >= 0) {
            int row = restoredRow, top = restoredTop;
            restoredRow = -1;
            list.post(() -> list.setSelectionFromTop(row, top));
        }
    }

    private void changeMode(String value) {
        saveSelectionForMode();
        mode = value;
        loadSelectionForMode();
        if (appSection != null) appSection.setVisibility(AppPolicy.ALL.equals(mode) ? View.GONE : View.VISIBLE);
        updateCount();
        filter();
    }



    @Override protected void onDestroy() {
        if (search != null) search.removeCallbacks(searchFilter);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        saveSelectionForMode();
        state.putString("routing_mode", mode);
        state.putStringArrayList("routing_only", new ArrayList<>(onlySelected));
        state.putStringArrayList("routing_excluded", new ArrayList<>(excludedSelected));
        state.putString("routing_search", searchText);
        state.putBoolean("routing_system", showSystem);
        if (list != null) {
            state.putInt("routing_row", list.getFirstVisiblePosition());
            state.putInt("routing_top", list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop());
        }
        super.onSaveInstanceState(state);
    }

    private void updateCount() {
        if (count != null) count.setText(AppPolicy.describe(mode, selected.size()));
    }

    private void saveSelectionForMode() {
        if (AppPolicy.ONLY.equals(mode)) {
            onlySelected.clear();
            onlySelected.addAll(selected);
        } else if (AppPolicy.EXCLUDE.equals(mode)) {
            excludedSelected.clear();
            excludedSelected.addAll(selected);
        }
    }

    private void loadSelectionForMode() {
        selected.clear();
        if (AppPolicy.ONLY.equals(mode)) selected.addAll(onlySelected);
        if (AppPolicy.EXCLUDE.equals(mode)) selected.addAll(excludedSelected);
    }

    private void save() {
        if (!appsLoaded && !AppPolicy.ALL.equals(mode)) {
            message("Подождите, пока загрузятся приложения");
            return;
        }
        if (AppPolicy.ONLY.equals(mode) && selected.isEmpty()) {
            message("Выберите хотя бы одно приложение для VPN");
            return;
        }
        try {
            saveSelectionForMode();
            store.setAppPolicy(mode, onlySelected, excludedSelected);
            if (store.automation) startForegroundService(new Intent(this, AutomationService.class)
                    .setAction(AutomationService.ACTION_REFRESH));
            finish();
        } catch (Exception e) { message("Не удалось сохранить правило приложений"); }
    }

    private void message(String text) {
        AlertDialog dialog = new AlertDialog.Builder(this).setMessage(text).setPositiveButton("Понятно", null).show();
        TerminalUi.styleDialog(dialog, palette);
    }

    private LinearLayout block() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(12), dp(15), dp(12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD); shape.setCornerRadius(dp(palette.radius));
        shape.setStroke(dp(1), palette.border);
        box.setBackground(shape);
        return box;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        return view;
    }

    private Button action(String text, View.OnClickListener click) {
        return TerminalUi.button(this, palette, text, true, click);
    }

    private void gap(LinearLayout box, int size) {
        box.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size)));
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private static final class AppEntry {
        final ApplicationInfo info;
        final String pkg;
        final String name;
        final String search;
        final Drawable icon;
        final boolean system;
        AppEntry(ApplicationInfo info, String name, Drawable icon) {
            this.info = info;
            this.pkg = info.packageName;
            this.name = name;
            this.icon = icon;
            this.search = (name + " " + pkg).toLowerCase(Locale.ROOT);
            this.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
    }

    private static final class RowHolder {
        ImageView icon;
        TextView name;
        TextView pkg;
        CheckBox check;
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reuse, ViewGroup parent) {
            LinearLayout row;
            RowHolder holder;
            if (reuse instanceof LinearLayout && reuse.getTag() instanceof RowHolder) {
                row = (LinearLayout) reuse;
                holder = (RowHolder) row.getTag();
            } else {
                row = block();
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                holder = new RowHolder();
                holder.icon = new ImageView(AppRoutingActivity.this);
                LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(34), dp(34));
                iconSize.setMargins(0, 0, dp(12), 0);
                row.addView(holder.icon, iconSize);
                LinearLayout names = new LinearLayout(AppRoutingActivity.this);
                names.setOrientation(LinearLayout.VERTICAL);
                holder.name = label("", 14, TEXT, true);
                holder.pkg = label("", 11, MUTED, false);
                names.addView(holder.name);
                names.addView(holder.pkg);
                row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
                holder.check = new CheckBox(AppRoutingActivity.this);
                holder.check.setButtonTintList(ColorStateList.valueOf(NEON));
                row.addView(holder.check);
                row.setTag(holder);
            }
            AppEntry entry = visible.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.name.setText(entry.name);
            holder.pkg.setText(entry.pkg);
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selected.contains(entry.pkg));
            holder.check.setContentDescription("Выбрать " + entry.name);
            CheckBox check = holder.check;
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(entry.pkg); else selected.remove(entry.pkg);
                updateCount();
            });
            row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
            return row;
        }
    }
}
