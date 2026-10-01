package com.orailnoor.droiddesk.runtime.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/** Runs the real scripts/droiddeskctl (Python) against a real ControlServer. */
class DroiddeskctlClientTest {
    private val script = File("../../../scripts/droiddeskctl")
    private lateinit var home: File
    private lateinit var server: ControlServer
    @Volatile private var code: String? = null
    private val authority = TokenAuthority(InMemorySecretStorage())

    private val handler = object : ControlHandler {
        override fun handle(request: ControlProtocol.Request, output: ControlOutput) = when (request.action) {
            ControlProtocol.Action.EXEC -> {
                output.out("ran: ${request.args[0]}\n")
                ControlHandler.Result(false, 5, mapOf("exit" to 5))
            }
            ControlProtocol.Action.SHELL_INFO -> ControlHandler.Result(true, data = mapOf("package" to "test.pkg", "uid" to 1))
            ControlProtocol.Action.NODE -> {
                output.out(request.args.joinToString("|") + "\n")
                ControlHandler.Result(true, data = mapOf("exit" to 0))
            }
            else -> ControlHandler.Result(true, data = mapOf("echo" to request.action.wire))
        }

        override fun showPairingCode(code: String, expiresInMs: Long) {
            this@DroiddeskctlClientTest.code = code
        }
    }

    private fun python(): String? = listOf("python3").firstOrNull { tool ->
        System.getenv("PATH").orEmpty().split(':').any { File(it, tool).canExecute() }
    }

