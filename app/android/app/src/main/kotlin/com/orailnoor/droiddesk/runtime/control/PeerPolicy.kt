package com.orailnoor.droiddesk.runtime.control

/**
 * Who may talk to DroidDesk's private abstract Unix sockets (the Android app
 * launcher used by XFCE). Native-mode Linux processes run with the app's own
 * UID; rooted chroot sessions run as root, which can do anything anyway.
 * Every other app UID is refused.
 */
object PeerPolicy {
    const val ROOT_UID = 0

    fun isAuthorizedLauncherPeer(peerUid: Int, appUid: Int): Boolean =
        peerUid == appUid || peerUid == ROOT_UID
}
