package com.orailnoor.droiddesk.runtime.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** End-to-end tests over a real 127.0.0.1 socket. */
class ControlServerTest {
    private val storage = InMemorySecretStorage()
    private val authority = TokenAuthority(storage)
    private val handled = CopyOnWriteArrayList<ControlProtocol.Request>()
    private val audit = CopyOnWriteArrayList<AuditEntry>()
    @Volatile private var lastCode: String? = null

    private val handler = object : ControlHandler {
        override fun handle(request: ControlProtocol.Request, output: ControlOutput): ControlHandler.Result {
            handled += request
            return when (request.action) {
                ControlProtocol.Action.STATUS -> ControlHandler.Result(
                    true, data = mapOf("bootstrapped" to true, "node" to mapOf("ok" to true, "version" to "v26.4.0")),
                )
                ControlProtocol.Action.EXEC -> {
                    output.out("hello\n")
                    output.out("world\n")
                    ControlHandler.Result(false, 7, mapOf("exit" to 7))
                }
                else -> ControlHandler.Result(true)
            }
        }

        override fun showPairingCode(code: String, expiresInMs: Long) {
            lastCode = code
        }
    }

    private lateinit var server: ControlServer

    @Before fun start() {
        server = ControlServer(port = 0, authority = authority, handler = handler, audit = { audit += it }, readTimeoutMs = 2_000)
        server.start()
    }

    @After fun stop() = server.stop()

    private fun nonce() = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /** Minimal client implementing the same handshake as scripts/droiddeskctl. */
    private fun call(
        action: String,
        args: List<String> = emptyList(),
        token: String? = null,
        verifyProof: Boolean = true,
        extra: Map<String, Any?> = emptyMap(),
    ): List<Map<*, *>> {
        Socket(server.boundAddress, server.localPort).use { socket ->
            socket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = socket.getOutputStream()
            val requestNonce = nonce()
            val request = linkedMapOf<String, Any?>("v" to 1, "action" to action, "args" to args, "nonce" to requestNonce)
            request.putAll(extra)
            out.write((MiniJson.stringify(request) + "\n").toByteArray())
            out.flush()
            val frames = mutableListOf<Map<*, *>>()
            while (true) {
                val line = reader.readLine() ?: return frames
                val frame = MiniJson.parse(line) as Map<*, *>
                frames += frame
                when (frame["type"]) {
                    "challenge" -> {
                        if (token == null) return frames
                        if (verifyProof) {
                            val expected = TokenAuthority.proof(TokenAuthority.tokenHash(token), requestNonce, action)
                            assertEquals("server proof", expected, frame["proof"])
                        }
                        out.write((MiniJson.stringify(mapOf("token" to token)) + "\n").toByteArray())
                        out.flush()
                    }
                    "result", "error" -> return frames
                }
            }
        }
    }

    private fun pair(): String {
        val start = call("pair").last()
        assertEquals(true, start["ok"])
        val code = lastCode!!
        val confirm = call("pair-confirm", listOf(code)).last()
        assertEquals(true, confirm["ok"])
        return (confirm["data"] as Map<*, *>)["token"] as String
    }

    @Test fun bindsLoopbackOnly() {
        val address = server.boundAddress!!
        assertTrue(address.isLoopbackAddress)
        assertFalse(address.isAnyLocalAddress)
        assertEquals("127.0.0.1", address.hostAddress)
    }

