package cn.crossdesk.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

@android.annotation.SuppressLint("ViewConstructor") // Constructed only with a live session, never from XML.
final class RemoteVideoView extends FrameLayout implements SurfaceHolder.Callback {
    interface ViewportListener {
        void onViewportChanged(float left, float top, float width, float height,
                               int containerWidth, int containerHeight, boolean zoomed);
    }
    private final SurfaceView video;
    private final Cursor cursor;
    private final NativeSession session;
    final RemoteMouseInput mouseInput;
    Runnable remoteFocusChanged;
    ViewportListener viewportListener;
    private final GestureDetector gestures;
    private final ScrollInertiaHelper scrollInertia;
    private final float touchSlop;
    private final float pinchSlop;
    private final RemoteViewport viewport = new RemoteViewport();
    private final RemoteViewport pictureInPictureViewport = new RemoteViewport();
    private boolean pictureInPicture;
    private int frameWidth = 16, frameHeight = 9;
    private float x = .5f, y = .5f, scrollY;
    private boolean dragging, twoFinger, relative, pinching, viewportPanActive, viewOnly;
    private float lastX,lastY,twoFingerX,twoFingerY;
    private float pinchSpan;
    private long touchRevision;
    private boolean suppressPointerGesture;
    private float touchScrollRemainder;
    private boolean longPressArmed;
    private float longPressStartX, longPressStartY;
    private long twoFingerDownTime;
    private float twoFingerStartX, twoFingerStartY;
    private int twoFingerMaxCount;
    private boolean twoFingerMoved;
    private boolean pointerAcceleration = true;
    private long lastRelativeMoveTime;
    private boolean singleFingerScrolling, twoFingerScrolling;
    void setRelative(boolean value) { releaseMouse(); relative=value; }
    void setPointerAcceleration(boolean value) { pointerAcceleration = value; }
    void setViewOnly(boolean value) {
        viewOnly = value;
        if (value) releaseMouse();
        cursor.setVisibility((pictureInPicture || viewOnly) ? GONE : VISIBLE);
    }
    void setPictureInPicture(boolean value) {
        if(pictureInPicture==value)return;
        releaseMouse();pictureInPicture=value;cursor.setVisibility((value||viewOnly)?GONE:VISIBLE);fit();
    }
    Rect pictureInPictureBounds() {
        Rect bounds=new Rect();video.getGlobalVisibleRect(bounds);return bounds;
    }
    void releaseMouse() {
        mouseInput.releaseAll();
        dragging=false; longPressArmed=false; suppressPointerGesture=true;
        lastRelativeMoveTime=0; singleFingerScrolling=false; twoFingerScrolling=false;
        if (scrollInertia != null) scrollInertia.stop();
    }
    private boolean suppressPointerGesture() {
        return suppressPointerGesture || mouseInput.hasVirtualButton() || touchRevision != mouseInput.revision();
    }
    RemoteVideoView(Context context, NativeSession session) {
        super(context); this.session = session;
        mouseInput = new RemoteMouseInput(this::sendMouse);
        mouseInput.relativeMotion = this::moveRelative;
        float density = getResources().getDisplayMetrics().density;
        scrollInertia = new ScrollInertiaHelper(density, 16f, dir -> {
            if (!viewOnly && !pictureInPicture) session.pointer(x, y, 7, dir);
        }, null);
        setBackgroundColor(Color.BLACK);
        video = new SurfaceView(context); video.getHolder().addCallback(this);
        addView(video, new LayoutParams(1, 1, android.view.Gravity.CENTER));
        cursor = new Cursor(context);
        // Keep input in container coordinates so transforms never move its hit area.
        addView(cursor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                touchScrollRemainder = 0;
                singleFingerScrolling = false;
                scrollInertia.stop();
                return viewOnly || point(e);
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (!viewOnly && !twoFinger && !suppressPointerGesture() && point(e)) {
                    cursor.performClick();
                }
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (viewOnly || twoFinger || dragging || suppressPointerGesture()) return;
                if (!usesRelativeMotion(e) && !point(e)) return;
                longPressArmed = true;
                longPressStartX = e.getX();
                longPressStartY = e.getY();
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (viewOnly || twoFinger || longPressArmed) return true;
                if (dragging || mouseInput.hasVirtualButton() || usesRelativeMotion(current)) {
                    point(current);
                    return true;
                }
                // Screen touch mode: single-finger swipe scrolls page instead of selecting text.
                if (!singleFingerScrolling) {
                    singleFingerScrolling = true;
                    scrollInertia.begin(first != null ? first.getY() : current.getY(),
                            first != null ? first.getEventTime() : current.getEventTime());
                }
                scrollInertia.track(current.getY(), current.getEventTime());
                touchScrollRemainder += dy;
                float step = 16 * getResources().getDisplayMetrics().density;
                int ticks = (int) (touchScrollRemainder / step);
                if (ticks != 0) {
                    for (int i = 0; i < Math.min(Math.abs(ticks), 12); i++) {
                        session.pointer(x, y, 7, ticks > 0 ? -1 : 1);
                    }
                    touchScrollRemainder -= ticks * step;
                }
                return true;
            }
        });
        // Let the remote desktop detect double-clicks; the default listener consumes the second tap.
        gestures.setOnDoubleTapListener(null);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // Detect a change in finger spacing even when the fingers start close together.
        pinchSlop = touchSlop * .5f;
        cursor.setOnGenericMotionListener((v, event) -> {
            if (viewOnly || !point(event)) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (delta != 0) session.pointer(x, y, 7, delta > 0 ? 1 : -1);
            }
            return true;
        });
        setContentDescription(L10n.tr("远程桌面：轻触点击，屏幕触控单指滑动滚轮翻页，长按微震后拖动左键拖拽、原地松手右键，双指轻点右键，双指捏合缩放，放大后双指拖动画面，原始比例下双指上下滑动滚动"));
    }
    void videoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        frameWidth = width; frameHeight = height; fit();
    }
    void resetViewport() { releaseMouse(); viewport.reset(); applyViewport(); }
    void setKeyboardInset(int inset) {
        if (viewport.setKeyboardInset(inset)) applyViewport();
    }
    static Rect previewCrop(int width,int height){
        int w=width,h=height;
        if((long)width*RemotePreviewStore.HEIGHT>(long)height*RemotePreviewStore.WIDTH)w=Math.max(1,height*RemotePreviewStore.WIDTH/RemotePreviewStore.HEIGHT);
        else h=Math.max(1,width*RemotePreviewStore.HEIGHT/RemotePreviewStore.WIDTH);
        return new Rect((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2);
    }
    void capturePreview(java.util.function.Consumer<Bitmap> completion){
        // Copy only the decoded-video Surface, excluding cursor, controls, keyboard and black margins.
        if(!isAttachedToWindow()||!video.getHolder().getSurface().isValid()){completion.accept(null);return;}
        Bitmap image=Bitmap.createBitmap(RemotePreviewStore.WIDTH,RemotePreviewStore.HEIGHT,Bitmap.Config.ARGB_8888);
        try{
            PixelCopy.request(video.getHolder().getSurface(),previewCrop(frameWidth,frameHeight),image,result->{
                if(result==PixelCopy.SUCCESS)completion.accept(image);else{image.recycle();completion.accept(null);}
            },new Handler(Looper.getMainLooper()));
        }catch(IllegalArgumentException error){image.recycle();completion.accept(null);}
    }
    private void fit() {
        if (getWidth() == 0 || getHeight() == 0) return;
        RemoteViewport active=pictureInPicture?pictureInPictureViewport:viewport;
        active.fit(getWidth(), getHeight(), frameWidth, frameHeight);
        video.setLayoutParams(new LayoutParams(active.baseWidth(),active.baseHeight(),android.view.Gravity.CENTER));
        applyViewport();
    }
    private void applyViewport() {
        // Transform the existing Surface without enlarging its decoder buffers.
        RemoteViewport active=pictureInPicture?pictureInPictureViewport:viewport;
        video.setScaleX(active.scale()); video.setScaleY(active.scale());
        video.setTranslationX(active.offsetX()); video.setTranslationY(active.offsetY());
        mouseInput.viewportWidth = active.width(); mouseInput.viewportHeight = active.height();
        cursor.invalidate();
        if (!pictureInPicture && viewportListener != null && viewport.containerWidth() > 0 && viewport.containerHeight() > 0) {
            viewportListener.onViewportChanged(viewport.left(), viewport.top(), viewport.width(), viewport.height(),
                    viewport.containerWidth(), viewport.containerHeight(), viewport.isZoomed());
        }
    }
    private void sendMouse(float x, float y, int flag, int wheel) {
        this.x = x; this.y = y;
        if((flag==1||flag==3||flag==5)&&remoteFocusChanged!=null)remoteFocusChanged.run();
        session.pointer(x,y,flag,wheel);
        cursor.invalidate();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { post(this::fit); }
    private void moveRelative(float dx, float dy) {
        if (pictureInPicture || viewport.width() <= 0 || viewport.height() <= 0) return;
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || (dx == 0 && dy == 0)) return;
        if (pointerAcceleration) {
            long now = android.os.SystemClock.uptimeMillis();
            float gain = 1f;
            if (lastRelativeMoveTime > 0) {
                long dtMs = Math.max(1L, Math.min(120L, now - lastRelativeMoveTime));
                float density = Math.max(1f, getResources().getDisplayMetrics().density);
                float speedDpPerSec = (float) Math.hypot(dx, dy) / density * 1000f / dtMs;
                if (speedDpPerSec < 80f) {
                    gain = 0.65f + (speedDpPerSec / 80f) * 0.35f;
                } else if (speedDpPerSec <= 400f) {
                    gain = 1.0f + ((speedDpPerSec - 80f) / 320f) * 0.45f;
                } else {
                    gain = Math.min(2.5f, 1.45f + ((speedDpPerSec - 400f) / 700f) * 1.05f);
                }
            }
            lastRelativeMoveTime = now;
            dx *= gain;
            dy *= gain;
        }
        float nextX = Math.max(0, Math.min(1, x + dx / viewport.width()));
        float nextY = Math.max(0, Math.min(1, y + dy / viewport.height()));
        viewport.panForPointer(nextX, nextY, y, dy, 18 * getResources().getDisplayMetrics().density);
        applyViewport();
        mouseInput.move(nextX, nextY);
    }
    private boolean usesRelativeMotion(MotionEvent event) {
        return relative && event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE;
    }
    void moveCursorAtViewCoords(float px, float py) {
        if (viewOnly || !viewport.contains(px, py)) return;
        x = viewport.normalizedX(px);
        y = viewport.normalizedY(py);
        lastX = px; lastY = py;
        mouseInput.move(x, y);
        cursor.invalidate();
    }
    private boolean point(MotionEvent e) {
        if (viewOnly) return false;
        boolean touchpad = usesRelativeMotion(e);
        float px=e.getX(),py=e.getY();
        if (!touchpad && !viewport.contains(px,py)) return false;
        if(touchpad){
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                moveRelative(px-lastX,py-lastY);
                lastX=px;lastY=py;
                return true;
            }
        }else{
            int action = e.getActionMasked();
            if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) &&
                    (px != lastX || py != lastY) &&
                    viewport.panForAbsolutePointer(px, py, 18 * getResources().getDisplayMetrics().density)) {
                applyViewport();
            }
            x=viewport.normalizedX(px);
            y=viewport.normalizedY(py);
        }
        lastX=px;lastY=py;
        mouseInput.move(x,y); cursor.invalidate();
        return true;
    }
    private void click(int down, int up) { mouseInput.click(down,up); }
    private boolean touch(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            twoFinger = pinching = viewportPanActive = false;
            longPressArmed = false;
            twoFingerMoved = false;
            twoFingerMaxCount = 0;
            twoFingerDownTime = 0;
            lastRelativeMoveTime = 0;
            singleFingerScrolling = false;
            twoFingerScrolling = false;
            scrollInertia.stop();
            touchRevision = mouseInput.revision();
            suppressPointerGesture = mouseInput.hasVirtualButton();
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            longPressArmed = false;
            singleFingerScrolling = false;
            if (dragging) { mouseInput.gestureLeft(false); dragging = false; }
            if (!twoFinger) {
                scrollInertia.stop();
                twoFinger = true;
                twoFingerDownTime = android.os.SystemClock.elapsedRealtime();
                twoFingerMaxCount = e.getPointerCount();
                twoFingerMoved = twoFingerMaxCount > 2;
                if (e.getPointerCount() >= 2) {
                    twoFingerStartX = (e.getX(0) + e.getX(1)) * 0.5f;
                    twoFingerStartY = (e.getY(0) + e.getY(1)) * 0.5f;
                }
                MotionEvent cancel = MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL);
                gestures.onTouchEvent(cancel); cancel.recycle();
            } else {
                twoFingerMaxCount = Math.max(twoFingerMaxCount, e.getPointerCount());
                if (e.getPointerCount() > 2) twoFingerMoved = true;
            }
        }
        if (twoFinger && e.getPointerCount() >= 2 && (action == MotionEvent.ACTION_MOVE ||
                action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
            updateTwoFingers(e);
        } else if (!twoFinger && !viewOnly) {
            if ((e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 && action == MotionEvent.ACTION_DOWN) {
                if (point(e)) click(3,4); return true;
            }
            if (action == MotionEvent.ACTION_MOVE && longPressArmed) {
                if (suppressPointerGesture()) {
                    longPressArmed = false;
                    if (mouseInput.hasVirtualButton()) {
                        point(e);
                        return true;
                    }
                } else if (Math.hypot(e.getX() - longPressStartX, e.getY() - longPressStartY) >= touchSlop) {
                    longPressArmed = false;
                    lastX = longPressStartX;
                    lastY = longPressStartY;
                    mouseInput.gestureLeft(true);
                    dragging = true;
                    point(e);
                    return true;
                } else {
                    return true;
                }
            }
            if (action == MotionEvent.ACTION_MOVE && dragging) {
                point(e);
                return true;
            }
            if (action == MotionEvent.ACTION_UP && longPressArmed && !dragging && !suppressPointerGesture()) {
                longPressArmed = false;
                click(3, 4);
            }
            gestures.onTouchEvent(e);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && twoFinger && !viewOnly && !suppressPointerGesture()
                    && twoFingerMaxCount == 2 && !twoFingerMoved && !pinching && !viewportPanActive
                    && (android.os.SystemClock.elapsedRealtime() - twoFingerDownTime <= 280)) {
                if (!usesRelativeMotion(e)) {
                    if (viewport.contains(twoFingerStartX, twoFingerStartY)) {
                        x = viewport.normalizedX(twoFingerStartX);
                        y = viewport.normalizedY(twoFingerStartY);
                        mouseInput.move(x, y);
                        click(3, 4);
                    }
                } else {
                    click(3, 4);
                }
            }
            if (action == MotionEvent.ACTION_UP && !viewOnly && !dragging && !suppressPointerGesture()) {
                if (singleFingerScrolling) {
                    scrollInertia.fling(1);
                } else if (twoFingerScrolling && twoFinger && !pinching && !viewportPanActive) {
                    scrollInertia.fling(1);
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                scrollInertia.stop();
            }
            if (dragging) mouseInput.gestureLeft(false);
            dragging = false;
            longPressArmed = false;
            twoFinger = false;
            twoFingerMoved = false;
            twoFingerMaxCount = 0;
            singleFingerScrolling = false;
            twoFingerScrolling = false;
            viewport.finishGesture(); applyViewport();
            viewportPanActive = false;
        }
        return true;
    }
    private void updateTwoFingers(MotionEvent event) {
        int action = event.getActionMasked();
        int lifted = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        int count = event.getPointerCount() - (lifted >= 0 ? 1 : 0);
        float focusX = 0, focusY = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == lifted) continue;
            focusX += event.getX(i);
            focusY += event.getY(i);
        }
        focusX /= count;
        focusY /= count;
        float radiusSum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == lifted) continue;
            radiusSum += (float) Math.hypot(event.getX(i) - focusX, event.getY(i) - focusY);
        }
        float span = Math.max(pinchSlop, 2 * radiusSum / count);
        boolean canMoveViewport = !mouseInput.hasVirtualButton() && viewport.width() > 0;
        if (action == MotionEvent.ACTION_MOVE && canMoveViewport) {
            moveTwoFingers(focusX, focusY, span, event.getEventTime());
        } else {
            // A change of contacts establishes a new baseline without moving the image.
            pinchSpan = span;
            pinching = pinching && count >= 2 && canMoveViewport;
            viewportPanActive = viewport.isZoomed() && viewport.contains(focusX, focusY);
            scrollY = focusY;
            twoFingerScrolling = false;
            scrollInertia.begin(focusY, event.getEventTime());
        }
        twoFingerX = focusX;
        twoFingerY = focusY;
    }
    private void moveTwoFingers(float focusX, float focusY, float span, long eventTimeMs) {
        // Accumulate small span changes until the pinch threshold is crossed.
        if (Math.abs(span - pinchSpan) >= pinchSlop) pinching = true;
        if (pinching) {
            viewport.zoomBy(span / pinchSpan, twoFingerX, twoFingerY);
            pinchSpan = span;
        }
        if (pinching || viewport.isZoomed()) {
            twoFingerScrolling = false;
            scrollInertia.stop();
            // Pinches can start in the letterbox. A pan alone starts over the image.
            if (pinching || viewportPanActive || viewport.contains(focusX, focusY)) {
                viewportPanActive = true;
                viewport.panBy(focusX - twoFingerX, focusY - twoFingerY);
                applyViewport();
            }
        } else if (!viewOnly && viewport.contains(focusX, focusY)) {
            twoFingerScrolling = true;
            scrollInertia.track(focusY, eventTimeMs);
            float delta = focusY - scrollY;
            if (Math.abs(delta) > 16 * getResources().getDisplayMetrics().density) {
                session.pointer(x, y, 7, delta > 0 ? 1 : -1);
                scrollY = focusY;
                twoFingerMoved = true;
            }
        }
        if (pinching || viewportPanActive
                || Math.hypot(focusX - twoFingerStartX, focusY - twoFingerStartY) > touchSlop * 1.5f) {
            twoFingerMoved = true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void surfaceCreated(SurfaceHolder holder) { session.surface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseMouse(); session.surface(null); }
    @Override protected void onDetachedFromWindow() { releaseMouse(); super.onDetachedFromWindow(); }
    private final class Cursor extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float arrowWidth = 12, arrowHeight = 15;
        Cursor(Context context) {
            super(context); setClickable(true); setContentDescription(L10n.tr("远程桌面触控区域"));
            // Match the iOS RemoteCursorArrowShape in density-independent units.
            arrow.moveTo(arrowWidth*.08f,arrowHeight*.04f);
            arrow.lineTo(arrowWidth*.08f,arrowHeight*.78f);
            arrow.lineTo(arrowWidth*.34f,arrowHeight*.60f);
            arrow.lineTo(arrowWidth*.55f,arrowHeight*.94f);
            arrow.lineTo(arrowWidth*.73f,arrowHeight*.84f);
            arrow.lineTo(arrowWidth*.52f,arrowHeight*.51f);
            arrow.lineTo(arrowWidth*.88f,arrowHeight*.49f);
            arrow.close();
        }
        // GestureDetector calls performClick only after resolving tap vs drag/long press.
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event) { return touch(event); }
        @Override public boolean performClick() { super.performClick(); click(1, 2); return true; }
        @Override protected void onDraw(Canvas canvas) {
            int saved=canvas.save();
            canvas.translate(viewport.left()+x*viewport.width(),viewport.top()+y*viewport.height());
            float density=getResources().getDisplayMetrics().density;canvas.scale(density,density);
            // Anchor the arrow tip to the same normalized coordinate sent to the desktop.
            canvas.translate(-arrowWidth*.08f,-arrowHeight*.04f);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.75f);paint.setColor(Color.BLACK);canvas.drawPath(arrow,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawPath(arrow,paint);
            canvas.restoreToCount(saved);
        }
    }
}
