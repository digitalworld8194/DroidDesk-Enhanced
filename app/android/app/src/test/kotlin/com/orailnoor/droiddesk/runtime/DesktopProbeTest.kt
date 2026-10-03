package com.orailnoor.droiddesk.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopProbeTest {
    private lateinit var root: File
    private lateinit var proc: File
    private lateinit var socket: File
    private var nextPid = 100
    private var displayAnswers = true

    @Before fun setUp() {
        root = Files.createTempDirectory("probe").toFile()
        proc = File(root, "proc").apply { mkdirs() }
        socket = File(root, "tmp/.X11-unix/X0").apply { parentFile.mkdirs(); writeText("") }
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun process(
        comm: String,
        uid: Int = APP_UID,
        startTime: Long = 4242,
        environ: List<String> = emptyList(),
        ppid: Int = 1,
    ): Int {
        val pid = nextPid++
        File(proc, pid.toString()).apply {
            mkdirs()
            File(this, "comm").writeText("$comm\n")
            File(this, "status").writeText("Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n")
            // pid (comm) state ppid ... field 22 = starttime
            val fields = listOf("S", ppid.toString()) + List(17) { "0" } + startTime.toString() + List(5) { "0" }
            File(this, "stat").writeText("$pid ($comm) ${fields.joinToString(" ")}\n")
            File(this, "environ").writeBytes(environ.joinToString("\u0000", postfix = "\u0000").toByteArray())
        }
        return pid
    }

    private fun probe() = DesktopProbe(proc, APP_UID) { displayAnswers }

    private fun xfce() = listOf("xfce4-session", "xfce4-panel", "xfdesktop", "dbus-daemon").forEach { process(it) }

    // ── State ──

    @Test fun activeWhenTheThreeComponentsRunAndDisplayAnswers() {
        xfce()
        val state = probe().state("xfce4", socket)
        assertTrue(state.toMap().toString(), state.active)
        assertEquals(mapOf("xfce4-session" to true, "xfce4-panel" to true, "xfdesktop" to true), state.components)
    }

    @Test fun missingXfdesktopIsNotActive() = assertMissing("xfdesktop")

    @Test fun missingPanelIsNotActive() = assertMissing("xfce4-panel")

    @Test fun missingSessionIsNotActive() = assertMissing("xfce4-session")

    private fun assertMissing(component: String) {
        listOf("xfce4-session", "xfce4-panel", "xfdesktop").filter { it != component }.forEach { process(it) }
        val state = probe().state("xfce4", socket)
        assertFalse(state.active)
        assertEquals(false, state.components[component])
    }

    @Test fun deadDisplayIsNotActive() {
        xfce()
        displayAnswers = false
        assertFalse(probe().state("xfce4", socket).active)
    }

    @Test fun missingSocketFileIsNotActiveAndNotProbed() {
        xfce()
        socket.delete()
        var probed = false
        val state = DesktopProbe(proc, APP_UID) { probed = true; true }.state("xfce4", socket)
        assertFalse(state.active)
        assertFalse(probed)
    }

    @Test fun prootAloneIsNeverADesktop() {
        listOf("proot", "proot-distro", "bash", "loader").forEach { process(it) }
        assertFalse(probe().state("xfce4", socket).active)
        assertFalse(probe().sessionPresent("xfce4"))
    }

    @Test fun similarNamesDoNotMatch() {
        listOf("xfce4-session2", "xfce4-panel-x", "xfdesktop-settings", "Xfdesktop", " xfce4-session")
            .forEach { process(it) }
        val state = probe().state("xfce4", socket)
        assertFalse(state.active)
        assertTrue(state.components.values.none { it })
    }

    @Test fun processesOfAnotherUidDoNotCount() {
        listOf("xfce4-session", "xfce4-panel", "xfdesktop").forEach { process(it, uid = 2000) }
        assertFalse(probe().state("xfce4", socket).active)
        assertFalse(probe().sessionPresent("xfce4"))
    }

    @Test fun unreadableOrVanishedProcEntriesDoNotCrash() {
        File(proc, "999").mkdirs()                                  // exited: no files
        File(proc, "998").apply { mkdirs(); File(this, "status").mkdirs() } // read error (EISDIR)
        File(proc, "997").apply { mkdirs(); File(this, "status").writeText("garbage") }
        File(proc, "self").mkdirs()
        xfce()
        assertTrue(probe().state("xfce4", socket).active)
    }

    @Test fun missingProcDirectoryMeansNothingRuns() {
        val state = DesktopProbe(File(root, "nope"), APP_UID) { true }.state("xfce4", socket)
        assertFalse(state.active)
    }

    @Test fun sessionPresentWhileTheDesktopIsStillStarting() {
        process("xfce4-session")
        assertTrue(probe().sessionPresent("xfce4"))
        assertFalse(probe().state("xfce4", socket).active)
    }

    @Test fun parsesStartTimeEvenWithParenthesesInComm() {
        val stat = "123 (we ird) (x)) S 1 " + List(17) { "0" }.joinToString(" ") + " 98765 0 0"
        assertEquals(98765L, DesktopProbe.startTime(stat))
    }

    // ── Session identity used by "Detener" ──

    private val dbus = "unix:path=/data/user/0/pkg/files/tmp/dbus-session"

    private fun session(uid: Int = APP_UID, startTime: Long = 4242, env: String = "DBUS_SESSION_BUS_ADDRESS=$dbus") =
        process("xfce4-session", uid, startTime, listOf("HOME=/x", env, "DISPLAY=:0"))

    @Test fun identityAcceptsExactlyTheRecordedSession() {
        val pid = session()
        assertEquals(pid, SessionIdentity.verify(probe(), pid, 4242, "xfce4-session", dbus))
    }

    @Test fun identityRejectsAReusedPid() {
        val pid = session(startTime = 9999)
        assertNull(SessionIdentity.verify(probe(), pid, 4242, "xfce4-session", dbus))
    }

    @Test fun identityRejectsAnotherUid() {
        val pid = session(uid = 2000)
        assertNull(SessionIdentity.verify(probe(), pid, 4242, "xfce4-session", dbus))
    }

    @Test fun identityRejectsAnotherProgram() {
        val pid = process("bash", startTime = 4242, environ = listOf("DBUS_SESSION_BUS_ADDRESS=$dbus"))
        assertNull(SessionIdentity.verify(probe(), pid, 4242, "xfce4-session", dbus))
    }

    @Test fun identityRejectsAnotherSessionsBus() {
        val pid = session(env = "DBUS_SESSION_BUS_ADDRESS=unix:path=/other/dbus")
        assertNull(SessionIdentity.verify(probe(), pid, 4242, "xfce4-session", dbus))
        val prefix = session(env = "DBUS_SESSION_BUS_ADDRESS=${dbus}-2")
        assertNull(SessionIdentity.verify(probe(), prefix, 4242, "xfce4-session", dbus))
    }

    @Test fun identityRejectsAGonePid() {
        assertNull(SessionIdentity.verify(probe(), 31337, 4242, "xfce4-session", dbus))
    }

    // ── X11 socket check (real Unix sockets) ──

    private fun unixConnect(file: File) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(file.toPath())) }
    }

    @Test fun displayAnswersWhenAServerListens() {
        val path = File(root, "live").toPath()
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(path))
            assertTrue(DisplayCheck.answers(path.toFile(), connect = ::unixConnect))
        }
    }

    @Test fun staleSocketFileIsNoDisplay() {
        val path = File(root, "stale").toPath()
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(path)) }
        assertTrue("socket file stays behind", path.toFile().exists())
        assertFalse(DisplayCheck.answers(path.toFile(), connect = ::unixConnect))
    }

    @Test fun hangingConnectTimesOutAndStartsNoSecondThread() {
        val release = CountDownLatch(1)
        var started = 0
        val hang: (File) -> Unit = { started++; release.await(10, TimeUnit.SECONDS) }
        val begin = System.nanoTime()
        assertFalse(DisplayCheck.answers(socket, timeoutMs = 200, connect = hang))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 2_000)
        assertFalse(DisplayCheck.answers(socket, timeoutMs = 200, connect = hang))
        assertEquals(1, started)
        release.countDown()
        Thread.sleep(100)
        assertTrue(DisplayCheck.answers(socket, timeoutMs = 200) { })
    }

    private companion object {
        const val APP_UID = 10337
    }
}
