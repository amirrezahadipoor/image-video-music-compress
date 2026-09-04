package com.compressly.core.engine.video

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * MP4 fast-start (moov-before-mdat) relocator.
 *
 * Android's [android.media.MediaMuxer] always writes the `moov` box AFTER the
 * `mdat` box, so the output is not "streamable": a player can't show anything
 * until the moov (which is at the end) has been read. moov-before-mdat — the
 * "fast start" flag ffmpeg produces with `-movflags +faststart` — lets a
 * player begin playback as soon as it has the box structure.
 *
 * This is a dependency-free remuxer: it parses the ISO-BMFF top-level box
 * list, reorders so `moov` sits right after the lead box(es), and patches the
 * chunk offset tables (`stco` = 32-bit, `co64` = 64-bit) by the amount the
 * `mdat` payload moved. It streams the bulk data (mdat) rather than loading it
 * into memory, so it is safe for multi-hundred-MB files. It is NOT a re-encode:
 * the media bytes are byte-identical, only the container order changes.
 *
 * Best-effort by contract: if the input is not a parseable MP4, or relocating
 * would overflow a 32-bit chunk offset, [remux] returns `false` and the caller
 * simply keeps the original file (which is still a valid MP4).
 */
object Mp4FastStart {

    private const val HEADER = 8 // 4-byte size + 4-byte type

    private class TopBox(val type: String, val start: Long, val size: Long)

    /** Is `moov` already before `mdat` (i.e. already fast-start)? */
    private fun isFastStart(boxes: List<TopBox>): Boolean {
        val moov = boxes.indexOfFirst { it.type == "moov" }
        val mdat = boxes.indexOfFirst { it.type == "mdat" }
        if (moov < 0 || mdat < 0) return true // nothing to do / not MP4
        return moov < mdat
    }

    /**
     * Rewrites [src] into [dst] with `moov` placed before `mdat`.
     *
     * @return `true` when [dst] is a valid fast-start MP4 (or [src] was already
     *   fast-start and was copied verbatim); `false` when it could not be done
     *   and [dst] is not written (the caller keeps [src]).
     */
    fun remux(src: File, dst: File): Boolean {
        val boxes = try { walk(src) } catch (_: Exception) { return false }
        if (boxes.isEmpty()) return false // not a parseable MP4 at all
        if (isFastStart(boxes)) {
            // Already streamable — copy verbatim so the caller always ends up
            // with one canonical file.
            return try { src.copyTo(dst, overwrite = true); true } catch (_: Exception) { false }
        }
        val moovIdx = boxes.indexOfFirst { it.type == "moov" }
        val mdatIdx = boxes.indexOfFirst { it.type == "mdat" }
        if (moovIdx < 0 || mdatIdx < 0) return false

        val moov = boxes[moovIdx]
        val mdat = boxes[mdatIdx]

        // New layout: [boxes before mdat][moov][mdat + trailing].
        val leadBytes = mdat.start                    // bytes [0, mdat.start) = lead boxes
        val moovNewStart = leadBytes
        val tailStartOld = mdat.start
        val mdatNewStart = moovNewStart + moov.size
        val delta = mdatNewStart - tailStartOld       // how far the mdat payload moved

        // Read moov (at most a few MB), patch its chunk offsets, then stream
        // the rest. mdat itself is never held in memory.
        val moovBytes = ByteArray(moov.size.toInt())
        try {
            RandomAccessFile(src, "r").use { raf ->
                raf.seek(moov.start)
                raf.readFully(moovBytes)
            }
            if (!patchChunkOffsets(moovBytes, delta)) return false
        } catch (_: Exception) {
            return false
        }

        // Tail must EXCLUDE the moov we just moved: copy [mdat .. moov) and then
        // anything AFTER moov (trailing boxes). Copying [mdat .. EOF) would
        // duplicate moov and corrupt the file.
        val seg1End = moov.start
        val seg2Start = moov.start + moov.size
        try {
            RandomAccessFile(src, "r").use { raf ->
                if (leadBytes > 0) streamCopy(raf, dst, 0L, leadBytes)
                appendBytes(dst, moovBytes)
                streamCopy(raf, dst, tailStartOld, seg1End - tailStartOld) // mdat & boxes up to moov
                val trailing = raf.length() - seg2Start
                if (trailing > 0) streamCopy(raf, dst, seg2Start, trailing)
            }
        } catch (_: Exception) {
            if (dst.exists()) dst.delete()
            return false
        }
        return true
    }

