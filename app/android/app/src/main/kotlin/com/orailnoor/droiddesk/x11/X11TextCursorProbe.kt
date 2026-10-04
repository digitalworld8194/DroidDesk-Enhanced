package com.orailnoor.droiddesk.x11

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detector X11 interno de DroidDesk.
 *
 * Consulta directamente XFixes por el socket privado X0.
 * No ejecuta Python, ProcessBuilder, Termux API ni otra aplicación.
 * Solo se ejecuta una vez después de un tap/click relevante.
 */
class X11TextCursorProbe(context: Context) {

    private val socketFile =
        File(context.applicationContext.filesDir, "tmp/.X11-unix/X0")

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    fun probe(callback: (Boolean) -> Unit) {
        if (!busy.compareAndSet(false, true)) return

        executor.execute {
            val result = try {
                query()
            } catch (t: Throwable) {
                Log.w(TAG, "X11 internal cursor probe failed", t)
                false
            } finally {
                busy.set(false)
            }

            callback(result)
        }
    }

    fun dispose() {
        executor.shutdownNow()
    }

    private fun query(): Boolean {
        if (!socketFile.exists()) return false

        val socket = LocalSocket()

        try {
            socket.soTimeout = 750

            socket.connect(
                LocalSocketAddress(
                    socketFile.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                )
            )

            val input = socket.inputStream
            val output = socket.outputStream

            // X11 connection setup: LE, protocol 11.0, no auth.
            output.write(
                byteArrayOf(
                    'l'.code.toByte(), 0,
                    11, 0,
                    0, 0,
                    0, 0,
                    0, 0,
                    0, 0,
                )
            )
            output.flush()

            val setup = readFully(input, 8)

            if (u8(setup, 0) != 1)
                return false

            val setupExtra = u16(setup, 6) * 4
            if (setupExtra > 0)
                readFully(input, setupExtra)

            val xfixesOpcode =
                queryExtension(input, output)
                    ?: return false

            // XFixesQueryVersion 5.0
            val versionReq = ByteArray(12)
            versionReq[0] = xfixesOpcode.toByte()
            versionReq[1] = 0
            put16(versionReq, 2, 3)
            put32(versionReq, 4, 5)
            put32(versionReq, 8, 0)

            output.write(versionReq)
            output.flush()

            val versionReply = readFully(input, 32)
            if (u8(versionReply, 0) != 1)
                return false

            // XFixesGetCursorImage = minor opcode 4.
            output.write(
                byteArrayOf(
                    xfixesOpcode.toByte(),
                    4,
                    1,
                    0,
                )
            )
            output.flush()

            val reply = readFully(input, 32)

            if (u8(reply, 0) != 1)
                return false

            val units = u32(reply, 4)

            if (units > MAX_REPLY_UNITS)
                return false

            val width = u16(reply, 12)
            val height = u16(reply, 14)

            val count = width.toLong() * height.toLong()

            if (count <= 0 || count > MAX_PIXELS)
                return false

            val payloadBytes = units * 4L

            if (payloadBytes > MAX_REPLY_BYTES)
                return false

            val payload =
                readFully(input, payloadBytes.toInt())

            if (payload.size < count * 4L)
                return false

            var hash = FNV_OFFSET

            repeat(count.toInt()) { index ->
                hash = hash xor u32(payload, index * 4)
                hash *= FNV_PRIME
            }

            val signature =
                java.lang.Long
                    .toUnsignedString(hash, 16)
                    .padStart(16, '0')

            val text =
                width == 9 &&
                height == 16 &&
                signature == TEXT_SIGNATURE

            Log.d(
                TAG,
                "cursor=${width}x$height sig=$signature text=$text"
            )

            return text

        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun queryExtension(
        input: InputStream,
        output: java.io.OutputStream,
    ): Int? {
        val name = "XFIXES".toByteArray(Charsets.US_ASCII)
        val pad = (4 - (name.size and 3)) and 3
        val length = (8 + name.size + pad) / 4

        val req = ByteArray(8)

        req[0] = 98
        put16(req, 2, length)
        put16(req, 4, name.size)

        output.write(req)
        output.write(name)

        repeat(pad) {
            output.write(0)
        }

        output.flush()

        val reply = readFully(input, 32)

        if (u8(reply, 0) != 1)
            return null

        if (u8(reply, 8) == 0)
            return null

        return u8(reply, 9)
    }

    private fun readFully(
        input: InputStream,
        size: Int,
    ): ByteArray {
        require(size in 0..MAX_REPLY_BYTES.toInt())

        val result = ByteArray(size)
        var offset = 0

        while (offset < size) {
            val n = input.read(
                result,
                offset,
                size - offset,
            )

            if (n < 0)
                error("Unexpected EOF from X11")

            offset += n
        }

        return result
    }

    private fun u8(b: ByteArray, o: Int): Int =
        b[o].toInt() and 0xff

    private fun u16(b: ByteArray, o: Int): Int =
        u8(b, o) or
            (u8(b, o + 1) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        u8(b, o).toLong() or
            (u8(b, o + 1).toLong() shl 8) or
            (u8(b, o + 2).toLong() shl 16) or
            (u8(b, o + 3).toLong() shl 24)

    private fun put16(
        b: ByteArray,
        o: Int,
        value: Int,
    ) {
        b[o] = value.toByte()
        b[o + 1] = (value ushr 8).toByte()
    }

    private fun put32(
        b: ByteArray,
        o: Int,
        value: Int,
    ) {
        b[o] = value.toByte()
        b[o + 1] = (value ushr 8).toByte()
        b[o + 2] = (value ushr 16).toByte()
        b[o + 3] = (value ushr 24).toByte()
    }

    companion object {
        private const val TAG = "X11TextCursorProbe"

        private const val TEXT_SIGNATURE =
            "8dbe01a416814055"

        private const val FNV_OFFSET =
            1469598103934665603L

        private const val FNV_PRIME =
            1099511628211L

        private const val MAX_PIXELS =
            1_048_576L

        private const val MAX_REPLY_UNITS =
            1_048_576L

        private const val MAX_REPLY_BYTES =
            4_194_304L
    }
}
