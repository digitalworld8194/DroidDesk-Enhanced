package com.orailnoor.droiddesk.runtime

/**
 * Decides when Wireless debugging may be re-enabled.
 *
 * Android itself writes adb_wifi_enabled=0 when the current Wi-Fi network is
 * not trusted; restoring it unconditionally would fight the system in a tight
 * loop. A restore that is undone shortly after it was written extends a
 * "fight streak": the first few retries are immediate, later ones back off
 * exponentially. An isolated switch-off (streak reset) is restored at once.
 */
internal object WirelessAdbRestorePolicy {
    const val FREE_RETRIES = 3
    const val BASE_BACKOFF_MS = 10_000L
    const val MAX_BACKOFF_MS = 30 * 60_000L

    /** Extra time after a backoff during which a switch-off still counts as a fight. */
    const val FIGHT_WINDOW_MS = 60_000L

    fun backoffMs(streak: Int): Long {
        if (streak < FREE_RETRIES) return 0L
        val doublings = (streak - FREE_RETRIES).coerceAtMost(20)
        return (BASE_BACKOFF_MS shl doublings).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** Streak a restore at [now] would have, given the previous restore. */
    fun nextStreak(now: Long, lastRestoreAt: Long, streak: Int): Int {
        if (lastRestoreAt <= 0L || now < lastRestoreAt) return 0
        val candidate = streak + 1
        return if (now - lastRestoreAt <= backoffMs(candidate) + FIGHT_WINDOW_MS) candidate else 0
    }

    /** Milliseconds to wait before restoring with [candidateStreak]; 0 means now. */
    fun waitMs(now: Long, lastRestoreAt: Long, candidateStreak: Int): Long {
        if (lastRestoreAt <= 0L || now < lastRestoreAt) return 0L
        return (lastRestoreAt + backoffMs(candidateStreak) - now).coerceAtLeast(0L)
    }
}
