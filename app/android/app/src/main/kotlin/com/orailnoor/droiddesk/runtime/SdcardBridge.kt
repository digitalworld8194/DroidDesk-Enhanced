package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Exposes shared Android storage (/sdcard) inside the Linux home so files
 * taken with the Android camera, downloaded by the browser, or saved by
 * any Android app are immediately visible in Thunar, GIMP, editors, etc.
 *
 * Uses symlinks instead of bind mounts so it works without root. Every
 * Linux tool we care about follows symlinks transparently.
 */
object SdcardBridge {
    private const val TAG = "SdcardBridge"

    private data class Entry(val androidRel: String, val linkName: String)

    private val entries = listOf(
        Entry("DCIM",       "DCIM"),
        Entry("Pictures",   "Pictures"),
        Entry("Download",   "Downloads"),
        Entry("Documents",  "Documents"),
        Entry("Music",      "Music"),
        Entry("Movies",     "Movies"),
        Entry("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp"),
    )

    /** Create symlinks from [linuxHome] to /sdcard/<folder>. Safe to re-run. */
    fun setup(context: Context, linuxHome: File) {
        val shared = sharedRoot() ?: run {
            Log.w(TAG, "Shared storage not available; skipping /sdcard bridge")
            return
        }
        if (!linuxHome.isDirectory && !linuxHome.mkdirs()) {
            Log.w(TAG, "Cannot create Linux home at ${linuxHome.absolutePath}")
            return
        }
        var created = 0; var skipped = 0
        for (e in entries) {
            val source = File(shared, e.androidRel)
            if (!source.exists()) { skipped++; continue }
            val link = File(linuxHome, e.linkName)
            if (link.exists() || isSymlink(link)) { skipped++; continue }
            val ok = runCatching {
                android.system.Os.symlink(source.absolutePath, link.absolutePath)
            }.isSuccess
            if (ok) { created++; Log.i(TAG, "linked ${e.linkName} -> $source") }
            else    { Log.w(TAG, "failed ${e.linkName} -> $source") }
        }
        Log.i(TAG, "SdcardBridge: $created new, $skipped skipped")
    }

    /** Remove broken links (e.g. after storage move) then re-create. */
    fun refresh(context: Context, linuxHome: File) {
        for (e in entries) {
            val link = File(linuxHome, e.linkName)
            if (isSymlink(link) && !link.exists()) link.delete()
        }
        setup(context, linuxHome)
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        (android.system.Os.lstat(file.absolutePath).st_mode and 0xF000) == 0xA000
    }.getOrDefault(false)

    private fun sharedRoot(): File? = listOf(
        File("/storage/emulated/0"),
        File("/sdcard"),
        Environment.getExternalStorageDirectory(),
    ).firstOrNull { it.isDirectory && it.canRead() }
}
