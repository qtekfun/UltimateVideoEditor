package com.ultimatevideo.uveditor.engine

import com.ultimatevideo.uveditor.engine.preview.AssetInfo
import com.ultimatevideo.uveditor.engine.preview.PreviewErrorCode
import com.ultimatevideo.uveditor.engine.preview.PreviewException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTypesTest {
    @Test
    fun `native status codes map to typed errors`() {
        assertEquals(PreviewErrorCode.IoError, PreviewErrorCode.fromValue(2))
        assertEquals(PreviewErrorCode.UnsupportedFormat, PreviewErrorCode.fromValue(3))
        assertEquals(PreviewErrorCode.CodecError, PreviewErrorCode.fromValue(4))
        assertEquals(PreviewErrorCode.InvalidState, PreviewErrorCode.fromValue(9))
    }

    @Test
    fun `unknown native status is not silently dropped`() {
        assertEquals(PreviewErrorCode.Unknown, PreviewErrorCode.fromValue(12345))
        assertEquals(PreviewErrorCode.Unknown, PreviewErrorCode.fromValue(0))
    }

    @Test
    fun `exception exposes code and message`() {
        val e = PreviewException(4, "codec exploded")
        assertEquals(PreviewErrorCode.CodecError, e.errorCode)
        assertEquals("codec exploded", e.message)
    }

    @Test
    fun `HLG is detected from the container transfer`() {
        assertTrue(AssetInfo(1920, 1080, 10, 30, 1, colorTransfer = 7).isHlg)
        assertFalse(AssetInfo(1920, 1080, 10, 30, 1, colorTransfer = 0).isHlg)
        assertFalse(AssetInfo(1920, 1080, 10, 30, 1, colorTransfer = 6).isHlg) // ST2084/PQ
    }
}
