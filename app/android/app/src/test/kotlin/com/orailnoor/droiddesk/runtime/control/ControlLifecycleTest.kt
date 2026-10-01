package com.orailnoor.droiddesk.runtime.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlLifecycleTest {
    private var starts = 0
    private var stops = 0
    private var startSucceeds = true
    private val lifecycle = ControlLifecycle(start = { starts++; startSucceeds }, stop = { stops++ })

    @Test fun runsWhileAnyOwnerHoldsIt() {
        assertTrue(lifecycle.acquire("MainActivity"))
        assertTrue(lifecycle.acquire("DroidDeskService"))
        assertEquals(1, starts)
        lifecycle.release("DroidDeskService")
        assertTrue(lifecycle.isRunning())
        assertEquals(0, stops)
        lifecycle.release("MainActivity")
        assertFalse(lifecycle.isRunning())
        assertEquals(1, stops)
    }

    @Test fun sameOwnerTwiceIsOneHold() {
        lifecycle.acquire("MainActivity")
        lifecycle.acquire("MainActivity")
        lifecycle.release("MainActivity")
        assertFalse(lifecycle.isRunning())
    }

    @Test fun failedStartIsRetriedByTheNextOwner() {
        startSucceeds = false
        assertFalse(lifecycle.acquire("MainActivity"))
        startSucceeds = true
        assertTrue(lifecycle.acquire("DroidDeskService"))
        assertEquals(2, starts)
        assertEquals(setOf("MainActivity", "DroidDeskService"), lifecycle.owners())
    }

    @Test fun releaseWithoutStartDoesNotStop() {
        lifecycle.release("nobody")
        assertEquals(0, stops)
    }

    @Test fun throwingStartCountsAsNotRunning() {
        val throwing = ControlLifecycle(start = { throw IllegalStateException("port busy") }, stop = {})
        assertFalse(throwing.acquire("MainActivity"))
    }
}