    @Before fun setUp() {
        assumeTrue("python3 not available", python() != null)
        assumeTrue(script.isFile)
        home = Files.createTempDirectory("ctlhome").toFile()
        server = ControlServer(port = 0, authority = authority, handler = handler)
        server.start()
        adbState.mkdirs()
        fakeAdb.writeText(FAKE_ADB)
        fakeAdb.setExecutable(true)
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.stop()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private val adbState get() = File(home, "adb")
    private val fakeAdb get() = File(home, "fake-adb")
    private fun adbLog() = File(adbState, "log").let { if (it.exists()) it.readText() else "" }
    private fun adbForwards() = File(adbState, "forwards").let { if (it.exists()) it.readText() else "" }
    private fun devices(vararg lines: String) = File(adbState, "devices").writeText(lines.joinToString("") { "$it\n" })

    private fun process(args: List<String>): Process =
        ProcessBuilder(listOf("python3", script.absolutePath) + args)
            .redirectErrorStream(true)
            .also {
                val env = it.environment()
                env["XDG_CONFIG_HOME"] = File(home, "config").absolutePath
                listOf("DROIDDESK_PACKAGE", "DROIDDESK_PORT", "DROIDDESK_LOCAL_PORT", "DROIDDESK_ADB_SERIAL",
                    "ANDROID_SERIAL", "DROIDDESK_DIRECT").forEach(env::remove)
                env["DROIDDESK_ADB"] = fakeAdb.absolutePath
                env["FAKE_ADB_STATE"] = adbState.absolutePath
            }
            .start()

    /** Direct connection to the test server (what DroidDesk sees behind the adb forward). */
    private fun start(vararg args: String): Process =
        process(listOf("--direct", "--port", server.localPort.toString()) + args)

    /** Through the (fake) adb forward: Termux connects to the local port, DroidDesk's port is 47821. */
    private fun startViaAdb(vararg args: String, localPort: Int = server.localPort): Process =
        process(listOf("--port", REMOTE_PORT.toString(), "--local-port", localPort.toString()) + args)

    private fun finish(process: Process): Pair<Int, String> {
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    private fun run(vararg args: String) = finish(start(*args))
    private fun runViaAdb(vararg args: String, localPort: Int = server.localPort) =
        finish(startViaAdb(*args, localPort = localPort))

    private fun tokenFile() = File(home, "config/droiddesk/token")

    private fun pair(starter: () -> Process = { start("pair") }) {
        val process = starter()
        val deadline = System.currentTimeMillis() + 15_000
        while (code == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        process.outputStream.write("$code\n".toByteArray())
        process.outputStream.flush()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }

    @Test fun pairsAndStoresTheTokenWith600() {
        pair()
        assertTrue(tokenFile().isFile)
        val perms = Files.getPosixFilePermissions(tokenFile().toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(tokenFile().parentFile.toPath()))
        assertTrue(authority.verify(tokenFile().readText().trim()))
    }

    @Test fun execStreamsAndPropagatesTheExitCode() {
        pair()
        val (exit, output) = run("exec", "--", "echo hi && false")
        assertEquals(5, exit)
        assertTrue(output, output.contains("ran: echo hi && false"))
    }

    @Test fun nodeArgumentsArePassedAsAList() {
        pair()
        val (exit, output) = run("node", "-e", "console.log('a b')")
        assertEquals(0, exit)
        assertTrue(output, output.contains("-e|console.log('a b')"))
    }

    @Test fun refusesToRunWithoutPairing() {
        val (exit, output) = run("status")
        assertEquals(77, exit)
        assertTrue(output, output.contains("droiddeskctl pair"))
    }

    @Test fun clientBlocksMassUpgradesWithoutForce() {
        pair()
        val (exit, output) = run("exec", "--", "apt upgrade -y")
        assertEquals(64, exit)
        assertTrue(output.contains("--force"))
    }

    @Test fun neverSendsTheTokenToAnImpostor() {
        pair()
        val token = tokenFile().readText().trim()
        // A fake server on another port that answers with a bogus challenge.
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { fake ->
            val received = StringBuilder()
            val thread = Thread {
                fake.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    received.append(reader.readLine())
                    socket.getOutputStream().write("{\"type\":\"challenge\",\"proof\":\"${"0".repeat(64)}\"}\n".toByteArray())
                    socket.soTimeout = 3_000
                    received.append(runCatching { reader.readLine() }.getOrNull().orEmpty())
                }
            }.apply { start() }
            val process = process(listOf("--direct", "--port", fake.localPort.toString(), "status"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            thread.join(5_000)
            assertEquals(76, process.exitValue())
            assertTrue(output, output.contains("no se envió el token"))
            assertFalse("token leaked to impostor", received.contains(token))
        }
    }

    @Test fun unpairRevokesAndDeletesTheToken() {
        pair()
        val token = tokenFile().readText().trim()
        val (exit, _) = run("unpair")
        assertEquals(0, exit)
        assertFalse(tokenFile().exists())
        assertFalse(authority.verify(token))
    }

    @Test fun reportsUnavailableServer() {
        val port = server.localPort
        server.stop()
        val (exit, output) = finish(process(listOf("--direct", "--port", port.toString(), "ping")))
        assertEquals(69, exit)
        assertTrue(output.contains("no responde"))
    }

    // ── adb forward transport ──

    @Test fun adbNotConnectedReportsAdbRequired() {
        devices()
        val (exit, output) = runViaAdb("ping")
        assertEquals(output, 69, exit)
        assertTrue(output, output.contains("ADB REQUIRED"))
        assertFalse(adbLog(), adbLog().contains("forward tcp:"))
    }

    @Test fun offlineOrUnauthorizedDevicesDoNotCount() {
        devices("emulator-5554\toffline", "R5CX\tunauthorized")
        val (exit, output) = runViaAdb("ping")
        assertEquals(output, 69, exit)
        assertTrue(output, output.contains("ADB REQUIRED"))
    }

    @Test fun missingAdbBinaryReportsAdbRequired() {
        fakeAdb.delete()
        val (exit, output) = runViaAdb("ping")
        assertEquals(output, 69, exit)
        assertTrue(output, output.contains("ADB REQUIRED"))
    }

    @Test fun createsTheForwardWhenMissing() {
        devices("10.0.0.5:4000\tdevice")
        val (exit, output) = runViaAdb("ping")
        assertEquals(output, 0, exit)
        val local = server.localPort
        assertTrue(adbLog(), adbLog().contains("-s 10.0.0.5:4000 forward tcp:$local tcp:$REMOTE_PORT"))
        assertTrue(adbForwards().contains("10.0.0.5:4000 tcp:$local tcp:$REMOTE_PORT"))
    }

    @Test fun reusesAnExistingForwardWithoutDuplicating() {
        devices("10.0.0.5:4000\tdevice")
        File(adbState, "forwards").writeText("10.0.0.5:4000 tcp:${server.localPort} tcp:$REMOTE_PORT\n")
        repeat(2) { assertEquals(0, runViaAdb("ping").first) }
        assertFalse(adbLog(), adbLog().contains("-s 10.0.0.5:4000 forward"))
        assertEquals(1, adbForwards().lines().count { it.isNotBlank() })
    }

    @Test fun replacesAForwardThatPointsElsewhere() {
        devices("10.0.0.5:4000\tdevice")
        File(adbState, "forwards").writeText("10.0.0.5:4000 tcp:${server.localPort} tcp:9999\n")
        assertEquals(0, runViaAdb("ping").first)
        assertTrue(adbForwards(), adbForwards().contains("tcp:${server.localPort} tcp:$REMOTE_PORT"))
        assertFalse(adbForwards(), adbForwards().contains("tcp:9999"))
    }

    @Test fun reportsAFailedForward() {
        devices("10.0.0.5:4000\tdevice")
        File(adbState, "fail_forward").writeText("")
        val (exit, output) = runViaAdb("ping")
        assertEquals(output, 69, exit)
        assertTrue(output, output.contains("adb forward tcp:${server.localPort} tcp:$REMOTE_PORT falló"))
    }

    @Test fun multipleDevicesRequireAnExplicitSerial() {
        devices("10.0.0.5:4000\tdevice", "R5CX123\tdevice")
        val (exit, output) = runViaAdb("ping")
        assertEquals(output, 64, exit)
        assertTrue(output, output.contains("--serial"))
        assertFalse(adbLog(), adbLog().contains("forward tcp:"))

        val (selected, selectedOutput) = runViaAdb("--serial", "R5CX123", "ping")
        assertEquals(selectedOutput, 0, selected)
        assertTrue(adbLog(), adbLog().contains("-s R5CX123 forward tcp:${server.localPort} tcp:$REMOTE_PORT"))
    }

    @Test fun unknownSerialIsRejected() {
        devices("10.0.0.5:4000\tdevice")
        val (exit, output) = runViaAdb("--serial", "nope", "ping")
        assertEquals(output, 69, exit)
        assertTrue(output, output.contains("nope"))
    }

    @Test fun clientConnectsToTheLocalPortOfTheForward() {
        devices("10.0.0.5:4000\tdevice")
        pair { startViaAdb("pair") }
        // Nothing listens on REMOTE_PORT here: success means the client used the local port.
        val (exit, output) = runViaAdb("status")
        assertEquals(output, 0, exit)
        assertTrue(output, output.contains("127.0.0.1:${server.localPort} → adb 10.0.0.5:4000 → DroidDesk 127.0.0.1:$REMOTE_PORT"))
        val (execExit, execOutput) = runViaAdb("exec", "--", "true")
        assertEquals(execOutput, 5, execExit)
    }

    @Test fun pairingThroughTheForwardStaysSecure() {
        devices("10.0.0.5:4000\tdevice")
        pair { startViaAdb("pair") }
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(tokenFile().toPath()))
        val token = tokenFile().readText().trim()
        assertTrue(authority.verify(token))
        assertFalse("token must not be passed to adb", adbLog().contains(token))
        // An impostor bound to the forward's local port never receives the token.
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { fake ->
            val received = StringBuilder()
            val thread = Thread {
                fake.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    received.append(reader.readLine())
                    socket.getOutputStream().write("{\"type\":\"challenge\",\"proof\":\"${"0".repeat(64)}\"}\n".toByteArray())
                    socket.soTimeout = 3_000
                    received.append(runCatching { reader.readLine() }.getOrNull().orEmpty())
                }
            }.apply { start() }
            val (exit, output) = runViaAdb("status", localPort = fake.localPort)
            thread.join(5_000)
            assertEquals(output, 76, exit)
            assertFalse("token leaked to impostor", received.contains(token))
        }
    }

    @Test fun silentForwardIsReportedAsUnavailable() {
        devices("10.0.0.5:4000\tdevice")
        // Like adb with a frozen DroidDesk behind it: accepts, never answers.
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            val thread = Thread { runCatching { silent.accept().use { Thread.sleep(20_000) } } }.apply { isDaemon = true; start() }
            val (exit, output) = runViaAdb("ping", localPort = silent.localPort)
            assertEquals(output, 69, exit)
            assertTrue(output, output.contains("no responde"))
            thread.interrupt()
        }
    }

    @Test fun localPortMustDifferFromDroidDeskPort() {
        val (exit, _) = runViaAdb("ping", localPort = REMOTE_PORT)
        assertEquals(64, exit)
    }

    companion object {
        private const val REMOTE_PORT = 47821

        /** Minimal adb: `devices`, `forward --list`, `-s SERIAL forward tcp:L tcp:R` over a state directory. */
        private val FAKE_ADB = """
            #!/usr/bin/env python3
            import os, sys
            state = os.environ["FAKE_ADB_STATE"]
            args = sys.argv[1:]
            def path(name):
                return os.path.join(state, name)
            def read(name):
                return open(path(name)).read() if os.path.exists(path(name)) else ""
            with open(path("log"), "a") as log:
                log.write(" ".join(args) + "\n")
            if args == ["devices"]:
                sys.stdout.write("List of devices attached\n" + read("devices") + "\n")
                sys.exit(0)
            if args == ["forward", "--list"]:
                sys.stdout.write(read("forwards"))
                sys.exit(0)
            if len(args) == 5 and args[0] == "-s" and args[2] == "forward":
                if os.path.exists(path("fail_forward")):
                    sys.stderr.write("adb: error: cannot bind listener: Address already in use\n")
                    sys.exit(1)
                lines = [l for l in read("forwards").splitlines() if l.split()[1:2] != [args[3]]]
                lines.append(" ".join([args[1], args[3], args[4]]))
                open(path("forwards"), "w").write("\n".join(lines) + "\n")
                sys.exit(0)
            sys.exit(2)
        """.trimIndent() + "\n"
    }
}
