package com.termux.x11.input;

/**
 * The persisted Trackpad-mode pointer sensitivity (percent) behind the desktop's sensitivity
 * slider. Android-independent: storage is behind {@link Store} so the range, reset and
 * persistence rules can be unit-tested on a plain JVM.
 */
public final class TrackpadSensitivity {
    public static final int MIN = PointerAcceleration.MIN_SENSITIVITY_PERCENT;
    public static final int MAX = PointerAcceleration.MAX_SENSITIVITY_PERCENT;
    public static final int DEFAULT = PointerAcceleration.DEFAULT_SENSITIVITY_PERCENT;

    /** Slider positions, from slowest to fastest. */
    private static final int[] STOPS = {25, 50, 75, 100, 125, 150, 175, 200, 250, 300};

    /** Where the value lives (SharedPreferences droiddesk_input/trackpad_sensitivity on Android). */
    public interface Store {
        int read(int fallback);
        void write(int percent);
    }

    private final Store mStore;
    private int mPercent;

    /** Loads the stored value right away, so it applies before the first cursor movement. */
    public TrackpadSensitivity(Store store) {
        mStore = store;
        mPercent = clamp(store.read(DEFAULT));
    }

    public static int clamp(int percent) {
        return Math.max(MIN, Math.min(MAX, percent));
    }

    public static int stopCount() {
        return STOPS.length;
    }

    /** Percent at a slider position; out-of-range positions are clamped to the ends. */
    public static int percentAt(int index) {
        return STOPS[Math.max(0, Math.min(STOPS.length - 1, index))];
    }

    /** Slider position closest to a percent (ties go to the lower stop). */
    public static int stopIndex(int percent) {
        int best = 0;
        for (int i = 1; i < STOPS.length; i++)
            if (Math.abs(STOPS[i] - percent) < Math.abs(STOPS[best] - percent))
                best = i;
        return best;
    }

    public int get() {
        return mPercent;
    }

    /** Clamps, persists if it changed and returns the value now in effect. */
    public int set(int percent) {
        int value = clamp(percent);
        if (value != mPercent) {
            mPercent = value;
            mStore.write(value);
        }
        return value;
    }

    public int reset() {
        return set(DEFAULT);
    }
}
