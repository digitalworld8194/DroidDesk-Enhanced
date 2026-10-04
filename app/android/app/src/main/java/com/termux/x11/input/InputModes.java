package com.termux.x11.input;

/**
 * DroidDesk hybrid input profiles.
 *
 * TOUCH is the default phone/tablet experience and maps to the real
 * Termux:X11 TOUCH mode, which forwards finger events through sendTouchEvent().
 *
 * TRACKPAD is the optional pointer/laptop experience.
 *
 * SIMULATED_TOUCH (2) remains an internal Termux:X11 mode and is
 * deliberately not exposed as a DroidDesk user profile.
 */
public final class InputModes {
    private InputModes() {}

    public static final int TRACKPAD = 1;
    public static final int TOUCH = 3;

    public static int toggle(int mode) {
        return mode == TOUCH ? TRACKPAD : TOUCH;
    }

    /**
     * Fresh installs and unknown/legacy values fall back to real TOUCH.
     * Only an explicitly saved TRACKPAD value enables pointer mode.
     */
    public static int fromStored(String stored) {
        return String.valueOf(TRACKPAD).equals(stored) ? TRACKPAD : TOUCH;
    }

    public static String toStored(int mode) {
        return String.valueOf(mode == TOUCH ? TOUCH : TRACKPAD);
    }
}
