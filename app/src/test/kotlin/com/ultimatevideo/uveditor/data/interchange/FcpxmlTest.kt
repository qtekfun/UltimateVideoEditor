package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.model.ProjectDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class FcpxmlTest {

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        // The DOCTYPE is the bare `<!DOCTYPE fcpxml>` Final Cut writes; never fetch anything for it.
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }.map { it as Element }

    @Test
    fun `golden FCPXML of the sample project`() {
        assertGolden("sample.fcpxml", Fcpxml.export(sampleProject()).xml)
    }

    @Test
    fun `golden FCPXML of an HLG project at 29_97`() {
        assertGolden("sample-hlg-2997.fcpxml", Fcpxml.export(sampleProject(30000, 1001, "Rec2020-HLG")).xml)
    }

    @Test
    fun `the document is well formed XML even with special characters in names`() {
        val doc = parse(Fcpxml.export(sampleProject()).xml)
        assertEquals("fcpxml", doc.documentElement.nodeName)
        assertEquals("1.9", doc.documentElement.getAttribute("version"))
    }

    @Test
    fun `the base is the primary storyline and other tracks are connected clips`() {
        val doc = parse(Fcpxml.export(sampleProject()).xml)
        val spine = doc.getElementsByTagName("spine").item(0) as Element
        val items = spine.children("asset-clip")
        assertEquals(listOf("0s", "5s", "10s"), items.map { it.getAttribute("offset") })
        // The overlay at frame 60 hangs on the first base clip, one lane up (the title above it, the audio below);
        // the photo, on the same overlay track, hangs on the second.
        val connected = items.flatMap { it.children("asset-clip") + it.children("video") + it.children("title") }
        assertEquals(setOf("1", "2", "-1"), connected.map { it.getAttribute("lane") }.toSet())
        assertEquals(1, items[1].children("video").size)
        assertTrue(items[0].children("asset-clip").any { it.getAttribute("lane") == "1" })
    }

    @Test
    fun `trims and retime are written in rational seconds`() {
        val doc = parse(Fcpxml.export(sampleProject()).xml)
        val items = (doc.getElementsByTagName("spine").item(0) as Element).children("asset-clip")
        assertEquals("1s", items[0].getAttribute("start"))
        assertEquals("5s", items[0].getAttribute("duration"))
        assertEquals(1, items[2].children("timeMap").size)
    }

    @Test
    fun `markers carry their note and colour`() {
        val xml = Fcpxml.export(sampleProject()).xml
        assertTrue(xml, xml.contains("value=\"[red] Cut here &amp; &lt;check&gt;\""))
        assertTrue(xml, xml.contains("value=\"Beat\""))
    }

    @Test
    fun `a marker name is the marker text, before the note`() {
        val project = sampleProject().let { it.copy(markers = listOf(com.ultimatevideo.uveditor.data.model.MarkerDto("mk1", 75, "manual", name = "Intro", note = "the note", color = "blue"))) }
        val xml = Fcpxml.export(project).xml
        assertTrue(xml, xml.contains("value=\"[blue] Intro\""))
        assertTrue(xml, xml.contains("note=\"the note\""))
    }

    @Test
    fun `the unsupported features are listed`() {
        val export = Fcpxml.export(sampleProject())
        assertTrue(export.notes.any { it.contains("content://") })
        assertTrue(export.notes.any { it.contains("Audio tools") })
        assertTrue(export.xml.contains("<note>Not exported:"))
    }

    @Test
    fun `an empty project is a single gap`() {
        val doc = parse(Fcpxml.export(ProjectDto(id = "e", name = "Empty", settings = sampleProject().settings)).xml)
        val spine = doc.getElementsByTagName("spine").item(0) as Element
        assertEquals(1, spine.children("gap").size)
    }
}
