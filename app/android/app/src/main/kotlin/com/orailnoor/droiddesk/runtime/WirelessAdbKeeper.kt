package com.orailnoor.droiddesk.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Keeps Android Wireless debugging enabled.
 *
 * WRITE_SECURE_SETTINGS is intentionally not silently obtainable by the app.
 * It is granted once through adb after installation.
 *
 * Uses Settings observers instead of polling every few seconds.
 */
object WirelessAdbKeeper {
    private const val TAG = "WirelessAdbKeeper"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    private val lock = Any()
    private var observer: ContentObserver? = null

    fun start(context: Context) {
        val app = context.applicationContext

        ensureEnabled(app)

        synchronized(lock) {
            if (observer != null) return

            val handler = Handler(Looper.getMainLooper())

            val created = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    ensureEnabled(app)
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    ensureEnabled(app)
                }
            }

            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(ADB_WIFI_ENABLED),
                false,
                created,
            )

            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(ADB_ALLOWED_CONNECTION_TIME),
                false,
                created,
            )

            observer = created
            Log.i(TAG, "Wireless ADB observer registered")
        }
    }

    fun ensureEnabled(context: Context): Boolean {
        if (
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS has not been granted")
            return false
        }

        return try {
            val resolver = context.contentResolver

            val wirelessEnabled = Settings.Global.getInt(
                resolver,
                ADB_WIFI_ENABLED,
                0,
            )

            if (wirelessEnabled != 1) {
                Log.w(TAG, "Wireless debugging was disabled; restoring it")
                Settings.Global.putInt(
                    resolver,
                    ADB_WIFI_ENABLED,
                    1,
                )
            }

            val timeout = Settings.Global.getLong(
                resolver,
                ADB_ALLOWED_CONNECTION_TIME,
                -1L,
            )

            if (timeout != 0L) {
                Settings.Global.putLong(
                    resolver,
                    ADB_ALLOWED_CONNECTION_TIME,
                    0L,
                )
            }

            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to maintain Wireless debugging permission", error)
            false
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to maintain Wireless debugging state", error)
            false
        }
    }
}
