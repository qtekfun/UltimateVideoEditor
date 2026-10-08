package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class EdlTest {

    @Test
    fun `one file per video and audio track with the base labelled V1`() {
        val export = Edl.export(sampleProject())
        assertEquals(listOf("Sample_Co-V2.edl", "Sample_Co-V1.edl", "Sample_Co-A1.edl"), export.files.map { it.name })
    }

    @Test
    fun `golden EDL of the base track`() {
        val file = Edl.export(sampleProject()).files.single { it.name.endsWith("-V1.edl") }
        assertGolden("sample-V1.edl", file.text)
    }

    @Test
    fun `golden EDL of an overlay and of an audio track`() {
        val files = Edl.export(sampleProject()).files.associateBy { it.name }
        assertGolden("sample-V2.edl", files.getValue("Sample_Co-V2.edl").text)
        assertGolden("sample-A1.edl", files.getValue("Sample_Co-A1.edl").text)
    }

    @Test
    fun `29_97 is written as drop frame`() {
        val file = Edl.export(sampleProject(30000, 1001)).files.first()
        assertTrue(file.text.contains("FCM: DROP FRAME"))
        assertTrue(file.text.contains(";"))
    }

    @Test
    fun `23_976 is non drop and noted`() {
        val export = Edl.export(sampleProject(24000, 1001))
        assertTrue(export.files.all { it.text.contains("FCM: NON-DROP FRAME") })
        assertTrue(export.notes.any { it.contains("non-drop") })
    }

    @Test
    fun `what the format cannot carry is listed`() {
        val notes = Edl.export(sampleProject()).notes
        assertTrue(notes.any { it.contains("title") })
        assertTrue(notes.any { it.contains("transforms") })
    }

    @Test
    fun `retimed clips get an M2 line with their speed`() {
        val text = Edl.export(sampleProject()).files.single { it.name.endsWith("-V1.edl") }.text
        assertTrue(text, text.contains("M2   AX       060.0"))
    }

    @Test
    fun `an empty project exports nothing and does not fail`() {
        val export = Edl.export(ProjectDto(id = "e", name = "Empty", settings = sampleProject().settings))
        assertTrue(export.files.isEmpty())
    }

    @Test
    fun `the zip holds every file and is reproducible`() {
        val export = Edl.export(sampleProject())
        val first = Edl.zip(export.files)
        assertEquals(first.toList(), Edl.zip(export.files).toList())
        val names = ArrayList<String>()
        ZipInputStream(first.inputStream()).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(export.files.map { it.name }, names)
    }
}
