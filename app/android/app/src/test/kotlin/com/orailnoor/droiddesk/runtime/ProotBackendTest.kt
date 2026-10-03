package com.orailnoor.droiddesk.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotBackendTest {
    private lateinit var root: File
    private lateinit var prefix: File
    private lateinit var tmp: File
    private lateinit var proc: File
    private val relocated = mutableListOf<File>()

    private val nativeEnv = mapOf(
        "HOME" to "/data/user/0/pkg/files/home",
        "TMPDIR" to "/data/user/0/pkg/files/tmp",
        "XDG_RUNTIME_DIR" to "/data/user/0/pkg/files/tmp",
        "DISPLAY" to ":0",
        "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/data/user/0/pkg/files/tmp/dbus-session",
        "PATH" to "/usr/bin:/bin",
    )

    @Before fun setUp() {
        root = Files.createTempDirectory("proot").toFile()
        prefix = File(root, "usr").apply { mkdirs() }
        tmp = File(root, "tmp").apply { mkdirs() }
        proc = File(root, "proc").apply { mkdirs() }
        File(prefix, "bin").mkdirs()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun backend(
        env: Map<String, String> = emptyMap(),
        nativeLibraryDir: File? = null,
    ) = ProotBackend(
        prefixDir = prefix,
        tmpDir = tmp,
        environment = { env },
        relocateElf = { relocated += it },
        procDir = proc,
        nativeLibraryDir = nativeLibraryDir,
    )

    private fun snapshot(dir: File = root): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() + "@" + it.lastModified() + it.canExecute() }

    private fun installDebian() {
        File(prefix, "bin/proot-distro").writeText("#!/bin/sh\n")
        File(prefix, "bin/proot").writeText("ELF")
        File(prefix, "var/lib/proot-distro/containers/debian/rootfs/etc").mkdirs()
        File(prefix, "var/lib/proot-distro/containers/debian/rootfs/etc/os-release").writeText("ID=debian\n")
        File(prefix, "var/lib/proot-distro/dlcache").mkdirs()
        File(prefix, "var/lib/proot-distro/dlcache/debian.tar.xz").writeText("x")
    }

    /** What the native session owns in tmp. */
    private fun nativeSessionFiles() {
        File(tmp, ".X11-unix").mkdirs()
        File(tmp, ".X11-unix/X0").writeText("")
        File(tmp, "dbus-session").writeText("")
        File(tmp, ".droiddesk-session").writeText("4321 4242 xfce4-session xfce4\n")
    }

    private fun linkBash(): Boolean {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() } ?: return false
        Files.createSymbolicLink(File(prefix, "bin/bash").toPath(), bash.toPath())
        return true
    }

    private fun failingProotDistro(script: String = "echo 'execve(...): Operation not permitted' >&2\nexit 1") {
        File(prefix, "bin/proot-distro").apply {
            writeText("#!/bin/sh\n$script\n")
            setExecutable(true)
        }
    }

    // ── Pure queries ──

    @Test fun isInstalledWritesNothing() {
        installDebian()
        val before = snapshot()
        assertTrue(backend().isInstalled())
        assertTrue(backend().hasRootfs())
        assertEquals(before, snapshot())
        assertTrue("no relocation on a query", relocated.isEmpty())
        assertFalse(File(prefix, "bin/start-debian").exists())
    }

    @Test fun notInstalledWithoutRootfsOrProotDistro() {
        assertFalse(backend().isInstalled())
        installDebian()
        File(prefix, "bin/proot-distro").delete()
        assertFalse(backend().isInstalled())
    }

    @Test fun prepareIsWhereLaunchersRelocationAndCacheHappen() {
        installDebian()
        backend().prepare()
        val launcher = File(prefix, "bin/start-debian")
        assertTrue(launcher.canExecute())
        assertEquals(listOf(File(prefix, "bin/proot")), relocated)
        assertFalse(File(prefix, "var/lib/proot-distro/dlcache").exists())
    }

    // ── Isolation ──

    @Test fun containerDoesNotGetTheNativeDbusOrTmp() {
        val env = backend().containerEnvironment(nativeEnv)
        assertFalse(env.containsKey("DBUS_SESSION_BUS_ADDRESS"))
        assertEquals(File(tmp, "proot-container").absolutePath, env["TMPDIR"])
        assertEquals(File(tmp, "proot-container").absolutePath, env["XDG_RUNTIME_DIR"])
        assertEquals(":0", env["DISPLAY"])
    }

    @Test fun launchersBindOnlyTheX11SocketFromNativeTmp() {
        installDebian()
        backend().prepare()
        val launcher = File(prefix, "bin/start-debian").readText()
        assertTrue(launcher, launcher.contains("unset DBUS_SESSION_BUS_ADDRESS"))
        assertTrue(launcher, launcher.contains("--bind \"${File(tmp, "proot-container").absolutePath}:/tmp\""))
        assertTrue(launcher, launcher.contains("--bind \"${File(tmp, ".X11-unix").absolutePath}:/tmp/.X11-unix\""))
        assertFalse(launcher, launcher.contains("--bind \"${tmp.absolutePath}:/tmp\""))
    }

    @Test fun prepareCreatesSharedStorageMountPointAndBothLaunchersBindIt() {
        installDebian()

        val mountPoint = File(
            prefix,
            "var/lib/proot-distro/containers/debian/rootfs/mnt/phone",
        )
        assertFalse(mountPoint.exists())

        backend().prepare()

        assertTrue("Debian shared-storage mount point was not created", mountPoint.isDirectory)

        listOf("start-debian", "debian-apps").forEach { name ->
            val launcher = File(prefix, "bin/$name").readText()
            assertTrue(
                launcher,
                launcher.contains("--bind \"/storage/emulated/0:/mnt/phone\""),
            )
        }
    }

    @Test fun launchersUseThePackagedNativeProotLoader() {
        installDebian()

        val nativeLibDir = File(root, "native-libs").apply { mkdirs() }
        val loader = File(nativeLibDir, "libproot-loader.so").apply {
            writeText("ELF")
        }

        backend(nativeLibraryDir = nativeLibDir).prepare()

        val expected = "--env PROOT_LOADER=\"${loader.absolutePath}\""

        listOf("start-debian", "debian-apps").forEach { name ->
            val launcher = File(prefix, "bin/$name").readText()

            assertTrue(launcher, launcher.contains(expected))
            assertFalse(launcher, launcher.contains("libexec/proot/loader"))
            assertFalse(launcher, launcher.contains("PROOT_LOADER_32"))
        }
    }

    @Test fun thirtyTwoBitLoaderIsOnlyAdvertisedWhenActuallyShipped() {
        installDebian()

        val nativeLibDir = File(root, "native-libs").apply { mkdirs() }
        File(nativeLibDir, "libproot-loader.so").writeText("ELF64")
        val loader32 = File(nativeLibDir, "libproot-loader32.so").apply {
            writeText("ELF32")
        }

        backend(nativeLibraryDir = nativeLibDir).prepare()

        val launcher = File(prefix, "bin/start-debian").readText()

        assertTrue(
            launcher,
            launcher.contains("--env PROOT_LOADER_32=\"${loader32.absolutePath}\""),
        )
    }

    @Test fun missingContainerFailsInsideTheBackendOnly() {
        nativeSessionFiles()
        val before = snapshot(tmp)
        val output = StringBuilder()
        assertNull(backend().runShell { output.append(it) })
        assertTrue(output.toString(), output.contains("no está instalado"))
        assertFalse(backend().isShellAlive())
        assertFalse("nothing to type into", backend().sendInput("ls"))
        assertEquals(before, snapshot(tmp))
    }

    @Test fun prootFailureLeavesTheNativeSessionUntouched() {
        assumeTrue(linkBash())
        installDebian()
        nativeSessionFiles()
        backend().prepare()
        failingProotDistro()
        val nativeBefore = snapshot(tmp).filterKeys { !it.contains("proot-container") }
        val output = StringBuilder()

        val exit = backend(nativeEnv).runShell { output.append(it) }

        assertEquals(1, exit)
        assertTrue(output.toString(), output.contains("Operation not permitted"))
        assertFalse(backend().isShellAlive())
        assertEquals(nativeBefore, snapshot(tmp).filterKeys { !it.contains("proot-container") })
    }

    @Test fun containerSeesNoNativeDbusAddress() {
        assumeTrue(linkBash())
        installDebian()
        backend().prepare()
        failingProotDistro("echo \"bus=[${'$'}DBUS_SESSION_BUS_ADDRESS] tmp=${'$'}TMPDIR\"")
        val output = StringBuilder()
        backend(nativeEnv).runShell { output.append(it) }
        assertTrue(output.toString(), output.contains("bus=[]"))
        assertTrue(output.toString(), output.contains("tmp=${File(tmp, "proot-container").absolutePath}"))
    }

    // ── Stop only the container's own process tree ──

    private fun proc(pid: Int, ppid: Int, comm: String) {
        File(proc, pid.toString()).apply {
            mkdirs()
            File(this, "stat").writeText("$pid ($comm) S $ppid 0 0 0\n")
        }
    }

    @Test fun stopTargetsOnlyDescendantsOfTheContainerShell() {
        // Native session: none of these descend from the container.
        proc(10, 1, "xfce4-session")
        proc(11, 10, "xfce4-panel")
        proc(12, 10, "xfdesktop")
        proc(13, 1, "dbus-daemon")
        proc(14, 1, "bash") // native terminal
        // Container: shell 50 -> proot-distro 51 -> proot 52 -> bash 53
        proc(50, 1, "bash")
        proc(51, 50, "proot-distro")
        proc(52, 51, "proot")
        proc(53, 52, "bash")
        File(proc, "77").mkdirs() // vanished, unreadable

        val targets = ProotBackend.descendants(50, proc)

        assertEquals(setOf(51, 52, 53), targets.toSet())
        assertTrue(targets.none { it in listOf(10, 11, 12, 13, 14) })
    }

    @Test fun stopWithoutAnOpenShellDoesNothing() {
        backend().stopShell()
        assertFalse(backend().isShellAlive())
    }
}
