package com.orailnoor.droiddesk.runtime.control

/**
 * How the bridge decides that Node/npm really work: the command must exit 0
 * and print a version. A binary that merely exists (e.g. npm whose #! still
 * points at Termux's prefix) is reported as broken, not installed.
 */
object ToolChecks {
    data class Spec(val command: String, val version: Regex, val timeoutMs: Long)
    data class Verdict(val ok: Boolean, val version: String?)

    private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+")

    fun spec(tool: String): Spec = when (tool) {
        "node" -> Spec("node -v", Regex("^v\\d+\\.\\d+\\.\\d+"), 30_000L)
        "npm" -> Spec("npm -v", SEMVER, 90_000L)
        "npx" -> Spec("npx -v", SEMVER, 90_000L)
        else -> throw IllegalArgumentException("Unknown tool $tool")
    }

    fun evaluate(tool: String, exitCode: Int, output: String): Verdict {
        val lastLine = output.trim().lines().lastOrNull()?.trim().orEmpty()
        val ok = exitCode == 0 && spec(tool).version.containsMatchIn(lastLine)
        return Verdict(ok, lastLine.takeIf { ok })
    }

    /** Parses `dpkg-query -W -f='${Package}\t${Status}\t${Version}\n'` output. */
    fun parseDpkgStates(output: String, packages: List<String>): Map<String, String> {
        val states = LinkedHashMap<String, String>()
        packages.forEach { states[it] = "not-installed" }
        output.lines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3 && parts[0] in states) {
                states[parts[0]] = "${parts[1]} ${parts[2]}".trim()
            }
        }
        return states
    }

    fun isInstalledOk(state: String?): Boolean = state?.startsWith("install ok installed") == true
}
