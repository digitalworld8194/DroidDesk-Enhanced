package com.termux.x11.input;

/**
 * Android-independent decision helpers used by {@link TouchInputHandler}. They only complete the
 * gesture set inherited from Termux:X11 (tap-and-drag, scroll axis lock, stable double tap in
 * touch mode) and are kept free of Android classes so they can be unit-tested on a plain JVM.
 */
public final class TrackpadGestures {
    private TrackpadGestures() {}

    // Values of android.view.InputDevice / MotionEvent constants (stable public API).
    public static final int SOURCE_MOUSE = 0x00002002;
    public static final int SOURCE_MOUSE_RELATIVE = 0x00020004;
    public static final int SOURCE_TOUCHPAD = 0x00100008;
    public static final int TOOL_TYPE_FINGER = 1;
    public static final int TOOL_TYPE_MOUSE = 3;

    /** Maps the number of fingers in a tap or long-press to a mouse button (upstream mapping). */
    public static int mouseButtonForFingers(int pointerCount) {
        switch (pointerCount) {
            case 1: return InputStub.BUTTON_LEFT;
            case 2: return InputStub.BUTTON_RIGHT;
            case 3: return InputStub.BUTTON_MIDDLE;
            default: return InputStub.BUTTON_UNDEFINED;
        }
    }

    public static final int GESTURE_UNKNOWN = 0, GESTURE_SWIPE = 1, GESTURE_PINCH = 2;

    /**
     * Swipe/pinch disambiguation moved verbatim from {@link SwipeDetector}. A swipe (two fingers
     * moving the same way, in any direction) is a two-finger scroll, vertical or horizontal.
     */
    public static int classifyTwoFingerGesture(float deltaX0, float deltaY0, float deltaX1, float deltaY1,
                                               float touchSlopSquare) {
        float squaredDistance0 = deltaX0 * deltaX0 + deltaY0 * deltaY0;
        float squaredDistance1 = deltaX1 * deltaX1 + deltaY1 * deltaY1;

        // Both fingers must pass the touch slop; one stationary finger keeps the gesture undecided.
        if (squaredDistance0 <= touchSlopSquare || squaredDistance1 <= touchSlopSquare)
            return GESTURE_UNKNOWN;

        // Positive scalar product: same direction (swipe); negative: opposite directions (pinch).
        float scalarProduct = deltaX0 * deltaX1 + deltaY0 * deltaY1;
        return scalarProduct > 0 ? GESTURE_SWIPE : GESTURE_PINCH;
    }

    /** Finger on a physical touchpad (SOURCE_TOUCHPAD), handled by the nested touchpad handler. */
    public static boolean isTouchpadFinger(int source, int toolType) {
        return toolType == TOOL_TYPE_FINGER && (source & SOURCE_TOUCHPAD) == SOURCE_TOUCHPAD;
    }

    /** DeX and keyboard+touchpad combos reporting SOURCE_MOUSE with finger tool type. */
    public static boolean isDexLike(int source, int toolType) {
        return (source & SOURCE_MOUSE) == SOURCE_MOUSE
                && (source & SOURCE_TOUCHPAD) != SOURCE_TOUCHPAD
                && toolType == TOOL_TYPE_FINGER;
    }

    /** Physical USB/Bluetooth mouse: routed to the hardware mouse listener, never to gestures. */
    public static boolean isHardwareMouse(int source, int toolType) {
        return (!isDexLike(source, toolType)
                && (toolType == TOOL_TYPE_MOUSE || (source & SOURCE_MOUSE) == SOURCE_MOUSE))
                || (source & SOURCE_MOUSE_RELATIVE) == SOURCE_MOUSE_RELATIVE;
    }

    public static final int SOURCE_TOUCHSCREEN = 0x00001002;

    /**
     * Only a finger on the phone's own touchscreen gets laptop pointer acceleration. Physical
     * mice, physical/DeX touchpads (already accelerated by Android) and styluses keep their path.
     */
    public static boolean usesLaptopAcceleration(int source, int toolType) {
        return toolType == TOOL_TYPE_FINGER
                && (source & SOURCE_TOUCHSCREEN) == SOURCE_TOUCHSCREEN
                && !isTouchpadFinger(source, toolType)
                && !isDexLike(source, toolType)
                && !isHardwareMouse(source, toolType);
    }

    /**
     * The one mouse button a gesture holds down. Every path that ends a gesture (lift, cancel,
     * mode switch, view teardown) releases it, and it is released exactly once.
     */
    public static final class HeldButton {
        private int mButton = InputStub.BUTTON_UNDEFINED;

        /** @return the button to release before pressing `button`, or BUTTON_UNDEFINED. */
        public int press(int button) {
            int previous = mButton;
            mButton = button;
            return previous == button ? InputStub.BUTTON_UNDEFINED : previous;
        }

        /** @return true if `button` was not already held, i.e. a press must be sent. */
        public boolean needsPress(int button) {
            return mButton != button;
        }

