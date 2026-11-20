package app.autoturn.vpn;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual language for the Terminal theme; contains no VPN or settings logic. */
final class TerminalUi {
    private TerminalUi() {}

    /** Corner radius for cards, rows and buttons (Terminal 2.0). */
    static final int RADIUS = 14;
    private static Typeface mono, display, sans;

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** JetBrains Mono for body text; falls back to the system monospace face. */
    static Typeface mono(Context context) {
        if (mono == null) mono = font(context, R.font.jetbrains_mono_medium, Typeface.MONOSPACE);
        return mono;
    }

    /** Inter Display Black for screen titles. */
    static Typeface display(Context context) {
        if (display == null) display = font(context, R.font.inter_display_black, Typeface.DEFAULT_BOLD);
        return display;
    }

    /** Inter Medium for the wordmark. */
    static Typeface sans(Context context) {
        if (sans == null) sans = font(context, R.font.inter_medium, Typeface.DEFAULT);
        return sans;
    }

    private static Typeface font(Context context, int id, Typeface fallback) {
        try { return context.getApplicationContext().getResources().getFont(id); }
        catch (Exception e) { return fallback; }
    }

    static GradientDrawable surface(Context context, int fill, int border) {
        return surface(context, fill, border, RADIUS);
    }

    static GradientDrawable surface(Context context, int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        shape.setStroke(dp(context, 1), border);
        return shape;
    }

