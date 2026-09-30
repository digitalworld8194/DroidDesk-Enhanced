package com.termux.x11.input;

/**
 * Laptop-touchpad pointer ballistics for one-finger movement on the touchscreen in Trackpad mode.
 * Android-independent so it can be unit-tested on a plain JVM; driven by the ACTION_MOVE events
 * the gesture detector already delivers, so it adds no timers and no polling.
 *
 * Per move event:
 *   1. slop removal: the first delta of a touch includes the whole touch slop; only the part past
 *      the slop is used, so the cursor never jumps when a movement starts.
 *   2. speed: finger speed in dp/ms, exponentially smoothed (time constant SPEED_TAU_MS). It only
 *      drives the gain, the position itself is never delayed.
 *   3. dead zone: while the finger rests, jitter below DEAD_ZONE_DP (accumulated) is dropped.
 *   4. micro smoothing: at crawling speed a delta is blended with the previous one (at most 50/50).
 *   5. gain: gain(speed) * sensitivity, piecewise linear between GAIN_SLOW and GAIN_FAST.
 *   6. step limit: one event can move at most MAX_STEP_DP.
 *   7. output scale and sub-pixel remainder: integer pixels are emitted and the fraction is kept
 *      for the next event, so slow movement is neither lost nor rounded into jumps.
 */
public final class PointerAcceleration {
    /** Below this speed (dp/ms) the gain is GAIN_SLOW: fine positioning. */
    public static final float SPEED_SLOW = 0.08f;
    /** Above this speed (dp/ms) the gain is GAIN_FAST: long travel. */
    public static final float SPEED_FAST = 1.6f;
    public static final float GAIN_SLOW = 0.55f;
    public static final float GAIN_FAST = 3.0f;

    public static final float DEAD_ZONE_DP = 0.5f;
    /** Below this smoothed speed the finger counts as resting again and the dead zone re-arms. */
    public static final float REST_SPEED = 0.02f;
    /** Below this speed deltas get blended with the previous one. */
    public static final float SMOOTH_SPEED = 0.15f;
    public static final float SPEED_TAU_MS = 24f;
    /** Event intervals are clamped to this range so bursts or pauses cannot spike the speed. */
    public static final float MIN_DT_MS = 4f, MAX_DT_MS = 80f;
    public static final float MAX_STEP_DP = 64f;

    public static final int DEFAULT_SENSITIVITY_PERCENT = 100;
    public static final int MIN_SENSITIVITY_PERCENT = 25, MAX_SENSITIVITY_PERCENT = 300;

    private final float mDensity;
    private float mSensitivity = 1f;

    private long mLastTime;
    private float mSpeed;
    private float mSlop;
    private boolean mSlopPending;
    private boolean mResting;
    private float mRestX, mRestY;
    private float mPrevX, mPrevY;
    private float mRemX, mRemY;

    /** @param density display density (px per dp), used to express speed and limits in dp. */
    public PointerAcceleration(float density) {
        mDensity = density > 0 ? density : 1f;
    }

    /** Gain for a finger speed in dp/ms, without sensitivity. Monotonic, never below GAIN_SLOW. */
    public static float gain(float speed) {
        if (speed <= SPEED_SLOW)
            return GAIN_SLOW;
        if (speed >= SPEED_FAST)
            return GAIN_FAST;
        return GAIN_SLOW + (GAIN_FAST - GAIN_SLOW) * (speed - SPEED_SLOW) / (SPEED_FAST - SPEED_SLOW);
    }

    /** Clamps a stored percentage and converts it to a multiplier. */
    public static float sensitivityFromPercent(int percent) {
        return Math.max(MIN_SENSITIVITY_PERCENT, Math.min(MAX_SENSITIVITY_PERCENT, percent)) / 100f;
    }

    public void setSensitivity(float sensitivity) {
        mSensitivity = sensitivity;
    }

    public float sensitivity() {
        return mSensitivity;
    }

    public float speed() {
        return mSpeed;
    }

    /** First finger down: starts a new movement. The first delta will lose slopPx of travel. */
    public void begin(long timeMs, float slopPx) {
        mLastTime = timeMs;
        mSpeed = 0;
        mSlop = slopPx;
        mSlopPending = slopPx > 0;
        mResting = true;
        mRestX = mRestY = mPrevX = mPrevY = mRemX = mRemY = 0;
    }

    /**
     * Converts a finger delta (screen px, finger direction) into a relative cursor move.
     *
     * @param scaleX, scaleY final scale into X pixels (1 when the touchpad is not scaled).
     * @param out receives integer pixels: out[0] = x, out[1] = y.
     * @return true if the cursor has to move.
     */
    public boolean move(long timeMs, float dx, float dy, float scaleX, float scaleY, float[] out) {
        out[0] = out[1] = 0;

        if (mSlopPending) {
            mSlopPending = false;
            float kept = shrink(dx, dy, mSlop);
            dx *= kept;
            dy *= kept;
        }

        long elapsed = timeMs - mLastTime;
        mLastTime = timeMs;
        if (elapsed > MAX_DT_MS)
            mSpeed = 0; // the finger paused: start again from precision speed
        float dt = Math.max(MIN_DT_MS, Math.min(MAX_DT_MS, elapsed));
        float instant = (float) Math.hypot(dx, dy) / mDensity / dt;
        mSpeed += (float) (1 - Math.exp(-dt / SPEED_TAU_MS)) * (instant - mSpeed);

        if (mResting) {
            mRestX += dx;
            mRestY += dy;
            float deadZone = DEAD_ZONE_DP * mDensity;
            float kept = shrink(mRestX, mRestY, deadZone);
            if (kept == 0)
                return false;
            dx = mRestX * kept;
            dy = mRestY * kept;
            mRestX = mRestY = 0;
            mResting = false;
        } else if (mSpeed < REST_SPEED) {
            mResting = true;
        }

        float weight = Math.max(0.5f, Math.min(1f, mSpeed / SMOOTH_SPEED));
        float sx = weight * dx + (1 - weight) * mPrevX;
        float sy = weight * dy + (1 - weight) * mPrevY;
        mPrevX = dx;
        mPrevY = dy;

        float g = gain(mSpeed) * mSensitivity;
        sx *= g;
        sy *= g;

        float limit = MAX_STEP_DP * mDensity;
        float length = (float) Math.hypot(sx, sy);
        if (length > limit) {
            sx *= limit / length;
            sy *= limit / length;
        }

        mRemX += sx * scaleX;
        mRemY += sy * scaleY;
        out[0] = (int) mRemX;
        out[1] = (int) mRemY;
        mRemX -= out[0];
        mRemY -= out[1];
        return out[0] != 0 || out[1] != 0;
    }

    /** Factor that shortens (x, y) by `by` along its direction; 0 if it is not longer than that. */
    static float shrink(float x, float y, float by) {
        float length = (float) Math.hypot(x, y);
        return length <= by ? 0 : (length - by) / length;
    }

    /** Limits a two-finger scroll delta so a delayed or merged event cannot jump the page. */
    public static void limitScroll(float dx, float dy, float maxPx, float[] out) {
        float length = (float) Math.hypot(dx, dy);
        float k = length > maxPx ? maxPx / length : 1f;
        out[0] = dx * k;
        out[1] = dy * k;
    }
}
