package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.R
import android.content.Context
import android.os.Build
import android.util.Log
import com.orailnoor.droiddesk.runtime.control.ProcessRunner
import com.orailnoor.droiddesk.runtime.control.ToolChecks
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Core Linux runtime engine.
 *
 * Runs native Termux Linux on Android without PRoot.
 * Uses a C hook (libsocket_hook.so) via LD_PRELOAD to redirect file operations
 * and Unix socket connections from /data/data/com.termux/files/usr to DroidDesk's prefix.
 */
class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "LinuxRuntime"
        private const val BOOTSTRAP_MARKER = ".bootstrap_extracted"
        private const val SHEBANG_MARKER = ".relocated_text_paths_v3"
        private const val ELF_PATCH_MARKER = ".elf_runpaths_patched"
        private const val DE_MARKER = ".de_installed"

        // ELF64 constants
        private const val ELFMAG0: Byte = 0x7f
        private const val ELFMAG1: Byte = 'E'.code.toByte()
        private const val ELFMAG2: Byte = 'L'.code.toByte()
        private const val ELFMAG3: Byte = 'F'.code.toByte()
        private const val ELFCLASS64: Byte = 2
        private const val ELFDATA2LSB: Byte = 1

        private const val PT_LOAD = 1
        private const val PT_DYNAMIC = 2

        private const val DT_NULL = 0L
        private const val DT_STRTAB = 5L
        private const val DT_STRSZ = 10L
        private const val DT_RPATH = 15L
        private const val DT_RUNPATH = 29L

        private const val EI_MAG0 = 0
        private const val EI_MAG1 = 1
        private const val EI_MAG2 = 2
        private const val EI_MAG3 = 3
        private const val EI_CLASS = 4
        private const val EI_DATA = 5

        private const val E_PHOFF_OFFSET = 32
        private const val E_PHENTSIZE_OFFSET = 54
        private const val E_PHNUM_OFFSET = 56

        private const val P_TYPE_OFFSET = 0
        private const val P_OFFSET_OFFSET = 8
        private const val P_VADDR_OFFSET = 16
        private const val P_FILESZ_OFFSET = 32
        private const val PH_SIZE = 56

        private const val D_TAG_OFFSET = 0
        private const val D_VAL_OFFSET = 8
        private const val DYN_SIZE = 16

        // The desktop is launched from DesktopActivity but queried/stopped from
        // MainActivity. Keep process ownership application-wide so both runtime
        // wrappers operate on the same session.
        @Volatile private var sessionProcess: Process? = null
        @Volatile private var dbusProcess: Process? = null

        /**
         * Process-wide guard for apt/dpkg transactions. Shared by the in-app
         * package store and the Termux control bridge so they never run two
         * transactions (or clear each other's locks) at the same time.
         */
        val packageOperationRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private val MAINTAINER_SCRIPTS = listOf("preinst", "postinst", "prerm", "postrm", "config")
    }

    @Volatile private var activeCommandProcess: Process? = null
    @Volatile private var installLogSink: ((String) -> Unit)? = null
    @Volatile private var packageOperationCancelled = false

    fun setInstallLogSink(sink: ((String) -> Unit)?) {
        installLogSink = sink
    }

    fun beginPackageOperation() {
        // A previous App Store screen may have been closed while apt was still
        // running. Stop that process before accepting a new transaction.
        if (activeCommandProcess?.isAlive == true) interruptCommand()
        packageOperationCancelled = false
        clearStalePackageLocks()
    }

    fun finishPackageOperation() {
        packageOperationCancelled = false
    }

    fun cancelPackageOperation(): Boolean {
        packageOperationCancelled = true
        val wasRunning = activeCommandProcess?.isAlive == true
        interruptCommand()
        return wasRunning
    }

    // ── Base directories (all inside app's private storage) ──

    private val baseDir: File get() = context.filesDir
    private val prefixDir: File get() = File(baseDir, "usr")
    private val binDir: File get() = File(prefixDir, "bin")
    private val libDir: File get() = File(prefixDir, "lib")
    // Shared with X11ServerService. X clients resolve /tmp/.X11-unix/X0 here
    // through libsocket_hook, so this must not be $PREFIX/tmp.
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val homeDir: File get() = File(baseDir, "home")

    /** Qualcomm exposes the Adreno render device through KGSL on Android. */
    private fun hasAdrenoGpu(): Boolean = File("/dev/kgsl-3d0").exists()

    private fun normalizedDesktop(desktopEnv: String): String = when (desktopEnv.lowercase()) {
        "lxqt", "mate", "kde", "xfce4" -> desktopEnv.lowercase()
        else -> "xfce4"
    }

    // ── Status ──

    fun isBootstrapped(): Boolean {
        return File(baseDir, BOOTSTRAP_MARKER).exists() && File(prefixDir, "bin/bash").exists()
    }

    /**
     * Resets the install markers so the setup flow re-runs on next launch.
     * Does NOT delete usr/ — installed packages survive for a fast reinstall.
     */
    fun resetInstall() {
        File(prefixDir, DE_MARKER).delete()
        File(baseDir, BOOTSTRAP_MARKER).delete()
        Log.i(TAG, "Cleared DE and bootstrap markers; packages preserved")
    }

    fun isRunning(): Boolean {
        return sessionProcess?.isAlive == true
    }

    fun getInstalledDE(): String {
        val marker = File(prefixDir, DE_MARKER)
        if (marker.exists()) return marker.readText().trim().ifEmpty { "xfce4" }
        // Individual desktop binaries may already exist after a partially failed
        // package transaction. Only the marker written at the end of the complete
        // setup flow means the runtime is ready to launch.
        return ""
    }

    fun getGraphicsMode(): String {
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        return if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            "Turnip + Zink"
        } else {
            "Software (llvmpipe)"
        }
    }

    fun getOptionalAppsStatus(): Map<String, Boolean> = mapOf(
        "firefox" to File(binDir, "firefox").exists(),
        "code_oss" to (File(binDir, "code-oss").exists() || File(binDir, "code").exists()),
        "nodejs" to (File(binDir, "node").exists() && File(binDir, "npm").exists()),
        "imagemagick" to (File(binDir, "magick").exists() || File(binDir, "convert").exists()),
        "proot_debian" to isMinimalDebianInstalled(),
    )

    private fun isMinimalDebianInstalled(): Boolean {
        val installed = debianRootfsMarkers().any(File::exists) &&
            File(binDir, "start-debian").exists()
        if (installed) {
            relocateProotExecutable()
            writeDebianLauncher()
            clearProotDownloadCache()
        }
        return installed
    }

    private fun relocateProotExecutable() {
        val proot = File(binDir, "proot")
        if (!proot.isFile) return
        patchElfFile(
            proot,
            "/data/data/com.termux/files/usr/lib",
            File(prefixDir, "lib").absolutePath,
            "/data/data/com.termux/files/usr/lib/dri",
        )
    }

    private fun writeDebianLauncher() {
        val launcher = File(binDir, "start-debian")
        launcher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
                env DISPLAY="${'$'}DISPLAY" TERM="${'$'}{TERM:-xterm-256color}" bash -l
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val appsLauncher = File(binDir, "debian-apps")
        appsLauncher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            mode="${'$'}{1:-gui}"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
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

    private fun clearProotDownloadCache() {
        File(prefixDir, "var/lib/proot-distro/dlcache").deleteRecursively()
        File(prefixDir, "var/lib/proot-distro/cache").deleteRecursively()
    }

    private fun debianRootfsMarkers(): List<File> = listOf(
        // proot-distro 5.4+
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/usr/lib/os-release"),
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/etc/os-release"),
        // Legacy proot-distro releases
        File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian/etc/os-release"),
    )

    // ── Bootstrap ──

    fun setupBootstrap() {
        Log.i(TAG, "Setting up bootstrap environment...")
        listOf(prefixDir, binDir, libDir, tmpDir, homeDir).forEach { it.mkdirs() }
        Log.i(TAG, "Bootstrap directories ready. Base: ${baseDir.absolutePath}")
    }

    fun extractBootstrapIfNeeded(context: Context) {
        val bashBin = File(prefixDir, "bin/bash")
        if (bashBin.exists()) {
            Log.i(TAG, "Bootstrap already extracted at ${prefixDir.absolutePath}")
            // Refresh wrapper/config in case the app was updated
            createAptConfigOverride()
            ensureAptDirectories()
            wrapDpkgForPath()
            wrapUpdateAlternatives()
            ensureSocketHookPrebuilt()
            return
        }

        val marker = File(baseDir, BOOTSTRAP_MARKER)
        if (marker.exists()) {
            marker.delete()
        }

        Log.i(TAG, "Extracting bootstrap from assets to ${prefixDir.absolutePath}...")

        // Remove any partial extraction to ensure a clean bootstrap
        if (prefixDir.exists()) {
            prefixDir.deleteRecursively()
        }
        prefixDir.mkdirs()

        // Flutter assets are located under "flutter_assets/" in the APK asset tree
        val assetName = "flutter_assets/assets/bootstrap-aarch64.zip"
        val tmpZip = File(tmpDir, "bootstrap-aarch64.zip")
        tmpZip.parentFile?.mkdirs()

        try {
            context.assets.open(assetName).use { input ->
                tmpZip.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bootstrap asset: ${e.message}", e)
            return
        }

        // Extract the bootstrap so the top-level bin/lib/etc directories land under prefixDir/usr
        extractZip(tmpZip, prefixDir)

        // Restore symlinks from SYMLINKS.txt
        restoreSymlinks(prefixDir)

        // Create apt config override so apt uses our prefix
        createAptConfigOverride()

        // Ensure apt cache/state directories exist (bootstrap zip may omit empty dirs)
        ensureAptDirectories()

        // Set executable permissions on binaries
        setExecutableRecursively(binDir)
        setExecutableRecursively(File(prefixDir, "libexec"))

        // Wrap dpkg so it and its children (e.g. dpkg-split) always see the right PATH/env
        wrapDpkgForPath()

        // Wrap update-alternatives so its postinst invocations don't fail under
        // our relocated root (it otherwise double-prefixes absolute paths).
        wrapUpdateAlternatives()

        // Copy prebuilt socket hook from jniLibs to prefix/lib
        ensureSocketHookPrebuilt()

        marker.writeText("DroidDesk native bootstrap\n")
        tmpZip.delete()
        Log.i(TAG, "Bootstrap extraction complete")
    }

    private fun ensureSocketHookPrebuilt() {
        try {
            val libDir = File(prefixDir, "lib")
            libDir.mkdirs()
            val destHook = File(libDir, "libsocket_hook.so")
            // A hook compiled for another prefix (e.g. the regular app's data
            // dir copied before the preview had its own variant) silently
            // breaks every path redirection, so only keep one built for us.
            if (destHook.exists() && hookMatchesPrefix(destHook)) return

            val jniDir = File(context.applicationInfo.nativeLibraryDir)
            // Preview builds ship a hook compiled for their own prefix; the
            // generic prebuilt one points at the regular app's data dir.
            val candidates = listOf(
                File(jniDir, "libsocket_hook_variant.so"),
                File(jniDir, "libsocket_hook.so"),
            ).filter { it.exists() }
            val srcHook = candidates.firstOrNull(::hookMatchesPrefix)
                // Unknown prefix spelling: keep the historical first-copy behaviour.
                ?: candidates.firstOrNull()?.takeIf { !destHook.exists() }
            if (srcHook == null) {
                if (destHook.exists()) {
                    Log.w(TAG, "Installed socket hook targets another prefix and no matching prebuilt exists")
                } else {
                    Log.w(TAG, "Prebuilt socket hook not found in jniLibs at ${jniDir.absolutePath}")
                }
                return
            }
            if (destHook.exists()) {
                Log.w(TAG, "Replacing socket hook built for another prefix with ${srcHook.name}")
            }
            // Running Linux processes have the old hook mapped: write a new
            // file and rename it over the old one instead of truncating it.
            val staged = File(libDir, ".libsocket_hook.so.new")
            srcHook.inputStream().use { input ->
                staged.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            staged.setExecutable(true, false)
            if (!staged.renameTo(destHook)) {
                staged.delete()
                Log.e(TAG, "Could not install socket hook at ${destHook.absolutePath}")
                return
            }
            Log.i(TAG, "Copied prebuilt socket hook ${srcHook.name} to ${destHook.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy prebuilt socket hook: ${e.message}")
        }
    }

    /** True when [hook] has this app's prefix compiled in as its NEW_PREFIX string. */
    private fun hookMatchesPrefix(hook: File): Boolean = runCatching {
        val needle = (prefixDir.absolutePath + "\u0000").toByteArray()
        val bytes = hook.readBytes()
        var index = 0
        while (index <= bytes.size - needle.size) {
            var match = true
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) { match = false; break }
            }
            if (match) return@runCatching true
            index++
        }
        false
    }.getOrDefault(false)

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun createAptConfigOverride() {
        try {
            val aptConfDir = File(prefixDir, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            val confFile = File(aptConfDir, "99-droiddesk-paths.conf")
            confFile.writeText(
                """
                Dir "${prefixDir.absolutePath}";
                Dir::Etc "${prefixDir.absolutePath}/etc/apt";
                Dir::State "${prefixDir.absolutePath}/var/lib/apt";
                Dir::State::dpkg "${prefixDir.absolutePath}/var/lib/dpkg";
                Dir::Cache "${prefixDir.absolutePath}/var/cache/apt";
                Dir::Log "${prefixDir.absolutePath}/var/log/apt";
                Dir::Bin::Methods "${prefixDir.absolutePath}/lib/apt/methods";
                Dir::Bin::dpkg "${prefixDir.absolutePath}/bin/dpkg";
                Dir::Bin::apt-key "${prefixDir.absolutePath}/bin/apt-key";
                Acquire::gpgv::Options:: "--homedir=${prefixDir.absolutePath}/etc/apt/trusted.gpg.d";
                Acquire::Retries "3";
                Acquire::http::Timeout "30";
                Acquire::https::Timeout "30";
                DPkg::Lock::Timeout "60";
                """.trimIndent()
            )
            Log.i(TAG, "Created apt config override at ${confFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create apt config override: ${e.message}")
        }
    }

    private fun wrapDpkgForPath() {
        try {
            val dpkgBin = File(prefixDir, "bin/dpkg")
            val dpkgReal = File(prefixDir, "bin/dpkg.real")
            val relocateShebangs = File(prefixDir, "bin/droiddesk-relocate-shebangs")
            val relocateDeb = File(prefixDir, "bin/droiddesk-relocate-deb")

            // Build a mini root tree so dpkg can use Termux-style paths internally
            // while the actual files land in our private prefix.
            val dpkgRoot = File(baseDir, "dpkgroot")
            mapOf(
                File(dpkgRoot, "data/data/com.termux/files/usr") to prefixDir.absolutePath,
                File(dpkgRoot, "var/lib/dpkg") to File(prefixDir, "var/lib/dpkg").absolutePath,
                File(dpkgRoot, "tmp") to tmpDir.absolutePath
            ).forEach { (linkDir, target) ->
                linkDir.parentFile?.mkdirs()
                // Remove an existing symlink so we can update its target on app upgrades.
                if (linkDir.exists()) {
                    linkDir.delete()
                }
                if (!linkDir.exists()) {
                    android.system.Os.symlink(target, linkDir.absolutePath)
                }
            }

            // Make sure the real dpkg binary is saved as dpkg.real, then always
            // rewrite the wrapper so updates take effect on app upgrade/reinstall.
            if (!dpkgReal.exists() && dpkgBin.exists()) {
                dpkgBin.renameTo(dpkgReal)
            }

            // dpkg enumerates its compile-time configuration directory through
            // libc APIs that are not consistently intercepted by the path hook
            // on every Android build. Point that one embedded path at the
            // wrapper's working directory instead.
            patchEmbeddedCommand(
                dpkgReal,
                "/data/data/com.termux/files/usr/etc/dpkg",
                "/proc/self/cwd/etc/dpkg",
            )

            // Package installations started inside the XFCE terminal bypass the
            // Kotlin installation flow. Run this after every dpkg transaction so
            // newly unpacked commands and maintainer scripts never retain
            // Termux's original, inaccessible interpreter prefix.
            relocateShebangs.writeText(RelocationScripts.relocateShebangs(prefixDir.absolutePath))
            relocateShebangs.setExecutable(true, false)

            relocateDeb.writeText(RelocationScripts.relocateDeb(prefixDir.absolutePath, tmpDir.absolutePath))
            relocateDeb.setExecutable(true, false)

            val wrapper = RelocationScripts.dpkgWrapper(
                prefix = prefixDir.absolutePath,
                tmp = tmpDir.absolutePath,
                dpkgReal = dpkgReal.absolutePath,
                dpkgRoot = dpkgRoot.absolutePath,
                relocateShebangs = relocateShebangs.absolutePath,
                relocateDeb = relocateDeb.absolutePath,
            )

            dpkgBin.writeText(wrapper)
            dpkgBin.setExecutable(true, false)
            Log.i(TAG, "Installed dpkg wrapper at ${dpkgBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install dpkg wrapper: ${e.message}")
        }
    }

    private fun wrapUpdateAlternatives() {
        try {
            val uaBin = File(prefixDir, "bin/update-alternatives")
            val uaReal = File(prefixDir, "bin/update-alternatives.real")

            if (!uaBin.exists() || uaReal.exists()) return

            uaBin.renameTo(uaReal)
            uaBin.writeText(
                """
                #!/system/bin/sh
                # update-alternatives is not needed for DroidDesk's single-prefix
                # environment and fails when dpkg is run with a relocated root.
                exit 0
                """.trimIndent()
            )
            uaBin.setExecutable(true, false)
            Log.i(TAG, "Installed update-alternatives wrapper at ${uaBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update-alternatives wrapper: ${e.message}")
        }
    }

    private fun ensureAptDirectories() {
        listOf(
            "etc/dpkg/dpkg.cfg.d",
            "var/cache/apt/archives/partial",
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/log/apt"
        ).forEach { relativePath ->
            File(prefixDir, relativePath).mkdirs()
        }
        // dpkg requires these files to exist even if empty
        listOf(
            "var/lib/dpkg/status",
            "var/lib/dpkg/available",
            "var/lib/dpkg/diversions"
        ).forEach { relativePath ->
            val f = File(prefixDir, relativePath)
            if (!f.exists()) f.createNewFile()
        }
        Log.i(TAG, "Ensured apt/dpkg cache/state directories exist")
    }

    private fun setExecutableRecursively(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun restoreSymlinks(prefixDir: File) {
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (!symlinksFile.exists()) {
            Log.w(TAG, "SYMLINKS.txt not found, skipping symlink restoration")
            return
        }

        val termuxPrefix = "/data/data/com.termux/files/usr"
        var created = 0
        var failed = 0
        symlinksFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // Format: /data/data/com.termux/files/usr/.../target←./linkpath
            val arrow = "\u2190"
            val parts = trimmed.split(arrow)
            if (parts.size != 2) return@forEach

            val targetPath = parts[0].trim()
            val linkPath = parts[1].trim()

            // linkPath is relative to prefix root (starts with ./)
            val cleanLinkPath = if (linkPath.startsWith("./")) linkPath.substring(2) else linkPath
            val linkFile = File(prefixDir, cleanLinkPath)

            // Rewrite target from Termux prefix to app prefix;
            // bare filenames are kept relative so the symlink resolves correctly.
            val newTarget = when {
                targetPath.startsWith(termuxPrefix) -> {
                    prefixDir.absolutePath + targetPath.substring(termuxPrefix.length)
                }
                targetPath.startsWith("/") -> targetPath
                else -> targetPath
            }

            try {
                if (linkFile.exists()) {
                    linkFile.deleteRecursively()
                }
                linkFile.parentFile?.mkdirs()
                android.system.Os.symlink(newTarget, linkFile.absolutePath)
                created++
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Failed to create symlink ${linkFile.absolutePath} -> $newTarget: ${e.message}")
            }
        }

        Log.i(TAG, "Restored $created symlinks, $failed failed")
    }

    // ── Shebang Patching ──
    fun patchShebangs(force: Boolean = false) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        if (oldPrefix == newPrefix) return

        val markerFile = File(prefixDir, SHEBANG_MARKER)
        if (!force && markerFile.exists()) {
            Log.i(TAG, "Shebangs already patched, skipping")
            return
        }

        Log.i(TAG, "Patching shebangs: $oldPrefix -> $newPrefix")
        var patchCount = 0

        val dirsToScan = listOf("bin", "libexec", "share", "etc", "var/lib/dpkg/info")
        for (dirName in dirsToScan) {
            val dir = File(prefixDir, dirName)
            if (!dir.exists()) continue

            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.canRead()) {
                    try {
                        val bytes = file.inputStream().use {
                            val buf = ByteArray(256)
                            val n = it.read(buf)
                            if (n > 0) buf.copyOf(n) else ByteArray(0)
                        }

                        val isScript = bytes.size >= 2 &&
                            bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()
                        // Never rewrite ELF objects or other binary data as
                        // text: decoding and re-encoding them corrupts them.
                        val isElf = bytes.size >= 4 && bytes[0] == 0x7f.toByte() &&
                            bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() &&
                            bytes[3] == 'F'.code.toByte()
                        if (isElf || bytes.contains(0.toByte())) return@forEach
                        val isPathConfig = file.extension.lowercase() in setOf(
                            "service", "desktop", "conf", "xml", "pc", "cmake",
                            "la", "prl", "sh", "pl", "py", "rb", "json", "ini",
                        )
                        if (isScript || isPathConfig) {
                            val content = file.readText()
                            if (content.contains(oldPrefix)) {
                                val updated = content.replace(oldPrefix, newPrefix)
                                file.writeText(updated)
                                patchCount++
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore binary or read-only files
                    }
                }
            }
        }
        markerFile.writeText("done")
        Log.i(TAG, "Patched $patchCount scripts.")
    }

    /** Relocate commands that Xfce compiled as absolute Termux paths. */
    private fun patchEmbeddedXfcePaths() {
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-session"),
            "/data/data/com.termux/files/usr/bin/iceauth",
            "iceauth",
        )
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-panel"),
            "/data/data/com.termux/files/usr/lib/xfce4/panel/migrate",
            "migrate",
        )
    }

    private fun patchEmbeddedCommand(file: File, oldCommand: String, newCommand: String) {
        if (!file.exists() || newCommand.length > oldCommand.length) return
        val oldValue = oldCommand.toByteArray()
        val newValue = newCommand.toByteArray()
        val bytes = file.readBytes()
        var patched = false
        var offset = 0
        while (offset <= bytes.size - oldValue.size) {
            var matches = true
            for (index in oldValue.indices) {
                if (bytes[offset + index] != oldValue[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                bytes.fill(0, offset, offset + oldValue.size)
                newValue.copyInto(bytes, offset)
                patched = true
                offset += oldValue.size
            } else {
                offset++
            }
        }
        if (patched) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            Log.i(TAG, "Relocated embedded command in ${file.name}: $newCommand")
        }
    }

    // ── ELF RUNPATH/RPATH Patching ──

    fun patchElfRunpaths(prefixDir: File) {
        val marker = File(prefixDir, ELF_PATCH_MARKER)
        if (marker.exists()) {
            Log.i(TAG, "ELF runpaths already patched, skipping")
            return
        }

        val libDir = File(prefixDir, "lib")
        if (!libDir.exists()) {
            Log.w(TAG, "lib directory not found, skipping ELF patch")
            return
        }

        val oldPath = "/data/data/com.termux/files/usr/lib"
        val newPath = libDir.absolutePath
        val driOldPath = "/data/data/com.termux/files/usr/lib/dri"

        var patched = 0
        libDir.walkTopDown()
            .filter { it.isFile && it.canRead() && isElf64(it) }
            .forEach { file ->
                try {
                    if (patchElfFile(file, oldPath, newPath, driOldPath)) {
                        patched++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch ${file.absolutePath}: ${e.message}")
                }
            }

        marker.writeText("patched $patched files")
        Log.i(TAG, "Patched ELF runpaths in $patched files")
    }

    private fun isElf64(file: File): Boolean {
        if (file.length() < 6) return false
        return file.inputStream().use { input ->
            val buf = ByteArray(6)
            val n = input.read(buf)
            if (n < 6) return@use false
            buf[EI_MAG0] == ELFMAG0 &&
                    buf[EI_MAG1] == ELFMAG1 &&
                    buf[EI_MAG2] == ELFMAG2 &&
                    buf[EI_MAG3] == ELFMAG3 &&
                    buf[EI_CLASS] == ELFCLASS64 &&
                    buf[EI_DATA] == ELFDATA2LSB
        }
    }

    private fun patchElfFile(file: File, oldPath: String, newPath: String, driOldPath: String): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 64) return false

        val ePhoff = getLongLe(bytes, E_PHOFF_OFFSET)
        val ePhentsize = getShortLe(bytes, E_PHENTSIZE_OFFSET).toInt() and 0xFFFF
        val ePhnum = getShortLe(bytes, E_PHNUM_OFFSET).toInt() and 0xFFFF

        if (ePhoff < 0 || ePhoff + ePhnum * ePhentsize.toLong() > bytes.size) return false

        var strTabAddr: Long? = null
        var strTabSize: Long? = null
        val runpathOffsets = mutableListOf<Long>()
        val rpathOffsets = mutableListOf<Long>()

        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_DYNAMIC) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (pOffset < 0 || pOffset + pFilesz > bytes.size) continue

                val dynCount = pFilesz / DYN_SIZE
                for (j in 0 until dynCount) {
                    val dynOffset = (pOffset + j * DYN_SIZE).toInt()
                    val dTag = getLongLe(bytes, dynOffset + D_TAG_OFFSET)
                    val dVal = getLongLe(bytes, dynOffset + D_VAL_OFFSET)
                    when (dTag) {
                        DT_STRTAB -> strTabAddr = dVal
                        DT_STRSZ -> strTabSize = dVal
                        DT_RPATH -> rpathOffsets.add(dVal)
                        DT_RUNPATH -> runpathOffsets.add(dVal)
                        DT_NULL -> break
                    }
                }
            }
        }

        if (strTabAddr == null || strTabSize == null || strTabSize <= 0) return false

        var strTabFileOffset: Long? = null
        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_LOAD) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pVaddr = getLongLe(bytes, phOffset + P_VADDR_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (strTabAddr >= pVaddr && strTabAddr < pVaddr + pFilesz) {
                    strTabFileOffset = pOffset + (strTabAddr - pVaddr)
                    break
                }
            }
        }

        if (strTabFileOffset == null) return false

        val origin = "\${ORIGIN}"
        val relativeLib = "\${ORIGIN}/../lib"
        val replacement = when {
            newPath.length <= oldPath.length -> newPath
            file.parentFile?.absolutePath == newPath -> origin
            else -> relativeLib
        }
        var modified = false

        for (offset in runpathOffsets + rpathOffsets) {
            val fileOffset = (strTabFileOffset + offset).toInt()
            if (fileOffset < 0 || fileOffset >= bytes.size) continue
            val endOffset = minOf((strTabFileOffset + strTabSize).toInt(), bytes.size)
            val current = readNullTerminatedString(bytes, fileOffset, endOffset)

            when {
                current == driOldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, origin, current.length)
                    modified = true
                }
                current == oldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, replacement, current.length)
                    modified = true
                }
                current.contains(driOldPath) -> {
                    val replaced = current.replace(driOldPath, origin)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
                current.contains(oldPath) -> {
                    val replaced = current.replace(oldPath, replacement)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
            }
        }

        if (modified) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            return true
        }
        return false
    }

    private fun readNullTerminatedString(bytes: ByteArray, start: Int, end: Int): String {
        var i = start
        while (i < end && bytes[i] != 0.toByte()) i++
        return String(bytes, start, i - start, Charsets.UTF_8)
    }

    private fun writeNullTerminatedString(bytes: ByteArray, offset: Int, value: String, padTo: Int) {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        for (i in valueBytes.indices) {
            bytes[offset + i] = valueBytes[i]
        }
        for (i in valueBytes.size until padTo) {
            bytes[offset + i] = 0
        }
    }

    private fun getLongLe(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    private fun getIntLe(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 3 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return result
    }

    private fun getShortLe(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset + 1].toInt() and 0xFF) shl 8 or (bytes[offset].toInt() and 0xFF)).toShort()
    }

    // ── C Socket Hook Native Compilation ──
    fun compileSocketHook() {
        val hookC = File(tmpDir, "socket_hook.c")
        val hookBuildC = File(tmpDir, "socket_hook_build.c")
        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        val buildMarker = File(prefixDir, "lib/.socket_hook_build")
        File(prefixDir, "lib").mkdirs()

        try {
            context.assets.open("flutter_assets/assets/socket_hook.c").use { input ->
                hookC.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy socket_hook.c asset: ${e.message}")
            return
        }

        val buildSignature = java.security.MessageDigest.getInstance("SHA-256")
            .digest((hookC.readText() + "\n" + prefixDir.absolutePath).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hookSo.isFile && buildMarker.readTextOrNull() == buildSignature) {
            Log.d(TAG, "Native socket hook is current; skipping compilation")
            return
        }

        hookBuildC.writeText(
            """
            #define _GNU_SOURCE
            #define NEW_PREFIX "${prefixDir.absolutePath}"
            #include "${hookC.absolutePath}"
            """.trimIndent()
        )

        val clang = File(prefixDir, "bin/clang")
        if (clang.exists()) {
            Log.i(TAG, "Compiling socket_hook.c natively using clang...")
            val compileCmd = listOf(
                clang.absolutePath,
                "-shared", "-fPIC",
                hookBuildC.absolutePath,
                "-I", tmpDir.absolutePath,
                "-o", hookSo.absolutePath,
                "-ldl", "-llog"
            )
            try {
                val pb = ProcessBuilder(compileCmd)
                    .redirectErrorStream(true)
                    .also {
                        it.environment().clear()
                        it.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                        it.environment()["PATH"] = "${prefixDir.absolutePath}/bin:${System.getenv("PATH")}"
                        it.environment()["TMPDIR"] = tmpDir.absolutePath
                    }
                val process = pb.start()
                val log = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    buildMarker.writeText(buildSignature)
                    Log.i(TAG, "Native compilation of libsocket_hook.so successful!")
                } else {
                    Log.e(TAG, "Native compilation failed (code $exitCode): $log")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error compiling natively: ${e.message}")
            }
        } else {
            Log.w(TAG, "clang binary not found yet. Cannot compile socket_hook.c.")
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    // ── Environment Configuration ──

    private fun getTermuxEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["EXTERNAL_STORAGE"] = System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"

        env["PREFIX"] = prefixDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        // proot-distro 5.x derives all container paths from these variables.
        // Without them it falls back to Termux's original com.termux sandbox.
        env["TERMUX_APP__PACKAGE_NAME"] = context.packageName
        env["TERMUX__PREFIX"] = prefixDir.absolutePath
        env["TERMUX__HOME"] = homeDir.absolutePath
        env["TERMUX_VERSION"] = "DroidDesk"
        env["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
        env["PATH"] = listOf(
            "${prefixDir.absolutePath}/bin",
            "${prefixDir.absolutePath}/lib/xfce4/panel",
            System.getenv("PATH") ?: "/system/bin",
        ).joinToString(":")
        env["HOME"] = homeDir.absolutePath
        // VTE-based terminals use SHELL to spawn their child process. Android's
        // account database points at /system/bin/sh, which is not the relocated
        // Termux userspace expected by XFCE Terminal.
        env["SHELL"] = File(binDir, "bash").absolutePath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        // Spanish session; see LinuxLocale for what Bionic/Termux can translate.
        env["LANG"] = LinuxLocale.PREFERRED
        env["LANGUAGE"] = LinuxLocale.LANGUAGE

        env["DISPLAY"] = ":0"
        env["XDG_RUNTIME_DIR"] = tmpDir.absolutePath

        env["PERL5LIB"] = "${prefixDir.absolutePath}/lib/perl5/core_perl:${prefixDir.absolutePath}/lib/perl5/site_perl:${prefixDir.absolutePath}/lib/perl5/vendor_perl:${prefixDir.absolutePath}/lib/perl5"
        env["PYTHONHOME"] = prefixDir.absolutePath
        env["PIP_CONFIG_FILE"] = "${prefixDir.absolutePath}/etc/pip.conf"
        env["XDG_DATA_DIRS"] = "${prefixDir.absolutePath}/share"
        // Xfce validates that its compile-time SYSCONFDIR is present literally,
        // even though actual file access is relocated to this app's prefix.
        env["XDG_CONFIG_DIRS"] = listOf(
            "${prefixDir.absolutePath}/etc/xdg",
            "${prefixDir.absolutePath}/etc",
            "/data/data/com.termux/files/usr/etc",
        ).joinToString(":")
        env["GDK_PIXBUF_MODULEDIR"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders"
        env["GDK_PIXBUF_MODULE_FILE"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache"

        // Mesa is always available. Adreno devices use Turnip + Zink for hardware
        // rendering; other GPUs use Mesa's software renderer instead of being
        // forced through an incompatible Freedreno Vulkan ICD.
        env["LIBGL_DRIVERS_PATH"] = "${prefixDir.absolutePath}/lib/dri"
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            env["VK_ICD_FILENAMES"] = freedrenoIcd.absolutePath
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["GALLIUM_DRIVER"] = "zink"
        } else {
            env["LIBGL_ALWAYS_SOFTWARE"] = "true"
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
            env["GALLIUM_DRIVER"] = "llvmpipe"
        }

        env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=${tmpDir.absolutePath}/dbus-session"

        env["DPKG_ADMINDIR"] = "${prefixDir.absolutePath}/var/lib/dpkg"
        env["APT_CONFIG"] = "${prefixDir.absolutePath}/etc/apt/apt.conf.d/99-droiddesk-paths.conf"

        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        if (hookSo.exists()) {
            env["LD_PRELOAD"] = hookSo.absolutePath
        }

        return env
    }

    // ── Native Package Installation ──

    private fun installRepoPackages(): Boolean {
        val pkgs = listOf("x11-repo", "tur-repo")

        // Ensure main package list is up to date before downloading the repo packages.
        if (executeCommand("apt-get update").startsWith("Error:")) {
            Log.e(TAG, "apt-get update failed before installing repo packages")
            return false
        }

        // Download the .debs to the prefix root.
        val downloadCmd = "cd \"${prefixDir.absolutePath}\" && apt-get download ${pkgs.joinToString(" ")}"
        if (executeCommand(downloadCmd).startsWith("Error:")) {
            Log.e(TAG, "Failed to download x11-repo/tur-repo .debs")
            return false
        }

        // Unpack without configuring so we can edit the maintainer scripts first.
        val debs = pkgs.joinToString(" ") { "${it}_*.deb" }
        if (executeCommand("dpkg --unpack $debs").startsWith("Error:")) {
            Log.e(TAG, "Failed to unpack x11-repo/tur-repo .debs")
            return false
        }

        // Replace the postinst scripts with no-ops. The originals just run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter.
        for (pkg in pkgs) {
            val postinst = File(prefixDir, "var/lib/dpkg/info/$pkg.postinst")
            postinst.writeText("#!/system/bin/sh\nexit 0\n")
            postinst.setExecutable(true, false)
        }

        // Now configure the repo packages and refresh apt's package lists.
        if (executeCommand("dpkg --configure ${pkgs.joinToString(" ")}").startsWith("Error:")) {
            Log.e(TAG, "Failed to configure x11-repo/tur-repo")
            return false
        }
        if (executeCommand("pkg update -y").startsWith("Error:")) {
            Log.e(TAG, "Failed to update package lists after installing repo packages")
            return false
        }

        return true
    }

    private fun installPackageGroup(cmd: String): Boolean {
        // pkg install downloads, unpacks and configures in one go. Newly unpacked
        // maintainer scripts still contain the original Termux shebang, so after
        // the command finishes (successfully or not) we patch them and run a
        // configuration pass to finish any half-configured packages. The first
        // command is therefore allowed to fail specifically when that recovery
        // pass succeeds and all requested packages are present.
        Log.i(TAG, "Running: $cmd")
        val boundedCommand = if (cmd.startsWith("dpkg --configure")) {
            "timeout --signal=TERM --kill-after=5s 90s $cmd"
        } else {
            cmd
        }
        val installOutput = executeCommand(boundedCommand)
        patchShebangs(force = true)
        if (packageOperationCancelled) return false
        val configureOutput = if (cmd.startsWith("dpkg --configure")) {
            installOutput
        } else {
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
        }
        if (configureOutput.startsWith("Error:")) {
            return false
        }
        if (!installOutput.startsWith("Error:")) {
            return true
        }

        // A configure command is itself the recovery operation. If the patched
        // second pass completed, dpkg has no remaining unconfigured packages.
        if (cmd.startsWith("dpkg --configure")) {
            Log.i(TAG, "dpkg configuration recovered after shebang patching")
            return true
        }

        val installPrefix = "pkg install -y "
        if (!cmd.startsWith(installPrefix)) {
            return false
        }

        val requestedPackages = cmd.removePrefix(installPrefix).trim()
        if (requestedPackages.isEmpty()) {
            return false
        }

        // Do not mistake an unrelated successful `dpkg --configure -a` for a
        // recovered install: every package requested by this group must now be
        // registered in dpkg's database.
        val queryOutput = executeCommand("dpkg-query -W $requestedPackages")
        val recovered = !queryOutput.startsWith("Error:")
        if (recovered) {
            Log.i(TAG, "Package group recovered after shebang patching: $requestedPackages")
        }
        return recovered
    }

    /**
     * Installs optional packages without `pkg`'s implicit repository refresh.
     *
     * Desktop setup already creates the package indexes. Refreshing every repo for
     * every optional app made an unrelated, temporarily syncing TUR mirror block
     * packages from the healthy main/X11 repositories. apt-get uses the last
     * verified indexes and only refreshes as a recovery step if installation fails.
     */
    private fun installOptionalPackages(
        packages: List<String>,
        onProgress: ((Double, String) -> Unit)? = null,
        retryProgress: Double = 0.5,
    ): Boolean {
        if (packages.isEmpty()) return true
        val packageNames = packages.joinToString(" ")

        fun installAndRecover(): Boolean {
            val installOutput = executeCommand(
                "DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold install -y $packageNames",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            // A relocated package may already be unpacked while dependencies are
            // still missing (notably Node.js -> c-ares). Let apt complete that
            // dependency transaction after its maintainer scripts are patched.
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 120s " +
                    "env DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold --fix-broken install -y",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            val configureOutput = executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
            val installed = packages.all(::isDpkgPackageInstalled)
            return installed && !configureOutput.startsWith("Error:") &&
                (!installOutput.startsWith("Error:") || installed)
        }

        if (installAndRecover()) return true
        if (packageOperationCancelled) return false

        onProgress?.invoke(retryProgress, context.getString(R.string.progress_retrying_repositories))
        // apt may update main/X11 successfully while a third-party repository is
        // temporarily inconsistent. Retrying is still useful with those newly
        // refreshed lists and apt's last verified TUR index.
        executeCommand("timeout --signal=TERM --kill-after=5s 120s apt-get update")
        if (packageOperationCancelled) return false
        return installAndRecover()
    }

    private fun isDpkgPackageInstalled(packageName: String): Boolean {
        val result = executeCommand(
            "dpkg-query -W -f='${'$'}{Status}' $packageName",
        )
        return !result.startsWith("Error:") && result.trim() == "install ok installed"
    }

    /**
     * Node.js currently ships a preinst script with Termux's absolute shebang.
     * Preinst runs before dpkg exposes the script in its admin directory, so it
     * must be relocated inside the deb before installation.
     */
    private fun installRelocatedNodejs(): Boolean {
        // Install Node's native dependency before unpacking the relocated deb.
        // Running apt --fix-broken with an unpacked local Node package can make
        // apt replace it with the repository deb, whose absolute preinst shebang
        // is exactly what this relocation path must avoid.
        if (!isDpkgPackageInstalled("c-ares") &&
            !installOptionalPackages(listOf("c-ares"))) return false

        val workDir = File(tmpDir, "nodejs-relocated-deb")
        deleteTreeNoFollow(workDir)
        workDir.mkdirs()

        if (executeCommand("cd \"${workDir.absolutePath}\" && apt-get download nodejs")
                .startsWith("Error:")) return false
        val sourceDeb = workDir.listFiles()
            ?.firstOrNull { it.name.startsWith("nodejs_") && it.extension == "deb" }
            ?: return false
        val unpacked = File(workDir, "unpacked")
        val rebuiltDeb = File(workDir, "nodejs-relocated.deb")
        // libsocket_hook rewrites relative data/data/com.termux/... paths, so a
        // hooked dpkg-deb extracts into the live prefix rather than [unpacked].
        // Rebuild unhooked; the historical hooked commands remain the fallback.
        fun rebuild(hooked: Boolean): Boolean {
            val dpkgDeb = if (hooked) "dpkg-deb" else "env -u LD_PRELOAD dpkg-deb"
            deleteTreeNoFollow(unpacked)
            rebuiltDeb.delete()
            if (executeCommand(
                    "$dpkgDeb -R \"${sourceDeb.absolutePath}\" \"${unpacked.absolutePath}\""
                ).startsWith("Error:")) return false
            File(unpacked, "DEBIAN").apply {
                // dpkg-deb -b requires 0755..0775; the app's umask is 077.
                setReadable(true, false)
                setExecutable(true, false)
                relocateMaintainerScripts(this)
            }
            return !executeCommand(
                "$dpkgDeb -b \"${unpacked.absolutePath}\" \"${rebuiltDeb.absolutePath}\""
            ).startsWith("Error:")
        }
        if (!rebuild(hooked = false) && !rebuild(hooked = true)) return false
        if (executeCommand("dpkg --unpack \"${rebuiltDeb.absolutePath}\"")
                .startsWith("Error:")) return false

        patchShebangs(force = true)
        if (executeCommand("dpkg --configure nodejs").startsWith("Error:")) return false
        return isDpkgPackageInstalled("nodejs")
    }

    /**
     * Rewrites Termux's prefix in the text maintainer scripts of an unpacked
     * package. conffiles/md5sums keep Termux's spelling: dpkg resolves those
     * inside its relocated root. Binary files are left alone.
     */
    private fun relocateMaintainerScripts(controlDir: File) {
        MAINTAINER_SCRIPTS.map { File(controlDir, it) }.filter { it.isFile }.forEach { script ->
            if (looksBinary(script)) return@forEach
            val content = script.readText()
            if (content.contains(TERMUX_PREFIX)) {
                script.writeText(content.replace(TERMUX_PREFIX, prefixDir.absolutePath))
            }
            script.setReadable(true, false)
            script.setExecutable(true, false)
        }
    }

    private fun looksBinary(file: File): Boolean = runCatching {
        val head = ByteArray(512)
        val count = file.inputStream().use { it.read(head) }
        (0 until maxOf(count, 0)).any { head[it] == 0.toByte() }
    }.getOrDefault(true)

    /** Deletes a work tree without following symlinks out of it (unlike File.deleteRecursively). */
    private fun deleteTreeNoFollow(root: File) {
        val path = root.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(
                    file: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    java.nio.file.Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: java.nio.file.Path,
                    exc: java.io.IOException,
                ): java.nio.file.FileVisitResult {
                    runCatching { java.nio.file.Files.deleteIfExists(file) }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: java.nio.file.Path,
                    exc: java.io.IOException?,
                ): java.nio.file.FileVisitResult {
                    runCatching { java.nio.file.Files.deleteIfExists(dir) }
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }.onFailure { Log.w(TAG, "Could not remove ${root.absolutePath}", it) }
    }

    /** Installs only PRoot, proot-distro and Debian's base rootfs. */
    private fun installMinimalDebian(
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        onProgress?.invoke(0.12, context.getString(R.string.progress_installing_proot))
        if (!installOptionalPackages(listOf("proot", "proot-distro"), onProgress, 0.28)) {
            return false
        }

        patchShebangs(force = true)
        relocateProotExecutable()
        if (executeCommand("proot --version").startsWith("Error:")) {
            Log.e(TAG, "Installed PRoot executable could not start")
            return false
        }

        if (debianRootfsMarkers().none(File::exists)) {
            // Remove an interrupted extraction so proot-distro can safely retry.
            File(prefixDir, "var/lib/proot-distro/containers/debian").deleteRecursively()
            File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian").deleteRecursively()
            onProgress?.invoke(0.38, context.getString(R.string.progress_downloading_debian))
            if (executeCommand("proot-distro install debian").startsWith("Error:")) {
                Log.e(TAG, "Minimal Debian rootfs installation failed")
                return false
            }
        }

        onProgress?.invoke(0.9, context.getString(R.string.progress_creating_debian_shortcut))
        writeDebianLauncher()

        // The downloaded archive is not needed after extraction.
        clearProotDownloadCache()
        onProgress?.invoke(1.0, context.getString(R.string.progress_debian_ready))
        return isMinimalDebianInstalled()
    }

    fun installDesktopEnvironmentNative(
        desktopEnv: String = "xfce4",
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        val marker = File(prefixDir, DE_MARKER)

        if (getInstalledDE() == selectedDesktop) {
            onProgress?.invoke(1.0, context.getString(R.string.progress_desktop_already_installed, selectedDesktop))
            Log.i(TAG, "$selectedDesktop desktop environment already installed")
            return true
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot install DE — bootstrap not extracted")
            return false
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()
        onProgress?.invoke(0.12, context.getString(R.string.progress_configuring_x11_tur))

        // Install the x11/tur repository packages. Their postinst scripts run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter, so we
        // unpack the .debs, neutralise the postinst scripts, configure them, and
        // then update the package lists ourselves.
        if (!installRepoPackages()) {
            Log.e(TAG, "Failed to install x11-repo/tur-repo")
            return false
        }
        onProgress?.invoke(0.24, context.getString(R.string.progress_updating_native_database))

        // Finish configuring anything left over from a previous run, then install
        // the desktop, GPU drivers, and build tools. Each install is followed by a
        // shebang patch + configure pass so postinst scripts find our prefix.
        if (!installPackageGroup("dpkg --configure -a")) {
            Log.e(TAG, "Initial dpkg --configure -a failed")
            return false
        }
        if (!installPackageGroup("pkg update -y")) {
            Log.e(TAG, "pkg update failed")
            return false
        }
        onProgress?.invoke(0.34, context.getString(R.string.progress_installing_x11_audio))
        // DroidDesk embeds the X server, so termux-x11-nightly is deliberately
        // not installed. All desktops connect to the service's DISPLAY=:0.
        if (!installPackageGroup("pkg install -y xorg-xrandr pulseaudio xclip")) {
            Log.e(TAG, "Native X11 runtime package install failed")
            return false
        }
        onProgress?.invoke(0.46, context.getString(R.string.progress_installing_desktop_packages, selectedDesktop))

        val desktopPackages = when (selectedDesktop) {
            "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
            "mate" -> "mate mate-terminal"
            "kde" -> "plasma-desktop konsole dolphin"
            else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin xfce4-notifyd thunar mousepad"
        }
        if (!installPackageGroup("pkg install -y $desktopPackages")) {
            Log.e(TAG, "$selectedDesktop package install failed")
            return false
        }
        onProgress?.invoke(0.70, context.getString(R.string.progress_installing_mesa_packages))

        // mesa-zink pulls the Vulkan loader selected by the active Termux repo.
        // Current repositories use vulkan-loader-generic, which provides and
        // conflicts with the older vulkan-loader-android package name.
        if (!installPackageGroup("pkg install -y mesa-zink")) {
            // Graphics acceleration is optional. A desktop with llvmpipe is much
            // better UX than failing setup because a vendor Vulkan stack is not
            // compatible with the current Mesa package set.
            Log.w(TAG, "Mesa/Zink install unavailable; continuing with software rendering")
            installPackageGroup("dpkg --configure -a")
        }

        // Turnip/Freedreno is the hardware path for Qualcomm Adreno. Do not
        // install or force that ICD on Mali/PowerVR devices.
        if (hasAdrenoGpu()) {
            onProgress?.invoke(0.78, context.getString(R.string.progress_installing_adreno))
            installPackageGroup("pkg install -y mesa-vulkan-icd-freedreno")
        }

        val nativeTools = "git wget curl openssh htop python clang"
        onProgress?.invoke(
            0.84,
            context.getString(R.string.progress_installing_essentials_tools),
        )
        if (!installPackageGroup("pkg install -y $nativeTools")) {
            Log.e(TAG, "Native Termux utility package install failed")
            return false
        }
        onProgress?.invoke(0.94, context.getString(R.string.progress_finalizing_native))

        // Rebuild the hook with the installed clang, then persist the selected DE.
        compileSocketHook()
        patchEmbeddedXfcePaths()
        marker.writeText(selectedDesktop)
        onProgress?.invoke(1.0, context.getString(R.string.progress_native_setup_complete))
        Log.i(TAG, "Native Termux $selectedDesktop installation complete")
        return true
    }

    fun installOptionalApp(
        appId: String,
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        if (getInstalledDE().isEmpty()) return false
        if (getOptionalAppsStatus()[appId] == true) {
            onProgress?.invoke(1.0, context.getString(R.string.progress_already_installed))
            return true
        }

        // Code OSS can pull npm, and npm can be left unpacked while Node's
        // original Termux preinst path is invalid in our relocated prefix.
        // Repair/install Node before the generic dpkg configure pass.
        if (appId == "nodejs" || appId == "code_oss") {
            onProgress?.invoke(0.08, context.getString(R.string.progress_preparing_nodejs))
            if (!isDpkgPackageInstalled("nodejs") && !installRelocatedNodejs()) return false
        }

        onProgress?.invoke(0.18, context.getString(R.string.progress_repairing_packages))
        if (!installPackageGroup("dpkg --configure -a")) return false

        val ok = when (appId) {
            "firefox" -> {
                onProgress?.invoke(0.25, context.getString(R.string.progress_installing_named, "Firefox"))
                installOptionalPackages(listOf("firefox"), onProgress, 0.55)
            }
            "code_oss" -> {
                onProgress?.invoke(0.45, context.getString(R.string.progress_installing_npm_dependency))
                installOptionalPackages(listOf("npm"), onProgress, 0.55) && run {
                    onProgress?.invoke(0.65, context.getString(R.string.progress_installing_named, "Code OSS"))
                    installOptionalPackages(listOf("code-oss"), onProgress, 0.78)
                }
            }
            "nodejs" -> {
                onProgress?.invoke(0.65, context.getString(R.string.progress_installing_named, "npm"))
                installOptionalPackages(listOf("npm"), onProgress, 0.78)
            }
            "imagemagick" -> {
                onProgress?.invoke(0.25, context.getString(R.string.progress_installing_named, "ImageMagick"))
                installOptionalPackages(listOf("imagemagick"), onProgress, 0.55)
            }
            "proot_debian" -> installMinimalDebian(onProgress)
            else -> false
        }

        val verified = ok && getOptionalAppsStatus()[appId] == true
        if (verified) {
            patchShebangs(force = true)
            onProgress?.invoke(1.0, context.getString(R.string.progress_installation_complete))
        } else {
            onProgress?.invoke(-1.0, context.getString(R.string.progress_optional_install_failed))
        }
        return verified
    }

    // ── Native package store ──

    fun searchNativePackages(query: String, limit: Int = 60): List<Map<String, Any>> {
        if (!isBootstrapped()) return emptyList()
        val cleanQuery = query.trim().take(60)
        if (!cleanQuery.matches(Regex("[A-Za-z0-9+._ -]*"))) return emptyList()
        val output = runQuickCommand("apt-cache search --names-only '${cleanQuery.ifEmpty { "." }}'")
        val installed = installedPackageRecords().associateBy { it["name"] as String }
        return output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(" - ")
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).substringBefore(':').trim()
            if (!isSafePackageName(name)) return@mapNotNull null
            val description = line.substring(separator + 3).trim()
            val record = installed[name]
            mapOf(
                "name" to name,
                "description" to description,
                "version" to (record?.get("version") ?: ""),
                "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                "installed" to (record != null),
                "gui" to providesDesktopEntry(name),
            )
        }.distinctBy { it["name"] }.take(limit.coerceIn(1, 100)).toList()
    }

    fun getInstalledNativePackages(limit: Int = 500): List<Map<String, Any>> {
        val visible = storeInstalledPackages() + setOf(
            "firefox", "code-oss", "libreoffice", "gimp", "blender", "vlc",
            "nodejs", "python", "imagemagick",
        )
        return installedPackageRecords()
            .filter { record ->
                val name = record["name"] as? String ?: return@filter false
                visible.contains(name)
            }
            .take(limit.coerceIn(1, 1000))
    }

    fun installStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName)) {
            onProgress(-1.0, context.getString(R.string.progress_invalid_package))
            return false
        }
        onProgress(0.08, context.getString(R.string.progress_repairing_operations))
        installPackageGroup("dpkg --configure -a")
        if (packageOperationCancelled) {
            onProgress(-1.0, context.getString(R.string.progress_installation_cancelled))
            return false
        }
        onProgress(0.22, context.getString(R.string.progress_installing_with_dependencies, packageName))
        val optionalId = when (packageName) {
            "firefox" -> "firefox"
            "code-oss", "code" -> "code_oss"
            "nodejs" -> "nodejs"
            "imagemagick" -> "imagemagick"
            else -> null
        }
        val ok = if (optionalId != null) {
            installOptionalApp(optionalId) { progress, status ->
                onProgress(0.22 + progress.coerceIn(0.0, 1.0) * 0.68, status)
            }
        } else {
            installOptionalPackages(listOf(packageName), onProgress, 0.5)
        }
        if (packageOperationCancelled) {
            onProgress(-1.0, context.getString(R.string.progress_installation_cancelled))
            return false
        }
        patchShebangs(force = true)
        refreshDesktopMenus()
        val installed = ok && isDpkgPackageInstalled(packageName)
        if (installed) setStorePackageInstalled(packageName, true)
        onProgress(if (installed) 1.0 else -1.0, if (installed) context.getString(R.string.progress_package_installed, packageName) else context.getString(R.string.progress_package_install_failed, packageName))
        return installed
    }

    fun removeStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName) || isProtectedPackage(packageName)) {
            onProgress(-1.0, context.getString(R.string.progress_package_required))
            return false
        }
        onProgress(0.15, context.getString(R.string.progress_removing_package, packageName))
        val output = executeCommand("apt-get remove -y $packageName")
        if (packageOperationCancelled) {
            onProgress(-1.0, context.getString(R.string.progress_removal_cancelled))
            return false
        }
        patchShebangs(force = true)
        executeCommand("dpkg --configure -a")
        refreshDesktopMenus()
        val removed = !isDpkgPackageInstalled(packageName)
        if (removed) setStorePackageInstalled(packageName, false)
        onProgress(if (removed) 1.0 else -1.0, if (removed) context.getString(R.string.progress_package_removed, packageName) else context.getString(R.string.progress_package_removal_failed, packageName))
        return removed && !output.startsWith("Error:")
    }

    private fun installedPackageRecords(): List<Map<String, Any>> {
        val statusFile = File(prefixDir, "var/lib/dpkg/status")
        if (!statusFile.isFile) return emptyList()
        return statusFile.readText().split(Regex("\\n\\s*\\n")).mapNotNull { paragraph ->
            val fields = mutableMapOf<String, String>()
            var currentKey: String? = null
            paragraph.lineSequence().forEach { line ->
                if (line.startsWith(" ") && currentKey != null) {
                    fields[currentKey!!] = fields[currentKey!!].orEmpty() + " " + line.trim()
                } else {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        currentKey = line.substring(0, colon)
                        fields[currentKey!!] = line.substring(colon + 1).trim()
                    }
                }
            }
            if (fields["Status"] != "install ok installed") return@mapNotNull null
            val name = fields["Package"]?.substringBefore(':') ?: return@mapNotNull null
            val description = fields["Description"].orEmpty().substringBefore(" . ").trim()
            mapOf(
                "name" to name,
                "description" to description,
                "version" to fields["Version"].orEmpty(),
                "section" to fields["Section"].orEmpty().ifEmpty { categoryForPackage(name, description) },
                "installed" to true,
                "gui" to providesDesktopEntry(name),
                "removable" to !isProtectedPackage(name),
            )
        }.sortedBy { (it["name"] as String).lowercase() }
    }

    private fun providesDesktopEntry(packageName: String): Boolean {
        val fileList = File(prefixDir, "var/lib/dpkg/info/$packageName.list")
        return fileList.isFile && fileList.useLines { lines ->
            lines.any { it.contains("/share/applications/") && it.endsWith(".desktop") }
        }
    }

    private fun categoryForPackage(name: String, description: String): String {
        val text = "$name $description".lowercase()
        return when {
            listOf("browser", "firefox", "chromium").any(text::contains) -> "Internet"
            listOf("editor", "compiler", "git", "python", "nodejs", "ide").any(text::contains) -> "Development"
            listOf("image", "photo", "graphics", "gimp", "blender").any(text::contains) -> "Graphics"
            listOf("audio", "video", "media", "music", "vlc").any(text::contains) -> "Multimedia"
            listOf("office", "document", "spreadsheet", "pdf").any(text::contains) -> "Office"
            else -> "System"
        }
    }

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.matches(Regex("[a-z0-9][a-z0-9+.-]{0,127}"))

    private fun isProtectedPackage(packageName: String): Boolean =
        packageName in setOf(
            "apt", "bash", "coreutils", "dpkg", "termux-tools", "termux-am", "glibc-repo",
            "x11-repo", "tur-repo", "pulseaudio", "dbus", "python", "xfce4", "xfce4-session",
            "xfce4-panel", "xfdesktop", "xfwm4", "xfconf", "thunar", "xorg-xrandr",
        )

    private fun storeInstalledPackages(): Set<String> =
        context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
            .getStringSet("installed", emptySet())
            ?.toSet()
            .orEmpty()

    private fun setStorePackageInstalled(packageName: String, installed: Boolean) {
        val preferences = context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
        val packages = preferences.getStringSet("installed", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (installed) packages.add(packageName) else packages.remove(packageName)
        preferences.edit().putStringSet("installed", packages).commit()
    }

    private fun runQuickCommand(command: String): String = runCatching {
        ProcessBuilder(File(binDir, "bash").absolutePath, "-c", command)
            .directory(prefixDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
            }
            .start().let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                if (process.exitValue() == 0) output else ""
            }
    }.getOrDefault("")

    private fun refreshDesktopMenus() {
        executeCommand(
            "update-desktop-database ${prefixDir.absolutePath}/share/applications >/dev/null 2>&1 || true",
        )
    }

    fun readLinuxClipboard(): String? = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return null
        ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-o")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }
            .start().let { process ->
                val value = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) value else ""
            }
    }.getOrNull()

    fun writeLinuxClipboard(value: String): Boolean = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return false
        val process = ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-in")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }.start()
        process.outputStream.use { it.write(value.toByteArray()) }
        process.waitFor() == 0
    }.getOrDefault(false)

    // ── Session Management ──

    fun startSession(desktopEnv: String = "xfce4", mode: String = "x11", width: Int = 1920, height: Int = 1080) {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        extractBootstrapIfNeeded(context)

        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot start session — not bootstrapped")
            return
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()
        patchEmbeddedXfcePaths()

        val clipboardTool = File(binDir, "xclip")
        if (!clipboardTool.canExecute()) {
            Log.i(TAG, "Installing the desktop clipboard compatibility helper")
            if (installPackageGroup("pkg install -y xclip")) {
                patchShebangs(force = true)
            } else {
                Log.w(TAG, "Clipboard helper unavailable; clipboard sync will retry next session")
            }
        }

        if (selectedDesktop == "xfce4") {
            XfceMobileProfile.install(
                context = context,
                homeDir = homeDir.apply { mkdirs() },
                wallpaperFile = File(
                    homeDir,
                    ".local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
                ),
            )
            AndroidAppBridge.syncLaunchers(
                context = context,
                homeDir = homeDir,
                python = File(prefixDir, "bin/python3"),
            )

            // Update Firefox and Camera launchers for this session
            val cameraPackage = listOf(
                "com.sec.android.app.camera",
                "com.android.camera2",
                "com.google.android.GoogleCamera"
            ).firstOrNull { pkg ->
                runCatching {
                    context.packageManager.getPackageInfo(pkg, 0)
                    true
                }.getOrDefault(false)
            }
            XfceMobileProfile.updateSessionLaunchers(
                context = context,
                homeDir = homeDir,
                firefoxBin = File(binDir, "firefox"),
                cameraPackage = cameraPackage,
            )
        }

        // X11ServerService owns this socket. Never delete it from the client runtime.
        File(tmpDir, ".X11-unix").mkdirs()

        // Remove stale Xfce ICE listeners left by a killed/restarted activity.
        tmpDir.listFiles { file -> file.name.startsWith(".xfsm-ICE-") }
            ?.forEach { it.delete() }

        // Start a session dbus-daemon and keep it as a child process. Do not use
        // --fork: a forked daemon can outlive the Android activity and leave an
        // orphaned bus behind after its socket is replaced.
        val dbusSocket = File(tmpDir, "dbus-session")
        try {
            dbusProcess?.destroyForcibly()
            if (dbusSocket.exists()) dbusSocket.delete()
            val dbusConfig = File(tmpDir, "dbus-session.conf")
            dbusConfig.writeText(
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>session</type>
                  <keep_umask/>
                  <listen>unix:path=${dbusSocket.absolutePath}</listen>
                  <auth>EXTERNAL</auth>
                  <servicedir>${File(prefixDir, "share/dbus-1/services").absolutePath}</servicedir>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent() + "\n",
            )
            val dbusCmd = listOf(
                File(prefixDir, "bin/dbus-daemon").absolutePath,
                "--config-file=${dbusConfig.absolutePath}",
                "--nofork",
                "--nopidfile"
            )
            val startedDbus = ProcessBuilder(dbusCmd)
                .directory(homeDir.apply { mkdirs() })
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()
            dbusProcess = startedDbus

            Thread {
                try {
                    startedDbus.inputStream.bufferedReader().forEachLine {
                        Log.d(TAG, "DBUS: $it")
                    }
                } catch (error: java.io.IOException) {
                    // Closing the pipe is expected when Stop Server terminates dbus.
                    Log.d(TAG, "D-Bus output stream closed")
                }
            }.start()

            val readyDeadline = System.currentTimeMillis() + 2_000
            while (!dbusSocket.exists() && startedDbus.isAlive &&
                System.currentTimeMillis() < readyDeadline) {
                Thread.sleep(20)
            }
            check(dbusSocket.exists() && startedDbus.isAlive) {
                "dbus-daemon did not create its session socket"
            }
            Log.i(TAG, "Session dbus-daemon ready")
        } catch (e: Exception) {
            Log.e(TAG, "dbus-daemon start failed: ${e.message}")
            dbusProcess?.destroyForcibly()
            dbusProcess = null
            return
        }

        val desktopCommand = when (selectedDesktop) {
            "lxqt" -> "startlxqt"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            // The Termux startxfce4 wrapper falls back to Android's /bin/sh
            // when an X server already exists, which corrupts DISPLAY on
            // recent Android releases. The session binary starts the same
            // XFCE components and preserves DroidDesk's prepared environment.
            else -> "xfce4-session"
        }

        val runScript = """
            # ── Disable AT-SPI accessibility bus ──
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export DISPLAY=:0

            # Use the session bus DroidDesk already started
            export DBUS_SESSION_BUS_ADDRESS="unix:path=${dbusSocket.absolutePath}"

            # Native Android audio. AAudio is reliable on modern Android while
            # OpenSL ES remains the compatibility fallback for older devices.
            pulseaudio -k >/dev/null 2>&1 || true
            if [ "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "1" else "0"}" = "1" ]; then
                pulseaudio --start --exit-idle-time=-1 \
                    --load=module-aaudio-sink >/dev/null 2>&1 || true
                audio_ready=0
                for attempt in 1 2 3 4 5 6 7 8 9 10; do
                    if pactl list short sinks 2>/dev/null | grep -q AAudio_sink; then
                        audio_ready=1
                        break
                    fi
                    sleep 0.1
                done
                if [ "${'$'}audio_ready" != "1" ]; then
                    pulseaudio -k >/dev/null 2>&1 || true
                    pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
                fi
            else
                pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
            fi
            echo "DIAG: PulseAudio sinks: ${'$'}(pactl list short sinks 2>/dev/null | cut -f2 | tr '\n' ' ')"

            echo "DIAG: Launching $desktopCommand natively on DISPLAY=:0 ..."
            exec $desktopCommand
        """.trimIndent()

        Log.i(TAG, "Starting native Termux session for $selectedDesktop")

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val command = listOf(bashBin, "-c", runScript)

        val startedSession = ProcessBuilder(command)
            .directory(homeDir.apply { mkdirs() })
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(getTermuxEnv())
            }
            .start()
        sessionProcess = startedSession

        Thread {
            try {
                val reader = java.io.InputStreamReader(startedSession.inputStream)
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "DESKTOP: " + String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                // destroyForcibly() closes the pipe during a normal shutdown.
                Log.d(TAG, "Desktop output stream closed")
            }
        }.start()

        Log.i(TAG, "Termux session started")
    }

    /** Wait until the desktop shell processes that paint the first frame exist. */
    fun waitForDesktopReady(desktopEnv: String = "xfce4", timeoutMs: Long = 45_000): Boolean {
        val processes = when (normalizedDesktop(desktopEnv)) {
            "lxqt" -> listOf("lxqt-session", "lxqt-panel")
            "mate" -> listOf("mate-session", "mate-panel")
            "kde" -> listOf("plasmashell")
            else -> listOf("xfdesktop", "xfce4-panel")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val check = processes.joinToString(" && ") { "pgrep -x '$it' >/dev/null" }
            val ready = runCatching {
                ProcessBuilder(File(binDir, "bash").absolutePath, "-c", check)
                    .directory(homeDir)
                    .redirectErrorStream(true)
                    .also { builder ->
                        builder.environment().clear()
                        builder.environment().putAll(getTermuxEnv())
                    }
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            if (ready) {
                Thread.sleep(650)
                return true
            }
            Thread.sleep(150)
        }
        Log.w(TAG, "Timed out waiting for $desktopEnv to paint its first desktop frame")
        return false
    }

    fun stopSession() {
        Log.i(TAG, "Stopping Linux session gracefully...")
        sessionProcess?.let { proc ->
            // Ask the desktop session to exit cleanly first
            try {
                val bashBin = File(prefixDir, "bin/bash").absolutePath
                ProcessBuilder(bashBin, "-c",
                    "pkill -TERM xfce4-session; pkill -TERM mate-session; " +
                    "pkill -TERM startlxqt; pkill -TERM plasmashell; true"
                ).also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }.start().waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Graceful TERM signal failed (non-fatal): ${e.message}")
            }
            // Wait up to 3 seconds for clean exit before forcing
            val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                Log.i(TAG, "Session did not exit after 3s; forcing kill")
                proc.destroyForcibly()
                proc.waitFor()
            }
        }
        sessionProcess = null

        dbusProcess?.destroyForcibly()
        dbusProcess = null

        // Clean up dbus socket file
        File(tmpDir, "dbus-session").delete()

        Log.i(TAG, "Session stopped")
    }

    // ── Command Execution ──

    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (packageOperationCancelled) return "Error: Package operation cancelled"
        activeCommandProcess?.let { process ->
            try {
                Log.d(TAG, "Routing input to active command: $command")
                val os = process.outputStream
                os.write((command + "\n").toByteArray())
                os.flush()
                return ""
            } catch (e: Exception) {
                Log.w(TAG, "Active process closed or failed to receive input: ${e.message}")
            }
        }

        if (!isBootstrapped()) return "Error: Runtime not bootstrapped"

        compileSocketHook()

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val fullCommand = listOf(bashBin, "-c", command)

        return try {
            Log.d(TAG, "Executing command natively: $command")

            val process = ProcessBuilder(fullCommand)
                .directory(prefixDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()

            activeCommandProcess = process

            val output = StringBuilder()
            val reader = java.io.InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                Log.d(TAG, "CHUNK: $chunk")
                output.append(chunk)
                installLogSink?.invoke(chunk)
                onOutput?.invoke(chunk)
            }
            process.waitFor()
            activeCommandProcess = null
            Log.d(TAG, "Command finished with exit code: ${process.exitValue()}")

            if (process.exitValue() != 0) {
                throw Exception("Command failed with exit code ${process.exitValue()}. Output: \n$output")
            }

            output.toString()
        } catch (e: Exception) {
            activeCommandProcess = null
            Log.e(TAG, "Command execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    fun interruptCommand() {
        activeCommandProcess?.let { process ->
            Log.d(TAG, "Interrupting active command...")
            val rootPid = processPid(process)
            val descendants = rootPid?.let(::descendantPids).orEmpty()
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 15) }
            rootPid?.let { android.os.Process.sendSignal(it, 15) } ?: process.destroy()
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 9) }
            if (process.isAlive) process.destroyForcibly()
        }
        activeCommandProcess = null
    }

    private fun processPid(process: Process): Int? = runCatching {
        var type: Class<*>? = process.javaClass
        var pidField: java.lang.reflect.Field? = null
        while (type != null && pidField == null) {
            pidField = runCatching { type.getDeclaredField("pid") }.getOrNull()
            type = type.superclass
        }
        requireNotNull(pidField).apply { isAccessible = true }.getInt(process)
    }.onFailure { Log.w(TAG, "Could not inspect package process PID", it) }
        .getOrNull()

    private fun descendantPids(parentPid: Int): List<Int> {
        val result = mutableListOf<Int>()
        fun collect(pid: Int) {
            val children = runCatching {
                File("/proc/$pid/task/$pid/children").readText()
                    .trim().split(Regex("\\s+"))
                    .mapNotNull(String::toIntOrNull)
            }.getOrDefault(emptyList())
            children.forEach { child ->
                result += child
                collect(child)
            }
        }
        collect(parentPid)
        return result
    }

    private fun clearStalePackageLocks() {
        listOf(
            File(prefixDir, "var/lib/dpkg/lock"),
            File(prefixDir, "var/lib/dpkg/lock-frontend"),
            File(prefixDir, "var/cache/apt/archives/lock"),
            File(prefixDir, "var/lib/apt/lists/lock"),
        ).forEach { lock ->
            if (lock.exists() && !lock.delete()) {
                Log.w(TAG, "Could not remove stale package lock: ${lock.absolutePath}")
            }
        }
    }

    // ── Termux control bridge (scripts/droiddeskctl) ──

    /**
     * Runs [command] with bash in the runtime environment for the control
     * bridge. Unlike [executeCommand] it never becomes [activeCommandProcess]
     * (so the in-app terminal cannot inject input into it), is bounded by
     * [timeoutMs], streams output and returns the real exit code.
     */
    fun runControlCommand(
        command: String,
        timeoutMs: Long,
        onOutput: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        workDir: File? = null,
    ): ProcessRunner.Outcome {
        if (!isBootstrapped()) {
            val message = "Error: Runtime not bootstrapped\n"
            runCatching { onOutput(message) }
            return ProcessRunner.Outcome(ProcessRunner.EXIT_START_FAILED, false, false, 0, 0, message)
        }
        compileSocketHook()
        val directory = workDir ?: homeDir.apply { mkdirs() }
        return ProcessRunner(killTree = ::killProcessTree).run(
            argv = listOf(File(binDir, "bash").absolutePath, "-c", command),
            environment = getTermuxEnv(),
            directory = directory,
            timeoutMs = timeoutMs,
            onOutput = onOutput,
            isCancelled = isCancelled,
        )
    }

    private fun killProcessTree(process: Process) {
        val rootPid = processPid(process)
        val descendants = rootPid?.let(::descendantPids).orEmpty()
        descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 15) }
        rootPid?.let { android.os.Process.sendSignal(it, 15) } ?: process.destroy()
        if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 9) }
            process.destroyForcibly()
        } else {
            descendants.forEach { pid -> android.os.Process.sendSignal(pid, 9) }
        }
    }

    /**
     * Verifies a tool by running it, not by checking that a file exists:
     * `node -v` must print vX.Y.Z and `npm -v` X.Y.Z with exit code 0.
     */
    fun toolStatus(tool: String): Map<String, Any?> {
        val spec = ToolChecks.spec(tool)
        if (!File(binDir, tool).exists()) {
            return linkedMapOf("present" to false, "ok" to false, "version" to null, "exit" to null, "detail" to "not installed")
        }
        val outcome = runControlCommand(spec.command, spec.timeoutMs)
        val verdict = ToolChecks.evaluate(tool, outcome.exitCode, outcome.tail)
        return linkedMapOf(
            "present" to true,
            "ok" to verdict.ok,
            "version" to verdict.version,
            "exit" to outcome.exitCode,
            "detail" to if (verdict.ok) null else outcome.tail.trim().takeLast(600),
        )
    }

    /** dpkg's view of [packages]: "install ok installed <version>" or why not. */
    fun dpkgPackageStates(packages: List<String>): Map<String, String> {
        val outcome = runControlCommand(
            "dpkg-query -W -f='\${Package}\\t\${Status}\\t\${Version}\\n' ${packages.joinToString(" ")} 2>/dev/null; true",
            30_000,
        )
        return ToolChecks.parseDpkgStates(outcome.tail, packages)
    }

    private fun dpkgAudit(): Pair<Boolean, String> {
        val outcome = runControlCommand("dpkg --audit 2>&1", 60_000)
        val output = outcome.tail.trim()
        return (outcome.exitCode == 0 && output.isEmpty()) to output
    }

    private fun socketHookStatus(): Map<String, Any?> {
        val hook = File(prefixDir, "lib/libsocket_hook.so")
        val jniDir = File(context.applicationInfo.nativeLibraryDir)
        return linkedMapOf(
            "path" to hook.absolutePath,
            "present" to hook.isFile,
            "matchesPrefix" to (hook.isFile && hookMatchesPrefix(hook)),
            "variantShipped" to File(jniDir, "libsocket_hook_variant.so").isFile,
            "variantMatchesPrefix" to File(jniDir, "libsocket_hook_variant.so").let { it.isFile && hookMatchesPrefix(it) },
        )
    }

    /** Snapshot for `droiddeskctl status`. Node/npm are verified by running them. */
    fun controlStatus(): Map<String, Any?> = linkedMapOf(
        "package" to context.packageName,
        "bootstrapped" to isBootstrapped(),
        "desktop" to getInstalledDE().ifEmpty { null },
        "sessionRunning" to isRunning(),
        "graphics" to getGraphicsMode(),
        "prefix" to prefixDir.absolutePath,
        "home" to homeDir.absolutePath,
        "socketHook" to socketHookStatus(),
        "apps" to linkedMapOf(
            "firefox" to File(binDir, "firefox").exists(),
            "code_oss" to (File(binDir, "code-oss").exists() || File(binDir, "code").exists()),
            "imagemagick" to (File(binDir, "magick").exists() || File(binDir, "convert").exists()),
            "python3" to File(binDir, "python3").exists(),
            "xclip" to File(binDir, "xclip").exists(),
        ),
        "node" to if (isBootstrapped()) toolStatus("node") else null,
        "npm" to if (isBootstrapped()) toolStatus("npm") else null,
        "dpkg" to if (isBootstrapped()) dpkgPackageStates(listOf("nodejs", "npm", "c-ares")) else null,
    )

    /** Runtime facts for `droiddeskctl shell-info`. */
    fun shellInfo(): Map<String, Any?> {
        val env = getTermuxEnv()
        val bash = if (isBootstrapped()) {
            runControlCommand("echo \"\$BASH_VERSION\"", 10_000).tail.trim()
        } else null
        return linkedMapOf(
            "package" to context.packageName,
            "uid" to android.os.Process.myUid(),
            "filesDir" to baseDir.absolutePath,
            "prefix" to prefixDir.absolutePath,
            "home" to homeDir.absolutePath,
            "tmp" to tmpDir.absolutePath,
            "termuxPrefix" to TERMUX_PREFIX,
            "bash" to bash,
            "env" to listOf(
                "PATH", "LD_LIBRARY_PATH", "LD_PRELOAD", "LANG", "LANGUAGE", "TMPDIR",
                "SHELL", "DISPLAY", "TERMUX__PREFIX", "APT_CONFIG", "DPKG_ADMINDIR",
            ).associateWith { env[it] },
            "socketHook" to socketHookStatus(),
            "dpkgWrapper" to File(prefixDir, "bin/dpkg.real").isFile,
        )
    }

    /** Health checks for `droiddeskctl doctor`; each entry is check/ok/detail. */
    fun doctor(): List<Map<String, Any?>> {
        val checks = mutableListOf<Map<String, Any?>>()
        fun check(name: String, ok: Boolean, detail: String? = null) {
            checks += linkedMapOf("check" to name, "ok" to ok, "detail" to detail)
        }
        val bootstrapped = isBootstrapped()
        check("bootstrap", bootstrapped, prefixDir.absolutePath)
        check("desktop installed", getInstalledDE().isNotEmpty(), getInstalledDE().ifEmpty { "no DE marker" })
        val hook = socketHookStatus()
        check("libsocket_hook.so", hook["present"] == true && hook["matchesPrefix"] == true,
            "present=${hook["present"]} matchesPrefix=${hook["matchesPrefix"]} variant=${hook["variantMatchesPrefix"]}")
        val dpkgBin = File(prefixDir, "bin/dpkg")
        check("dpkg wrapper", File(prefixDir, "bin/dpkg.real").isFile && !looksBinary(dpkgBin),
            "dpkg.real=${File(prefixDir, "bin/dpkg.real").isFile}")
        listOf("droiddesk-relocate-shebangs", "droiddesk-relocate-deb").forEach { helper ->
            check(helper, File(binDir, helper).canExecute())
        }
        val dpkgRootLink = File(baseDir, "dpkgroot/data/data/com.termux/files/usr")
        check("dpkgroot", runCatching { dpkgRootLink.canonicalPath == prefixDir.canonicalPath }.getOrDefault(false),
            runCatching { dpkgRootLink.canonicalPath }.getOrNull())
        check("apt config", File(prefixDir, "etc/apt/apt.conf.d/99-droiddesk-paths.conf").isFile)
        val staleScripts = File(prefixDir, "var/lib/dpkg/info").listFiles { file ->
            file.isFile && MAINTAINER_SCRIPTS.any { file.name.endsWith(".$it") } &&
                !looksBinary(file) && runCatching { file.readText().contains(TERMUX_PREFIX) }.getOrDefault(false)
        }.orEmpty()
        check("maintainer scripts relocated", staleScripts.isEmpty(),
            staleScripts.take(10).joinToString { it.name }.ifEmpty { null })
        val staleShebangs = binDir.listFiles()?.filter { file ->
            file.isFile && runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(TERMUX_PREFIX.length + 2)
                    val count = input.read(head)
                    count > 2 && String(head, 0, count).startsWith("#!$TERMUX_PREFIX")
                }
            }.getOrDefault(false)
        }.orEmpty()
        check("bin shebangs relocated", staleShebangs.isEmpty(),
            staleShebangs.take(10).joinToString { it.name }.ifEmpty { null })
        if (bootstrapped) {
            val (auditOk, auditOutput) = dpkgAudit()
            check("dpkg --audit", auditOk, auditOutput.take(1500).ifEmpty { null })
            val node = toolStatus("node")
            check("node -v", node["ok"] == true, (node["version"] ?: node["detail"])?.toString())
            val npm = toolStatus("npm")
            check("npm -v", npm["ok"] == true, (npm["version"] ?: npm["detail"])?.toString())
        }
        check("python3 (XFCE app launchers)", File(binDir, "python3").canExecute())
        check("xclip (clipboard)", File(binDir, "xclip").canExecute())
        val shared = File("/storage/emulated/0")
        check("Android shared storage", shared.isDirectory && shared.canRead(), shared.absolutePath)
        check("desktop session", true, if (isRunning()) "running" else "stopped")
        return checks
    }

    /** Takes the process-wide package lock for [block]; null when another transaction runs. */
    fun <T> withPackageLock(block: () -> T): T? {
        if (!packageOperationRunning.compareAndSet(false, true)) return null
        return try {
            packageOperationCancelled = false
            block()
        } finally {
            packageOperationRunning.set(false)
        }
    }

    private fun packageProcessesRunning(): Boolean =
        runControlCommand("pgrep -f '(^|/)(dpkg\\.real|apt-get|apt) ' >/dev/null", 10_000).exitCode == 0

    /**
     * Brings apt/dpkg back to a consistent state without reinstalling the
     * runtime or upgrading anything: refreshes the wrappers, relocates every
     * maintainer script and shebang, finishes interrupted configuration,
     * repairs Node.js through [installRelocatedNodejs] and npm, then reports
     * `dpkg --audit`, `node -v` and `npm -v`. Call under [withPackageLock].
     */
    fun repairPackages(onOutput: (String) -> Unit): Map<String, Any?> {
        val steps = mutableListOf<Map<String, Any?>>()
        fun say(text: String) = runCatching { onOutput(text) }
        fun step(name: String, ok: Boolean, detail: String? = null) {
            steps += linkedMapOf("step" to name, "ok" to ok, "detail" to detail)
            say("${if (ok) "[ok]" else "[!!]"} $name${detail?.let { ": $it" } ?: ""}\n")
        }
        if (!isBootstrapped()) {
            step("bootstrap", false, "runtime not bootstrapped; open DroidDesk to finish setup")
            return linkedMapOf("ok" to false, "steps" to steps)
        }
        if (packageProcessesRunning()) {
            step("no other apt/dpkg running", false, "another apt/dpkg process is active; try again later")
            return linkedMapOf("ok" to false, "steps" to steps)
        }

        say("== Refreshing dpkg wrapper, relocation helpers and socket hook\n")
        createAptConfigOverride()
        ensureAptDirectories()
        wrapDpkgForPath()
        wrapUpdateAlternatives()
        ensureSocketHookPrebuilt()
        clearStalePackageLocks()
        step("wrappers refreshed", File(binDir, "droiddesk-relocate-deb").canExecute())

        say("== Relocating Termux paths in shebangs and maintainer scripts\n")
        val relocate = runControlCommand("\"${File(binDir, "droiddesk-relocate-shebangs").absolutePath}\" --all", 600_000, onOutput)
        patchShebangs(force = true)
        step("relocation pass", relocate.exitCode == 0, "exit ${relocate.exitCode}")

        say("== dpkg --configure -a\n")
        val configure = runControlCommand("dpkg --configure -a", 900_000, onOutput)
        step("dpkg --configure -a", configure.exitCode == 0, "exit ${configure.exitCode}")

        val previousSink = installLogSink
        installLogSink = { chunk -> say(chunk) }
        try {
            var states = dpkgPackageStates(listOf("nodejs", "npm"))
            val nodeOk = toolStatus("node")["ok"] == true
            val nodeKnown = states["nodejs"] != "not-installed"
            if (nodeKnown && (!nodeOk || states["nodejs"].let { !ToolChecks.isInstalledOk(it) })) {
                say("== Repairing Node.js with the relocated package\n")
                step("nodejs (relocated reinstall)", installRelocatedNodejs())
            }
            states = dpkgPackageStates(listOf("nodejs", "npm"))
            val npmKnown = states["npm"] != "not-installed"
            if (npmKnown && (states["npm"].let { !ToolChecks.isInstalledOk(it) } || toolStatus("npm")["ok"] != true)) {
                say("== Repairing npm\n")
                val configured = runControlCommand("dpkg --configure npm", 600_000, onOutput).exitCode == 0
                val npmFixed = (configured && toolStatus("npm")["ok"] == true) ||
                    installOptionalPackages(listOf("npm"))
                step("npm", npmFixed)
            }

            var (auditOk, auditOutput) = dpkgAudit()
            if (!auditOk) {
                say("== dpkg --audit reported problems; letting apt complete dependencies\n")
                val fix = runControlCommand(
                    "env DEBIAN_FRONTEND=noninteractive apt-get " +
                        "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold " +
                        "--fix-broken install -y && dpkg --configure -a",
                    1_800_000,
                    onOutput,
                )
                step("apt-get --fix-broken install", fix.exitCode == 0, "exit ${fix.exitCode}")
                dpkgAudit().let { auditOk = it.first; auditOutput = it.second }
            }
            step("dpkg --audit", auditOk, auditOutput.take(1500).ifEmpty { null })
        } finally {
            installLogSink = previousSink
        }

        patchShebangs(force = true)
        val node = toolStatus("node")
        val npm = toolStatus("npm")
        val states = dpkgPackageStates(listOf("nodejs", "npm"))
        val audit = dpkgAudit()
        return linkedMapOf(
            "ok" to (audit.first && steps.none { it["ok"] == false && it["step"] != "dpkg --configure -a" }),
            "steps" to steps,
            "node" to node,
            "npm" to npm,
            "dpkg" to states,
            "dpkgAudit" to audit.second.ifEmpty { null },
        )
    }

    /**
     * Makes `node -v` and `npm -v` work, using DroidDesk's relocated Node.js
     * flow (never a bare `pkg install nodejs`). Call under [withPackageLock].
     */
    fun ensureNodeToolchain(onOutput: (String) -> Unit): Boolean {
        fun say(text: String) = runCatching { onOutput(text) }
        if (!isBootstrapped()) {
            say("Runtime not bootstrapped; open DroidDesk to finish setup first.\n")
            return false
        }
        if (toolStatus("node")["ok"] == true && toolStatus("npm")["ok"] == true &&
            dpkgPackageStates(listOf("nodejs", "npm")).values.all(ToolChecks::isInstalledOk)) {
            say("Node.js and npm are already installed and working.\n")
            return true
        }
        val previousSink = installLogSink
        installLogSink = { chunk -> say(chunk) }
        try {
            wrapDpkgForPath()
            clearStalePackageLocks()
            say("== Finishing interrupted dpkg configuration\n")
            installPackageGroup("dpkg --configure -a")
            val nodeState = dpkgPackageStates(listOf("nodejs"))["nodejs"].orEmpty()
            if (toolStatus("node")["ok"] != true || !ToolChecks.isInstalledOk(nodeState)) {
                say("== Installing Node.js (relocated package)\n")
                if (!installRelocatedNodejs()) {
                    say("Relocated Node.js installation failed.\n")
                    return false
                }
            }
            if (toolStatus("npm")["ok"] != true ||
                dpkgPackageStates(listOf("npm"))["npm"].let { !ToolChecks.isInstalledOk(it) }) {
                say("== Installing npm\n")
                if (!installOptionalPackages(listOf("npm"))) {
                    say("npm installation failed.\n")
                    return false
                }
            }
            patchShebangs(force = true)
        } finally {
            installLogSink = previousSink
        }
        val node = toolStatus("node")
        val npm = toolStatus("npm")
        say("node -v: ${node["version"] ?: "FAILED (${node["detail"]})"}\n")
        say("npm -v: ${npm["version"] ?: "FAILED (${npm["detail"]})"}\n")
        return node["ok"] == true && npm["ok"] == true
    }

    /** `droiddeskctl install <app>`; verifies the result by running/querying it. Call under [withPackageLock]. */
    fun installForControl(appId: String, onOutput: (String) -> Unit): Map<String, Any?> {
        fun say(text: String) = runCatching { onOutput(text) }
        if (appId == "nodejs") {
            val ok = ensureNodeToolchain(onOutput)
            return linkedMapOf("ok" to ok, "node" to toolStatus("node"), "npm" to toolStatus("npm"),
                "dpkgAudit" to dpkgAudit().second.ifEmpty { null })
        }
        if (appId == "code_oss" && !ensureNodeToolchain(onOutput)) {
            return linkedMapOf("ok" to false, "reason" to "Node.js/npm could not be prepared")
        }
        if (getInstalledDE().isEmpty()) {
            say("The Linux desktop is not installed yet; finish setup in DroidDesk first.\n")
            return linkedMapOf("ok" to false, "reason" to "desktop not installed")
        }
        val previousSink = installLogSink
        installLogSink = { chunk -> say(chunk) }
        val installed = try {
            installOptionalApp(appId) { progress, status ->
                if (progress >= 0) say("[${(progress * 100).toInt()}%] $status\n") else say("[!!] $status\n")
            }
        } finally {
            installLogSink = previousSink
        }
        val packageName = when (appId) {
            "code_oss" -> "code-oss"
            else -> appId
        }
        val state = dpkgPackageStates(listOf(packageName))[packageName].orEmpty()
        val verified = installed && ToolChecks.isInstalledOk(state)
        return linkedMapOf("ok" to verified, "dpkg" to mapOf(packageName to state),
            "dpkgAudit" to dpkgAudit().second.ifEmpty { null })
    }

}
