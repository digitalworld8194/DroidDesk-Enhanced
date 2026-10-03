package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.runtime.WirelessAdbRestorePolicy.BASE_BACKOFF_MS
import com.orailnoor.droiddesk.runtime.WirelessAdbRestorePolicy.FIGHT_WINDOW_MS
import com.orailnoor.droiddesk.runtime.WirelessAdbRestorePolicy.FREE_RETRIES
import com.orailnoor.droiddesk.runtime.WirelessAdbRestorePolicy.MAX_BACKOFF_MS
import com.orailnoor.droiddesk.runtime.WirelessAdbRestorePolicy.backoffMs
import com.orailnoor.droiddesk.runtime.WirelessAdbRestorePolicy.nextStreak
import com.orailnoor.droiddesk.runtime.WirelessAdbRestorePolicy.waitMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbRestorePolicyTest {
    private val t0 = 1_800_000_000_000L

    @Test fun firstRestoreIsImmediate() {
        assertEquals(0, nextStreak(t0, 0L, 0))
        assertEquals(0L, waitMs(t0, 0L, 0))
    }

    @Test fun isolatedSwitchOffLongAfterLastRestoreIsImmediate() {
        val streak = nextStreak(t0 + 6 * 3_600_000L, t0, 7)
        assertEquals(0, streak)
        assertEquals(0L, waitMs(t0 + 6 * 3_600_000L, t0, streak))
    }

    @Test fun firstFightRetriesAreImmediate() {
        var last = 0L
        var streak = 0
        var now = t0
        repeat(FREE_RETRIES) {
            streak = nextStreak(now, last, streak)
            assertEquals(0L, waitMs(now, last, streak))
            last = now
            now += 30
        }
        assertEquals(FREE_RETRIES - 1, streak)
    }

    @Test fun tightFightIsThrottled() {
        // Android writes 0 back ~30ms after every restore (seen with Wi-Fi off).
        var last = 0L
        var streak = 0
        var now = t0
        var restores = 0
        while (now < t0 + 10_000L) {
            val candidate = nextStreak(now, last, streak)
            if (waitMs(now, last, candidate) == 0L) {
                last = now
                streak = candidate
                restores++
            }
            now += 30
        }
        // The unthrottled keeper did 390 restores in 10s on the S24.
        assertTrue("restores=$restores", restores <= FREE_RETRIES + 1)
    }

    @Test fun backoffDoublesAndIsCapped() {
        assertEquals(0L, backoffMs(FREE_RETRIES - 1))
        assertEquals(BASE_BACKOFF_MS, backoffMs(FREE_RETRIES))
        assertEquals(2 * BASE_BACKOFF_MS, backoffMs(FREE_RETRIES + 1))
        assertEquals(4 * BASE_BACKOFF_MS, backoffMs(FREE_RETRIES + 2))
        assertEquals(MAX_BACKOFF_MS, backoffMs(FREE_RETRIES + 50))
    }

    @Test fun deferredRetryKeepsTheStreakGrowing() {
        // A retry fired exactly at the end of its backoff must not reset the streak.
        for (streak in FREE_RETRIES until FREE_RETRIES + 12) {
            val candidate = streak + 1
            val now = t0 + backoffMs(candidate)
            assertEquals(candidate, nextStreak(now, t0, streak))
            assertEquals(0L, waitMs(now, t0, candidate))
        }
    }

    @Test fun switchOffAfterTheFightWindowResetsTheStreak() {
        val streak = FREE_RETRIES + 2
        val now = t0 + backoffMs(streak + 1) + FIGHT_WINDOW_MS + 1
        assertEquals(0, nextStreak(now, t0, streak))
    }

    @Test fun clockMovingBackwardsDoesNotBlockRestores() {
        assertEquals(0, nextStreak(t0 - 1_000L, t0, 9))
        assertEquals(0L, waitMs(t0 - 1_000L, t0, 9))
    }
}
