package com.orailnoor.droiddesk.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.orailnoor.droiddesk.R
import com.orailnoor.droiddesk.runtime.control.AuditEntry
import com.orailnoor.droiddesk.runtime.control.ControlHandler
import com.orailnoor.droiddesk.runtime.control.ControlLifecycle
import com.orailnoor.droiddesk.runtime.control.ControlOutput
import com.orailnoor.droiddesk.runtime.control.ControlProtocol
import com.orailnoor.droiddesk.runtime.control.ControlProtocol.Action
import com.orailnoor.droiddesk.runtime.control.ControlServer
import com.orailnoor.droiddesk.runtime.control.ExecPolicy
import com.orailnoor.droiddesk.runtime.control.MiniJson
import com.orailnoor.droiddesk.runtime.control.ProcessRunner
import com.orailnoor.droiddesk.runtime.control.SecretStorage
import com.orailnoor.droiddesk.runtime.control.TokenAuthority
import com.orailnoor.droiddesk.view.DesktopActivity
import com.orailnoor.droiddesk.x11.X11ServerService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lets the user's normal Termux app administer DroidDesk's private runtime
 * through `scripts/droiddeskctl`: Termux stays the control centre, DroidDesk
 * the visual PC desktop. See CONTROL_BRIDGE.md.
 *
 * Transport and security live in [ControlServer]/[TokenAuthority] (loopback
 * TCP, paired token, fixed action list); this object is the Android glue.
 * MainActivity and DroidDeskService each hold the bridge while alive.
 */
object ControlBridge {
    private const val TAG = "ControlBridge"
    private const val PREFS = "control_bridge"
    private const val CHANNEL_ID = "droiddesk_control"
    private const val PAIR_NOTIFICATION_ID = 1101
    private const val AUDIT_MAX_BYTES = 512 * 1024L

    @Volatile private var appContext: Context? = null
    @Volatile private var server: ControlServer? = null
    @Volatile private var authority: TokenAuthority? = null

    private val lifecycle = ControlLifecycle(start = ::startServer, stop = ::stopServer)

