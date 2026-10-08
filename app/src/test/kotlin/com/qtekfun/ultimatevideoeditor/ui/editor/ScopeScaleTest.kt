package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.engine.preview.ScopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeScaleTest {

    @Test
    fun `an SDR waveform is labelled in percent from the top`() {
        assertEquals(listOf("100", "75", "50", "25", "0"), ScopeScale.verticalLabels(ScopeMode.WAVEFORM, ProjectColorSpace.REC709_SDR))
        assertEquals(ScopeScale.verticalLabels(ScopeMode.WAVEFORM, ProjectColorSpace.REC709_SDR), ScopeScale.verticalLabels(ScopeMode.PARADE, ProjectColorSpace.REC709_SDR))
    }

    @Test
    fun `an HLG waveform marks reference white and the peak in nits`() {
        val labels = ScopeScale.verticalLabels(ScopeMode.WAVEFORM, ProjectColorSpace.REC2020_HLG)
        assertEquals(5, labels.size)
        assertTrue(labels[0].contains("1000 nit"))
        assertTrue(labels[1].contains("203 nit"))
    }

    @Test
    fun `only the histogram has horizontal labels and only waveforms have vertical ones`() {
        assertEquals(listOf("0", "25", "50", "75", "100"), ScopeScale.horizontalLabels(ScopeMode.HISTOGRAM))
        assertTrue(ScopeScale.horizontalLabels(ScopeMode.WAVEFORM).isEmpty())
        assertTrue(ScopeScale.verticalLabels(ScopeMode.HISTOGRAM, ProjectColorSpace.REC709_SDR).isEmpty())
        assertTrue(ScopeScale.verticalLabels(ScopeMode.VECTORSCOPE, ProjectColorSpace.REC709_SDR).isEmpty())
    }

    @Test
    fun `every mode has a caption that names the signal of the project`() {
        for (mode in ScopeMode.entries) {
            assertTrue(ScopeScale.caption(mode, ProjectColorSpace.REC709_SDR).isNotBlank())
        }
        assertTrue(ScopeScale.caption(ScopeMode.WAVEFORM, ProjectColorSpace.REC2020_HLG).contains("HLG"))
        assertTrue(ScopeScale.caption(ScopeMode.WAVEFORM, ProjectColorSpace.REC709_SDR).contains("Rec.709"))
    }

    @Test
    fun `scope modes round trip through their native codes`() {
        for (mode in ScopeMode.entries) assertEquals(mode, ScopeMode.fromCode(mode.code))
        assertEquals(listOf(0, 1, 2, 3), ScopeMode.entries.map { it.code })
        assertEquals(null, ScopeMode.fromCode(9))
    }
}
