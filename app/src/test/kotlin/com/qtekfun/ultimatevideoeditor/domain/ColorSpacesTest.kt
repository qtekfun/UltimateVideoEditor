package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSpacesTest {

    @Test
    fun `project colour space ids round trip`() {
        for (space in ProjectColorSpace.entries) assertEquals(space, ProjectColorSpace.fromId(space.id))
    }

    @Test
    fun `unknown or missing project colour space reads as SDR`() {
        assertEquals(ProjectColorSpace.REC709_SDR, ProjectColorSpace.fromId(null))
        assertEquals(ProjectColorSpace.REC709_SDR, ProjectColorSpace.fromId("DisplayP3"))
        assertEquals(ProjectColorSpace.REC2020_HLG, ProjectColorSpace.fromId("rec2020-hlg"))
    }

    @Test
    fun `only the HLG project space is HDR`() {
        assertTrue(ProjectColorSpace.REC2020_HLG.isHdr)
        assertFalse(ProjectColorSpace.REC709_SDR.isHdr)
    }

    @Test
    fun `media colour spaces map to the native source class`() {
        assertEquals(SourceColorSpace.SDR, SourceColorSpace.fromId("Rec709-SDR"))
        assertEquals(SourceColorSpace.HLG, SourceColorSpace.fromId("Rec2020-HLG"))
        assertEquals(SourceColorSpace.PQ, SourceColorSpace.fromId("Rec2020-PQ"))
        assertEquals(SourceColorSpace.SDR, SourceColorSpace.fromId(null))
        assertEquals(SourceColorSpace.SDR, SourceColorSpace.fromId("something else"))
    }

    @Test
    fun `native values of the source classes are the render ColorMode source modes`() {
        // uv::render::ColorMode: 0 SDR, 1 HLG -> SDR, 4 PQ -> SDR; the engine re-derives the target.
        assertEquals(0, SourceColorSpace.SDR.nativeModeValue)
        assertEquals(1, SourceColorSpace.HLG.nativeModeValue)
        assertEquals(4, SourceColorSpace.PQ.nativeModeValue)
    }
}
