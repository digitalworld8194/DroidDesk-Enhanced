package com.orailnoor.droiddesk.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Detects the native desktop from what really runs, never from a launcher
 * process DroidDesk happens to hold (or from PRoot): the session components
 * must exist in /proc under DroidDesk's UID and the X display socket must
 * accept a connection.
 *
 * Everything here does blocking I/O (/proc, a socket connect): call it from a
 * worker thread, never from the main thread.
 */
class DesktopProbe(
    private val procDir: File = File("/proc"),
    private val uid: Int = android.os.Process.myUid(),
    private val displayAnswers: (File) -> Boolean = { DisplayCheck.answers(it) },
) {
    data class State(
        val components: Map<String, Boolean>,
        val display: Boolean,
    ) {
        /** Every component of the desktop runs and DISPLAY=:0 answers. */
        val active: Boolean get() = display && components.values.all { it }

        fun toMap(): Map<String, Any> = mapOf("active" to active, "display" to display) + components
    }

    /** A process of this app as the kernel reports it. */
    data class Proc(val pid: Int, val comm: String, val startTime: Long)

    /** The processes that make a desktop visible, per desktop environment. */
    fun components(desktopEnv: String): List<String> = when (desktopEnv.lowercase()) {
        "lxqt" -> listOf("lxqt-session", "lxqt-panel")
        "mate" -> listOf("mate-session", "mate-panel")
        "kde" -> listOf("plasmashell")
        else -> listOf("xfce4-session", "xfce4-panel", "xfdesktop")
    }

    fun state(desktopEnv: String, x11Socket: File): State {
        val running = processes().mapTo(HashSet()) { it.comm }
        return State(
            // Exact kernel names only: "xfce4-panel-foo" or "proot" never match.
            components = components(desktopEnv).associateWith { it in running },
            display = x11Socket.exists() && displayAnswers(x11Socket),
        )
    }

    /** True when the desktop's session manager runs (it may still be starting). */
    fun sessionPresent(desktopEnv: String): Boolean =
        processes().any { it.comm == components(desktopEnv).first() }

    /** This app's processes; unreadable or vanished entries are skipped. */
    fun processes(): List<Proc> {
        val entries = runCatching { procDir.listFiles() }.getOrNull() ?: return emptyList()
        return entries.mapNotNull { entry -> entry.name.toIntOrNull()?.let { read(it) } }
    }

    /** Reads one PID; null when it is not ours, gone, or unreadable. */
    fun read(pid: Int): Proc? = try {
        val dir = File(procDir, pid.toString())
        if (ownerUid(dir) != uid) {
            null
        } else {
            // Exact name: only the kernel's trailing newline is removed.
            val comm = File(dir, "comm").readText().removeSuffix("\n")
            val startTime = startTime(File(dir, "stat").readText())
            if (comm.isEmpty() || startTime == null) null else Proc(pid, comm, startTime)
        }
    } catch (error: Exception) {
        // IOException (process exited), SecurityException (hidepid/SELinux), parse errors.
        null
    }

    /** True when the process's environment contains exactly [entry] ("KEY=value"). */
    fun environContains(pid: Int, entry: String): Boolean = try {
        File(procDir, "$pid/environ").readBytes().toString(Charsets.UTF_8).split('\u0000').contains(entry)
    } catch (error: Exception) {
        false
    }

    private fun ownerUid(dir: File): Int? =
        File(dir, "status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
        }

    companion object {
        /**
         * Field 22 of /proc/<pid>/stat (start time in clock ticks). Parsed after
         * the last ')' because comm may contain spaces or parentheses.
         */
        fun startTime(stat: String): Long? {
            val fields = stat.substringAfterLast(')').trim().split(' ')
            // fields[0] is field 3 (state), so field 22 is fields[19].
            return fields.getOrNull(19)?.toLongOrNull()
        }
    }
}

/**
 * Checks that something accepts connections on the X11 socket: a stale
 * socket file (server gone) refuses immediately and counts as no display.
 * The connect runs on its own daemon thread bounded by [TIMEOUT_MS]; the
 * socket is always closed and no data is sent. While a previous check is
 * still stuck no new thread is started and the display counts as absent.
 */
object DisplayCheck {
    const val TIMEOUT_MS = 500L

    private val inFlight = AtomicReference<Thread?>(null)

    fun answers(socket: File, timeoutMs: Long = TIMEOUT_MS, connect: (File) -> Unit = ::connectLocal): Boolean {
        if (inFlight.get()?.isAlive == true) return false
        val result = AtomicReference<Boolean?>(null)
        val worker = Thread({
            result.set(runCatching { connect(socket) }.isSuccess)
        }, "x11-display-check").apply { isDaemon = true }
        inFlight.set(worker)
        worker.start()
        worker.join(timeoutMs)
        return result.get() == true
    }

    private fun connectLocal(socket: File) {
        LocalSocket().use {
            it.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        }
    }
}

/** Validates a recorded session PID before DroidDesk signals it. */
object SessionIdentity {
    /**
     * Returns [pid] only when /proc shows exactly the recorded process: owned
     * by this app's UID ([DesktopProbe.read] rejects other UIDs), the same
     * start time (a reused PID has another), the exact session-manager name
     * and this session's D-Bus address in its environment.
     */
    fun verify(probe: DesktopProbe, pid: Int, startTime: Long, comm: String, dbusAddress: String): Int? {
        val proc = probe.read(pid) ?: return null
        if (proc.startTime != startTime || proc.comm != comm) return null
        if (!probe.environContains(pid, "DBUS_SESSION_BUS_ADDRESS=$dbusAddress")) return null
        return pid
    }
}
