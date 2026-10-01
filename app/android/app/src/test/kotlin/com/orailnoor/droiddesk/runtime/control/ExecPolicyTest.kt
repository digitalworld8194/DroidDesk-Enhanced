package com.orailnoor.droiddesk.runtime.control

import com.orailnoor.droiddesk.runtime.control.ExecPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecPolicyTest {
    private val prefix = "/data/user/0/com.orailnoor.droiddesk.preview/files/usr"
    private val home = "/data/user/0/com.orailnoor.droiddesk.preview/files/home"
    private val policy = ExecPolicy(listOf(prefix, home, "/data/user/0/com.orailnoor.droiddesk.preview/files"))

    @Test fun allowsOrdinaryCommands() {
        listOf(
            "node -v", "npm -v", "npm install -g typescript", "ls -la ~", "apt list --installed",
            "apt-get install -y git", "pkg install python", "dpkg --audit", "rm -rf ~/build", "rm -rf node_modules",
            "rm -r \$PREFIX/tmp/cache", "echo upgrade", "git pull && npm run build",
        ).forEach { assertEquals(it, Decision.Allowed, policy.check(it, force = false)) }
    }

    @Test fun massUpgradesAndRemovalsNeedForce() {
        listOf(
            "apt upgrade", "apt-get -y full-upgrade", "apt dist-upgrade", "pkg upgrade -y",
            "apt-get update && apt-get upgrade -y", "apt remove nodejs", "apt-get purge npm",
            "apt autoremove", "pkg uninstall nodejs", "dpkg --purge npm", "dpkg -r nodejs",
        ).forEach { command ->
            assertTrue(command, policy.check(command, force = false) is Decision.NeedsForce)
            assertEquals(command, Decision.Allowed, policy.check(command, force = true))
        }
    }

    @Test fun destructiveCommandsAreDeniedEvenWithForce() {
        listOf(
            "pm clear com.orailnoor.droiddesk.preview", "pm uninstall com.orailnoor.droiddesk",
            "cmd package uninstall com.orailnoor.droiddesk", "rm -rf /", "rm -rf ~", "rm -fr \$HOME",
            "rm -rf \$PREFIX", "rm -rf \${PREFIX}/", "rm --recursive --force $prefix", "rm -Rf $home/",
            "cd /tmp; rm -rf /data/user/0/com.orailnoor.droiddesk.preview/files", "/system/bin/rm -rf /sdcard",
            "mkfs.ext4 /dev/block/sda", "dd if=/dev/zero of=/dev/block/sda", "reboot",
        ).forEach { command ->
            assertTrue(command, policy.check(command, force = true) is Decision.Denied)
        }
    }

    @Test fun rejectsEmptyOrNul() {
        assertTrue(policy.check("   ", force = true) is Decision.Denied)
        assertTrue(policy.check("ls\u0000", force = true) is Decision.Denied)
    }
}
