package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Touch ownership continues outside the original key bounds. */
@SuppressLint("ViewConstructor")
final class VirtualMouseBar extends LinearLayout {
    static final int MODE_EXPANDED = 0, MODE_THUMB_SPLIT = 1, MODE_MINI = 2;
    static final int WIDTH_DP = 164, HEIGHT_DP = 74, MINI_DP = 40;
    final FrameLayout dragHandle;
    final View miniOrb;
    private final LinearLayout expandedPanel;
    private final RemoteMouseInput input;
    private final MobileUi ui;
    private final TextView[] keys = new TextView[3];
    private int generation;
    private int mode = MODE_EXPANDED;
    Runnable onLayoutModeChanged;

    VirtualMouseBar(MobileUi ui, RemoteMouseInput input) {
        super(ui.context); this.ui = ui; this.input = input;
        setOrientation(VERTICAL);
        setMotionEventSplittingEnabled(true);

        expandedPanel = ui.column();
        GradientDrawable shell = ui.background(MobileUi.SURFACE, 18);
        shell.setStroke(ui.dp(.5f), MobileUi.LINE);
        expandedPanel.setBackground(shell);
        expandedPanel.setMotionEventSplittingEnabled(true);

        LinearLayout header = ui.row();
        header.setPadding(ui.dp(4), 0, ui.dp(2), 0);
        TextView thumbModeBtn = ui.text(L10n.tr("双球"), 10, true);
        thumbModeBtn.setTextColor(MobileUi.BLUE);
        thumbModeBtn.setGravity(Gravity.CENTER);
        thumbModeBtn.setContentDescription(L10n.tr("切换为极简双拇指鼠标"));
        GradientDrawable thumbChip = ui.background(0x283A8BFF, 10);
        thumbChip.setStroke(ui.dp(.5f), 0x803A8BFF);
        thumbModeBtn.setBackground(thumbChip);
        thumbModeBtn.setOnClickListener(v -> setMode(MODE_THUMB_SPLIT));
        header.addView(thumbModeBtn, new LayoutParams(ui.dp(38), ui.dp(20)));

        dragHandle = new FrameLayout(ui.context);
        dragHandle.setContentDescription(L10n.tr("拖动虚拟鼠标面板"));
        View grip = new View(ui.context);
        grip.setBackground(ui.background(MobileUi.SECONDARY, 2));
        dragHandle.addView(grip, new FrameLayout.LayoutParams(ui.dp(28), ui.dp(3), Gravity.CENTER));
        header.addView(dragHandle, new LayoutParams(0, ui.dp(24), 1));

        TextView collapseBtn = ui.text("×", 14, true);
        collapseBtn.setTextColor(MobileUi.SECONDARY);
        collapseBtn.setGravity(Gravity.CENTER);
        collapseBtn.setContentDescription(L10n.tr("收起为迷你鼠标"));
        collapseBtn.setOnClickListener(v -> setMode(MODE_MINI));
        header.addView(collapseBtn, new LayoutParams(ui.dp(28), ui.dp(24)));
        expandedPanel.addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(24)));

        LinearLayout row = ui.row();
        row.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        row.setMotionEventSplittingEnabled(true);
        expandedPanel.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(50)));
        keys[0] = key(L10n.tr("鼠标左键，按住并拖动，松开结束拖拽"), 0);
        keys[1] = key(L10n.tr("鼠标滚轮，上下滑动，可滑出按键范围"), 1);
        keys[2] = key(L10n.tr("鼠标右键，按住并拖动，松开结束拖拽"), 2);
        for (TextView key : keys) row.addView(key, new LayoutParams(0, ui.dp(46), 1));
        addView(expandedPanel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        miniOrb = new MiniMouseOrb(ui);
        miniOrb.setVisibility(GONE);
        miniOrb.setContentDescription(L10n.tr("展开虚拟鼠标"));
        addView(miniOrb, new LayoutParams(ui.dp(MINI_DP), ui.dp(MINI_DP)));

        input.didCancel = this::resetTouches;
        setContentDescription(L10n.tr("虚拟鼠标"));
    }

    int mode() { return mode; }
    boolean isCollapsed() { return mode == MODE_MINI; }
    boolean isThumbSplit() { return mode == MODE_THUMB_SPLIT; }
    int currentWidthDp() { return mode == MODE_EXPANDED ? WIDTH_DP : MINI_DP; }
    int currentHeightDp() { return mode == MODE_EXPANDED ? HEIGHT_DP : MINI_DP; }
    RemoteMouseInput mouseInput() { return input; }

    void setMode(int value) {
        int normalized = Math.max(MODE_EXPANDED, Math.min(MODE_MINI, value));
        if (mode == normalized) return;
        cancelInput();
        mode = normalized;
        expandedPanel.setVisibility(normalized == MODE_EXPANDED ? VISIBLE : GONE);
        miniOrb.setVisibility(normalized == MODE_EXPANDED ? GONE : VISIBLE);
        if (onLayoutModeChanged != null) onLayoutModeChanged.run();
    }

    void setCollapsed(boolean value) {
        setMode(value ? MODE_MINI : MODE_EXPANDED);
    }

    @SuppressLint("ClickableViewAccessibility") // Touch sends real down/up; performClick serves accessibility only.
    private TextView key(String description, int index) {
        TextView key = index == 1 ? new WheelKey(ui) : new TextView(ui.context);
        key.setGravity(android.view.Gravity.CENTER);
        key.setContentDescription(description);
        key.setClickable(true); key.setFocusable(true);
        highlight(key, index, false);
        key.setOnClickListener(v -> {
            if (index == 1) { ((WheelKey)key).advance(ui.dp(10)); input.scroll(-1); }
            else { input.virtualButton(index == 0, true); input.virtualButton(index == 0, false); }
        });
        if (index == 1) {
            key.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setScrollable(true);
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                }
                @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        int direction = action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? -1 : 1;
                        ((WheelKey)key).advance(-direction * ui.dp(10));
                        input.scroll(direction); return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
        key.setOnTouchListener(new View.OnTouchListener() {
            int pointer = -1, startedGeneration;
            float lastX, lastY, remainder;
            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0); startedGeneration = generation;
                    lastX = event.getRawX(); lastY = event.getRawY(); remainder = 0;
                    highlight(key, index, true);
                    if (index != 1) input.virtualButton(index == 0, true);
                    return true;
                }
                if (startedGeneration != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p < 0) return true;
                    // Use screen coordinates even if another finger moves the bar.
                    float x = event.getRawX() + (event.getX(p) - event.getX());
                    float y = event.getRawY() + (event.getY(p) - event.getY());
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x; lastY = y;
                    if (index != 1) { input.drag(dx, dy); return true; }
                    ((WheelKey)key).advance(dy);
                    remainder += dy;
                    float step = ui.dp(10);
                    int ticks = (int)(remainder / step);
                    // Positive protocol wheel values scroll up; finger-down scrolls down.
                    for (int i = 0; i < Math.min(Math.abs(ticks), 32); i++) input.scroll(ticks > 0 ? -1 : 1);
                    remainder -= ticks * step;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                    (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
                    if (index != 1) input.virtualButton(index == 0, false);
                    pointer = -1; remainder = 0; highlight(key, index, false);
                }
                return true;
            }
        });
        return key;
    }
    private void highlight(TextView key, int index, boolean pressed) {
        key.setPressed(pressed);
        key.setTextColor(pressed ? Color.WHITE : MobileUi.INK);
        GradientDrawable face = ui.background(pressed ? MobileUi.BLUE : MobileUi.SURFACE_ELEVATED, 6);
        float left = ui.dp(index == 0 ? 14 : 6), right = ui.dp(index == 2 ? 14 : 6);
        face.setCornerRadii(new float[]{left,left,right,right,right,right,left,left});
        int horizontal = ui.dp(index == 1 ? 13 : 1), vertical = ui.dp(index == 1 ? 7 : 2);
        key.setBackground(new InsetDrawable(face, horizontal, vertical, horizontal, vertical));
    }
    void cancelInput() {
        input.releaseAll();
    }
    void resetTouches() {
        generation++;
        for (int i=0;i<keys.length;i++) if (keys[i] != null) highlight(keys[i], i, false);
    }
    /** Compact floating mouse icon shown when collapsed. */
    private static final class MiniMouseOrb extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        MiniMouseOrb(MobileUi ui) {
            super(ui.context);
            density = getResources().getDisplayMetrics().density;
            GradientDrawable bg = ui.background(0xE61A1A1E, 20);
            bg.setStroke(ui.dp(1f), 0x80FFFFFF);
            setBackground(bg);
            setElevation(ui.dp(6));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float w = 13 * density, h = 19 * density;
            RectF body = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * density);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(body, w / 2f, w / 2f, paint);
            canvas.drawLine(cx, body.top + 3.2f * density, cx, body.top + 7.5f * density, paint);
        }
    }
    /** Flat, uniformly colored treads follow the finger, wrapping every 5 dp. */
    static final class WheelKey extends TextView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final float density;
        private float phase = 2;
        WheelKey(MobileUi ui) {
            super(ui.context); density = getResources().getDisplayMetrics().density;
            clip.addRoundRect(new RectF(0,0,12,24),2,2,Path.Direction.CW);
        }
        void advance(float distance) {
            phase = (phase + distance / density * .65f) % 5;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate((getWidth()-12*density)/2f, (getHeight()-24*density)/2f);
            canvas.scale(density,density);
            canvas.clipPath(clip);
            paint.setColor(getCurrentTextColor());
            for (int i=-1;i<=6;i++) {
                float y=i*5+phase;
                canvas.drawRect(0,y,12,y+1.5f,paint);
            }
            canvas.restore();
        }
    }
    @Override protected void onDetachedFromWindow() { cancelInput(); super.onDetachedFromWindow(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (!focused) cancelInput(); }
}