    /** Socket work stays off the main thread; one thread keeps acquire/release ordered. */
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "control-bridge-lifecycle").apply { isDaemon = true }
    }

    fun acquire(context: Context, owner: String) {
        appContext = context.applicationContext
        lifecycleExecutor.execute {
            if (!lifecycle.acquire(owner)) Log.w(TAG, "Control bridge is not running (owner $owner)")
        }
    }

    fun release(owner: String) {
        lifecycleExecutor.execute { lifecycle.release(owner) }
    }

    fun isRunning(): Boolean = lifecycle.isRunning()

    private fun startServer(): Boolean {
        val context = appContext ?: return false
        val tokens = authority ?: TokenAuthority(PrefsSecretStorage(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))
            .also { authority = it }
        val port = ControlProtocol.portFor(context.packageName)
        return try {
            ControlServer(
                port = port,
                authority = tokens,
                handler = RequestHandler(context),
                audit = { entry -> appendAudit(context, entry) },
                identity = mapOf("package" to context.packageName, "name" to "DroidDesk"),
            ).also {
                it.start()
                server = it
            }
            Log.i(TAG, "Termux control bridge listening on 127.0.0.1:$port")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not start the control bridge on 127.0.0.1:$port", error)
            false
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        Log.i(TAG, "Termux control bridge stopped")
    }

    private class PrefsSecretStorage(private val prefs: SharedPreferences) : SecretStorage {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).commit() }
        override fun remove(key: String) { prefs.edit().remove(key).commit() }
    }

    // ── Audit log ──

    private fun auditFile(context: Context) = File(context.filesDir, "control/audit.log")

    @Synchronized
    private fun appendAudit(context: Context, entry: AuditEntry) {
        val file = auditFile(context)
        file.parentFile?.mkdirs()
        if (file.length() > AUDIT_MAX_BYTES) {
            file.renameTo(File(file.parentFile, "audit.log.1"))
        }
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(entry.timeMs))
        file.appendText(
            MiniJson.stringify(
                linkedMapOf(
                    "time" to time,
                    "action" to entry.action,
                    "detail" to entry.detail,
                    "outcome" to entry.outcome,
                    "exit" to entry.exit,
                    "ms" to entry.durationMs,
                ),
            ) + "\n",
        )
    }

    private fun auditTail(context: Context, lines: Int): String {
        val file = auditFile(context)
        if (!file.isFile) return ""
        return file.readLines().takeLast(lines).joinToString("\n", postfix = "\n")
    }

    // ── Request handling ──

    private class RequestHandler(private val context: Context) : ControlHandler {
        private val main = android.os.Handler(Looper.getMainLooper())

        private fun runtime() = LinuxRuntime(context)

        private fun policy() = ExecPolicy(
            listOf(
                context.filesDir.absolutePath,
                File(context.filesDir, "usr").absolutePath,
                File(context.filesDir, "home").absolutePath,
                File(context.filesDir, "rootfs").absolutePath,
            ),
        )

        override fun showPairingCode(code: String, expiresInMs: Long) {
            val seconds = (expiresInMs / 1000).toInt()
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.control_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = context.getString(R.string.control_channel_description) },
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(context.getString(R.string.control_pair_title))
                .setContentText(context.getString(R.string.control_pair_text, code, seconds))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.control_pair_text, code, seconds)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setTimeoutAfter(expiresInMs)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(PAIR_NOTIFICATION_ID, notification) }
                .onFailure { Log.w(TAG, "Could not post the pairing notification", it) }
            toast(context.getString(R.string.control_pair_toast, code))
        }

        override fun onPaired() {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(PAIR_NOTIFICATION_ID) }
            toast(context.getString(R.string.control_paired))
        }

        override fun onRevoked() = toast(context.getString(R.string.control_revoked))

        private fun toast(text: String) {
            main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }

        override fun handle(request: ControlProtocol.Request, output: ControlOutput): ControlHandler.Result =
            when (request.action) {
                Action.STATUS -> ControlHandler.Result(true, data = runtime().controlStatus() + bridgeStatus())
                Action.DOCTOR -> {
                    val checks = doctorChecks()
                    ControlHandler.Result(checks.all { it["ok"] == true }, data = mapOf("checks" to checks))
                }
                Action.LOGS -> logs(request.args, output)
                Action.LAUNCH -> {
                    val component = AndroidAppBridge.launcherComponent(context, request.args[0])
                    if (component == null) {
                        ControlHandler.Result(false, data = mapOf("reason" to "${request.args[0]} is not an installed launcher app"))
                    } else {
                        ControlHandler.Result(true, data = mapOf("component" to component))
                    }
                }
                Action.SETTINGS -> settings(request.args[0])
                Action.EXEC -> runShell(request.args[0], request, output)
                Action.NODE -> runShell(ControlProtocol.commandLine("node", request.args), request, output)
                Action.NPM -> runShell(ControlProtocol.commandLine("npm", request.args), request, output)
                Action.NPX -> runShell(ControlProtocol.commandLine("npx", request.args), request, output)
                Action.INSTALL -> packageOperation { runtime().installForControl(request.args[0], output::out) }
                Action.REPAIR_PACKAGES -> packageOperation { runtime().repairPackages(output::out) }
                Action.SYNC_APPS -> syncApps(output)
                Action.SYNC_STORAGE -> syncStorage()
                Action.STOP_DESKTOP -> stopDesktop()
                Action.SHELL_INFO -> ControlHandler.Result(true, data = runtime().shellInfo() + bridgeStatus())
                // Handled by ControlServer itself.
                Action.PING, Action.PAIR, Action.PAIR_CONFIRM, Action.REVOKE ->
                    ControlHandler.Result(false, data = mapOf("reason" to "unexpected"))
            }

        private fun bridgeStatus(): Map<String, Any?> = mapOf(
            "controlBridge" to linkedMapOf(
                "port" to server?.localPort,
                "address" to server?.boundAddress?.hostAddress,
                "pairedAt" to authority?.pairedAt(),
                "auditLog" to auditFile(context).absolutePath,
            ),
        )

        private fun doctorChecks(): List<Map<String, Any?>> {
            val checks = runtime().doctor().toMutableList()
            val bound = server?.boundAddress
            checks += linkedMapOf(
                "check" to "control bridge on loopback",
                "ok" to (bound?.isLoopbackAddress == true),
                "detail" to "${bound?.hostAddress}:${server?.localPort}",
            )
            checks += linkedMapOf(
                "check" to "AndroidAppBridge launcher socket",
                "ok" to AndroidAppBridge.isRunning(),
                "detail" to if (AndroidAppBridge.isRunning()) "running (peer uid checked)" else "starts with the desktop service",
            )
            return checks
        }

        private fun settings(action: String): ControlHandler.Result {
            if (action == "samsung_home") {
                val component = AndroidAppBridge.stockLauncherComponent(context)
                    ?: return ControlHandler.Result(false, data = mapOf("reason" to "No Samsung/AOSP launcher installed"))
                return ControlHandler.Result(true, data = mapOf("component" to component))
            }
            val intentAction = ControlProtocol.SETTINGS_ACTIONS[action]
                ?: return ControlHandler.Result(false, data = mapOf("reason" to "Unknown settings action"))
            return ControlHandler.Result(true, data = mapOf("intentAction" to intentAction))
        }

        private fun runShell(command: String, request: ControlProtocol.Request, output: ControlOutput): ControlHandler.Result {
            when (val decision = policy().check(command, request.force)) {
                is ExecPolicy.Decision.Denied ->
                    return ControlHandler.Result(false, 126, mapOf("reason" to decision.reason))
                is ExecPolicy.Decision.NeedsForce ->
                    return ControlHandler.Result(false, 2, mapOf("reason" to decision.reason, "hint" to "--force"))
                ExecPolicy.Decision.Allowed -> Unit
            }
            val outcome = runtime().runControlCommand(
                command = command,
                timeoutMs = request.timeoutSec * 1000L,
                onOutput = output::out,
                isCancelled = { output.cancelled },
            )
            return ControlHandler.Result(
                ok = outcome.exitCode == 0,
                exit = outcome.exitCode,
                data = linkedMapOf(
                    "exit" to outcome.exitCode,
                    "timedOut" to outcome.timedOut,
                    "cancelled" to outcome.cancelled,
                    "durationMs" to outcome.durationMs,
                ),
            )
        }

        private fun packageOperation(block: () -> Map<String, Any?>): ControlHandler.Result {
            val result = LinuxRuntime(context).withPackageLock(block)
                ?: return ControlHandler.Result(
                    false, 75, mapOf("reason" to "Another package operation is running in DroidDesk; try again later"),
                )
            return ControlHandler.Result(result["ok"] == true, data = result)
        }

        private fun logs(args: List<String>, output: ControlOutput): ControlHandler.Result {
            val kind = args.firstOrNull { it == "app" || it == "audit" } ?: "app"
            val lines = args.firstNotNullOfOrNull(String::toIntOrNull) ?: 200
            if (kind == "audit") {
                output.out(auditTail(context, lines))
                return ControlHandler.Result(true)
            }
            // An app can read only its own log buffer entries.
            val outcome = ProcessRunner().run(
                argv = listOf("/system/bin/logcat", "-d", "-t", lines.toString(), "-v", "time"),
                timeoutMs = 20_000,
                onOutput = output::out,
            )
            return ControlHandler.Result(outcome.exitCode == 0, outcome.exitCode)
        }

        private fun syncApps(output: ControlOutput): ControlHandler.Result {
            val chroot = ChrootRuntime(context)
            if (chroot.hasRoot()) {
                val rootfs = File(context.filesDir, "rootfs")
                val homeDir = File(rootfs, "root")
                AndroidAppBridge.syncLaunchers(context, homeDir, File(rootfs, "usr/bin/python3"), sessionRoot = rootfs)
                if (chroot.isRunning()) {
                    chroot.executeCommand(
                        "export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session; " +
                            AndroidAppBridge.xfceDockCommand(context, homeDir) +
                            " DISPLAY=:0 xfce4-panel -r >/dev/null 2>&1 || true",
                    )
                }
                return ControlHandler.Result(true, data = mapOf("mode" to "chroot", "apps" to AndroidAppBridge.listApps(context).size))
            }
            val runtime = runtime()
            val homeDir = File(context.filesDir, "home")
            AndroidAppBridge.syncLaunchers(context, homeDir, File(context.filesDir, "usr/bin/python3"))
            val running = runtime.isRunning()
            if (running) {
                runtime.runControlCommand(
                    AndroidAppBridge.xfceDockCommand(context, homeDir) +
                        " DISPLAY=:0 xfce4-panel -r >/dev/null 2>&1 || true",
                    60_000,
                    output::out,
                )
            }
            return ControlHandler.Result(
                true,
                data = mapOf("mode" to "termux", "apps" to AndroidAppBridge.listApps(context).size, "panelReloaded" to running),
            )
        }

        private fun syncStorage(): ControlHandler.Result {
            val home = File(context.filesDir, "home")
            SdcardBridge.setup(context, home)
            val links = listOf("DCIM", "Pictures", "Downloads", "Documents", "Music", "Movies", "WhatsApp")
                .associateWith { name ->
                    val link = File(home, name)
                    when {
                        !link.exists() -> "missing"
                        link.canRead() -> "ok -> ${runCatching { link.canonicalPath }.getOrDefault("?")}"
                        else -> "no access (grant DroidDesk 'All files access')"
                    }
                }
            val shared = File("/storage/emulated/0")
            return ControlHandler.Result(
                shared.canRead(),
                data = mapOf("sharedStorageReadable" to shared.canRead(), "links" to links),
            )
        }

        private fun stopDesktop(): ControlHandler.Result {
            DesktopActivity.finishActive()
            val chroot = ChrootRuntime(context)
            if (chroot.hasRoot() || chroot.isRunning()) chroot.stopSession()
            LinuxRuntime(context).stopSession()
            context.stopService(Intent(context, X11ServerService::class.java))
            // Same as the in-app "stop Linux": the foreground service ends too.
            // MainActivity (HOME) keeps holding the bridge when it is alive.
            context.stopService(Intent(context, com.orailnoor.droiddesk.service.DroidDeskService::class.java))
            return ControlHandler.Result(true, data = mapOf("sessionRunning" to LinuxRuntime(context).isRunning()))
        }
    }
}
