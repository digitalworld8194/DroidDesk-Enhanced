package com.orailnoor.droiddesk.runtime.control

import com.orailnoor.droiddesk.runtime.RelocationScripts
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the real relocation helpers with the host's dpkg-deb/sed/grep against
 * a scratch prefix. Skipped where dpkg-deb is unavailable.
 */
class RelocationScriptsTest {
    private val termux = RelocationScripts.TERMUX_PREFIX
    private lateinit var root: File
    private lateinit var prefix: File
    private lateinit var tmp: File

    private fun which(tool: String): String? =
        System.getenv("PATH").orEmpty().split(':').map { File(it, tool) }.firstOrNull { it.canExecute() }?.absolutePath

    @Before fun setUp() {
        assumeTrue("dpkg-deb not available", which("dpkg-deb") != null)
        root = Files.createTempDirectory("reloc").toFile()
        prefix = File(root, "files/usr")
        tmp = File(root, "files/tmp").apply { mkdirs() }
        val bin = File(prefix, "bin").apply { mkdirs() }
        listOf("dpkg-deb", "grep", "sed", "basename", "rm", "mkdir", "chmod", "find", "head", "tar", "gzip", "xz", "zstd")
            .forEach { tool -> which(tool)?.let { Files.createSymbolicLink(File(bin, tool).toPath(), File(it).toPath()) } }
    }

    @After fun tearDown() {
        if (::root.isInitialized) ProcessBuilder("rm", "-rf", root.absolutePath).start().waitFor()
    }

    private fun run(vararg command: String, dir: File = root): Pair<Int, String> {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).also {
            it.environment()["PATH"] = "${File(prefix, "bin").absolutePath}:${System.getenv("PATH")}"
            it.environment()["TMPDIR"] = tmp.absolutePath
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private val binaryPayload = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 1, 2) +
        termux.toByteArray() + byteArrayOf(0, 9)

    /** Builds a Termux-style package: #! preinst/postinst, a conffile and a binary carrying the prefix. */
    private fun buildPackage(withScripts: Boolean = true): File {
        val pkg = File(root, "pkg").apply { mkdirs() }
        val control = File(pkg, "DEBIAN").apply { mkdirs() }
        File(control, "control").writeText(
            "Package: demo\nVersion: 1.0\nArchitecture: all\nMaintainer: t <t@t>\nDescription: demo\n",
        )
        val etc = File(pkg, "data/data/com.termux/files/usr/etc").apply { mkdirs() }
        File(etc, "demo.conf").writeText("prefix=$termux\n")
        File(control, "conffiles").writeText("$termux/etc/demo.conf\n")
        File(pkg, "data/data/com.termux/files/usr/bin").mkdirs()
        File(pkg, "data/data/com.termux/files/usr/bin/demo").writeBytes(binaryPayload)
        if (withScripts) {
            File(control, "preinst").writeText("#!$termux/bin/sh\nexec $termux/bin/true\n")
            File(control, "postinst").writeText("#!$termux/bin/sh\n[ -d $termux/etc ] || exit 1\n")
        }
        // A real package's control directory is 0755 whatever the local umask.
        ProcessBuilder("chmod", "-R", "u+rwX,go+rX", pkg.absolutePath).start().waitFor()
        control.listFiles()!!.filter { it.name in setOf("preinst", "postinst") }.forEach { it.setExecutable(true, false) }
        val deb = File(root, "demo_1.0_all.deb")
        val (code, output) = run("dpkg-deb", "--root-owner-group", "-b", pkg.absolutePath, deb.absolutePath)
        assertEquals(output, 0, code)
        return deb
    }

    private fun relocate(deb: File): Pair<File, String> {
        val script = File(root, "relocate-deb").apply {
            writeText(RelocationScripts.relocateDeb(prefix.absolutePath, tmp.absolutePath))
        }
        val (code, output) = run("sh", script.absolutePath, deb.absolutePath, File(tmp, "out").absolutePath)
        assertEquals(output, 0, code)
        return File(output.trim().lines().last()) to output
    }

    private fun controlFile(deb: File, name: String): String = run("dpkg-deb", "--info", deb.absolutePath, name).second

