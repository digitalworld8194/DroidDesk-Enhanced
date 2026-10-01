package com.termux.x11.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TrackpadSensitivityTest {
    /** Stands in for SharedPreferences droiddesk_input/trackpad_sensitivity. */
    private static final class MemoryStore implements TrackpadSensitivity.Store {
        Integer value;
        int writes;

        MemoryStore(Integer value) {
            this.value = value;
        }

        @Override public int read(int fallback) {
            return value != null ? value : fallback;
        }

        @Override public void write(int percent) {
            value = percent;
            writes++;
        }
    }

    // --- range and default ---

    @Test public void rangeIs25To300WithDefault100() {
        assertEquals(25, TrackpadSensitivity.MIN);
        assertEquals(300, TrackpadSensitivity.MAX);
        assertEquals(100, TrackpadSensitivity.DEFAULT);
    }

    @Test public void nothingStoredMeans100AndWritesNothing() {
        MemoryStore store = new MemoryStore(null);
        TrackpadSensitivity s = new TrackpadSensitivity(store);
        assertEquals(100, s.get());
        assertNull(store.value);
    }

    @Test public void outOfRangeValuesAreClamped() {
        TrackpadSensitivity s = new TrackpadSensitivity(new MemoryStore(null));
        assertEquals(25, s.set(0));
        assertEquals(25, s.set(-50));
        assertEquals(25, s.set(24));
        assertEquals(300, s.set(301));
        assertEquals(300, s.set(Integer.MAX_VALUE));
        assertEquals(137, s.set(137));
    }

    @Test public void outOfRangeStoredValueIsClampedOnLoad() {
        assertEquals(300, new TrackpadSensitivity(new MemoryStore(1000)).get());
        assertEquals(25, new TrackpadSensitivity(new MemoryStore(3)).get());
    }

    // --- persistence and reset ---

    @Test public void valueSurvivesReopening() {
        MemoryStore store = new MemoryStore(null);
        new TrackpadSensitivity(store).set(175);
        // A new controller (DroidDesk closed and reopened) reads the same preference.
        assertEquals(175, new TrackpadSensitivity(store).get());
    }

    @Test public void storedValueIsLoadedImmediately() {
        assertEquals(250, new TrackpadSensitivity(new MemoryStore(250)).get());
    }

    @Test public void resetGoesBackTo100AndPersists() {
        MemoryStore store = new MemoryStore(200);
        TrackpadSensitivity s = new TrackpadSensitivity(store);
        assertEquals(100, s.reset());
        assertEquals(100, s.get());
        assertEquals(Integer.valueOf(100), store.value);
    }

    @Test public void unchangedValueIsNotRewritten() {
        MemoryStore store = new MemoryStore(150);
        TrackpadSensitivity s = new TrackpadSensitivity(store);
        s.set(150);
        assertEquals(0, store.writes);
        s.set(200);
        s.set(200);
        assertEquals(1, store.writes);
    }

    // --- slider positions ---

    @Test public void sliderStopsAreTheUsefulValues() {
        int[] expected = {25, 50, 75, 100, 125, 150, 175, 200, 250, 300};
        assertEquals(expected.length, TrackpadSensitivity.stopCount());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], TrackpadSensitivity.percentAt(i));
            assertEquals(i, TrackpadSensitivity.stopIndex(expected[i]));
        }
    }

    @Test public void sliderPositionsAreClampedAndNearest() {
        assertEquals(25, TrackpadSensitivity.percentAt(-1));
        assertEquals(300, TrackpadSensitivity.percentAt(99));
        assertEquals(TrackpadSensitivity.stopIndex(100), TrackpadSensitivity.stopIndex(110));
        assertEquals(TrackpadSensitivity.stopIndex(250), TrackpadSensitivity.stopIndex(240));
        assertEquals(0, TrackpadSensitivity.stopIndex(-5));
        assertEquals(TrackpadSensitivity.stopCount() - 1, TrackpadSensitivity.stopIndex(1000));
    }

    @Test public void storedPercentDrivesPointerGain() {
        assertEquals(1f, PointerAcceleration.sensitivityFromPercent(TrackpadSensitivity.DEFAULT), 0);
        assertEquals(2.5f, PointerAcceleration.sensitivityFromPercent(TrackpadSensitivity.percentAt(8)), 0);
    }
}
