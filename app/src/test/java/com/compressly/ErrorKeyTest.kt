package com.compressly

import com.compressly.core.engine.CompressionCancelledException
import com.compressly.core.engine.audio.AudioCompressionException
import com.compressly.core.engine.errorKeyOf
import com.compressly.core.engine.photo.PhotoCompressionException
import com.compressly.core.engine.video.GifConversionException
import com.compressly.core.engine.video.VideoCompressionException
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * H1 (test gap) — the engine -> UI error mapping (audit item A5).
 *
 * Every failure the user sees is one of these keys; two of them were wrong in
 * the shipped app: a refused SAF grant was reported as "file not found" (it is
 * a permission problem) and a full volume as a generic write error (the
 * "no_space" string existed but nothing ever produced it).
 */
class ErrorKeyTest {

    @Test
    fun cancelled_isItsOwnKey() {
        assertEquals("cancelled", errorKeyOf(CompressionCancelledException()))
    }

    @Test
    fun refusedGrant_isPermissionDeniedAndNotFileNotFound() {
        // A5: the file was right where the user left it.
        assertEquals("permission_denied", errorKeyOf(SecurityException("no grant for content://x")))
    }

    @Test
    fun missingFile_isFileNotFound() {
        assertEquals("file_not_found", errorKeyOf(FileNotFoundException("content://x")))
    }

    @Test
    fun fullVolume_isNoSpace() {
        assertEquals("no_space", errorKeyOf(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun fullVolume_detectedThroughACauseChain() {
        val wrapped = IOException("copy failed", IOException("No space left on device"))
        assertEquals("no_space", errorKeyOf(wrapped))
    }

    @Test
    fun plainIoFailure_isOutput() {
        assertEquals("output", errorKeyOf(IOException("unexpected end of stream")))
    }

    @Test
    fun engineExceptionsKeepTheirOwnKey() {
        assertEquals("photo_decode", errorKeyOf(PhotoCompressionException("photo_decode")))
        assertEquals("audio_encode", errorKeyOf(AudioCompressionException("audio_encode")))
        assertEquals("video_encode", errorKeyOf(VideoCompressionException("video_encode")))
        assertEquals("gif_decode", errorKeyOf(GifConversionException("gif_decode")))
    }

    @Test
    fun illegalState_isEncodeFailed() {
        assertEquals("encode_failed", errorKeyOf(IllegalStateException("codec released")))
    }

    @Test
    fun unknownFailure_isGeneric() {
        assertEquals("generic", errorKeyOf(RuntimeException("???")))
    }

    @Test
    fun encodeFailedWinsOverTheGenericIOExceptionBranch() {
        // Ordering guard: IllegalStateException and IOException are unrelated
        // types, but MediaCodec failures are wrapped in both shapes in the wild.
        assertEquals("encode_failed", errorKeyOf(IllegalStateException(IOException("codec"))))
    }
}
