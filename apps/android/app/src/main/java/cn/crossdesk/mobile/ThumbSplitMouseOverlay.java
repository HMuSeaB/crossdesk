package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Minimal twin-thumb floating mouse controls (left orb, right orb, edge wheel capsule). */
@SuppressLint("ViewConstructor")
final class ThumbSplitMouseOverlay extends FrameLayout {
    private static final int ORB_DP = 54, WHEEL_WIDTH_DP = 32, WHEEL_HEIGHT_DP = 128;
    private static final float DEFAULT_LEFT_X = 0.34f, DEFAULT_LEFT_Y = 0.86f;
    private static final float DEFAULT_RIGHT_X = 0.66f, DEFAULT_RIGHT_Y = 0.86f;
    private static final float DEFAULT_WHEEL_Y = 0.56f;

    private final MobileUi ui;
    private final RemoteMouseInput input;
    private final Consumer<String> onLayoutSaved;
    private final MouseOrbButton leftOrb, rightOrb;
    private final LinearLayout wheelCapsule;
    private final VirtualMouseBar.WheelKey wheelKey;

    private float leftXFrac = DEFAULT_LEFT_X, leftYFrac = DEFAULT_LEFT_Y;
    private float rightXFrac = DEFAULT_RIGHT_X, rightYFrac = DEFAULT_RIGHT_Y;
    private float wheelXFrac = Float.NaN, wheelYFrac = DEFAULT_WHEEL_Y;

    private int generation;
    private boolean editingLayout;
    private int lastContainerW, lastContainerH, lastKeyboardInset, lastRightAvoid;

    ThumbSplitMouseOverlay(MobileUi ui, RemoteMouseInput input, Consumer<String> onLayoutSaved) {
        super(ui.context);
        this.ui = ui;
        this.input = input;
        this.onLayoutSaved = onLayoutSaved;
        setMotionEventSplittingEnabled(true);
        setClickable(false);
        setFocusable(false);

        leftOrb = new MouseOrbButton(ui, true);
        leftOrb.setContentDescription(L10n.tr("极简鼠标左键，按住可在屏幕拖动"));
        addView(leftOrb, new LayoutParams(ui.dp(ORB_DP), ui.dp(ORB_DP)));

        rightOrb = new MouseOrbButton(ui, false);
        rightOrb.setContentDescription(L10n.tr("极简鼠标右键，轻触或按住拖动"));
        addView(rightOrb, new LayoutParams(ui.dp(ORB_DP), ui.dp(ORB_DP)));

        wheelCapsule = new LinearLayout(ui.context);
        wheelCapsule.setOrientation(LinearLayout.VERTICAL);
        wheelCapsule.setGravity(Gravity.CENTER_HORIZONTAL);
        wheelCapsule.setElevation(ui.dp(6));
        wheelCapsule.setContentDescription(L10n.tr("极简鼠标滚轮条"));

        TextView upArrow = ui.text("▲", 9, true);
        upArrow.setTextColor(0xCCFFFFFF);
        upArrow.setGravity(Gravity.CENTER);
        upArrow.setClickable(true);
        upArrow.setOnClickListener(v -> {
            if (editingLayout) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            wheelKey.advance(-ui.dp(10));
            input.scroll(1);
        });
        wheelCapsule.addView(upArrow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(24)));

        wheelKey = new VirtualMouseBar.WheelKey(ui);
        wheelKey.setTextColor(Color.WHITE);
        wheelKey.setClickable(true);
        wheelKey.setContentDescription(L10n.tr("上下滑动滚动滚轮"));
        wheelCapsule.addView(wheelKey, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView downArrow = ui.text("▼", 9, true);
        downArrow.setTextColor(0xCCFFFFFF);
        downArrow.setGravity(Gravity.CENTER);
        downArrow.setClickable(true);
        downArrow.setOnClickListener(v -> {
            if (editingLayout) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            wheelKey.advance(ui.dp(10));
            input.scroll(-1);
        });
        wheelCapsule.addView(downArrow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(24)));

