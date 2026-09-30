package com.termux.x11.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackpadGesturesTest {
    private static final long DOUBLE_TAP_TIMEOUT = 300;
    private static final float SLOP = 20;
    private static final float SLOP_SQUARE = SLOP * SLOP;

    // --- taps: 1 finger = left, 2 = right, 3 = middle (upstream Termux:X11 mapping) ---

    @Test public void singleFingerTapIsLeftClick() {
        assertEquals(InputStub.BUTTON_LEFT, TrackpadGestures.mouseButtonForFingers(1));
    }

    @Test public void twoFingerTapIsRightClick() {
        assertEquals(InputStub.BUTTON_RIGHT, TrackpadGestures.mouseButtonForFingers(2));
    }

    @Test public void threeFingerTapIsMiddleClick() {
        assertEquals(InputStub.BUTTON_MIDDLE, TrackpadGestures.mouseButtonForFingers(3));
    }

    @Test public void fourFingerTapSendsNothing() {
        assertEquals(InputStub.BUTTON_UNDEFINED, TrackpadGestures.mouseButtonForFingers(4));
    }

    // --- double tap ---

    @Test public void doubleTapInTrackpadModeDoesNotStartDrag() {
        TrackpadGestures.TapDrag drag = new TrackpadGestures.TapDrag(DOUBLE_TAP_TIMEOUT, SLOP);
        drag.onDown(0, 100, 100);
        drag.onTap(80);
        drag.onDown(150, 102, 101);
        assertTrue(drag.isArmed());
        // Second touch lifts within the slop: it stays a tap (second click of a double click).
        assertFalse(drag.onMove(105, 104));
        drag.cancel();
        assertFalse(drag.isArmed());
    }

    @Test public void doubleTapInTouchModeReusesFirstTapPosition() {
        TrackpadGestures.DoubleTapAnchor anchor = new TrackpadGestures.DoubleTapAnchor(DOUBLE_TAP_TIMEOUT, SLOP);
        assertFalse(anchor.isSecondTap(1000, 200, 200));
        assertTrue(anchor.isSecondTap(1200, 208, 195));
        // A third tap starts a new pair instead of chaining.
        assertFalse(anchor.isSecondTap(1300, 208, 195));
    }

    @Test public void slowOrDistantTapsInTouchModeAreSeparateClicks() {
        TrackpadGestures.DoubleTapAnchor anchor = new TrackpadGestures.DoubleTapAnchor(DOUBLE_TAP_TIMEOUT, SLOP);
        assertFalse(anchor.isSecondTap(0, 200, 200));
        assertFalse(anchor.isSecondTap(500, 200, 200));
        assertFalse(anchor.isSecondTap(600, 400, 400));
    }

    // --- drag (tap then touch-and-move) ---

    @Test public void tapThenTouchAndMoveStartsDragOnce() {
        TrackpadGestures.TapDrag drag = new TrackpadGestures.TapDrag(DOUBLE_TAP_TIMEOUT, SLOP);
        drag.onTap(1000);
        drag.onDown(1200, 50, 50);
        assertFalse(drag.onMove(55, 55));
        assertTrue(drag.onMove(90, 50));
        assertFalse("button must be pressed only once", drag.onMove(120, 50));
    }

    @Test public void moveWithoutPrecedingTapJustMovesPointer() {
        TrackpadGestures.TapDrag drag = new TrackpadGestures.TapDrag(DOUBLE_TAP_TIMEOUT, SLOP);
        drag.onDown(0, 50, 50);
        assertFalse(drag.onMove(300, 300));
    }

    @Test public void touchAfterDoubleTapTimeoutDoesNotDrag() {
        TrackpadGestures.TapDrag drag = new TrackpadGestures.TapDrag(DOUBLE_TAP_TIMEOUT, SLOP);
        drag.onTap(1000);
        drag.onDown(1000 + DOUBLE_TAP_TIMEOUT + 1, 50, 50);
        assertFalse(drag.onMove(300, 50));
    }

    @Test public void secondFingerCancelsPendingDrag() {
        TrackpadGestures.TapDrag drag = new TrackpadGestures.TapDrag(DOUBLE_TAP_TIMEOUT, SLOP);
        drag.onTap(1000);
        drag.onDown(1100, 50, 50);
        drag.cancel(); // ACTION_POINTER_DOWN: becomes a two-finger gesture
        assertFalse(drag.onMove(300, 50));
    }

    // --- two-finger scroll ---

    @Test public void twoFingersMovingVerticallyAreAScroll() {
        assertEquals(TrackpadGestures.GESTURE_SWIPE,
                TrackpadGestures.classifyTwoFingerGesture(2, 60, -3, 58, SLOP_SQUARE));
    }

    @Test public void twoFingersMovingHorizontallyAreAScroll() {
        assertEquals(TrackpadGestures.GESTURE_SWIPE,
                TrackpadGestures.classifyTwoFingerGesture(-70, 4, -66, -2, SLOP_SQUARE));
    }

    @Test public void fingersMovingApartArePinchNotScroll() {
        assertEquals(TrackpadGestures.GESTURE_PINCH,
                TrackpadGestures.classifyTwoFingerGesture(-50, 0, 50, 0, SLOP_SQUARE));
    }

    @Test public void oneStationaryFingerKeepsGestureUndecided() {
        assertEquals(TrackpadGestures.GESTURE_UNKNOWN,
                TrackpadGestures.classifyTwoFingerGesture(0, 1, 0, 80, SLOP_SQUARE));
    }

    @Test public void verticalScrollIsLockedToVerticalAxis() {
        TrackpadGestures.ScrollAxisLock lock = new TrackpadGestures.ScrollAxisLock(10);
        float[] out = new float[2];
        lock.filter(1, 6, out);
        assertEquals(0, out[0], 0);
        assertEquals(0, out[1], 0);
        lock.filter(1, 6, out); // decision: accumulated (2, 12)
        assertEquals(TrackpadGestures.ScrollAxisLock.VERTICAL, lock.axis());
        assertEquals(0, out[0], 0);
        assertEquals("movement accumulated while deciding is delivered", 12, out[1], 0);
        lock.filter(3, 5, out); // sideways drift is ignored
        assertEquals(0, out[0], 0);
        assertEquals(5, out[1], 0);
    }

    @Test public void horizontalScrollIsLockedToHorizontalAxis() {
        TrackpadGestures.ScrollAxisLock lock = new TrackpadGestures.ScrollAxisLock(10);
        float[] out = new float[2];
        lock.filter(-15, 2, out);
        assertEquals(TrackpadGestures.ScrollAxisLock.HORIZONTAL, lock.axis());
        assertEquals(-15, out[0], 0);
        assertEquals(0, out[1], 0);
        lock.filter(-8, 3, out);
        assertEquals(-8, out[0], 0);
        assertEquals(0, out[1], 0);
    }

    @Test public void diagonalScrollStaysFreeAndResetsPerGesture() {
        TrackpadGestures.ScrollAxisLock lock = new TrackpadGestures.ScrollAxisLock(10);
        float[] out = new float[2];
        lock.filter(12, 10, out);
        assertEquals(TrackpadGestures.ScrollAxisLock.FREE, lock.axis());
        assertEquals(12, out[0], 0);
        assertEquals(10, out[1], 0);
        lock.reset();
        assertEquals(TrackpadGestures.ScrollAxisLock.UNDECIDED, lock.axis());
    }

    // --- physical mouse / touchpad routing ---

    private static final int SOURCE_TOUCHSCREEN = 0x00001002;
    private static final int TOOL_TYPE_STYLUS = 2;

    @Test public void usbOrBluetoothMouseUsesHardwareMousePath() {
        assertTrue(TrackpadGestures.isHardwareMouse(TrackpadGestures.SOURCE_MOUSE, TrackpadGestures.TOOL_TYPE_MOUSE));
    }

    @Test public void capturedRelativeMouseUsesHardwareMousePath() {
        assertTrue(TrackpadGestures.isHardwareMouse(TrackpadGestures.SOURCE_MOUSE_RELATIVE, TrackpadGestures.TOOL_TYPE_MOUSE));
    }

    @Test public void touchscreenFingerIsNeverTreatedAsMouse() {
        assertFalse(TrackpadGestures.isHardwareMouse(SOURCE_TOUCHSCREEN, TrackpadGestures.TOOL_TYPE_FINGER));
        assertFalse(TrackpadGestures.isTouchpadFinger(SOURCE_TOUCHSCREEN, TrackpadGestures.TOOL_TYPE_FINGER));
        assertFalse(TrackpadGestures.isDexLike(SOURCE_TOUCHSCREEN, TrackpadGestures.TOOL_TYPE_FINGER));
    }

    @Test public void physicalTouchpadFingerUsesTouchpadGestures() {
        assertTrue(TrackpadGestures.isTouchpadFinger(TrackpadGestures.SOURCE_TOUCHPAD, TrackpadGestures.TOOL_TYPE_FINGER));
        assertFalse(TrackpadGestures.isDexLike(TrackpadGestures.SOURCE_TOUCHPAD, TrackpadGestures.TOOL_TYPE_FINGER));
    }

    @Test public void dexStyleTouchpadIsNotAMouse() {
        assertTrue(TrackpadGestures.isDexLike(TrackpadGestures.SOURCE_MOUSE, TrackpadGestures.TOOL_TYPE_FINGER));
        assertFalse(TrackpadGestures.isHardwareMouse(TrackpadGestures.SOURCE_MOUSE, TrackpadGestures.TOOL_TYPE_FINGER));
    }

    @Test public void stylusIsNotAMouse() {
        assertFalse(TrackpadGestures.isHardwareMouse(SOURCE_TOUCHSCREEN, TOOL_TYPE_STYLUS));
    }
}
