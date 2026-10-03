package com.orailnoor.droiddesk.runtime

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * Uses Settings observers instead of polling every few seconds. The observer
 * only reacts while the process is alive and not frozen, so a content-trigger
 * job (see [WirelessAdbJobService]) covers the cached/killed case.
 */
object WirelessAdbKeeper {
    private const val TAG = "WirelessAdbKeeper"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
    private const val CONTENT_JOB_ID = 0x0ADB
    private const val WIFI_JOB_ID = 0x0ADC
    private const val RETRY_JOB_ID = 0x0ADD
    private const val PREFS = "wireless_adb_keeper"
    private const val KEY_LAST_RESTORE = "last_restore_at"
    private const val KEY_STREAK = "restore_streak"

    private val lock = Any()
    private var observer: ContentObserver? = null

    fun start(context: Context) {
        val app = context.applicationContext

        ensureEnabled(app)
        scheduleContentJob(app)

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

    fun scheduleContentJob(context: Context) {
        try {
            val job = JobInfo.Builder(
                CONTENT_JOB_ID,
                ComponentName(context, WirelessAdbJobService::class.java),
            )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(Settings.Global.getUriFor(ADB_WIFI_ENABLED), 0),
                )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        Settings.Global.getUriFor(ADB_ALLOWED_CONNECTION_TIME),
                        0,
                    ),
                )
                .setTriggerContentUpdateDelay(0)
                .setTriggerContentMaxDelay(1000)
                .build()

            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "Wireless ADB content job was not scheduled")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to schedule Wireless ADB content job", error)
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
                restoreWirelessDebugging(context)
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

    private fun restoreWirelessDebugging(context: Context) {
        // Device-protected so it also works from LOCKED_BOOT_COMPLETED.
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Android refuses Wireless debugging without Wi-Fi and immediately
        // writes 0 back, so wait for a Wi-Fi network instead of fighting it.
        if (!isOnWifi(context)) {
            Log.i(TAG, "Wireless debugging is off and Wi-Fi is not connected; waiting for Wi-Fi")
            prefs.edit().putInt(KEY_STREAK, 0).apply()
            scheduleJobOnce(context, WIFI_JOB_ID) {
                setRequiredNetwork(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                )
            }
            return
        }

        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_RESTORE, 0L)
        val streak = WirelessAdbRestorePolicy.nextStreak(now, last, prefs.getInt(KEY_STREAK, 0))
        val wait = WirelessAdbRestorePolicy.waitMs(now, last, streak)

        if (wait > 0L) {
            Log.w(TAG, "Android keeps disabling Wireless debugging (streak $streak); retrying in ${wait / 1000}s")
            scheduleJobOnce(context, RETRY_JOB_ID) {
                setMinimumLatency(wait)
                setOverrideDeadline(wait + 5_000L)
            }
            return
        }

        Log.w(TAG, "Wireless debugging was disabled; restoring it")
        prefs.edit().putLong(KEY_LAST_RESTORE, now).putInt(KEY_STREAK, streak).apply()
        Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
    }

    private fun isOnWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun scheduleJobOnce(context: Context, id: Int, configure: JobInfo.Builder.() -> Unit) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(id) != null) return
            val job = JobInfo.Builder(id, ComponentName(context, WirelessAdbJobService::class.java))
                .apply(configure)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "Wireless ADB job $id was not scheduled")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to schedule Wireless ADB job $id", error)
        }
    }
}
