package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.qtekfun.ultimatevideoeditor.ui.text.english

class HubFormatTest {

    private fun project(colour: String = "Rec709-SDR", frames: Long = 900, missing: Int = 0) =
        ProjectSummary("p", "P", ProjectSettingsDto(3840, 2160, 30000, 1001, colour), 0, durationFrames = frames, missingMedia = missing)

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `the format line names size, rate and colour`() {
        assertEquals("4K · 29.97 fps · SDR", HubFormat.formatLine(project()).english())
        assertEquals("4K · 29.97 fps", HubFormat.sizeLine(project()).english())
        assertEquals("4K · 29.97 fps · HLG", HubFormat.formatLine(project("Rec2020-HLG")).english())
    }

    @Test
    fun `only a non SDR project is HDR`() {
        assertFalse(HubFormat.isHdr(project()))
        assertTrue(HubFormat.isHdr(project("Rec2020-HLG")))
        assertTrue(HubFormat.isHdr(project("Rec2020-PQ")))
    }

    @Test
    fun `length is a clock or Empty`() {
        assertEquals("0:30", HubFormat.length(project(frames = 900)).english())
        assertEquals("Empty", HubFormat.length(project(frames = 0)).english())
    }

    @Test
    fun `the missing chip only appears when something is missing`() {
        assertNull(HubFormat.missing(project()))
        assertEquals("1 missing", HubFormat.missing(project(missing = 1))?.english())
        assertEquals("4 missing", HubFormat.missing(project(missing = 4))?.english())
    }

    @Test
    fun `relative dates step from minutes to a calendar date`() {
        val now = 100 * day
        val date = { _: Long -> "ABS" }
        assertEquals("Just now", HubFormat.relativeDate(now - 20_000, now, date).english())
        assertEquals("Just now", HubFormat.relativeDate(now + 5 * day, now, date).english())
        assertEquals("5 min ago", HubFormat.relativeDate(now - 5 * 60_000, now, date).english())
        assertEquals("3 h ago", HubFormat.relativeDate(now - 3 * 3_600_000, now, date).english())
        assertEquals("Yesterday", HubFormat.relativeDate(now - day - 1000, now, date).english())
        assertEquals("4 days ago", HubFormat.relativeDate(now - 4 * day, now, date).english())
        assertEquals("ABS", HubFormat.relativeDate(now - 7 * day, now, date).english())
    }

    @Test
    fun `project counts are singular and plural`() {
        assertEquals("1 project", HubFormat.projectCount(1).english())
        assertEquals("0 projects", HubFormat.projectCount(0).english())
        assertEquals("12 projects", HubFormat.projectCount(12).english())
    }
}
