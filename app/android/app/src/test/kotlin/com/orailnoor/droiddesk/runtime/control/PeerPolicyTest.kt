package com.orailnoor.droiddesk.runtime.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** AndroidAppBridge: who may use the XFCE -> Android app launcher socket. */
class PeerPolicyTest {
    private val appUid = 10_321

    @Test fun acceptsOwnLinuxProcessesAndRootChroot() {
        assertTrue(PeerPolicy.isAuthorizedLauncherPeer(appUid, appUid))
        assertTrue(PeerPolicy.isAuthorizedLauncherPeer(0, appUid))
    }

    @Test fun rejectsOtherApps() {
        assertFalse(PeerPolicy.isAuthorizedLauncherPeer(10_528, appUid)) // e.g. Termux
        assertFalse(PeerPolicy.isAuthorizedLauncherPeer(2000, appUid))   // adb shell
        assertFalse(PeerPolicy.isAuthorizedLauncherPeer(1000, appUid))   // system
        assertFalse(PeerPolicy.isAuthorizedLauncherPeer(-1, appUid))
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/orailnoor/droiddesk/runtime/$name").readText()

    @Test fun launcherSocketChecksPeerCredentialsBeforeReadingACommand() {
        val serve = source("AndroidAppBridge.kt").substringAfter("private fun serve(")
            .substringBefore("private fun launchPackage(")
        val check = serve.indexOf("PeerPolicy.isAuthorizedLauncherPeer(")
        val read = serve.indexOf("readLine()")
        assertTrue("peer check missing", check > 0)
        assertTrue("peer check must happen before the command is read", check < read)
        assertTrue(serve.contains("peerCredentials"))
    }

    @Test fun termuxSettingsActionsMatchTheDesktopLauncherActions() {
        val bridge = source("AndroidAppBridge.kt").substringAfter("fun launchSystemAction(")
        val desktopActions = Regex("\"([a-z_]+)\" ->").findAll(bridge.substringBefore("runCatching {\n            context.startActivity(Intent(settingsAction)"))
            .map { it.groupValues[1] }.toSet()
        val termuxActions = ControlProtocol.SETTINGS_ACTIONS.keys + "samsung_home"
        assertEquals(desktopActions + "settings", termuxActions)
    }
}
