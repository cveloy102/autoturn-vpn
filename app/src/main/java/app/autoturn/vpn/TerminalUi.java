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

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int fill, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(0);
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
        android.text.SpannableString word = new android.text.SpannableString("AUTOTURN_");
        word.setSpan(new android.text.style.ForegroundColorSpan(palette.accent), 4, 9, 0);
        brand.setText(word);
        row.addView(brand, brandSize);
        TextView edition = text(context, "HL100\nVPN / CLIENT", 10, palette.muted);
        edition.setGravity(Gravity.END);
        row.addView(edition);
        root.addView(row);
        space(root, 20);
        divider(root, palette.divider);
        space(root, 27);
    }

    static TextView text(Context context, String content, int size, int color) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        return view;
    }

    static TextView title(LinearLayout root, ThemePalette palette, String content, int size) {
        TextView title = text(root.getContext(), content + "_", size, palette.text);
        setTitle(title, palette, content);
        title.setLetterSpacing(-.05f);
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
        button.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        button.setAllCaps(false);
        button.setTextColor(primary ? palette.onAccent : palette.text);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x18000000 | (palette.text & 0x00FFFFFF)),
                surface(context, primary ? palette.accent : palette.secondaryButton,
                        primary ? palette.accent : palette.border), null));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
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
            dialog.getWindow().setBackgroundDrawable(surface(dialog.getContext(), palette.card, palette.border));
        for (int which : new int[]{android.app.AlertDialog.BUTTON_POSITIVE,
                android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) {
                button.setTextColor(palette.accent);
                button.setTypeface(Typeface.MONOSPACE);
                button.setAllCaps(false);
            }
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTypeface(Typeface.MONOSPACE);
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

    static final class Signal extends View {
        private final Paint paint = new Paint();
        private final ThemePalette palette;
        private final android.animation.ArgbEvaluator evaluator = new android.animation.ArgbEvaluator();
        private String state;
        private ValueAnimator transition;
        private float level;
        public Signal(Context context) {
            this(context, ThemePalette.of(SecureStore.THEME_DARK), StatusStore.OFF);
        }
        Signal(Context context, ThemePalette palette, String state) {
            super(context);
            this.palette = palette;
            this.state = state;
            level = StatusStore.ON.equals(state) ? 1 : StatusStore.CONNECTING.equals(state) ? 7f / 18 : 0;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setState(String value) {
            if (value.equals(state)) return;
            state = value;
            float target = StatusStore.ON.equals(value) ? 1 : StatusStore.CONNECTING.equals(value) ? 7f / 18 : 0;
            if (transition != null) transition.cancel();
            if (!ValueAnimator.areAnimatorsEnabled()) { level = target; invalidate(); return; }
            transition = ValueAnimator.ofFloat(level, target);
            transition.setDuration(220);
            transition.addUpdateListener(animation -> { level = (float) animation.getAnimatedValue(); invalidate(); });
            transition.start();
        }
        @Override protected void onDetachedFromWindow() {
            if (transition != null) transition.cancel();
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            paint.setStrokeWidth(density);
            paint.setColor(palette.divider);
            paint.setAlpha(100);
            for (float x = 0; x < getWidth(); x += 18 * density)
                canvas.drawLine(x, 0, x, getHeight(), paint);
            for (float y = 0; y < getHeight(); y += 18 * density)
                canvas.drawLine(0, y, getWidth(), y, paint);
            paint.setAlpha(255);
            float gap = 4 * density;
            float width = Math.max(1, (getWidth() - 17 * gap) / 18);
            float bottom = getHeight() - 12 * density;
            for (int i = 0; i < 18; i++) {
                float light = Math.max(0, Math.min(1, level * 18 - i));
                paint.setColor((int) evaluator.evaluate(light, palette.divider, palette.accent));
                float height = (i % 3 == 2 ? 24 : i % 3 == 0 ? 16 : 8) * density;
                float x = i * (width + gap);
                canvas.drawRect(x, bottom - height, x + width, bottom, paint);
            }
        }
    }
}
