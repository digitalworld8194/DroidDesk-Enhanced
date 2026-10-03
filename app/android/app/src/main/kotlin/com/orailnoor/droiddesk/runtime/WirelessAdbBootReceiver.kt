package com.orailnoor.droiddesk.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * One-shot boot/update recovery for Wireless debugging.
 *
 * Deliberately does NOT start DroidDeskService here because Android 15+
 * restricts dataSync foreground services launched from BOOT_COMPLETED.
 */
class WirelessAdbBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pending = goAsync()

        thread(name = "wireless-adb-boot") {
            try {
                WirelessAdbKeeper.start(context)
                Log.i("WirelessAdbBoot", "Wireless debugging recovery checked: $action")
            } finally {
                pending.finish()
            }
        }
    }
}
