package com.orailnoor.droiddesk.runtime.control

/**
 * Reference-counted lifecycle for the control server.
 *
 * MainActivity (the HOME screen) and DroidDeskService (the desktop session)
 * each hold the bridge; it runs while at least one of them is alive, so
 * Termux can reach DroidDesk whether or not the Linux desktop is open.
 */
class ControlLifecycle(
    private val start: () -> Boolean,
    private val stop: () -> Unit,
) {
    private val owners = LinkedHashSet<String>()
    private var running = false

    /** Registers [owner]; starts the server if needed. Returns whether it is running. */
    @Synchronized
    fun acquire(owner: String): Boolean {
        owners += owner
        if (!running) running = runCatching(start).getOrDefault(false)
        return running
    }

    @Synchronized
    fun release(owner: String) {
        owners -= owner
        if (owners.isEmpty() && running) {
            runCatching(stop)
            running = false
        }
    }

    @Synchronized fun isRunning(): Boolean = running
    @Synchronized fun owners(): Set<String> = owners.toSet()
}