    @Test fun rebuildsArchivesWhoseMaintainerScriptsUseTheTermuxPrefix() {
        val deb = buildPackage()
        val (relocated, log) = relocate(deb)
        assertNotEquals(deb.absolutePath, relocated.absolutePath)
        assertTrue(relocated.isFile)
        assertTrue(log.contains("relocated maintainer scripts"))

        val p = prefix.absolutePath
        assertEquals("#!$p/bin/sh\nexec $p/bin/true\n", controlFile(relocated, "preinst"))
        assertEquals("#!$p/bin/sh\n[ -d $p/etc ] || exit 1\n", controlFile(relocated, "postinst"))
        // conffiles must keep Termux's spelling: dpkg resolves it inside its relocated root.
        assertEquals("$termux/etc/demo.conf", controlFile(relocated, "conffiles").trim())

        // Data, including binaries embedding the old prefix, is byte-for-byte unchanged.
        val extracted = File(root, "extracted")
        assertEquals(0, run("dpkg-deb", "-x", relocated.absolutePath, extracted.absolutePath).first)
        assertArrayEquals(binaryPayload, File(extracted, "data/data/com.termux/files/usr/bin/demo").readBytes())
        assertEquals("prefix=$termux\n", File(extracted, "data/data/com.termux/files/usr/etc/demo.conf").readText())
    }

    @Test fun leavesCleanArchivesUntouched() {
        val deb = buildPackage(withScripts = false)
        val (relocated, _) = relocate(deb)
        assertEquals(deb.absolutePath, relocated.absolutePath)
    }

    @Test fun passesThroughNonArchives() {
        val (relocated, _) = relocate(File(root, "not-there.deb"))
        assertEquals(File(root, "not-there.deb").absolutePath, relocated.absolutePath)
    }

    @Test fun relocatesShebangsSymlinkedScriptsAndInstalledMaintainerScripts() {
        assumeTrue(which("find") != null)
        val bin = File(prefix, "bin")
        val npmCli = File(prefix, "lib/node_modules/npm/bin/npm-cli.js").apply { parentFile.mkdirs() }
        npmCli.writeText("#!$termux/bin/env node\nrequire('$termux/lib/x')\n")
        Files.createSymbolicLink(File(bin, "npm").toPath(), File("../lib/node_modules/npm/bin/npm-cli.js").toPath())
        val tool = File(bin, "tool").apply { writeText("#!$termux/bin/bash\necho $termux\n") }
        val elf = File(bin, "elfthing").apply { writeBytes(binaryPayload) }
        val info = File(prefix, "var/lib/dpkg/info").apply { mkdirs() }
        val prerm = File(info, "demo.prerm").apply { writeText("#!$termux/bin/sh\nrm -f $termux/etc/x\n") }
        val list = File(info, "demo.list").apply { writeText("$termux/bin/tool\n") }

        val script = File(root, "relocate-shebangs").apply {
            writeText(RelocationScripts.relocateShebangs(prefix.absolutePath))
        }
        val (code, output) = run("sh", script.absolutePath, "--all")
        assertEquals(output, 0, code)

        assertTrue(Files.isSymbolicLink(File(bin, "npm").toPath()))
        assertEquals("#!${prefix.absolutePath}/bin/env node", npmCli.readLines().first())
        // Only the #! line of commands changes; their bodies are left alone.
        assertEquals("require('$termux/lib/x')", npmCli.readLines()[1])
        assertEquals(listOf("#!${prefix.absolutePath}/bin/bash", "echo $termux"), tool.readLines())
        assertArrayEquals(binaryPayload, elf.readBytes())
        assertEquals("#!${prefix.absolutePath}/bin/sh\nrm -f ${prefix.absolutePath}/etc/x\n", prerm.readText())
        assertEquals("$termux/bin/tool\n", list.readText())
    }

    @Test fun scanMarkerModeOnlyTouchesNewerFiles() {
        val bin = File(prefix, "bin")
        val old = File(bin, "old").apply { writeText("#!$termux/bin/sh\n") }
        Thread.sleep(1_100)
        val marker = File(tmp, "marker").apply { writeText("") }
        Thread.sleep(1_100)
        val fresh = File(bin, "fresh").apply { writeText("#!$termux/bin/sh\n") }
        val script = File(root, "relocate-shebangs").apply {
            writeText(RelocationScripts.relocateShebangs(prefix.absolutePath))
        }
        assertEquals(0, run("sh", script.absolutePath, marker.absolutePath).first)
        assertTrue(old.readText().startsWith("#!$termux"))
        assertTrue(fresh.readText().startsWith("#!${prefix.absolutePath}"))
    }

    @Test fun wrapperRelocatesOnlyForInstallOperations() {
        val wrapper = RelocationScripts.dpkgWrapper("/p", "/t", "/p/bin/dpkg.real", "/r", "/p/bin/rs", "/p/bin/rd")
        assertTrue(wrapper.contains("-i|--install|--unpack)"))
        assertTrue(wrapper.contains("--force-script-chrootless --root=\"/r\" --admindir=\"/r/var/lib/dpkg\""))
        assertTrue(wrapper.indexOf("/p/bin/rd") < wrapper.indexOf("\"/p/bin/dpkg.real\""))
        assertTrue(wrapper.indexOf("\"/p/bin/rs\" \"\$scan_marker\"") > wrapper.indexOf("\"/p/bin/dpkg.real\""))
    }
}
