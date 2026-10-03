package com.orailnoor.droiddesk.runtime

import android.util.Log
import java.io.File

/**
 * Optional Linux container backend: PRoot + proot-distro + Debian.
 *
 * Completely separate from the native Termux desktop: it owns its own shell
 * process (never [LinuxRuntime]'s active command), its output goes to its own
 * terminal, and nothing here starts, stops or inspects XFCE, X11 or D-Bus.
 * A PRoot failure therefore stays inside this backend.
 */
class ProotBackend(
    private val prefixDir: File,
    private val tmpDir: File,
    private val environment: () -> Map<String, String>,
    private val relocateElf: (File) -> Unit = {},
    private val procDir: File = File("/proc"),
    private val nativeLibraryDir: File? = null,
) {
    companion object {
        private const val TAG = "ProotBackend"

        /** The open container shell, application-wide like the desktop session. */
        @Volatile private var containerProcess: Process? = null

        /** PIDs whose parent chain leads to [root] (not including [root]). */
        fun descendants(root: Int, procDir: File): List<Int> {
            val parents = (procDir.listFiles() ?: emptyArray()).mapNotNull { entry ->
                val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
                val ppid = runCatching {
                    File(entry, "stat").readText().substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
                }.getOrNull() ?: return@mapNotNull null
                pid to ppid
            }
            val result = mutableListOf<Int>()
            var frontier = listOf(root)
            while (frontier.isNotEmpty()) {
                val children = parents.filter { (pid, ppid) -> ppid in frontier && pid !in result && pid != root }
                    .map { it.first }
                result += children
                frontier = children
            }
            return result
        }

        private fun pidOf(process: Process): Int? = runCatching {
            var type: Class<*>? = process.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField("pid") }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching field.getInt(process)
                }
                type = type.superclass
            }
            null
        }.getOrNull()
    }

    private val binDir get() = File(prefixDir, "bin")
    /** The container's own /tmp; the native tmp (D-Bus socket, session files) is not bound. */
    private val containerTmp get() = File(tmpDir, "proot-container")
    private val x11Dir get() = File(tmpDir, ".X11-unix")
    private val distroDir get() = File(prefixDir, "var/lib/proot-distro")

    /**
     * Android 10+ may refuse execution of dynamically supplied helper binaries
     * from the app's writable data directory. PRoot's loader is therefore used
     * directly from ApplicationInfo.nativeLibraryDir, where PackageManager
     * installed the APK's native libraries as executable files.
     */
    internal fun prootLoaderArguments(): String {
        val dir = nativeLibraryDir ?: return ""
        val args = mutableListOf<String>()

        val loader64 = File(dir, "libproot-loader.so")
        if (loader64.isFile) {
            args += "--env PROOT_LOADER=\"${loader64.absolutePath}\""
        }

        // Do not advertise a 32-bit loader unless the APK really ships one.
        val loader32 = File(dir, "libproot-loader32.so")
        if (loader32.isFile) {
            args += "--env PROOT_LOADER_32=\"${loader32.absolutePath}\""
        }

        return if (args.isEmpty()) "" else " " + args.joinToString(" ")
    }

    fun rootfsMarkers(): List<File> = listOf(
        // proot-distro 5.4+
        File(distroDir, "containers/debian/rootfs/usr/lib/os-release"),
        File(distroDir, "containers/debian/rootfs/etc/os-release"),
        // Legacy proot-distro releases
        File(distroDir, "installed-rootfs/debian/etc/os-release"),
    )

    fun hasRootfs(): Boolean = rootfsMarkers().any(File::exists)

    /** Pure file check: safe to call from status refreshes of the native mode. */
    fun isInstalled(): Boolean = hasRootfs() && File(binDir, "proot-distro").exists()

    fun removePartialRootfs() {
        File(distroDir, "containers/debian").deleteRecursively()
        File(distroDir, "installed-rootfs/debian").deleteRecursively()
    }

    fun relocateExecutable() {
        val proot = File(binDir, "proot")
        if (proot.isFile) relocateElf(proot)
    }

    /** Launchers and relocation, done when installing or opening the container. */
    fun prepare() {
        relocateExecutable()
        ensureSharedStorageMountPoint()
        writeLaunchers()
        clearDownloadCache()
    }

    /**
     * Shared Android storage is exposed inside Debian at /mnt/phone.
     * The destination must exist in the rootfs before PRoot binds it.
     */
    private fun ensureSharedStorageMountPoint() {
        listOf(
            File(distroDir, "containers/debian/rootfs"),
            File(distroDir, "installed-rootfs/debian"),
        ).firstOrNull { it.isDirectory }?.let { rootfs ->
            File(rootfs, "mnt/phone").mkdirs()
        }
    }

    fun clearDownloadCache() {
        File(distroDir, "dlcache").deleteRecursively()
        File(distroDir, "cache").deleteRecursively()
    }

    /** Runs `proot --version` in its own process. */
    fun selfTest(): Boolean = runCatching {
        val process = builder(listOf(File(binDir, "proot").absolutePath, "--version")).start()
        process.inputStream.bufferedReader().readText()
        process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    fun isShellAlive(): Boolean = containerProcess?.isAlive == true

    /**
     * Opens the Debian shell and streams its output until it exits. Returns
     * the exit code, or null when the container is missing or could not start.
     */
    fun runShell(onOutput: (String) -> Unit): Int? {
        if (isShellAlive()) return null
        if (!isInstalled()) {
            onOutput("Debian (PRoot) no está instalado.\n")
            return null
        }
        prepare()
        val process = runCatching {
            builder(listOf(File(binDir, "bash").absolutePath, File(binDir, "start-debian").absolutePath)).start()
        }.getOrElse { error ->
            Log.w(TAG, "Could not start the container shell", error)
            onOutput("No se pudo iniciar PRoot: ${error.message}\n")
            return null
        }
        containerProcess = process
        return try {
            val reader = process.inputStream.reader()
            val buffer = CharArray(1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                onOutput(String(buffer, 0, read))
            }
            process.waitFor()
        } catch (error: java.io.IOException) {
            Log.d(TAG, "Container shell output closed")
            if (process.isAlive) null else process.exitValue()
        } finally {
            if (containerProcess === process) containerProcess = null
        }
    }

    /** Sends one line to the container shell; false when no shell is open. */
    fun sendInput(line: String): Boolean {
        val process = containerProcess?.takeIf { it.isAlive } ?: return false
        return runCatching {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()
            true
        }.getOrDefault(false)
    }

    /**
     * Stops the container shell and only its own descendants (found through
     * PPid in /proc starting at the shell's PID). XFCE, D-Bus and the X server
     * are never descendants of the container, so they cannot be signalled.
     */
    fun stopShell() {
        val process = containerProcess ?: return
        val root = pidOf(process)
        val tree = root?.let { listOf(it) + descendants(it, procDir) }.orEmpty()
        tree.asReversed().forEach { android.os.Process.sendSignal(it, 15) }
        if (root == null) process.destroy()
        if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            tree.asReversed().forEach { android.os.Process.sendSignal(it, 9) }
            process.destroyForcibly()
        }
        containerProcess = null
    }

    /** The native environment minus the native session's D-Bus. */
    internal fun containerEnvironment(native: Map<String, String>): Map<String, String> =
        native - "DBUS_SESSION_BUS_ADDRESS" +
            ("TMPDIR" to containerTmp.absolutePath) +
            ("XDG_RUNTIME_DIR" to containerTmp.absolutePath)

    private fun builder(command: List<String>) = ProcessBuilder(command)
        .directory(prefixDir)
        .redirectErrorStream(true)
        .also { pb ->
            pb.environment().clear()
            pb.environment().putAll(containerEnvironment(environment()))
        }

    fun writeLaunchers() {
        val loaderArguments = prootLoaderArguments()
        val launcher = File(binDir, "start-debian")
        launcher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            unset DBUS_SESSION_BUS_ADDRESS
            export TMPDIR="${containerTmp.absolutePath}"
            mkdir -p "${containerTmp.absolutePath}/proot"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${containerTmp.absolutePath}:/tmp" \
                --bind "${x11Dir.absolutePath}:/tmp/.X11-unix" \
                --bind "/storage/emulated/0:/mnt/phone" \
                --env PROOT_TMP_DIR="${containerTmp.absolutePath}/proot"${loaderArguments} -- \
                env DISPLAY="${'$'}DISPLAY" TERM="${'$'}{TERM:-xterm-256color}" bash -l
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val appsLauncher = File(binDir, "debian-apps")
        appsLauncher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            unset DBUS_SESSION_BUS_ADDRESS
            export TMPDIR="${containerTmp.absolutePath}"
            mkdir -p "${containerTmp.absolutePath}/proot"
            mode="${'$'}{1:-gui}"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${containerTmp.absolutePath}:/tmp" \
                --bind "${x11Dir.absolutePath}:/tmp/.X11-unix" \
                --bind "/storage/emulated/0:/mnt/phone" \
                --env PROOT_TMP_DIR="${containerTmp.absolutePath}/proot"${loaderArguments} -- \
                bash -s -- "${'$'}mode" <<'DROIDDESK_DEBIAN_APPS'
            mode="${'$'}1"
            case "${'$'}mode" in
                --all)
                    dpkg-query -W -f='${'$'}{binary:Package}\t${'$'}{Version}\n' | sort
                    ;;
                --manual)
                    apt-mark showmanual | sort
                    ;;
                gui)
                    for desktop in \
                        /usr/share/applications/*.desktop \
                        /usr/local/share/applications/*.desktop
                    do
                        [ -f "${'$'}desktop" ] || continue
                        no_display=${'$'}(sed -n 's/^NoDisplay=//p' "${'$'}desktop" | head -n 1)
                        [ "${'$'}no_display" = "true" ] && continue
                        name=${'$'}(sed -n 's/^Name=//p' "${'$'}desktop" | head -n 1)
                        command=${'$'}(sed -n 's/^Exec=//p' "${'$'}desktop" | head -n 1)
                        [ -n "${'$'}name" ] && [ -n "${'$'}command" ] &&
                            printf '%-32s %s\n' "${'$'}name" "${'$'}command"
                    done | sort -f
                    ;;
                *)
                    echo "Usage: debian-apps [--manual|--all]" >&2
                    exit 2
                    ;;
            esac
            DROIDDESK_DEBIAN_APPS
            """.trimIndent() + "\n",
        )
        appsLauncher.setExecutable(true, false)
    }
}