    @Test fun isNotReachableThroughOtherInterfaces() {
        val external = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }
        }.getOrNull() ?: return // no non-loopback interface in this sandbox
        try {
            Socket().use { it.connect(InetSocketAddress(external, server.localPort), 2_000) }
            fail("control port reachable on $external")
        } catch (_: java.io.IOException) {
            // expected: refused or unreachable
        }
    }

    @Test fun pingNeedsNoToken() {
        val result = call("ping").single()
        assertEquals("result", result["type"])
        assertEquals(false, (result["data"] as Map<*, *>)["paired"])
    }

    @Test fun tokenActionsAreRefusedBeforePairing() {
        val frame = call("status", token = "a".repeat(64)).single()
        assertEquals("not_paired", frame["code"])
        assertTrue(handled.isEmpty())
    }

    @Test fun pairedClientGetsAVerifiableChallengeAndStatus() {
        val token = pair()
        val frames = call("status", token = token)
        assertEquals("challenge", frames[0]["type"])
        val result = frames.last()
        assertEquals(true, result["ok"])
        val data = result["data"] as Map<*, *>
        assertEquals("v26.4.0", (data["node"] as Map<*, *>)["version"])
        assertEquals(ControlProtocol.Action.STATUS, handled.single().action)
    }

    @Test fun invalidTokenIsRejectedAfterTheChallenge() {
        pair()
        val frames = call("status", token = "f".repeat(64), verifyProof = false)
        assertEquals("unauthorized", frames.last()["code"])
        assertTrue(handled.isEmpty())
    }

    @Test fun missingTokenLineTimesOutAsUnauthorized() {
        pair()
        val frames = call("status", token = null)
        assertEquals("challenge", frames.single()["type"])
        assertTrue(handled.isEmpty())
    }

    @Test fun tokenInTheRequestLineIsRejected() {
        val token = pair()
        val frame = call("status", extra = mapOf("token" to token)).single()
        assertEquals("bad_request", frame["code"])
    }

    @Test fun unknownActionsAreRejectedWithoutReachingTheHandler() {
        val token = pair()
        assertEquals("unknown_action", call("pm-clear", token = token).single()["code"])
        assertTrue(handled.isEmpty())
    }

    @Test fun streamsOutputAndReturnsTheExitCode() {
        val token = pair()
        val frames = call("exec", listOf("echo hi"), token = token)
        val outputs = frames.filter { it["type"] == "out" }.joinToString("") { it["data"] as String }
        assertEquals("hello\nworld\n", outputs)
        assertEquals(7L, frames.last()["exit"])
        assertEquals(false, frames.last()["ok"])
    }

    @Test fun revokeInvalidatesTheToken() {
        val token = pair()
        assertEquals(true, call("revoke", token = token).last()["ok"])
        assertEquals("not_paired", call("status", token = token).single()["code"])
    }

    @Test fun wrongPairingCodeDoesNotPair() {
        call("pair")
        val wrong = if (lastCode == "123456") "654321" else "123456"
        val confirm = call("pair-confirm", listOf(wrong)).last()
        assertEquals(false, confirm["ok"])
        assertFalse(authority.isPaired())
    }

    @Test fun auditNeverContainsTokensOrCodes() {
        val token = pair()
        call("exec", listOf("echo secret-free"), token = token)
        val text = audit.joinToString { it.toString() }
        assertFalse(text.contains(token))
        assertFalse(text.contains(lastCode!!))
        assertTrue(audit.any { it.action == "exec" && it.detail == "echo secret-free" && it.exit == 7 })
    }

    @Test fun stopAndRestartLifecycle() {
        val port = server.localPort
        server.stop()
        assertFalse(server.isRunning)
        try {
            Socket("127.0.0.1", port).close()
            fail("server still accepting after stop")
        } catch (_: ConnectException) {
        }
        val again = ControlServer(port = port, authority = authority, handler = handler)
        again.start()
        try {
            Socket("127.0.0.1", port).use { assertNotNull(it) }
        } finally {
            again.stop()
        }
    }

    @Test fun oversizedRequestIsRejected() {
        Socket(server.boundAddress, server.localPort).use { socket ->
            socket.getOutputStream().write(ByteArray(ControlProtocol.MAX_REQUEST_BYTES + 10) { 'a'.code.toByte() })
            socket.getOutputStream().write('\n'.code)
            val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            assertEquals("bad_request", (MiniJson.parse(line) as Map<*, *>)["code"])
        }
        assertNull(handled.firstOrNull())
    }
}
