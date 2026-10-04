package com.orailnoor.droiddesk.x11

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs a short one-shot XFixes query inside DroidDesk's private
 * Termux-compatible runtime.
 *
 * The bundled/core X11 text cursor has a stable pixel signature in the
 * DroidDesk desktop environment. The probe only runs after an actual tap or
 * primary mouse click, so merely hovering over text never opens Android's IME.
 */
class X11TextCursorProbe(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val prefix = File(filesDir, "usr")
    private val home = File(filesDir, "home")
    private val tmp = File(filesDir, "tmp")
    private val python = File(prefix, "bin/python3")
    private val socketHook = File(prefix, "lib/libsocket_hook.so")
    private val probeFile = File(tmp, ".droiddesk_text_cursor_probe.py")

    private val executor = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)

    fun probe(callback: (Boolean) -> Unit) {
        if (!inFlight.compareAndSet(false, true)) return

        executor.execute {
            val result = try {
                queryTextCursor()
            } catch (error: Throwable) {
                Log.w(TAG, "X11 text-cursor probe failed", error)
                false
            } finally {
                inFlight.set(false)
            }

            callback(result)
        }
    }

    fun dispose() {
        executor.shutdownNow()
    }

    private fun queryTextCursor(): Boolean {
        if (!python.canExecute() || !socketHook.isFile) {
            Log.w(TAG, "Cursor probe unavailable: python=${python.canExecute()} hook=${socketHook.isFile}")
            return false
        }

        tmp.mkdirs()

        if (!probeFile.isFile || probeFile.readText() != SCRIPT) {
            probeFile.writeText(SCRIPT)
        }

        val processBuilder = ProcessBuilder(
            python.absolutePath,
            probeFile.absolutePath,
        )

        processBuilder.redirectErrorStream(true)

        processBuilder.environment().apply {
            put("PREFIX", prefix.absolutePath)
            put("TERMUX__PREFIX", prefix.absolutePath)
            put("HOME", home.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("DISPLAY", ":0")
            put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
            put("LD_PRELOAD", socketHook.absolutePath)
            put("PATH", "${File(prefix, "bin").absolutePath}:${File(prefix, "lib/xfce4/panel").absolutePath}:/system/bin")
        }

        val process = processBuilder.start()

        if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            Log.w(TAG, "X11 text-cursor probe timed out")
            return false
        }

        val output = process.inputStream
            .bufferedReader()
            .use { it.readText() }

        val isText = process.exitValue() == 0 &&
            output.lineSequence().any { it.trim() == "TEXT" }

        Log.d(TAG, "X11 cursor probe text=$isText")
        return isText
    }

    companion object {
        private const val TAG = "X11TextCursorProbe"
        private const val PROBE_TIMEOUT_MS = 1000L

        /*
         * Proven on the DroidDesk bundled/core cursor:
         *
         * text:
         *   9x16  8dbe01a416814055
         *
         * default pointer:
         *   10x16 bce895fd04b4a70b
         *
         * This same text signature was observed in both Xfce4 Terminal
         * and Firefox editable areas.
         */
        private val SCRIPT = """
import ctypes
import os
import sys

TEXT_SIGNATURES = {
    "8dbe01a416814055",
}

class CursorImage(ctypes.Structure):
    _fields_ = [
        ("x", ctypes.c_short),
        ("y", ctypes.c_short),
        ("width", ctypes.c_ushort),
        ("height", ctypes.c_ushort),
        ("xhot", ctypes.c_ushort),
        ("yhot", ctypes.c_ushort),
        ("cursor_serial", ctypes.c_ulong),
        ("pixels", ctypes.POINTER(ctypes.c_ulong)),
    ]

def signature(cursor):
    count = int(cursor.width) * int(cursor.height)

    if count <= 0 or not cursor.pixels:
        return "none"

    value = 1469598103934665603

    for index in range(count):
        value ^= int(cursor.pixels[index]) & 0xffffffffffffffff
        value = (value * 1099511628211) & 0xffffffffffffffff

    return format(value, "016x")

prefix = os.environ.get("PREFIX", "")

try:
    x11 = ctypes.CDLL(prefix + "/lib/libX11.so")
    xfixes = ctypes.CDLL(prefix + "/lib/libXfixes.so")

    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = ctypes.c_void_p

    x11.XFree.argtypes = [ctypes.c_void_p]
    x11.XFree.restype = ctypes.c_int

    x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    x11.XCloseDisplay.restype = ctypes.c_int

    xfixes.XFixesGetCursorImage.argtypes = [ctypes.c_void_p]
    xfixes.XFixesGetCursorImage.restype = ctypes.POINTER(CursorImage)

    display = x11.XOpenDisplay(b":0")

    if not display:
        print("OTHER")
        sys.exit(0)

    pointer = xfixes.XFixesGetCursorImage(display)

    if not pointer:
        x11.XCloseDisplay(display)
        print("OTHER")
        sys.exit(0)

    cursor = pointer.contents
    sig = signature(cursor)

    is_text = (
        int(cursor.width) == 9
        and int(cursor.height) == 16
        and sig in TEXT_SIGNATURES
    )

    x11.XFree(ctypes.cast(pointer, ctypes.c_void_p))
    x11.XCloseDisplay(display)

    print("TEXT" if is_text else "OTHER")

except Exception:
    print("OTHER")
""".trimIndent()
    }
}
