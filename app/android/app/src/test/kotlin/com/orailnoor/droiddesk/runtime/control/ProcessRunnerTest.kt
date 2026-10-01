package com.orailnoor.droiddesk.runtime.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ProcessRunnerTest {
    private val runner = ProcessRunner()

    private fun sh(script: String, timeoutMs: Long = 10_000, onOutput: (String) -> Unit = {}, cancelled: () -> Boolean = { false }) =
        runner.run(listOf("sh", "-c", script), timeoutMs = timeoutMs, onOutput = onOutput, isCancelled = cancelled)

    @Test fun returnsTheRealExitCode() {
        assertEquals(0, sh("true").exitCode)
        assertEquals(3, sh("exit 3").exitCode)
        assertEquals(127, sh("definitely-not-a-command-xyz 2>/dev/null").exitCode)
    }

    @Test fun streamsMergedOutput() {
        val chunks = StringBuilder()
        val outcome = sh("echo out; echo err >&2; printf 'ñ'", onOutput = { chunks.append(it) })
        assertEquals(0, outcome.exitCode)
        assertTrue(chunks.contains("out"))
        assertTrue(chunks.contains("err"))
        assertTrue(chunks.endsWith("ñ"))
        assertEquals(chunks.toString(), outcome.tail)
    }

    @Test fun timesOutAndKills() {
        val started = System.currentTimeMillis()
        val outcome = sh("sleep 30", timeoutMs = 300)
        assertTrue(outcome.timedOut)
        assertEquals(ProcessRunner.EXIT_TIMEOUT, outcome.exitCode)
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }

    @Test fun stopsWhenTheClientGoesAway() {
        val gone = AtomicBoolean(false)
        val outcome = sh("echo start; sleep 30", onOutput = { gone.set(true) }, cancelled = { gone.get() })
        assertTrue(outcome.cancelled)
        assertFalse(outcome.timedOut)
        assertEquals(ProcessRunner.EXIT_CANCELLED, outcome.exitCode)
    }

    @Test fun aFailingSinkNeverBlocksTheChild() {
        val outcome = sh("i=0; while [ \$i -lt 2000 ]; do echo line \$i; i=\$((i+1)); done; exit 4",
            onOutput = { throw java.io.IOException("client gone") })
        // The sink failure counts as a cancelled request, never as a hang.
        assertTrue(outcome.exitCode == 4 || outcome.cancelled)
    }

    @Test fun reportsStartFailures() {
        val outcome = runner.run(listOf("/nonexistent/binary"), timeoutMs = 1_000)
        assertEquals(ProcessRunner.EXIT_START_FAILED, outcome.exitCode)
    }
}
