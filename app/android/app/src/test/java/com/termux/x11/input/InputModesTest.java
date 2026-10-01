package com.termux.x11.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InputModesTest {
    @Test public void modeValuesMatchTermuxX11InputModes() {
        assertEquals(1, InputModes.TRACKPAD); // TouchInputHandler.InputMode.TRACKPAD
        assertEquals(2, InputModes.TOUCH);    // TouchInputHandler.InputMode.SIMULATED_TOUCH
    }

    @Test public void buttonTogglesBetweenTrackpadAndTouch() {
        assertEquals(InputModes.TOUCH, InputModes.toggle(InputModes.TRACKPAD));
        assertEquals(InputModes.TRACKPAD, InputModes.toggle(InputModes.TOUCH));
    }

    @Test public void legacyDirectTouchModeTogglesBackToTrackpad() {
        assertEquals(InputModes.TOUCH, InputModes.toggle(3));
    }

    @Test public void persistedModeRoundTrips() {
        assertEquals(InputModes.TRACKPAD, InputModes.fromStored(InputModes.toStored(InputModes.TRACKPAD)));
        assertEquals(InputModes.TOUCH, InputModes.fromStored(InputModes.toStored(InputModes.TOUCH)));
    }

    @Test public void missingOrUnknownPersistedModeDefaultsToTrackpad() {
        assertEquals(InputModes.TRACKPAD, InputModes.fromStored(null));
        assertEquals(InputModes.TRACKPAD, InputModes.fromStored(""));
        assertEquals(InputModes.TRACKPAD, InputModes.fromStored("3"));
        assertEquals(InputModes.TRACKPAD, InputModes.fromStored("garbage"));
    }
}
