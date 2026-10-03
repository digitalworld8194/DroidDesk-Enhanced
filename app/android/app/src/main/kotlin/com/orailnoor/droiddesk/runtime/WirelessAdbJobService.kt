package com.orailnoor.droiddesk.runtime

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * Content-trigger job that restores Wireless debugging when DroidDesk is not
 * running or is cached/frozen, where the in-process ContentObserver cannot react.
 * Also runs the Wi-Fi-available and backoff-retry jobs of [WirelessAdbKeeper].
 *
 * Content-trigger jobs fire once, so every run re-arms the trigger.
 */
class WirelessAdbJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val uris = params?.triggeredContentUris?.joinToString()
        if (uris != null) {
            Log.i("WirelessAdbJob", "Settings change detected: $uris")
        } else {
            Log.i("WirelessAdbJob", "Wi-Fi/retry job ${params?.jobId} running")
        }
        WirelessAdbKeeper.ensureEnabled(applicationContext)
        WirelessAdbKeeper.scheduleContentJob(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