    static void header(LinearLayout root, ThemePalette palette) {
        Context context = root.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new PixelMark(context, palette.accent),
                new LinearLayout.LayoutParams(dp(context, 25), dp(context, 25)));
        LinearLayout.LayoutParams brandSize = new LinearLayout.LayoutParams(0, -2, 1);
        brandSize.setMargins(dp(context, 12), 0, dp(context, 8), 0);
        TextView brand = text(context, "AUTOTURN_", 21, palette.text);
        brand.setTypeface(sans(context));
        brand.setLetterSpacing(.02f);
        android.text.SpannableString word = new android.text.SpannableString("AUTOTURN_");
        word.setSpan(new android.text.style.ForegroundColorSpan(palette.accent), 4, 9, 0);
        brand.setText(word);
        row.addView(brand, brandSize);
        TextView edition = text(context, "", 10, palette.muted);
        android.text.SpannableString signature = new android.text.SpannableString("powered by\nLENCHA_");
        signature.setSpan(new android.text.style.ForegroundColorSpan(palette.accent), 11, 18, 0);
        edition.setText(signature);
        edition.setGravity(Gravity.END);
        row.addView(edition);
        root.addView(row);
        space(root, 16);
        divider(root, palette.divider);
        space(root, 20);
    }

    static TextView text(Context context, String content, int size, int color) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(mono(context));
        return view;
    }

    static TextView title(LinearLayout root, ThemePalette palette, String content, int size) {
        TextView title = text(root.getContext(), content + "_", size, palette.text);
        setTitle(title, palette, content);
        title.setTypeface(display(root.getContext()));
        title.setLetterSpacing(-.035f);
        title.setIncludeFontPadding(false);
        title.setLineSpacing(0, .95f);
        root.addView(title);
        return title;
    }

    static void setTitle(TextView title, ThemePalette palette, String content) {
        android.text.SpannableString word = new android.text.SpannableString(content + "_");
        word.setSpan(new android.text.style.ForegroundColorSpan(palette.accent),
                content.length(), content.length() + 1, 0);
        title.setText(word);
    }

    static Button button(Context context, ThemePalette palette, String content,
                         boolean primary, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(content);
        button.setTextSize(13);
        button.setTypeface(mono(context));
        button.setAllCaps(false);
        button.setTextColor(primary ? palette.onAccent : palette.text);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x18000000 | (palette.text & 0x00FFFFFF)),
                surface(context, primary ? palette.accent : palette.secondaryButton,
                        primary ? palette.accent : palette.border), null));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setMinHeight(dp(context, primary ? 54 : 50));
        button.setMinimumHeight(dp(context, primary ? 54 : 50));
        if (primary) button.setTextSize(14);
        button.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        button.setOnClickListener(listener);
        return button;
    }

    static void divider(LinearLayout root, int color) {
        View line = new View(root.getContext());
        line.setBackgroundColor(color);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(root.getContext(), 1)));
    }

    static void space(LinearLayout root, int height) {
        root.addView(new View(root.getContext()),
                new LinearLayout.LayoutParams(1, dp(root.getContext(), height)));
    }

    static void styleDialog(android.app.AlertDialog dialog, ThemePalette palette) {
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(
                    surface(dialog.getContext(), palette.card, palette.border, 22), dp(dialog.getContext(), 16)));
        for (int which : new int[]{android.app.AlertDialog.BUTTON_POSITIVE,
                android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) {
                button.setTextColor(palette.accent);
                button.setTypeface(mono(dialog.getContext()));
                button.setAllCaps(false);
            }
        }
        TextView title = dialog.findViewById(dialog.getContext().getResources()
                .getIdentifier("alertTitle", "id", "android"));
        if (title != null) { title.setTypeface(mono(dialog.getContext())); title.setTextColor(palette.text); }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTypeface(mono(dialog.getContext()));
            message.setTextColor(palette.text);
        }
    }

    private static final class PixelMark extends View {
        private final Paint paint = new Paint();
        PixelMark(Context context, int color) { super(context); paint.setColor(color); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.rotate(-5, getWidth() / 2f, getHeight() / 2f);
            float unit = Math.min(getWidth(), getHeight()) / 9f;
            for (int i = 0; i < 9; i++) {
                if (i == 4) continue;
                float x = unit + (i % 3) * unit * 2.5f;
                float y = unit + (i / 3) * unit * 2.5f;
                canvas.drawRect(x, y, x + unit * 1.8f, y + unit * 1.8f, paint);
            }
            canvas.restore();
        }
    }

    /**
     * Link meter: 18 rounded bars. Lit bars follow the state (all when on, 7 while connecting)
     * and move gently while the meter is on screen; the motion stops when it is hidden.
     */
    static final class Signal extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bar = new android.graphics.RectF();
        private final ThemePalette palette;
        private final android.animation.ArgbEvaluator evaluator = new android.animation.ArgbEvaluator();
        private String state;
        private ValueAnimator transition, pulse;
        private float level, phase;
        private boolean visible;
        public Signal(Context context) {
            this(context, ThemePalette.of(SecureStore.THEME_DARK), StatusStore.OFF);
        }
        Signal(Context context, ThemePalette palette, String state) {
            super(context);
            this.palette = palette;
            this.state = state;
            level = target(state);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private static float target(String value) {
            return StatusStore.ON.equals(value) ? 1 : StatusStore.CONNECTING.equals(value) ? 7f / 18 : 0;
        }
        private boolean live() {
            return StatusStore.ON.equals(state) || StatusStore.CONNECTING.equals(state);
        }
        void setState(String value) {
            if (value.equals(state)) return;
            state = value;
            float target = target(value);
            if (transition != null) transition.cancel();
            updatePulse();
            if (!ValueAnimator.areAnimatorsEnabled()) { level = target; invalidate(); return; }
            transition = ValueAnimator.ofFloat(level, target);
            transition.setDuration(220);
            transition.addUpdateListener(animation -> { level = (float) animation.getAnimatedValue(); invalidate(); });
            transition.start();
        }
        @Override public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            visible = isVisible;
            updatePulse();
        }
        private void updatePulse() {
            boolean run = visible && live() && ValueAnimator.areAnimatorsEnabled();
            if (run && pulse == null) {
                pulse = ValueAnimator.ofFloat(0, 1);
                pulse.setDuration(1600);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setInterpolator(new android.view.animation.LinearInterpolator());
                pulse.addUpdateListener(animation -> { phase = (float) animation.getAnimatedValue(); invalidate(); });
                pulse.start();
            } else if (!run && pulse != null) {
                pulse.cancel(); pulse = null; phase = 0; invalidate();
            }
        }
        @Override protected void onDetachedFromWindow() {
            if (transition != null) transition.cancel();
            if (pulse != null) { pulse.cancel(); pulse = null; }
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float gap = 4 * density;
            float width = Math.max(1, (getWidth() - 17 * gap) / 18);
            float bottom = getHeight() - 6 * density;
            float radius = Math.min(3 * density, width / 2);
            boolean on = StatusStore.ON.equals(state);
            for (int i = 0; i < 18; i++) {
                float light = Math.max(0, Math.min(1, level * 18 - i));
                int color = (int) evaluator.evaluate(light, palette.divider, palette.accent);
                float height = (i % 3 == 2 ? 30 : i % 3 == 0 ? 20 : 10) * density;
                paint.setColor(color);
                if (pulse != null) {
                    // On: bars breathe in height. Connecting: lit bars fade in a running wave.
                    double wave = Math.sin((phase - i / 18f) * Math.PI * 2);
                    if (on) height *= (float) (0.82 + 0.18 * wave);
                    else if (light > 0) paint.setAlpha((int) (255 * (0.8 + 0.2 * wave)));
                }
                float x = i * (width + gap);
                bar.set(x, bottom - height, x + width, bottom);
                canvas.drawRoundRect(bar, radius, radius, paint);
            }
        }
    }
}
