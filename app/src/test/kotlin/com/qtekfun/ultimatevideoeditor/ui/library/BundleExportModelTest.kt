package com.qtekfun.ultimatevideoeditor.ui.library

import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.data.interchange.BundlePreview
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteResult
import com.qtekfun.ultimatevideoeditor.data.interchange.ResourceImportReport
import com.qtekfun.ultimatevideoeditor.data.interchange.ResourceInfo
import com.qtekfun.ultimatevideoeditor.data.interchange.ResourceKind
import com.qtekfun.ultimatevideoeditor.data.interchange.ResourceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import com.qtekfun.ultimatevideoeditor.ui.text.english
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.EnglishStrings

class BundleExportModelTest {
    private val preview = BundlePreview(
        mediaCount = 3,
        mediaBytes = 5L * 1024 * 1024,
        mediaUnreadable = listOf("gone.mp4"),
        luts = listOf(
            ResourceInfo(ResourceKind.LUT, "1", "Warm", 3000, true),
            ResourceInfo(ResourceKind.LUT, "2", "Cold", 0, false),
        ),
        fonts = listOf(ResourceInfo(ResourceKind.FONT, "ab", "Serif", 2048, true)),
    )

    @Test
    fun `by default LUTs are in and fonts and media are out`() {
        val choice = BundleChoice()
        assertTrue(choice.includeLuts)
        assertFalse(choice.includeFonts)
        assertFalse(choice.includeMedia)
    }

    @Test
    fun `the estimate counts only what is ticked and only what is available`() {
        assertEquals(3000L, preview.estimatedBytes(BundleChoice()))
        assertEquals(3000L + 2048L, preview.estimatedBytes(BundleChoice(includeFonts = true)))
        assertEquals(5L * 1024 * 1024 + 3000L, preview.estimatedBytes(BundleChoice(includeMedia = true)))
        assertEquals(0L, preview.estimatedBytes(BundleChoice(includeLuts = false)))
    }

    @Test
    fun `a draft can export once measured and not when it failed`() {
        assertFalse(BundleExportDraft().canExport)
        assertNull(BundleExportDraft().estimatedBytes)
        assertEquals("Measuring…", BundleExportText.estimateLine(BundleExportDraft()).english())
        assertTrue(BundleExportDraft(preview = preview).canExport)
        assertFalse(BundleExportDraft(preview = preview, failed = UiText.Raw("no")).canExport)
    }

    @Test
    fun `the dialog lines say how much, how many and what is missing`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("Media files: 3 (5.0 MB), 1 cannot be read and stay out", BundleExportText.mediaLine(preview).english())
            assertEquals("Colour LUTs: 1 (2.9 KB), 1 not in this device's library", BundleExportText.lutLine(preview)?.english())
            assertEquals("Fonts: 1 (2.0 KB)", BundleExportText.fontLine(preview)?.english())
            assertNull(BundleExportText.lutLine(BundlePreview()))
            assertNull(BundleExportText.fontLine(BundlePreview()))
            assertEquals("About 2.9 KB plus the project file", BundleExportText.estimateLine(BundleExportDraft(preview = preview)).english())
        } finally {
            Locale.setDefault(saved)
        }
        assertTrue(EnglishStrings.text(R.string.bundle_font_licence_note).contains("licence"))
    }

    @Test
    fun `an export message names what went in and what was left out`() {
        val result = BundleWriteResult(
            mediaCopied = 1,
            mediaSkipped = listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4"),
            resourcesIncluded = 3,
            resourcesSkipped = listOf("font x (not in this device's library)"),
            lutsIncluded = 2,
            fontsIncluded = 1,
        )
        val text = BundleExportText.exportMessage(UiText.Raw("Bundle exported"), BundleChoice(includeMedia = true, includeFonts = true), result)
        assertEquals(
            "Bundle exported with 1 media file and 2 LUTs and 1 font. Not copied (cannot be read): a.mp4, b.mp4, c.mp4 and 1 more. " +
                "Not included: font x (not in this device's library)",
            text.english(),
        )
        val plain = BundleExportText.exportMessage(UiText.Raw("Bundle exported"), BundleChoice(), BundleWriteResult(0, emptyList()))
        assertEquals("Bundle exported", plain.english())
    }

    @Test
    fun `an import says what was installed and lists the problems`() {
        val report = ResourceImportReport(
            installed = listOf("LUT Warm", "font Serif"),
            alreadyHere = listOf("LUT Cold"),
            rekeyed = listOf("LUT Warm"),
            failed = listOf(ResourceProblem("LUT Junk", "it is not a valid .cube file")),
            missing = listOf("font Missing"),
        )
        assertEquals("2 LUT/fonts installed, 1 could not be installed, 1 still missing", BundleExportText.importSentence(report)?.english())
        assertEquals(
            listOf("LUT Junk: it is not a valid .cube file", "font Missing: not in the bundle and not on this device"),
            BundleExportText.importProblems(report).english(),
        )
        assertEquals(3, BundleExportText.importNotes(report).size)
        assertNull(BundleExportText.importSentence(ResourceImportReport.EMPTY))
        assertEquals(emptyList<String>(), BundleExportText.importProblems(ResourceImportReport(installed = listOf("LUT A"))).english())
        assertEquals("1 LUT/font installed", BundleExportText.importSentence(ResourceImportReport(installed = listOf("LUT A")))?.english())
    }
}
