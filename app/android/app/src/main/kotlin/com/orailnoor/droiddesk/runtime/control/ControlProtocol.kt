package com.orailnoor.droiddesk.runtime.control

/**
 * Wire protocol between `scripts/droiddeskctl` (Termux) and [ControlServer].
 *
 * One request per TCP connection on 127.0.0.1. The client sends one JSON line
 *   {"v":1,"action":"status","args":[...],"timeout":300,"force":false,"nonce":"<hex>"}
 * Actions that need the token then run a mutual check before the token is
 * ever sent, so a process squatting the port cannot harvest it:
 *   server -> {"type":"challenge","proof":HMAC-SHA256(SHA-256(token), "droiddesk-ctl-server|nonce|action")}
 *   client verifies the proof, then sends {"token":"<64 hex>"}
 * after which the server streams response lines:
 *   {"type":"out","data":"..."}                       streamed output
 *   {"type":"result","ok":true,"exit":0,"data":{...}} final frame
 *   {"type":"error","code":"unauthorized","message":"..."} final frame
 */
object ControlProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47820

    /** One port per package so the original app and the preview never collide. */
    fun portFor(packageName: String): Int = when (packageName) {
        "com.orailnoor.droiddesk" -> DEFAULT_PORT
        "com.orailnoor.droiddesk.preview" -> DEFAULT_PORT + 1
        else -> DEFAULT_PORT + 2
    }
    const val MAX_REQUEST_BYTES = 16 * 1024
    const val MAX_ARGS = 64
    const val MAX_ARG_LENGTH = 4096

    const val DEFAULT_TIMEOUT_SEC = 300
    const val MAX_TIMEOUT_SEC = 3600
    /** Package installs and repairs may download hundreds of MB. */
    const val MAX_ADMIN_TIMEOUT_SEC = 7200

    enum class Auth { NONE, TOKEN }

    /**
     * Every action the bridge understands. Anything else is rejected before
     * authentication is even considered, so the server is not a generic RPC.
     */
    enum class Action(
        val wire: String,
        val auth: Auth,
        val minArgs: Int = 0,
        val maxArgs: Int = 0,
        /** Modifies packages; serialized with the in-app package store. */
        val packageOperation: Boolean = false,
    ) {
        PING("ping", Auth.NONE),
        PAIR("pair", Auth.NONE),
        PAIR_CONFIRM("pair-confirm", Auth.NONE, minArgs = 1, maxArgs = 1),
        REVOKE("revoke", Auth.TOKEN),
        STATUS("status", Auth.TOKEN),
        DOCTOR("doctor", Auth.TOKEN),
        LOGS("logs", Auth.TOKEN, maxArgs = 2),
        LAUNCH("launch", Auth.TOKEN, minArgs = 1, maxArgs = 1),
        SETTINGS("settings", Auth.TOKEN, minArgs = 1, maxArgs = 1),
        EXEC("exec", Auth.TOKEN, minArgs = 1, maxArgs = 1),
        NODE("node", Auth.TOKEN, maxArgs = MAX_ARGS),
        NPM("npm", Auth.TOKEN, maxArgs = MAX_ARGS),
        NPX("npx", Auth.TOKEN, maxArgs = MAX_ARGS),
        INSTALL("install", Auth.TOKEN, minArgs = 1, maxArgs = 1, packageOperation = true),
        REPAIR_PACKAGES("repair-packages", Auth.TOKEN, packageOperation = true),
        SYNC_APPS("sync-apps", Auth.TOKEN),
        SYNC_STORAGE("sync-storage", Auth.TOKEN),
        STOP_DESKTOP("stop-desktop", Auth.TOKEN),
        SHELL_INFO("shell-info", Auth.TOKEN),
        ;

        companion object {
            private val byWire = entries.associateBy { it.wire }
            fun fromWire(name: String): Action? = byWire[name]
        }
    }

    /** Optional apps `install` accepts; each maps to LinuxRuntime.installOptionalApp ids. */
    val INSTALLABLE_APPS = setOf("nodejs", "code_oss", "firefox", "imagemagick")

    /** `settings <action>` values (mirrors AndroidAppBridge.launchSystemAction). */
    val SETTINGS_ACTIONS = linkedMapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "hotspot" to "android.settings.TETHER_SETTINGS",
        "battery" to "android.settings.BATTERY_SAVER_SETTINGS",
        "home_settings" to "android.settings.HOME_SETTINGS",
        "settings" to "android.settings.SETTINGS",
    )

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val TOKEN_FORMAT = Regex("[0-9a-f]{64}")
    private val NONCE_FORMAT = Regex("[0-9a-f]{32,128}")

    fun isValidPackageName(value: String): Boolean = value.length <= 255 && PACKAGE_NAME.matches(value)

    fun isWellFormedToken(value: String?): Boolean = value != null && TOKEN_FORMAT.matches(value)

    data class Request(
        val action: Action,
        val args: List<String>,
        val timeoutSec: Int,
        val force: Boolean,
        /** Client nonce for the server proof; required for token actions. */
        val nonce: String?,
    )

    sealed class ParseResult {
        data class Ok(val request: Request) : ParseResult()
        data class Rejected(val code: String, val message: String) : ParseResult()
    }

    fun parseRequest(line: String): ParseResult {
        if (line.length > MAX_REQUEST_BYTES) return reject("too_large", "Request too large")
        val root = try {
            MiniJson.parse(line)
        } catch (error: MiniJson.ParseException) {
            return reject("bad_request", "Malformed JSON: ${error.message}")
        }
        if (root !is Map<*, *>) return reject("bad_request", "Request must be a JSON object")

        val version = (root["v"] as? Number)?.toInt()
        if (version != VERSION) return reject("bad_version", "Unsupported protocol version $version")

        val actionName = root["action"] as? String
            ?: return reject("bad_request", "Missing action")
        val action = Action.fromWire(actionName)
            ?: return reject("unknown_action", "Action not allowed: $actionName")

        val rawArgs = root["args"] ?: emptyList<Any?>()
        if (rawArgs !is List<*>) return reject("bad_request", "args must be an array")
        if (rawArgs.any { it !is String }) return reject("bad_request", "args must be strings")
        val args = rawArgs.map { it as String }
        if (args.size < action.minArgs || args.size > action.maxArgs) {
            return reject("bad_args", "${action.wire} expects ${action.minArgs}..${action.maxArgs} arguments")
        }
        if (args.any { it.length > MAX_ARG_LENGTH || it.contains('\u0000') }) {
            return reject("bad_args", "Argument too long or contains NUL")
        }

        // The token never travels in the request line, only after the server proof.
        if (root.containsKey("token")) return reject("bad_request", "Send the token only after the challenge")
        val nonce = root["nonce"]
        if (nonce != null && (nonce !is String || !NONCE_FORMAT.matches(nonce))) {
            return reject("bad_request", "nonce must be 32-128 lowercase hex characters")
        }
        if (action.auth == Auth.TOKEN && nonce == null) {
            return reject("bad_request", "Missing nonce")
        }

        val maxTimeout = if (action.packageOperation) MAX_ADMIN_TIMEOUT_SEC else MAX_TIMEOUT_SEC
        val timeout = when (val raw = root["timeout"]) {
            null -> if (action.packageOperation) MAX_ADMIN_TIMEOUT_SEC else DEFAULT_TIMEOUT_SEC
            is Number -> raw.toInt()
            else -> return reject("bad_request", "timeout must be a number")
        }
        if (timeout < 1 || timeout > maxTimeout) {
            return reject("bad_args", "timeout must be between 1 and $maxTimeout seconds")
        }
        val force = root["force"] as? Boolean ?: false

        val semantic = validateArguments(action, args)
        if (semantic != null) return reject("bad_args", semantic)

        return ParseResult.Ok(Request(action, args, timeout, force, nonce as String?))
    }

    /** Parses the client's second line `{"token":"<64 hex>"}`; null if malformed. */
    fun parseTokenLine(line: String?): String? {
        if (line == null || line.length > 256) return null
        val root = runCatching { MiniJson.parse(line) }.getOrNull() as? Map<*, *> ?: return null
        return (root["token"] as? String)?.takeIf(::isWellFormedToken)
    }

    fun challengeFrame(proof: String): String =
        MiniJson.stringify(linkedMapOf("type" to "challenge", "proof" to proof))

    /** Action-specific argument validation; returns an error message or null. */
    private fun validateArguments(action: Action, args: List<String>): String? = when (action) {
        Action.PAIR_CONFIRM -> if (Regex("[0-9]{6}").matches(args[0])) null else "Pairing code must be 6 digits"
        Action.LAUNCH -> if (isValidPackageName(args[0])) null else "Invalid Android package name"
        Action.SETTINGS -> if (args[0] in SETTINGS_ACTIONS || args[0] == "samsung_home") null
            else "Unknown settings action; use one of ${(SETTINGS_ACTIONS.keys + "samsung_home").joinToString()}"
        Action.INSTALL -> if (args[0] in INSTALLABLE_APPS) null
            else "Installable apps: ${INSTALLABLE_APPS.joinToString()}"
        Action.LOGS -> args.firstOrNull { arg ->
            arg !in setOf("app", "audit") && arg.toIntOrNull()?.let { it in 1..5000 } != true
        }?.let { "logs accepts [app|audit] [1..5000]" }
        Action.EXEC -> if (args[0].isBlank()) "Empty command" else null
        else -> null
    }

    private fun reject(code: String, message: String) = ParseResult.Rejected(code, message)

    // ── Response frames ──

    fun outFrame(data: String): String = MiniJson.stringify(mapOf("type" to "out", "data" to data))

    fun resultFrame(ok: Boolean, exit: Int, data: Any? = null): String =
        MiniJson.stringify(linkedMapOf("type" to "result", "ok" to ok, "exit" to exit, "data" to data))

    fun errorFrame(code: String, message: String): String =
        MiniJson.stringify(linkedMapOf("type" to "error", "code" to code, "message" to message))

    /** POSIX single-quote escaping so `node`/`npm`/`npx` arguments are never re-interpreted by bash. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun commandLine(program: String, args: List<String>): String =
        (listOf(program) + args.map(::shellQuote)).joinToString(" ")
}
