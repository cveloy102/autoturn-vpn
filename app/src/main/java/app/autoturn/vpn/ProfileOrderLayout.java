package app.autoturn.vpn;

import android.content.ClipData;
import android.content.Context;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/** Native, group-local drag ordering. Persistence happens only on a completed drop. */
final class ProfileOrderLayout extends LinearLayout {
    interface Listener {
        void dragging(boolean active);
        void reordered(List<String> ids);
    }
    private final ScrollView scroll;
    private final Listener listener;
    private View dragged;
    private int originalIndex;
    private boolean dropped;
    private float screenY;
    private int scrollStep;
    private final int[] location = new int[2];
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragged == null || scrollStep == 0) return;
            scroll.scrollBy(0, scrollStep);
            getLocationOnScreen(location);
            preview(screenY - location[1]);
            postDelayed(this, 16);
        }
    };

    ProfileOrderLayout(Context context, ScrollView scroll, Listener listener) {
        super(context); this.scroll = scroll; this.listener = listener;
        setOrientation(VERTICAL);
        setOnDragListener((view, event) -> drag(event));
    }

    void attachHandle(View handle, View row) {
        handle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(view, row);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
            return true;
        });
        handle.setOnClickListener(v -> { });
        handle.setOnLongClickListener(view -> { begin(view, row); return true; });
    }

    private void begin(View handle, View row) {
        if (dragged != null || getChildCount() < 2) return;
        dragged = row; originalIndex = indexOfChild(row); dropped = false;
        listener.dragging(true);
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(row) {
            @Override public void onProvideShadowMetrics(android.graphics.Point size, android.graphics.Point touch) {
                super.onProvideShadowMetrics(size, touch);
                touch.set(handle.getLeft() + handle.getWidth() / 2, row.getHeight() / 2);
            }
        };
        boolean started = handle.startDragAndDrop(ClipData.newPlainText("profile-order", String.valueOf(row.getTag())),
                shadow, this, 0);
        if (started) {
            row.setAlpha(.4f);
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else finish();
    }

    private boolean drag(DragEvent event) {
        if (event.getLocalState() != this) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return dragged != null;
            case DragEvent.ACTION_DRAG_LOCATION:
                getLocationOnScreen(location); screenY = location[1] + event.getY();
                preview(event.getY());
                scroll.getLocationOnScreen(location);
                int edge = TerminalUi.dp(getContext(), 56), step = TerminalUi.dp(getContext(), 8);
                float y = screenY - location[1];
                int next = y < edge ? -step : y > scroll.getHeight() - edge ? step : 0;
                if (next != scrollStep) {
                    removeCallbacks(autoScroll); scrollStep = next;
                    if (next != 0) post(autoScroll);
                }
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                scrollStep = 0; removeCallbacks(autoScroll); return true;
            case DragEvent.ACTION_DROP:
                preview(event.getY()); dropped = true;
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < getChildCount(); i++) ids.add(String.valueOf(getChildAt(i).getTag()));
                listener.reordered(ids); return true;
            case DragEvent.ACTION_DRAG_ENDED: finish(); return true;
            default: return true;
        }
    }

    private void preview(float y) {
        if (dragged == null) return;
        int target = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != dragged && y > (child.getTop() + child.getBottom()) / 2f) target++;
        }
        if (target != indexOfChild(dragged)) {
            removeView(dragged); addView(dragged, target);
        }
    }

    private void finish() {
        removeCallbacks(autoScroll); scrollStep = 0;
        if (dragged != null) {
            dragged.setAlpha(1);
            if (!dropped && indexOfChild(dragged) != originalIndex) {
                removeView(dragged); addView(dragged, Math.min(originalIndex, getChildCount()));
            }
            dragged = null; listener.dragging(false);
        }
    }

    @Override protected void onDetachedFromWindow() { finish(); super.onDetachedFromWindow(); }
}
