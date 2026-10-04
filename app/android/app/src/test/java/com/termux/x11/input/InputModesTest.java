package com.termux.x11.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InputModesTest {
    @Test
    public void modeValuesMatchTermuxX11InputModes() {
        assertEquals(
                TouchInputHandler.InputMode.TRACKPAD,
                InputModes.TRACKPAD
        );
        assertEquals(
                TouchInputHandler.InputMode.TOUCH,
                InputModes.TOUCH
        );
    }

    @Test
    public void buttonTogglesBetweenPointerAndRealTouch() {
        assertEquals(
                InputModes.TOUCH,
                InputModes.toggle(InputModes.TRACKPAD)
        );
        assertEquals(
                InputModes.TRACKPAD,
                InputModes.toggle(InputModes.TOUCH)
        );
    }

    @Test
    public void simulatedTouchIsNotExposedAsAUserProfile() {
        assertEquals(
                InputModes.TOUCH,
                InputModes.fromStored(
                        String.valueOf(
                                TouchInputHandler.InputMode.SIMULATED_TOUCH
                        )
                )
        );
    }

    @Test
    public void persistedModesRoundTrip() {
        assertEquals(
                InputModes.TRACKPAD,
                InputModes.fromStored(
                        InputModes.toStored(InputModes.TRACKPAD)
                )
        );
        assertEquals(
                InputModes.TOUCH,
                InputModes.fromStored(
                        InputModes.toStored(InputModes.TOUCH)
                )
        );
    }

    @Test
    public void missingUnknownAndLegacyModesDefaultToRealTouch() {
        assertEquals(
                InputModes.TOUCH,
                InputModes.fromStored(null)
        );
        assertEquals(
                InputModes.TOUCH,
                InputModes.fromStored("")
        );
        assertEquals(
                InputModes.TOUCH,
                InputModes.fromStored("2")
        );
        assertEquals(
                InputModes.TOUCH,
                InputModes.fromStored("3")
        );
        assertEquals(
                InputModes.TOUCH,
                InputModes.fromStored("garbage")
        );
    }
}
