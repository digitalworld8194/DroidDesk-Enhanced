package com.orailnoor.droiddesk.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts DroidDeskService on device boot so the Unix socket bridge is ready
 * before the user presses Home.
 *
 * Android 10+ forbids BroadcastReceivers from starting Activities directly —
 * we start the foreground service only; the actual DesktopActivity is deferred
 * to handleHomeLaunch() in MainActivity when the user first presses Home.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed — starting DroidDeskService")
        val serviceIntent = Intent(context, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
