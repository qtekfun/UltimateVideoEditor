package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.LayerShadowDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TitleDto
import com.ultimatevideo.uveditor.data.model.TitleLayerDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.ImageLayer
import com.ultimatevideo.uveditor.domain.LayerBox
import com.ultimatevideo.uveditor.domain.LayerPlacement
import com.ultimatevideo.uveditor.domain.LayerShadow
import com.ultimatevideo.uveditor.domain.LayerStroke
import com.ultimatevideo.uveditor.domain.ShapeKind
import com.ultimatevideo.uveditor.domain.ShapeLayer
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.TextLayer
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLayerEdit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLayerMapperTest {
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project(title: TitleDto) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(
            TrackDto(
                "t",
                "title",
                0,
                listOf(ClipDto(id = "T1", assetId = null, timelineStartFrame = 0, sourceInFrame = 0, sourceOutFrame = 40, title = title)),
            ),
        ),
    )

    private val everything = TitleLayerEdit.of(
        listOf(
            ShapeLayer(
                kind = ShapeKind.ROUNDED_RECT,
                widthFraction = 0.6,
                heightFraction = 0.12,
                fillArgb = 0xCC102030.toInt(),
                stroke = LayerStroke(0xFFFFFFFF.toInt(), 0.004),
                shadow = LayerShadow(0x80000000.toInt(), 0.003, 0.005, 0.01),
                cornerRadiusFraction = 0.4,
                placement = LayerPlacement(-0.1, 0.25, 1.2, 5.0, 0.8),
            ),
            ImageLayer(StillKind.STICKER, "emoji:1f600", sizeFraction = 0.2, placement = LayerPlacement(offsetX = 0.3)),
            ImageLayer(StillKind.PHOTO, "asset-7", sizeFraction = 0.35, shadow = LayerShadow.DEFAULT),
            TextLayer(
                text = "Ada\nLovelace",
                fontId = "font-abc",
                sizeFraction = 0.06,
                colorArgb = 0xFF00FF00.toInt(),
                alignment = TitleAlignment.LEFT,
                bold = true,
                italic = true,
                letterSpacing = 0.05,
                lineHeight = 1.3,
                border = LayerStroke(0xFF000000.toInt(), 0.005),
                shadow = LayerShadow.DEFAULT,
                box = LayerBox(0xCC000000.toInt(), 0.02, 0.01),
                placement = LayerPlacement(0.0, -0.2, 1.0, -3.0, 1.0),
            ),
        ),
    )

    @Test
    fun `a multilayer title round trips through the project JSON`() {
        val dto = TimelineMapper.toDto(project(TitleDto("x")), timelineWith(everything), emptyList())
        val json = Json.encodeToString(dto)
        val back = TimelineMapper.toTimeline(Json.decodeFromString<ProjectDto>(json))
        val title = back.track("t")!!.clip("T1")!!.title!!
        assertEquals(everything, title)
        // The mirrored text is the first text layer's, and nothing about a photo's file is stored.
        assertEquals("Ada\nLovelace", title.text)
        assertTrue(!json.contains("resolvedUri"))
        assertNull(title.problem())
    }

    @Test
    fun `projects written before layers existed load as plain titles`() {
        val old = Json.decodeFromString<TitleDto>("""{"text":"Old","bold":true}""")
        assertTrue(old.layers.isEmpty())
        val title = TimelineMapper.toTimeline(project(old)).track("t")!!.clip("T1")!!.title!!
        assertTrue(!title.isLayered)
        assertEquals("Old", title.text)
        assertEquals(TitleContent("Old", bold = true), title)
        // A plain title writes no layers, so older builds read the new file too.
        assertTrue(TimelineMapper.toDto(project(old), TimelineMapper.toTimeline(project(old)), emptyList()).tracks.single().clips.single().title!!.layers.isEmpty())
    }

    @Test
    fun `unknown layer types shapes picture kinds alignments and colours are corrupt data`() {
        fun load(layer: TitleLayerDto) = TimelineMapper.toTimeline(project(TitleDto("x", layers = listOf(layer))))
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleLayerDto(type = "video")) }
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleLayerDto(type = "shape", shape = "star")) }
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleLayerDto(type = "image", image = "gif", source = "x")) }
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleLayerDto(type = "text", text = "x", alignment = "justify")) }
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleLayerDto(type = "text", text = "x", color = "red")) }
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleLayerDto(type = "text", text = "x", shadow = LayerShadowDto("nope", 0.0, 0.0, 0.0))) }
    }

    @Test
    fun `layer fields are optional in the JSON`() {
        val layer = Json.decodeFromString<TitleLayerDto>("""{"type":"text","text":"Hi"}""")
        val title = TimelineMapper.toTimeline(project(TitleDto("Hi", layers = listOf(layer)))).track("t")!!.clip("T1")!!.title!!
        val text = title.layers.single() as TextLayer
        assertEquals("Hi", text.text)
        assertEquals(null, text.fontId)
        assertEquals(LayerPlacement(), text.placement)
    }

    private fun timelineWith(content: TitleContent) = TimelineMapper.toTimeline(project(TitleDto("x")))
        .let { base ->
            val clip = base.track("t")!!.clip("T1")!!
            com.ultimatevideo.uveditor.domain.TimelineOps.setTitle(base, "T1", content).let { result ->
                (result as com.ultimatevideo.uveditor.domain.EditResult.Success).value.also { assertEquals(clip.id, it.track("t")!!.clip("T1")!!.id) }
            }
        }
}
