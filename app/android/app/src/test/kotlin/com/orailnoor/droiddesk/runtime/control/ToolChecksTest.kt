package com.orailnoor.droiddesk.runtime.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Node/npm are "installed" only when they actually run and print a version. */
class ToolChecksTest {
    @Test fun workingNodeAndNpm() {
        assertEquals(ToolChecks.Verdict(true, "v26.4.0"), ToolChecks.evaluate("node", 0, "v26.4.0\n"))
        assertEquals(ToolChecks.Verdict(true, "11.20.0"), ToolChecks.evaluate("npm", 0, "11.20.0\n"))
        // npm may print update notices before the version.
        assertEquals("11.20.0", ToolChecks.evaluate("npm", 0, "npm notice New version\n11.20.0\n").version)
    }

    @Test fun brokenToolsAreNotOk() {
        // Termux shebang still in npm: the kernel cannot find the interpreter.
        val shebang = ToolChecks.evaluate("npm", 126,
            "bash: /data/user/0/x/files/usr/bin/npm: /data/data/com.termux/files/usr/bin/env: bad interpreter")
        assertFalse(shebang.ok)
        assertNull(shebang.version)
        assertFalse(ToolChecks.evaluate("node", 127, "node: command not found").ok)
        assertFalse(ToolChecks.evaluate("node", 0, "").ok)
        assertFalse(ToolChecks.evaluate("node", 0, "CANNOT LINK EXECUTABLE \"node\": library \"libcares.so\" not found").ok)
        assertFalse(ToolChecks.evaluate("npm", 1, "11.20.0").ok)
        assertFalse(ToolChecks.evaluate("node", 124, "").ok)
    }

    @Test fun parsesDpkgQueryOutput() {
        val output = "nodejs\tinstall ok installed\t26.4.0\nnpm\tinstall ok unpacked\t11.20.0\n"
        val states = ToolChecks.parseDpkgStates(output, listOf("nodejs", "npm", "c-ares"))
        assertEquals("install ok installed 26.4.0", states["nodejs"])
        assertEquals("install ok unpacked 11.20.0", states["npm"])
        assertEquals("not-installed", states["c-ares"])
        assertTrue(ToolChecks.isInstalledOk(states["nodejs"]))
        assertFalse(ToolChecks.isInstalledOk(states["npm"]))
        assertFalse(ToolChecks.isInstalledOk(states["c-ares"]))
        assertFalse(ToolChecks.isInstalledOk(null))
    }

    @Test(expected = IllegalArgumentException::class) fun unknownToolIsRejected() {
        ToolChecks.spec("bash")
    }
}
