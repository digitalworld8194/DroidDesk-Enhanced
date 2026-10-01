package com.orailnoor.droiddesk.runtime.control

import com.orailnoor.droiddesk.runtime.control.ControlProtocol.Action
import com.orailnoor.droiddesk.runtime.control.ControlProtocol.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ControlProtocolTest {
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun request(vararg fields: Pair<String, Any?>): String =
        MiniJson.stringify(linkedMapOf<String, Any?>("v" to 1, "nonce" to nonce).apply { putAll(fields) })

    private fun ok(line: String): ControlProtocol.Request = when (val result = ControlProtocol.parseRequest(line)) {
        is ParseResult.Ok -> result.request
        is ParseResult.Rejected -> { fail("rejected: ${result.code} ${result.message}"); throw AssertionError() }
    }

    private fun rejected(line: String): String = when (val result = ControlProtocol.parseRequest(line)) {
        is ParseResult.Ok -> { fail("unexpectedly accepted: $line"); "" }
        is ParseResult.Rejected -> result.code
    }

    @Test fun parsesAllowedActionWithDefaults() {
        val parsed = ok(request("action" to "status"))
        assertEquals(Action.STATUS, parsed.action)
        assertEquals(emptyList<String>(), parsed.args)
        assertEquals(ControlProtocol.DEFAULT_TIMEOUT_SEC, parsed.timeoutSec)
        assertFalse(parsed.force)
        assertEquals(nonce, parsed.nonce)
    }

    @Test fun everyRequiredCommandIsAnAllowedAction() {
        // Commands droiddeskctl sends to DroidDesk (open/start-desktop use `am` locally).
        listOf(
            "ping", "pair", "revoke", "status", "doctor", "logs", "launch", "settings", "exec",
            "node", "npm", "npx", "install", "repair-packages", "sync-apps", "sync-storage",
            "stop-desktop", "shell-info",
        ).forEach { name -> assertTrue(name, Action.fromWire(name) != null) }
    }

    @Test fun rejectsUnknownActions() {
        listOf("shell", "reset", "resetInstall", "uninstall", "pm", "upgrade", "reinstall-runtime", "")
            .forEach { assertEquals(it, "unknown_action", rejected(request("action" to it))) }
    }

    @Test fun onlyPingAndPairingSkipTheToken() {
        val open = Action.entries.filter { it.auth == ControlProtocol.Auth.NONE }.map { it.wire }.toSet()
        assertEquals(setOf("ping", "pair", "pair-confirm"), open)
    }

    @Test fun tokenActionsNeedANonceAndNeverCarryTheToken() {
        assertEquals("bad_request", rejected(MiniJson.stringify(mapOf("v" to 1, "action" to "status"))))
        assertEquals("bad_request", rejected(request("action" to "status", "token" to "a".repeat(64))))
        assertEquals("bad_request", rejected(request("action" to "status", "nonce" to "XYZ")))
        // ping needs no nonce.
        ok(MiniJson.stringify(mapOf("v" to 1, "action" to "ping")))
    }

    @Test fun rejectsMalformedInput() {
        assertEquals("bad_request", rejected("not json"))
        assertEquals("bad_request", rejected("[1,2]"))
        assertEquals("bad_version", rejected(request("action" to "status", "v" to 2)))
        assertEquals("bad_request", rejected(request("action" to "exec", "args" to "ls")))
        assertEquals("bad_request", rejected(request("action" to "exec", "args" to listOf(1))))
        assertEquals("too_large", rejected("x".repeat(ControlProtocol.MAX_REQUEST_BYTES + 1)))
    }

    @Test fun validatesArgumentCountsAndValues() {
        assertEquals("bad_args", rejected(request("action" to "launch")))
        assertEquals("bad_args", rejected(request("action" to "launch", "args" to listOf("not a package"))))
        assertEquals("bad_args", rejected(request("action" to "launch", "args" to listOf("com.a.b; rm -rf /"))))
        ok(request("action" to "launch", "args" to listOf("com.android.chrome")))
        assertEquals("bad_args", rejected(request("action" to "settings", "args" to listOf("factory_reset"))))
        ok(request("action" to "settings", "args" to listOf("wifi")))
        ok(request("action" to "settings", "args" to listOf("samsung_home")))
        assertEquals("bad_args", rejected(request("action" to "install", "args" to listOf("everything"))))
        ok(request("action" to "install", "args" to listOf("nodejs")))
        ok(request("action" to "install", "args" to listOf("code_oss")))
        assertEquals("bad_args", rejected(request("action" to "exec", "args" to listOf("  "))))
        assertEquals("bad_args", rejected(request("action" to "exec", "args" to listOf("a\u0000b"))))
        assertEquals("bad_args", rejected(request("action" to "logs", "args" to listOf("kernel"))))
        ok(request("action" to "logs", "args" to listOf("audit", "50")))
        assertEquals("bad_args", rejected(request("action" to "pair-confirm", "args" to listOf("12ab56"))))
        ok(MiniJson.stringify(mapOf("v" to 1, "action" to "pair-confirm", "args" to listOf("123456"))))
    }

    @Test fun boundsTimeouts() {
        assertEquals("bad_args", rejected(request("action" to "exec", "args" to listOf("ls"), "timeout" to 0)))
        assertEquals("bad_args", rejected(request("action" to "exec", "args" to listOf("ls"), "timeout" to 3601)))
        assertEquals(10, ok(request("action" to "exec", "args" to listOf("ls"), "timeout" to 10)).timeoutSec)
        // Package operations default to, and may use, the longer admin limit.
        assertEquals(
            ControlProtocol.MAX_ADMIN_TIMEOUT_SEC,
            ok(request("action" to "repair-packages")).timeoutSec,
        )
    }

    @Test fun tokenLineParsing() {
        val token = "ab".repeat(32)
        assertEquals(token, ControlProtocol.parseTokenLine("{\"token\":\"$token\"}"))
        assertNull(ControlProtocol.parseTokenLine("{\"token\":\"short\"}"))
        assertNull(ControlProtocol.parseTokenLine("{\"token\":\"${"AB".repeat(32)}\"}"))
        assertNull(ControlProtocol.parseTokenLine("garbage"))
        assertNull(ControlProtocol.parseTokenLine(null))
    }

    @Test fun shellQuotingKeepsArgumentsLiteral() {
        assertEquals("node '-e' 'console.log(1)'", ControlProtocol.commandLine("node", listOf("-e", "console.log(1)")))
        assertEquals("npm 'it'\\''s'", ControlProtocol.commandLine("npm", listOf("it's")))
        assertEquals("npx '\$(reboot)' '; rm -rf /'", ControlProtocol.commandLine("npx", listOf("\$(reboot)", "; rm -rf /")))
    }

    @Test fun portsArePerPackage() {
        assertEquals(47820, ControlProtocol.portFor("com.orailnoor.droiddesk"))
        assertEquals(47821, ControlProtocol.portFor("com.orailnoor.droiddesk.preview"))
        assertTrue(ControlProtocol.portFor("other.pkg") !in setOf(47820, 47821))
    }

    @Test fun framesAreSingleLineJson() {
        val frame = ControlProtocol.outFrame("line1\nline2\t\"q\"")
        assertFalse(frame.contains('\n'))
        assertEquals(mapOf("type" to "out", "data" to "line1\nline2\t\"q\""), MiniJson.parse(frame))
        val result = MiniJson.parse(ControlProtocol.resultFrame(false, 3, mapOf("a" to listOf(1L, true, null)))) as Map<*, *>
        assertEquals(3L, result["exit"])
        assertEquals(false, result["ok"])
    }
}
