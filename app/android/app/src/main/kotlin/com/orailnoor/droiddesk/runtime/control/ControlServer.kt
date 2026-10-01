package com.orailnoor.droiddesk.runtime.control

import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Streams frames back to the Termux client for one request. */
interface ControlOutput {
    fun out(text: String)
    /** True once the client disconnected; long operations should stop. */
    val cancelled: Boolean
}

/** Executes authenticated, already validated requests. Android glue lives in ControlBridge. */
interface ControlHandler {
    data class Result(val ok: Boolean, val exit: Int = if (ok) 0 else 1, val data: Any? = null)

    fun handle(request: ControlProtocol.Request, output: ControlOutput): Result

    /** Shows the pairing code on the phone (notification). */
    fun showPairingCode(code: String, expiresInMs: Long)

    fun onPaired() {}
    fun onRevoked() {}
}

data class AuditEntry(
    val timeMs: Long,
    val action: String,
    val detail: String,
    val outcome: String,
    val exit: Int?,
    val durationMs: Long,
)

/**
 * Loopback-only TCP server for `droiddeskctl`.
 *
 * It binds 127.0.0.1 explicitly (never 0.0.0.0 / ::), refuses non-loopback
 * peers as a second line of defence, accepts only the actions declared in
 * [ControlProtocol.Action] and requires the paired token for everything but
 * ping/pairing.
 */
