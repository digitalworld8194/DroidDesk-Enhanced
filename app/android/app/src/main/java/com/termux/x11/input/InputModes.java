package com.termux.x11.input;

/**
 * The two modes offered by DroidDesk's top "Trackpad" button and their persisted form.
 * TRACKPAD behaves like a laptop touchpad; TOUCH is tablet-style direct pointing (upstream
 * SIMULATED_TOUCH: taps click where the finger is, gestures keep right click and scrolling).
 */
public final class InputModes {
    private InputModes() {}

    // Same values as TouchInputHandler.InputMode.TRACKPAD / SIMULATED_TOUCH.
    public static final int TRACKPAD = 1;
    public static final int TOUCH = 2;

    public static int toggle(int mode) {
        return mode == TOUCH ? TRACKPAD : TOUCH;
    }

    /** Parses a stored value; anything unknown or missing falls back to TRACKPAD. */
    public static int fromStored(String stored) {
        return String.valueOf(TOUCH).equals(stored) ? TOUCH : TRACKPAD;
    }

    public static String toStored(int mode) {
        return String.valueOf(mode == TOUCH ? TOUCH : TRACKPAD);
    }
}