        /** @return the held button to release, or BUTTON_UNDEFINED if none; clears it. */
        public int release() {
            int button = mButton;
            mButton = InputStub.BUTTON_UNDEFINED;
            return button;
        }

        public boolean isHeld() {
            return mButton != InputStub.BUTTON_UNDEFINED;
        }
    }

    /**
     * Laptop-style tap-and-drag: a one-finger tap followed, within the double-tap timeout, by a
     * touch that moves beyond the slop holds the left button until the finger lifts. Taps are not
     * delayed, so single and double clicks stay immediate.
     */
    public static final class TapDrag {
        private final long mTimeoutMs;
        private final float mSlopSquare;
        private long mLastTapTime = Long.MIN_VALUE;
        private boolean mArmed;
        private float mDownX, mDownY;

        public TapDrag(long timeoutMs, float slopPx) {
            mTimeoutMs = timeoutMs;
            mSlopSquare = slopPx * slopPx;
        }

        /** A one-finger tap (click) has been sent. */
        public void onTap(long timeMs) {
            mLastTapTime = timeMs;
        }

        /** First finger down. */
        public void onDown(long timeMs, float x, float y) {
            mArmed = mLastTapTime != Long.MIN_VALUE && timeMs - mLastTapTime <= mTimeoutMs;
            mDownX = x;
            mDownY = y;
        }

        /** @return true exactly once, when an armed touch starts moving: press the left button. */
        public boolean onMove(float x, float y) {
            if (!mArmed)
                return false;
            float dx = x - mDownX, dy = y - mDownY;
            if (dx * dx + dy * dy <= mSlopSquare)
                return false;
            mArmed = false;
            mLastTapTime = Long.MIN_VALUE;
            return true;
        }

        /** Lift: this touch can no longer start a drag (a tap on lift re-arms the next touch). */
        public void cancel() {
            mArmed = false;
        }

        /** Extra finger or ACTION_CANCEL: forget the preceding tap too, so no later touch drags. */
        public void reset() {
            mArmed = false;
            mLastTapTime = Long.MIN_VALUE;
        }

        public boolean isArmed() {
            return mArmed;
        }
    }

    /**
     * Locks two-finger scrolling to the dominant axis like laptop touchpads, so a vertical scroll
     * does not also scroll sideways from finger drift. Diagonal gestures stay free.
     */
    public static final class ScrollAxisLock {
        public static final int UNDECIDED = 0, VERTICAL = 1, HORIZONTAL = 2, FREE = 3;
        private static final float DOMINANCE = 2f;

        private final float mDecisionDistance;
        private int mAxis = UNDECIDED;
        private float mAccX, mAccY;

        public ScrollAxisLock(float decisionDistancePx) {
            mDecisionDistance = decisionDistancePx;
        }

        public void reset() {
            mAxis = UNDECIDED;
            mAccX = mAccY = 0;
        }

        public int axis() {
            return mAxis;
        }

        /** Filters a scroll delta in place: out[0] = x, out[1] = y. */
        public void filter(float dx, float dy, float[] out) {
            if (mAxis == UNDECIDED) {
                mAccX += dx;
                mAccY += dy;
                float ax = Math.abs(mAccX), ay = Math.abs(mAccY);
                if (Math.max(ax, ay) < mDecisionDistance) {
                    out[0] = out[1] = 0;
                    return;
                }
                mAxis = ay >= DOMINANCE * ax ? VERTICAL : ax >= DOMINANCE * ay ? HORIZONTAL : FREE;
                // Deliver the movement accumulated while deciding, so nothing is lost.
                dx = mAccX;
                dy = mAccY;
            }
            out[0] = mAxis == VERTICAL ? 0 : dx;
            out[1] = mAxis == HORIZONTAL ? 0 : dy;
        }
    }

    /**
     * In touch mode each tap moves the cursor to the finger. Finger jitter between the two taps of a
     * double tap can exceed X's double-click distance, so the second tap reuses the first position.
     */
    public static final class DoubleTapAnchor {
        private final long mTimeoutMs;
        private final float mSlopSquare;
        private long mLastTime = Long.MIN_VALUE;
        private float mLastX, mLastY;

        public DoubleTapAnchor(long timeoutMs, float slopPx) {
            mTimeoutMs = timeoutMs;
            mSlopSquare = slopPx * slopPx;
        }

        /** @return true if this left tap completes a double tap and must keep the previous point. */
        public boolean isSecondTap(long timeMs, float x, float y) {
            float dx = x - mLastX, dy = y - mLastY;
            boolean second = mLastTime != Long.MIN_VALUE
                    && timeMs - mLastTime <= mTimeoutMs
                    && dx * dx + dy * dy <= mSlopSquare;
            if (second) {
                mLastTime = Long.MIN_VALUE;
            } else {
                mLastTime = timeMs;
                mLastX = x;
                mLastY = y;
            }
            return second;
        }

        public void reset() {
            mLastTime = Long.MIN_VALUE;
        }
    }
}
