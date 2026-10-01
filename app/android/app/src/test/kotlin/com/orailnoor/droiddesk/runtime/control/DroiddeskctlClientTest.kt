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
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.stop()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun start(vararg args: String): Process =
        ProcessBuilder(listOf("python3", script.absolutePath, "--port", server.localPort.toString()) + args)
            .redirectErrorStream(true)
            .also {
                it.environment()["XDG_CONFIG_HOME"] = File(home, "config").absolutePath
                it.environment().remove("DROIDDESK_PACKAGE")
            }
            .start()

    private fun run(vararg args: String): Pair<Int, String> {
        val process = start(*args)
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    private fun tokenFile() = File(home, "config/droiddesk/token")

    private fun pair() {
        val process = start("pair")
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
            val process = ProcessBuilder("python3", script.absolutePath, "--port", fake.localPort.toString(), "status")
                .redirectErrorStream(true)
                .also { it.environment()["XDG_CONFIG_HOME"] = File(home, "config").absolutePath }
                .start()
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
        val process = ProcessBuilder("python3", script.absolutePath, "--port", port.toString(), "ping")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        assertEquals(69, process.exitValue())
        assertTrue(output.contains("no responde"))
    }
}
