package com.qtekfun.ultimatevideoeditor.ui.preview

import com.qtekfun.ultimatevideoeditor.engine.preview.ColorMode
import com.qtekfun.ultimatevideoeditor.engine.preview.OutputSpace
import com.qtekfun.ultimatevideoeditor.engine.preview.SourceColor
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayHdrTest {

    @Test
    fun `HLG preview needs both an HDR project and an HDR screen`() {
        assertEquals(OutputSpace.HLG_2020, wantedOutputSpace(projectIsHdr = true, displaySupportsHlg = true))
        assertEquals(OutputSpace.SDR_709, wantedOutputSpace(projectIsHdr = true, displaySupportsHlg = false))
        assertEquals(OutputSpace.SDR_709, wantedOutputSpace(projectIsHdr = false, displaySupportsHlg = true))
        assertEquals(OutputSpace.SDR_709, wantedOutputSpace(projectIsHdr = false, displaySupportsHlg = false))
    }

    @Test
    fun `output space values mirror the native enum`() {
        assertEquals(0, OutputSpace.SDR_709.value)
        assertEquals(1, OutputSpace.HLG_2020.value)
        assertEquals(OutputSpace.HLG_2020, OutputSpace.fromValue(1))
        assertEquals(OutputSpace.SDR_709, OutputSpace.fromValue(0))
        assertEquals(OutputSpace.SDR_709, OutputSpace.fromValue(99))
    }

    @Test
    fun `colour mode values mirror the native enum and know their source`() {
        val expected = listOf(
            ColorMode.Sdr709 to 0, ColorMode.Hlg2020ToSdr709 to 1, ColorMode.Sdr709ToHlg2020 to 2,
            ColorMode.Hlg2020 to 3, ColorMode.Pq2020ToSdr709 to 4, ColorMode.Pq2020ToHlg2020 to 5,
        )
        for ((mode, value) in expected) assertEquals(value, mode.value)
        assertEquals(SourceColor.SDR, ColorMode.Sdr709ToHlg2020.sourceClass)
        assertEquals(SourceColor.HLG, ColorMode.Hlg2020.sourceClass)
        assertEquals(SourceColor.PQ, ColorMode.Pq2020ToHlg2020.sourceClass)
    }
}