class ControlServer(
    private val port: Int,
    private val authority: TokenAuthority,
    private val handler: ControlHandler,
    private val audit: (AuditEntry) -> Unit = {},
    private val maxConcurrent: Int = 4,
    private val readTimeoutMs: Int = 10_000,
    private val identity: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** 127.0.0.1 as a literal so no resolver or IPv6 wildcard is ever involved. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    }

    @Volatile private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val slots = Semaphore(maxConcurrent)

    val localPort: Int get() = serverSocket?.localPort ?: -1
    val boundAddress: InetAddress? get() = serverSocket?.inetAddress
    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start() {
        if (running.get()) return
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, port), 16)
            check(socket.inetAddress.isLoopbackAddress && !socket.inetAddress.isAnyLocalAddress) {
                "Control server must bind loopback only, got ${socket.inetAddress}"
            }
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
        serverSocket = socket
        running.set(true)
        Thread({ acceptLoop(socket) }, "droiddesk-control-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                continue
            } catch (_: Throwable) {
                break
            }
            if (!client.inetAddress.isLoopbackAddress) {
                runCatching { client.close() }
                continue
            }
            if (!slots.tryAcquire()) {
                respondAndClose(client, ControlProtocol.errorFrame("busy", "Too many concurrent requests"))
                continue
            }
            Thread({
                try {
                    serve(client)
                } catch (_: Throwable) {
                    // Never let a client thread crash the Android app process.
                    runCatching { client.close() }
                } finally {
                    slots.release()
                }
            }, "droiddesk-control-client").apply { isDaemon = true; start() }
        }
    }

    private fun respondAndClose(client: Socket, frame: String) {
        runCatching {
            client.getOutputStream().write((frame + "\n").toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
        }
        runCatching { client.close() }
    }

    private fun serve(client: Socket) {
        val started = System.currentTimeMillis()
        client.use { socket ->
            socket.soTimeout = readTimeoutMs
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val closed = AtomicBoolean(false)
            val output = object : ControlOutput {
                override fun out(text: String) {
                    if (text.isEmpty()) return
                    send(writer, closed, ControlProtocol.outFrame(text))
                }
                override val cancelled: Boolean get() = closed.get()
            }

            val line = try {
                readRequestLine(socket.getInputStream())
            } catch (_: IOException) {
                null
            }
            if (line == null) {
                send(writer, closed, ControlProtocol.errorFrame("bad_request", "Empty, oversized or timed-out request"))
                return
            }
            val request = when (val parsed = ControlProtocol.parseRequest(line)) {
                is ControlProtocol.ParseResult.Rejected -> {
                    send(writer, closed, ControlProtocol.errorFrame(parsed.code, parsed.message))
                    record(started, "?", parsed.code, "rejected", null)
                    return
                }
                is ControlProtocol.ParseResult.Ok -> parsed.request
            }
            // The request is complete; long operations only write from here on.
            socket.soTimeout = 0

            if (request.action.auth == ControlProtocol.Auth.TOKEN) {
                val proof = authority.serverProof(request.nonce!!, request.action.wire)
                if (proof == null) {
                    send(writer, closed, ControlProtocol.errorFrame(
                        "not_paired", "DroidDesk is not paired with Termux; run: droiddeskctl pair"))
                    record(started, request.action.wire, "", "not_paired", null)
                    return
                }
                // Prove our identity first; only then does the client send its token.
                send(writer, closed, ControlProtocol.challengeFrame(proof))
                socket.soTimeout = readTimeoutMs
                val tokenLine = try {
                    readRequestLine(socket.getInputStream())
                } catch (_: IOException) {
                    null
                }
                if (!authority.verify(ControlProtocol.parseTokenLine(tokenLine))) {
                    send(writer, closed, ControlProtocol.errorFrame(
                        "unauthorized", "Invalid or revoked token; run: droiddeskctl pair"))
                    record(started, request.action.wire, "", "unauthorized", null)
                    return
                }
                socket.soTimeout = 0
            }

            val result = try {
                dispatch(request, output)
            } catch (error: Exception) {
                send(writer, closed, ControlProtocol.errorFrame("internal", error.message ?: error.javaClass.simpleName))
                record(started, request.action.wire, describe(request), "internal_error", null)
                return
            }
            send(writer, closed, ControlProtocol.resultFrame(result.ok, result.exit, result.data))
            record(started, request.action.wire, describe(request), if (result.ok) "ok" else "failed", result.exit)
        }
    }

    private fun dispatch(request: ControlProtocol.Request, output: ControlOutput): ControlHandler.Result =
        when (request.action) {
            ControlProtocol.Action.PING -> ControlHandler.Result(
                true,
                data = identity + mapOf("protocol" to ControlProtocol.VERSION, "paired" to authority.isPaired()),
            )
            ControlProtocol.Action.PAIR -> when (val start = authority.startPairing()) {
                is TokenAuthority.PairStart.Started -> {
                    handler.showPairingCode(start.code, start.expiresInMs)
                    ControlHandler.Result(true, data = mapOf("expiresInSec" to start.expiresInMs / 1000))
                }
                is TokenAuthority.PairStart.Refused -> ControlHandler.Result(
                    false, 1, mapOf("reason" to start.reason, "retryAfterSec" to start.retryAfterMs / 1000 + 1),
                )
            }
            ControlProtocol.Action.PAIR_CONFIRM -> when (val confirm = authority.confirmPairing(request.args[0])) {
                is TokenAuthority.PairConfirm.Paired -> {
                    handler.onPaired()
                    ControlHandler.Result(true, data = mapOf("token" to confirm.token))
                }
                is TokenAuthority.PairConfirm.Failed -> ControlHandler.Result(false, 1, mapOf("reason" to confirm.reason))
            }
            ControlProtocol.Action.REVOKE -> {
                authority.revoke()
                handler.onRevoked()
                ControlHandler.Result(true, data = mapOf("revoked" to true))
            }
            else -> handler.handle(request, output)
        }

    /** Writes one frame; never throws (a vanished client just marks the request cancelled). */
    private fun send(writer: BufferedWriter, closed: AtomicBoolean, frame: String): Boolean {
        if (closed.get()) return false
        synchronized(writer) {
            return try {
                writer.write(frame)
                writer.write("\n")
                writer.flush()
                true
            } catch (_: IOException) {
                closed.set(true)
                false
            }
        }
    }

    /** Reads one '\n'-terminated line, refusing anything over MAX_REQUEST_BYTES. */
    private fun readRequestLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            if (byte == '\n'.code) return buffer.toString(Charsets.UTF_8.name())
            buffer.write(byte)
            if (buffer.size() > ControlProtocol.MAX_REQUEST_BYTES) return null
        }
        return buffer.takeIf { it.size() > 0 }?.toString(Charsets.UTF_8.name())
    }

    /** Never logs tokens or pairing codes; exec commands are truncated. */
    private fun describe(request: ControlProtocol.Request): String = when (request.action) {
        ControlProtocol.Action.PAIR_CONFIRM, ControlProtocol.Action.PAIR, ControlProtocol.Action.REVOKE -> ""
        else -> request.args.joinToString(" ").let { if (it.length > 300) it.take(300) + "…" else it } +
            if (request.force) " [force]" else ""
    }

    private fun record(started: Long, action: String, detail: String, outcome: String, exit: Int?) {
        runCatching {
            audit(AuditEntry(started, action, detail, outcome, exit, System.currentTimeMillis() - started))
        }
    }
}
