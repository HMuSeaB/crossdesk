package cn.crossdesk.mobile;

import android.os.SystemClock;
import android.view.Choreographer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Exponentially damped frame-driven wheel inertia helper. */
final class ScrollInertiaHelper {
    private static final int BUFFER_SIZE = 8;
    private static final long WINDOW_MS = 120L;
    private static final long STALE_LIFT_MS = 100L;
    private static final long MIN_DT_MS = 8L;
    private static final float TAU_SECONDS = 0.24f;
    private static final int MAX_TICKS_PER_FRAME = 8;

    private final float stepPx;
    private final float minFlingVelocity;
    private final float stopVelocity;
    private final float maxVelocity;
    private final IntConsumer onScrollTick;
    private final Consumer<Float> onVisualDelta;

    private final float[] ySamples = new float[BUFFER_SIZE];
    private final long[] tSamples = new long[BUFFER_SIZE];
    private int sampleHead;
    private int sampleCount;

    private boolean running;
    private float velocityPxPerSec;
    private float remainder;
    private int directionSign = 1;
    private long lastFrameNanos;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameTimeNanos;
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }
            float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = frameTimeNanos;
            dt = Math.max(0.001f, Math.min(0.05f, dt));

            double decay = Math.exp(-dt / TAU_SECONDS);
            float avgVelocity = (float) (velocityPxPerSec * (1.0 - decay) * TAU_SECONDS / dt);
            float deltaPx = avgVelocity * dt;
            velocityPxPerSec = (float) (velocityPxPerSec * decay);

            if (onVisualDelta != null) {
                onVisualDelta.accept(deltaPx);
            }
            remainder += deltaPx;
            int ticks = (int) (remainder / stepPx);
            if (ticks != 0) {
                int count = Math.min(Math.abs(ticks), MAX_TICKS_PER_FRAME);
                int dir = (ticks > 0 ? 1 : -1) * directionSign;
                for (int i = 0; i < count; i++) {
                    onScrollTick.accept(dir);
                }
                remainder -= ticks * stepPx;
            }

            if (Math.abs(velocityPxPerSec) < stopVelocity) {
                stop();
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    ScrollInertiaHelper(float density, float stepDp, IntConsumer onScrollTick, Consumer<Float> onVisualDelta) {
        float safeDensity = Math.max(1f, density);
        this.stepPx = Math.max(1f, stepDp * safeDensity);
        this.minFlingVelocity = 280f * safeDensity;
        this.stopVelocity = 45f * safeDensity;
        this.maxVelocity = 7000f * safeDensity;
        this.onScrollTick = onScrollTick;
        this.onVisualDelta = onVisualDelta;
    }

    void begin(float posY, long eventTimeMs) {
        stop();
        sampleHead = 0;
        sampleCount = 0;
        addSample(posY, eventTimeMs);
    }

    void track(float posY, long eventTimeMs) {
        addSample(posY, eventTimeMs);
    }

    void fling(int directionSign) {
        if (sampleCount < 2) {
            sampleCount = 0;
            return;
        }
        int newestIdx = (sampleHead - 1 + BUFFER_SIZE) % BUFFER_SIZE;
        long tEnd = tSamples[newestIdx];
        float yEnd = ySamples[newestIdx];
        if (SystemClock.uptimeMillis() - tEnd > STALE_LIFT_MS) {
            sampleCount = 0;
            return;
        }

        int oldestIdx = newestIdx;
        for (int i = 1; i < sampleCount; i++) {
            int idx = (newestIdx - i + BUFFER_SIZE) % BUFFER_SIZE;
            if (tEnd - tSamples[idx] > WINDOW_MS) break;
            oldestIdx = idx;
        }
        long dtMs = tEnd - tSamples[oldestIdx];
        sampleCount = 0;
        if (dtMs < MIN_DT_MS) return;

        float vy = (yEnd - ySamples[oldestIdx]) / (dtMs / 1000f);
        if (!Float.isFinite(vy) || Math.abs(vy) < minFlingVelocity) return;

        this.velocityPxPerSec = Math.max(-maxVelocity, Math.min(maxVelocity, vy));
        this.directionSign = directionSign >= 0 ? 1 : -1;
        this.remainder = 0f;
        this.lastFrameNanos = 0L;
        this.running = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    void stop() {
        running = false;
        velocityPxPerSec = 0f;
        remainder = 0f;
        lastFrameNanos = 0L;
        sampleCount = 0;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void addSample(float posY, long eventTimeMs) {
        if (!Float.isFinite(posY)) return;
        if (sampleCount > 0) {
            int prevIdx = (sampleHead - 1 + BUFFER_SIZE) % BUFFER_SIZE;
            if (tSamples[prevIdx] == eventTimeMs) {
                ySamples[prevIdx] = posY;
                return;
            }
        }
        ySamples[sampleHead] = posY;
        tSamples[sampleHead] = eventTimeMs;
        sampleHead = (sampleHead + 1) % BUFFER_SIZE;
        if (sampleCount < BUFFER_SIZE) sampleCount++;
    }
}