    /** Parse the top-level box list from [src]. */
    private fun walk(src: File): List<TopBox> {
        val boxes = mutableListOf<TopBox>()
        RandomAccessFile(src, "r").use { raf ->
            val len = raf.length()
            var pos = 0L
            while (pos + HEADER <= len) {
                raf.seek(pos)
                var size = readU32(raf).toLong()
                val tb = ByteArray(4); raf.readFully(tb)
                val type = String(tb, Charsets.ISO_8859_1)
                if (size == 1L) {
                    size = raf.readLong()          // 64-bit largesize
                } else if (size == 0L) {
                    size = len - pos               // box extends to EOF
                }
                if (size < HEADER || pos + size > len) break // malformed
                boxes += TopBox(type, pos, size)
                pos += size
            }
        }
        return boxes
    }

    /** Append [n] bytes starting at [from] (absolute file offset) to [dst]. */
    private fun streamCopy(raf: RandomAccessFile, dst: File, from: Long, n: Long) {
        val ch = raf.channel
        ch.position(from)
        val out = FileOutputStream(dst, true)
        val buf = ByteBuffer.allocate(256 * 1024)
        try {
            var remaining = n
            while (remaining > 0) {
                buf.clear()
                val read = ch.read(buf)
                if (read < 0) break
                buf.flip()
                out.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining())
                remaining -= read
            }
        } finally {
            out.close()
        }
    }

    private fun appendBytes(dst: File, b: ByteArray) {
        FileOutputStream(dst, true).use { it.write(b) }
    }

    /**
     * Recursively walk the (in-memory) `moov` box and add [delta] to every
     * chunk offset in any `stco` / `co64` box it finds. Returns false if a
     * 32-bit offset would overflow.
     */
    private fun patchChunkOffsets(moov: ByteArray, delta: Long): Boolean =
        patchAt(moov, 0, moov.size, delta)

    private fun patchAt(b: ByteArray, start: Int, end: Int, delta: Long): Boolean {
        var pos = start
        while (pos + HEADER <= end) {
            val size = readU32b(b, pos).toLong()
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            if (size < HEADER) break
            val boxEnd = (pos + size).toInt().coerceAtMost(end)
            when (type) {
                "stco" -> if (!patchStco(b, pos + 8, boxEnd, delta)) return false
                "co64" -> if (!patchCo64(b, pos + 8, boxEnd, delta)) return false
                else -> if (!patchAt(b, pos + 8, boxEnd, delta)) return false
            }
            pos = boxEnd
            if (size <= HEADER) break
        }
        return true
    }

    private fun patchStco(b: ByteArray, dataStart: Int, boxEnd: Int, delta: Long): Boolean {
        if (dataStart + 8 > boxEnd) return true
        val count = readU32b(b, dataStart + 4).toInt()
        var p = dataStart + 8
        repeat(count) {
            if (p + 4 > boxEnd) return true
            val off = readU32b(b, p).toLong()
            val newOff = off + delta
            if (newOff < 0 || newOff > 0xFFFFFFFFL) return false // would overflow
            writeU32b(b, p, newOff.toInt())
            p += 4
        }
        return true
    }

    private fun patchCo64(b: ByteArray, dataStart: Int, boxEnd: Int, delta: Long): Boolean {
        if (dataStart + 8 > boxEnd) return true
        val count = readU32b(b, dataStart + 4).toInt()
        var p = dataStart + 8
        repeat(count) {
            if (p + 8 > boxEnd) return true
            val off = readS64b(b, p)
            writeS64b(b, p, off + delta)
            p += 8
        }
        return true
    }

    private fun readU32(raf: RandomAccessFile): Int = raf.readInt()

    private fun readU32b(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun writeU32b(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }

    private fun readS64b(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun writeS64b(b: ByteArray, i: Int, v: Long) {
        for (k in 0 until 8) b[i + k] = (v ushr (56 - 8 * k)).toByte()
    }
}
