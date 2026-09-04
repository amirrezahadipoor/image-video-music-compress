package com.compressly

import com.compressly.core.engine.video.Mp4FastStart
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the fast-start (moov-before-mdat) MP4 remuxer.
 *
 * The real MediaMuxer always writes moov last; these tests build a minimal
 * ISO-BMFF stream that mirrors that shape (ftyp + mdat + moov, moov AFTER
 * mdat) and assert that remux reorders moov before mdat and patches the stco
 * chunk-offset table by the amount the mdat payload moved.
 */
class Mp4FastStartTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── minimal ISO-BMFF builder helpers (big-endian) ────────────────────
    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt(out, 8 + payload.size)          // size
        out.write(type.toByteArray(Charsets.ISO_8859_1)) // type
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v ushr 24); out.write(v ushr 16)
        out.write(v ushr 8); out.write(v)
    }

    private fun writeLong(out: ByteArrayOutputStream, v: Long) {
        for (k in 0 until 8) out.write(((v ushr (56 - 8 * k)) and 0xFFL).toInt())
    }

    private fun readInt(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun findBox(b: ByteArray, type: String): Pair<Int, Int>? {
        var pos = 0
        while (pos + 8 <= b.size) {
            val size = readInt(b, pos)
            val t = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            if (t == type) return pos to size
            if (size < 8) return null
            pos += size
        }
        return null
    }

    /** Build: ftyp + mdat(100 bytes) + moov(trak->stbl->stco with 2 chunks). */
    private fun buildNonFastStart(): ByteArray {
        // A real MP4 nests stco as a proper box (size+type) inside stbl. Build
        // it that way so the relocator's box-walk can find and patch it.
        val ftyp = box("ftyp", "isom".toByteArray())
        val mdatPayload = ByteArray(100) { 0xAB.toByte() }
        val mdat = box("mdat", mdatPayload)
        // mdat data (payload) starts right after ftyp + the 8-byte mdat header.
        val mdatDataOffset = ftyp.size + 8

        fun stcoBox(): ByteArray {
            val s = ByteArrayOutputStream()
            s.write(ByteArray(4))                 // version/flags
            writeInt(s, 2)                        // entry_count
            writeInt(s, mdatDataOffset)           // chunk 0 -> start of mdat data
            writeInt(s, mdatDataOffset + 50)      // chunk 1 -> +50 bytes
            return box("stco", s.toByteArray())
        }
        // Layout: ftyp + mdat + moov (moov AFTER mdat => NOT fast-start).
        val moov = box("moov", box("trak", box("stbl", stcoBox())))

        val out = ByteArrayOutputStream()
        out.write(ftyp)
        out.write(mdat)
        out.write(moov)
        return out.toByteArray()
    }

    @Test
    fun relocatesMoovBeforeMdatAndPatchesStco() {
        val src = tmp.newFile("in.mp4")
        src.writeBytes(buildNonFastStart())

        // sanity: in the source, mdat comes before moov.
        val preMdat = findBox(src.readBytes(), "mdat")!!
        val preMoov = findBox(src.readBytes(), "moov")!!
        assertTrue("source should be mdat-before-moov", preMdat.first < preMoov.first)

        val dst = tmp.newFile("out.mp4")
        assertTrue(Mp4FastStart.remux(src, dst))

        val out = dst.readBytes()
        val mdatIdx = findBox(out, "mdat")!!
        val moovIdx = findBox(out, "moov")!!
        assertTrue("moov should now come before mdat", moovIdx.first < mdatIdx.first)

        // mdat payload in the new file must be byte-identical (no re-encode).
        val newMdatDataOffset = mdatIdx.first + 8
        val expectedPayload = ByteArray(100) { 0xAB.toByte() }
        val actualPayload = out.copyOfRange(newMdatDataOffset, newMdatDataOffset + 100)
        assertEquals("mdat payload must be unchanged", expectedPayload.toList(), actualPayload.toList())

        // The stco offsets must have shifted by delta = newMdatStart - oldMdatStart.
        val oldMdatData = preMdat.first + 8
        val delta = newMdatDataOffset - oldMdatData
        assertEquals("delta must be exactly the moov size we moved", out.size - src.readBytes().size, 0)
        // read stco table from moov in dst and confirm offsets shifted by delta.
        val stcoEntries = readStco(out, moovIdx.first, moovIdx.second)
        assertEquals(2, stcoEntries.size)
        assertEquals(oldMdatData + delta, stcoEntries[0])
        assertEquals(oldMdatData + 50 + delta, stcoEntries[1])
    }

    /** Extract the entry_count + offsets from the first stco found under moov. */
    private fun readStco(b: ByteArray, moovStart: Int, moovSize: Int): List<Int> {
        val result = mutableListOf<Int>()
        fun walk(start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val size = readInt(b, pos)
                val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
                if (size < 8) return
                val boxEnd = (pos + size).coerceAtMost(end)
                if (type == "stco") {
                    val count = readInt(b, pos + 12)
                    var p = pos + 16
                    repeat(count) { result += readInt(b, p); p += 4 }
                } else {
                    walk(pos + 8, boxEnd)
                }
                pos = boxEnd
            }
        }
        walk(moovStart, moovStart + moovSize)
        return result
    }

    @Test
    fun alreadyFastStartIsLeftVerballyIdentical() {
        // Build an already-fast-start file: ftyp + moov + mdat.
        val ftyp = box("ftyp", "isom".toByteArray())
        val stco = ByteArrayOutputStream().apply { write(ByteArray(4)); writeInt(this, 1); writeInt(this, 0) }.toByteArray()
        val moov = box("moov", box("trak", box("stbl", stco)))
        val mdat = box("mdat", ByteArray(10) { 1 })
        val out = ByteArrayOutputStream()
        out.write(ftyp); out.write(moov); out.write(mdat)
        val src = tmp.newFile("fast.mp4"); src.writeBytes(out.toByteArray())
        val dst = tmp.newFile("fast_out.mp4")
        assertTrue(Mp4FastStart.remux(src, dst))
        // Already fast-start: remux copies verbatim -> byte-identical.
        assertEquals(src.readBytes().toList(), dst.readBytes().toList())
    }

    @Test
    fun nonMp4IsLeftUntouched() {
        val src = tmp.newFile("random.bin")
        src.writeBytes("this is not an mp4 file at all".toByteArray())
        // NOT tmp.newFile: the rule pre-creates the file, so exists() is always true.
        // remux must not create dst for a non-MP4, so use a path that doesn't exist.
        val dst = File(tmp.root, "random_out.bin")
        if (dst.exists()) dst.delete()
        // No parseable boxes -> must return false and leave dst absent.
        assertFalse(Mp4FastStart.remux(src, dst))
        assertFalse(dst.exists())
    }
}
