package com.orailnoor.droiddesk.runtime.control

import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one non-interactive process with a hard timeout, streaming its merged
 * stdout/stderr and returning the real exit code.
 *
 * Unlike LinuxRuntime.executeCommand it keeps no shared state: it never
 * becomes the "active command" that the in-app terminal writes input into.
 */
class ProcessRunner(
    /** Kills the process and its descendants; Android passes a /proc-based tree kill. */
    private val killTree: (Process) -> Unit = { it.destroyForcibly() },
) {
    companion object {
        /** Same convention as coreutils `timeout`. */
        const val EXIT_TIMEOUT = 124
        const val EXIT_CANCELLED = 130
        const val EXIT_START_FAILED = 127
        const val MAX_FORWARDED_CHARS = 64L * 1024 * 1024
    }

    data class Outcome(
        val exitCode: Int,
        val timedOut: Boolean,
        val cancelled: Boolean,
        val durationMs: Long,
        val outputChars: Long,
        val tail: String,
    )

    fun run(
        argv: List<String>,
        environment: Map<String, String>? = null,
        directory: File? = null,
        timeoutMs: Long,
        onOutput: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        val started = System.nanoTime()
        val process = try {
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .also { builder ->
                    directory?.let(builder::directory)
                    if (environment != null) {
                        builder.environment().clear()
                        builder.environment().putAll(environment)
                    }
                }
                .start()
        } catch (error: Exception) {
            val message = "Could not start ${argv.firstOrNull()}: ${error.message}\n"
            runCatching { onOutput(message) }
            return Outcome(EXIT_START_FAILED, false, false, elapsed(started), 0, message)
        }
        runCatching { process.outputStream.close() }

        val tail = TailBuffer(8 * 1024)
        val forwarded = AtomicLong(0)
        val sinkFailed = AtomicBoolean(false)
        val reader = Thread({
            runCatching {
                InputStreamReader(process.inputStream, Charsets.UTF_8).use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        val chunk = String(buffer, 0, count)
                        tail.append(chunk)
                        if (!sinkFailed.get() && forwarded.get() < MAX_FORWARDED_CHARS) {
                            forwarded.addAndGet(count.toLong())
                            try {
                                onOutput(chunk)
                            } catch (_: Exception) {
                                // The client went away; keep draining so the child never blocks.
                                sinkFailed.set(true)
                            }
                        }
                    }
                }
            }
        }, "control-process-output").apply { isDaemon = true; start() }

        val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var timedOut = false
        var cancelled = false
        while (true) {
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) break
            if (System.nanoTime() >= deadline) { timedOut = true; break }
            if (sinkFailed.get() || isCancelled()) { cancelled = true; break }
        }
        if (timedOut || cancelled) {
            runCatching { killTree(process) }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(2_000)
        val exit = when {
            timedOut -> EXIT_TIMEOUT
            cancelled -> EXIT_CANCELLED
            else -> process.exitValue()
        }
        return Outcome(exit, timedOut, cancelled, elapsed(started), forwarded.get(), tail.toString())
    }

    private fun elapsed(startedNanos: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    /** Keeps the last [capacity] characters of output for status parsing and audit. */
    class TailBuffer(private val capacity: Int) {
        private val builder = StringBuilder()
        @Synchronized fun append(text: String) {
            builder.append(text)
            if (builder.length > capacity) builder.delete(0, builder.length - capacity)
        }
        @Synchronized override fun toString(): String = builder.toString()
    }
}