        addView(wheelCapsule, new LayoutParams(ui.dp(WHEEL_WIDTH_DP), ui.dp(WHEEL_HEIGHT_DP)));
        setupWheelTouch(upArrow, downArrow);
        refreshVisuals();
    }

    boolean isEditingLayout() { return editingLayout; }

    void setEditingLayout(boolean editing) {
        if (editingLayout == editing) return;
        resetTouches();
        input.releaseAll();
        editingLayout = editing;
        refreshVisuals();
        if (!editing && onLayoutSaved != null) {
            onLayoutSaved.accept(serializeLayout());
        }
    }

    void resetTouches() {
        generation++;
        leftOrb.setPressedState(false);
        rightOrb.setPressedState(false);
        refreshWheelBackground(false);
    }

    void loadLayout(String json) {
        leftXFrac = DEFAULT_LEFT_X;
        leftYFrac = DEFAULT_LEFT_Y;
        rightXFrac = DEFAULT_RIGHT_X;
        rightYFrac = DEFAULT_RIGHT_Y;
        wheelXFrac = Float.NaN;
        wheelYFrac = DEFAULT_WHEEL_Y;
        if (json != null && !json.trim().isEmpty()) {
            try {
                JSONObject obj = new JSONObject(json);
                if (obj.has("lx")) leftXFrac = clamp01((float) obj.optDouble("lx", DEFAULT_LEFT_X));
                if (obj.has("ly")) leftYFrac = clamp01((float) obj.optDouble("ly", DEFAULT_LEFT_Y));
                if (obj.has("rx")) rightXFrac = clamp01((float) obj.optDouble("rx", DEFAULT_RIGHT_X));
                if (obj.has("ry")) rightYFrac = clamp01((float) obj.optDouble("ry", DEFAULT_RIGHT_Y));
                if (obj.has("wx") && !obj.isNull("wx")) {
                    double wx = obj.optDouble("wx", Double.NaN);
                    if (Double.isFinite(wx)) wheelXFrac = clamp01((float) wx);
                }
                if (obj.has("wy")) wheelYFrac = clamp01((float) obj.optDouble("wy", DEFAULT_WHEEL_Y));
            } catch (Exception ignored) {
            }
        }
        applyPositions();
    }

    String serializeLayout() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("lx", round3(leftXFrac));
            obj.put("ly", round3(leftYFrac));
            obj.put("rx", round3(rightXFrac));
            obj.put("ry", round3(rightYFrac));
            if (Float.isFinite(wheelXFrac)) obj.put("wx", round3(wheelXFrac));
            obj.put("wy", round3(wheelYFrac));
            return obj.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    void updateBounds(int containerW, int containerH, int keyboardBottomInset, int rightAvoidPx) {
        lastContainerW = containerW;
        lastContainerH = containerH;
        lastKeyboardInset = Math.max(0, keyboardBottomInset);
        lastRightAvoid = Math.max(0, rightAvoidPx);
        applyPositions();
    }

    private void applyPositions() {
        if (lastContainerW <= 0 || lastContainerH <= 0) return;
        int margin = ui.dp(8);
        int orbSize = ui.dp(ORB_DP);
        int wheelW = ui.dp(WHEEL_WIDTH_DP);
        int wheelH = ui.dp(WHEEL_HEIGHT_DP);

        int maxBottom = lastContainerH - margin;
        if (lastKeyboardInset > 0) {
            maxBottom = Math.min(maxBottom, lastContainerH - lastKeyboardInset - ui.dp(6));
        }

        positionChild(leftOrb, orbSize, orbSize, margin, lastContainerW - orbSize - margin,
                margin, Math.max(margin, maxBottom - orbSize), leftXFrac, leftYFrac);
        positionChild(rightOrb, orbSize, orbSize, margin, lastContainerW - orbSize - margin,
                margin, Math.max(margin, maxBottom - orbSize), rightXFrac, rightYFrac);

        int wheelMaxX = Math.max(margin, lastContainerW - wheelW - margin - lastRightAvoid);
        int wheelMaxY = Math.max(margin, maxBottom - wheelH);
        if (Float.isFinite(wheelXFrac)) {
            positionChild(wheelCapsule, wheelW, wheelH, margin, wheelMaxX, margin, wheelMaxY, wheelXFrac, wheelYFrac);
        } else {
            float defaultX = Math.max(margin, lastContainerW - lastRightAvoid - wheelW - ui.dp(10));
            float yRange = Math.max(0, wheelMaxY - margin);
            wheelCapsule.setTranslationX(bound(defaultX, margin, Math.max(margin, lastContainerW - wheelW - margin)));
            wheelCapsule.setTranslationY(margin + yRange * wheelYFrac);
        }
    }

    private void positionChild(View child, int w, int h, float minX, float maxX, float minY, float maxY, float xFrac, float yFrac) {
        float xRange = Math.max(0, maxX - minX);
        float yRange = Math.max(0, maxY - minY);
        child.setTranslationX(bound(minX + xRange * xFrac, minX, Math.max(minX, maxX)));
        child.setTranslationY(bound(minY + yRange * yFrac, minY, Math.max(minY, maxY)));
    }

    private void refreshVisuals() {
        leftOrb.invalidate();
        rightOrb.invalidate();
        refreshWheelBackground(false);
    }

    private void refreshWheelBackground(boolean active) {
        GradientDrawable bg = ui.background(active ? 0xD92E7ADB : 0xB81E1E24, 16);
        bg.setStroke(ui.dp(editingLayout ? 2f : 1.2f), editingLayout ? MobileUi.BLUE : 0x80FFFFFF);
        wheelCapsule.setBackground(bg);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupWheelTouch(View upArrow, View downArrow) {
        View.OnTouchListener dragInEdit = new View.OnTouchListener() {
            int pointer = -1;
            float startRawX, startRawY, startTransX, startTransY;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                if (!editingLayout) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startTransX = wheelCapsule.getTranslationX();
                    startTransY = wheelCapsule.getTranslationY();
                    moved = false;
                    return true;
                }
                if (pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p < 0) return true;
                    float dx = event.getRawX() - startRawX;
                    float dy = event.getRawY() - startRawY;
                    if (!moved && Math.hypot(dx, dy) > ui.dp(3)) moved = true;
                    if (moved) moveWheelTo(startTransX + dx, startTransY + dy);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
                    pointer = -1;
                }
                return true;
            }
        };
        upArrow.setOnTouchListener(dragInEdit);
        downArrow.setOnTouchListener(dragInEdit);

        wheelKey.setOnTouchListener(new View.OnTouchListener() {
            int pointer = -1, startedGeneration;
            float lastRawX, lastRawY, startTransX, startTransY, remainder;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGeneration = generation;
                    lastRawX = event.getRawX();
                    lastRawY = event.getRawY();
                    startTransX = wheelCapsule.getTranslationX();
                    startTransY = wheelCapsule.getTranslationY();
                    remainder = 0;
                    if (!editingLayout) refreshWheelBackground(true);
                    return true;
                }
                if (startedGeneration != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p < 0) return true;
                    float rawX = event.getRawX() + (event.getX(p) - event.getX());
                    float rawY = event.getRawY() + (event.getY(p) - event.getY());
                    if (editingLayout) {
                        moveWheelTo(startTransX + (rawX - lastRawX), startTransY + (rawY - lastRawY));
                        return true;
                    }
                    float dy = rawY - lastRawY;
                    lastRawX = rawX;
                    lastRawY = rawY;
                    wheelKey.advance(dy);
                    remainder += dy;
                    float step = ui.dp(10);
                    int ticks = (int) (remainder / step);
                    for (int i = 0; i < Math.min(Math.abs(ticks), 32); i++) {
                        input.scroll(ticks > 0 ? -1 : 1);
                    }
                    remainder -= ticks * step;
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
                    pointer = -1;
                    remainder = 0;
                    refreshWheelBackground(false);
                }
                return true;
            }
        });
    }

    private void moveWheelTo(float targetX, float targetY) {
        if (lastContainerW <= 0 || lastContainerH <= 0) return;
        int margin = ui.dp(8);
        int wheelW = ui.dp(WHEEL_WIDTH_DP);
        int wheelH = ui.dp(WHEEL_HEIGHT_DP);
        int maxBottom = lastContainerH - margin;
        if (lastKeyboardInset > 0) {
            maxBottom = Math.min(maxBottom, lastContainerH - lastKeyboardInset - ui.dp(6));
        }
        int maxX = Math.max(margin, lastContainerW - wheelW - margin - lastRightAvoid);
        int maxY = Math.max(margin, maxBottom - wheelH);
        float clampedX = bound(targetX, margin, Math.max(margin, maxX));
        float clampedY = bound(targetY, margin, Math.max(margin, maxY));
        wheelCapsule.setTranslationX(clampedX);
        wheelCapsule.setTranslationY(clampedY);
        wheelXFrac = maxX > margin ? (clampedX - margin) / (maxX - margin) : 1f;
        wheelYFrac = maxY > margin ? (clampedY - margin) / (maxY - margin) : 0.5f;
    }

    private void moveOrbTo(MouseOrbButton orb, boolean isLeft, float targetX, float targetY) {
        if (lastContainerW <= 0 || lastContainerH <= 0) return;
        int margin = ui.dp(8);
        int orbSize = ui.dp(ORB_DP);
        int maxBottom = lastContainerH - margin;
        if (lastKeyboardInset > 0) {
            maxBottom = Math.min(maxBottom, lastContainerH - lastKeyboardInset - ui.dp(6));
        }
        int maxX = Math.max(margin, lastContainerW - orbSize - margin);
        int maxY = Math.max(margin, maxBottom - orbSize);
        float clampedX = bound(targetX, margin, Math.max(margin, maxX));
        float clampedY = bound(targetY, margin, Math.max(margin, maxY));
        orb.setTranslationX(clampedX);
        orb.setTranslationY(clampedY);
        float xFrac = maxX > margin ? (clampedX - margin) / (maxX - margin) : (isLeft ? 0.34f : 0.66f);
        float yFrac = maxY > margin ? (clampedY - margin) / (maxY - margin) : 0.86f;
        if (isLeft) {
            leftXFrac = xFrac;
            leftYFrac = yFrac;
        } else {
            rightXFrac = xFrac;
            rightYFrac = yFrac;
        }
    }

    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float bound(float value, float min, float max) { return Math.max(min, Math.min(value, Math.max(min, max))); }
    private static double round3(float value) { return Math.round(value * 1000.0) / 1000.0; }

    /** Floating circular mouse button (left or right half highlighted). */
    private final class MouseOrbButton extends View {
        private final boolean isLeft;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private boolean pressedState;
        private int pointer = -1, startedGeneration;
        private float lastRawX, lastRawY, startTransX, startTransY;

        MouseOrbButton(MobileUi ui, boolean isLeft) {
            super(ui.context);
            this.isLeft = isLeft;
            this.density = getResources().getDisplayMetrics().density;
            setClickable(true);
            setFocusable(true);
            setElevation(ui.dp(6));
        }

        void setPressedState(boolean pressed) {
            if (pressedState == pressed) return;
            pressedState = pressed;
            invalidate();
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pointer = event.getPointerId(0);
                startedGeneration = generation;
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                startTransX = getTranslationX();
                startTransY = getTranslationY();
                if (!editingLayout) {
                    setPressedState(true);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    input.virtualButton(isLeft, true);
                }
                return true;
            }
            if (startedGeneration != generation || pointer == -1) return true;
            if (action == MotionEvent.ACTION_MOVE) {
                int p = event.findPointerIndex(pointer);
                if (p < 0) return true;
                float rawX = event.getRawX() + (event.getX(p) - event.getX());
                float rawY = event.getRawY() + (event.getY(p) - event.getY());
                if (editingLayout) {
                    moveOrbTo(this, isLeft, startTransX + (rawX - lastRawX), startTransY + (rawY - lastRawY));
                    return true;
                }
                float dx = rawX - lastRawX;
                float dy = rawY - lastRawY;
                lastRawX = rawX;
                lastRawY = rawY;
                input.drag(dx, dy);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
                if (!editingLayout) {
                    input.virtualButton(isLeft, false);
                }
                pointer = -1;
                setPressedState(false);
                if (action == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - 1.5f * density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pressedState ? 0xE63A8BFF : 0xB81E1E24);
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((editingLayout ? 2f : 1.2f) * density);
            paint.setColor(editingLayout ? MobileUi.BLUE : (pressedState ? Color.WHITE : 0x80FFFFFF));
            canvas.drawCircle(cx, cy, radius, paint);

            float w = 18 * density;
            float h = 26 * density;
            float left = cx - w / 2f;
            float top = cy - h / 2f;
            float right = cx + w / 2f;
            float bottom = cy + h / 2f;
            float corner = w / 2f;
            float splitY = top + h * 0.44f;

            // Highlight left or right button half inside the mouse silhouette.
            canvas.save();
            android.graphics.Path bodyPath = new android.graphics.Path();
            bodyPath.addRoundRect(new RectF(left, top, right, bottom), corner, corner, android.graphics.Path.Direction.CW);
            canvas.clipPath(bodyPath);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pressedState ? Color.WHITE : MobileUi.BLUE);
            if (isLeft) {
                canvas.drawRect(left, top, cx, splitY, paint);
            } else {
                canvas.drawRect(cx, top, right, splitY, paint);
            }
            canvas.restore();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), corner, corner, paint);
            canvas.drawLine(left, splitY, right, splitY, paint);
            canvas.drawLine(cx, top, cx, splitY, paint);
        }
    }
}
