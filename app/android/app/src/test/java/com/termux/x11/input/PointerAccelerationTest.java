package com.termux.x11.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PointerAccelerationTest {
    private static final float DENSITY = 2.75f; // common 1080p phone
    private static final float SLOP = 8 * DENSITY;
    private static final long FRAME = 8; // 120 Hz touch reports

    private final float[] out = new float[2];

    /** Feeds `steps` equal deltas `frame` ms apart and returns the summed cursor movement. */
    private float[] run(PointerAcceleration accel, long start, int steps, long frame, float dx, float dy) {
        float[] total = new float[2];
        long t = start;
        for (int i = 0; i < steps; i++) {
            t += frame;
            accel.move(t, dx, dy, 1, 1, out);
            total[0] += out[0];
            total[1] += out[1];
        }
        return total;
    }

    private static PointerAcceleration started() {
        PointerAcceleration accel = new PointerAcceleration(DENSITY);
        accel.begin(0, 0);
        return accel;
    }

    // --- gain curve ---

    @Test public void gainIsMonotonicAndBounded() {
        float previous = 0;
        for (float v = 0; v <= 3; v += 0.05f) {
            float g = PointerAcceleration.gain(v);
            assertTrue(g >= previous);
            assertTrue(g >= PointerAcceleration.GAIN_SLOW && g <= PointerAcceleration.GAIN_FAST);
            previous = g;
        }
        assertEquals(PointerAcceleration.GAIN_SLOW, PointerAcceleration.gain(0), 0);
        assertEquals(PointerAcceleration.GAIN_FAST, PointerAcceleration.gain(10), 0);
    }

    // --- precision / acceleration ---

    @Test public void smallSlowMovementIsPrecise() {
        // 0.5 px per 8 ms = 0.023 dp/ms: a careful crawl.
        float[] total = run(started(), 0, 200, FRAME, 0.5f, 0);
        float raw = 200 * 0.5f;
        assertTrue("slow movement moves the cursor", total[0] > 0);
        assertTrue("slow movement is scaled down for precision: " + total[0], total[0] <= raw * 0.6f);
    }

    @Test public void slowMovementNeverStepsMoreThanOnePixel() {
        PointerAcceleration accel = started();
        long t = 0;
        for (int i = 0; i < 200; i++) {
            accel.move(t += FRAME, 0.5f, 0.3f, 1, 1, out);
            assertTrue(Math.abs(out[0]) <= 1 && Math.abs(out[1]) <= 1);
        }
    }

    @Test public void fastMovementIsAccelerated() {
        // 20 px per 8 ms = 0.9 dp/ms, then steady.
        float[] total = run(started(), 0, 30, FRAME, 20, 0);
        float raw = 30 * 20;
        assertTrue("fast movement travels further than the finger: " + total[0], total[0] > raw * 1.5f);
    }

    @Test public void sameFingerDistanceGoesFurtherWhenFast() {
        float[] slow = run(started(), 0, 100, FRAME, 1, 0);   // 100 px slowly
        float[] fast = run(started(), 0, 5, FRAME, 20, 0);    // 100 px quickly
        assertTrue(fast[0] > 2 * slow[0]);
    }

    @Test public void pauseDropsBackToPrecisionSpeed() {
        PointerAcceleration accel = started();
        run(accel, 0, 20, FRAME, 25, 0);
        assertTrue(accel.speed() > PointerAcceleration.SPEED_FAST / 2);
        accel.move(1000, 0.5f, 0, 1, 1, out);
        assertTrue(accel.speed() < PointerAcceleration.SPEED_SLOW);
    }

    // --- start, dead zone, limits ---

    @Test public void noJumpWhenMovementStarts() {
        PointerAcceleration accel = new PointerAcceleration(DENSITY);
        accel.begin(0, SLOP);
        // GestureDetector's first scroll carries the whole slop plus a little.
        accel.move(60, SLOP + 2, 0, 1, 1, out);
        assertTrue("first step must not include the touch slop: " + out[0], Math.abs(out[0]) <= 2);
        assertEquals(0, out[1], 0);
    }

    @Test public void deadZoneSwallowsRestingJitter() {
        PointerAcceleration accel = started();
        long t = 0;
        float sum = 0;
        for (int i = 0; i < 100; i++) {
            float j = (i % 2 == 0) ? 0.4f : -0.4f;
            accel.move(t += FRAME, j, -j, 1, 1, out);
            sum += Math.abs(out[0]) + Math.abs(out[1]);
        }
        assertEquals("tremor of a resting finger must not move the cursor", 0, sum, 0);
    }

    @Test public void movementPastDeadZoneIsDelivered() {
        PointerAcceleration accel = started();
        assertFalse(accel.move(FRAME, 1, 0, 1, 1, out)); // 0.36 dp < dead zone
        float[] total = run(accel, FRAME, 40, FRAME, 3, 0);
        assertTrue(total[0] > 0);
    }

    @Test public void singleEventStepIsLimited() {
        PointerAcceleration accel = started();
        run(accel, 0, 10, FRAME, 30, 0);
        accel.move(10 * FRAME + 4, 2000, 0, 1, 1, out);
        assertTrue(out[0] <= PointerAcceleration.MAX_STEP_DP * DENSITY + 1);
        assertTrue(out[0] > 0);
    }

    // --- direction ---

    @Test public void directionIsPreserved() {
        float[] total = run(started(), 0, 40, FRAME, -12, 6);
        assertTrue(total[0] < 0);
        assertTrue(total[1] > 0);
        assertEquals(-2, total[0] / total[1], 0.15);
    }

    @Test public void eachAxisMovesOnlyWhenTheFingerDoes() {
        float[] up = run(started(), 0, 40, FRAME, 0, -10);
        assertEquals(0, up[0], 0);
        assertTrue(up[1] < 0);
    }

    // --- sensitivity and output scale ---

    @Test public void sensitivityScalesOutput() {
        PointerAcceleration normal = started();
        PointerAcceleration doubled = started();
        doubled.setSensitivity(PointerAcceleration.sensitivityFromPercent(200));
        float[] a = run(normal, 0, 40, FRAME, 4, 0);
        float[] b = run(doubled, 0, 40, FRAME, 4, 0);
        assertEquals(2 * a[0], b[0], 2);
    }

    @Test public void sensitivityIsClamped() {
        assertEquals(0.25f, PointerAcceleration.sensitivityFromPercent(0), 0);
        assertEquals(1f, PointerAcceleration.sensitivityFromPercent(100), 0);
        assertEquals(3f, PointerAcceleration.sensitivityFromPercent(999), 0);
    }

    @Test public void outputScaleIsAppliedWithSubPixelRemainder() {
        PointerAcceleration full = started();
        PointerAcceleration half = started();
        float[] a = run(full, 0, 50, FRAME, 6, 0);
        float sumHalf = 0;
        long t = 0;
        for (int i = 0; i < 50; i++) {
            half.move(t += FRAME, 6, 0, 0.5f, 0.5f, out);
            assertEquals("integer pixels only", Math.round(out[0]), out[0], 0);
            sumHalf += out[0];
        }
        assertEquals(a[0] / 2, sumHalf, 1.5);
    }

    // --- scroll ---

    @Test public void scrollKeepsNormalDeltasAndLimitsSpikes() {
        float[] s = new float[2];
        PointerAcceleration.limitScroll(0, 12, 48 * DENSITY, s);
        assertEquals(0, s[0], 0);
        assertEquals(12, s[1], 0);
        PointerAcceleration.limitScroll(-900, 0, 100, s);
        assertEquals(-100, s[0], 0.001);
        assertEquals(0, s[1], 0);
    }
}
