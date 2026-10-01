package com.orailnoor.droiddesk.runtime.control

/**
 * Guard rails for `exec` (and node/npm/npx) commands.
 *
 * The caller is the authenticated device owner, so this is not a sandbox: it
 * blocks a short list of commands that would destroy the runtime or the app
 * data, and makes mass upgrades/removals require an explicit --force.
 */
class ExecPolicy(
    /** Absolute paths that must never be deleted recursively (PREFIX, HOME, files dir). */
    protectedPaths: Collection<String> = emptyList(),
) {
    sealed class Decision {
        object Allowed : Decision()
        data class NeedsForce(val reason: String) : Decision()
        data class Denied(val reason: String) : Decision()
    }

    private val protectedTargets: Set<String> = buildSet {
        addAll(listOf("/", "/*", "~", "~/", "~/*", "\$HOME", "\${HOME}", "\$PREFIX", "\${PREFIX}", "/data", "/sdcard", "/storage"))
        listOf("\$HOME", "\${HOME}", "\$PREFIX", "\${PREFIX}").forEach { add("$it/"); add("$it/*") }
        protectedPaths.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.forEach {
            add(it); add("$it/"); add("$it/*")
        }
    }

    private val denied = listOf(
        Regex("""(^|[\s;&|(])pm\s+(clear|uninstall|reset-permissions)\b""") to "pm clear/uninstall is not allowed",
        Regex("""(^|[\s;&|(])cmd\s+package\s+(clear|uninstall)\b""") to "package clear/uninstall is not allowed",
        Regex("""(^|[\s;&|(])mkfs(\.\w+)?\b""") to "mkfs is not allowed",
        Regex("""(^|[\s;&|(])dd\b[^;&|]*\bof=/dev/""") to "writing to block devices is not allowed",
        Regex("""(^|[\s;&|(])(reboot|recovery)\b""") to "reboot is not allowed",
    )

    private val needsForce = listOf(
        Regex("""(^|[\s;&|(])(apt|apt-get|pkg)\b[^;&|]*\s(upgrade|full-upgrade|dist-upgrade)\b""") to
            "mass upgrades require --force",
        Regex("""(^|[\s;&|(])(apt|apt-get)\b[^;&|]*\s(remove|purge|autoremove)\b""") to
            "removing packages requires --force",
        Regex("""(^|[\s;&|(])pkg\s+(uninstall|remove|autoclean)\b""") to "removing packages requires --force",
        Regex("""(^|[\s;&|(])dpkg\b[^;&|]*\s(-r|-P|--remove|--purge)\b""") to "removing packages requires --force",
    )

    fun check(command: String, force: Boolean): Decision {
        if (command.isBlank()) return Decision.Denied("empty command")
        if (command.contains('\u0000')) return Decision.Denied("NUL byte in command")
        denied.firstOrNull { (pattern, _) -> pattern.containsMatchIn(command) }?.let {
            return Decision.Denied(it.second)
        }
        recursiveDeleteOfProtected(command)?.let { return Decision.Denied(it) }
        if (!force) {
            needsForce.firstOrNull { (pattern, _) -> pattern.containsMatchIn(command) }?.let {
                return Decision.NeedsForce(it.second)
            }
        }
        return Decision.Allowed
    }

    /** Looks at each simple command for `rm -r…`/`rm --recursive` aimed at a protected root. */
    private fun recursiveDeleteOfProtected(command: String): String? {
        for (segment in command.split(Regex("""&&|\|\||[;|&\n]"""))) {
            val words = segment.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .map { it.trim('"', '\'') }
            val rmIndex = words.indexOfFirst { it == "rm" || it.endsWith("/rm") }
            if (rmIndex < 0) continue
            val rest = words.drop(rmIndex + 1)
            val recursive = rest.any { word ->
                word == "--recursive" || (word.startsWith("-") && !word.startsWith("--") &&
                    (word.contains('r') || word.contains('R')))
            }
            if (!recursive) continue
            val target = rest.filterNot { it.startsWith("-") }.firstOrNull { it in protectedTargets }
            if (target != null) return "recursive delete of $target is not allowed"
        }
        return null
    }
}
