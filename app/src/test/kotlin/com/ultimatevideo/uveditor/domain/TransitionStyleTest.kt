package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransitionDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionStyleTest {
    private fun scene(type: TransitionType = TransitionType.CROSSFADE, direction: TransitionDirection = TransitionDirection.LEFT): Timeline {
        val base = timeline(track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 50)))
        return TimelineOps.addTransition(base, Transition("t", "A", "B", 10, type, direction)).getOrFail()
    }

    // region operation

    @Test
    fun `changing the look keeps the length and the place of the transition`() {
        val result = TimelineOps.setTransitionStyle(scene(), "t", TransitionType.PUSH, TransitionDirection.UP).getOrFail()
        assertEquals(Transition("t", "A", "B", 10, TransitionType.PUSH, TransitionDirection.UP), result.transitions.single())
        assertEquals(emptyList<String>(), result.invariantViolations())
    }

    @Test
    fun `setting the same look changes nothing and an unknown transition is refused`() {
        val tl = scene(TransitionType.ZOOM)
        assertEquals(tl, TimelineOps.setTransitionStyle(tl, "t", TransitionType.ZOOM, TransitionDirection.LEFT).getOrFail())
        assertEquals(EditError.TransitionNotFound("zz"), TimelineOps.setTransitionStyle(tl, "zz", TransitionType.SLIDE, TransitionDirection.LEFT).errorOrFail())
    }

    @Test
    fun `a look change is one undo step`() {
        val before = scene()
        val done = EditHistory(before).execute(EditCommand.SetTransitionStyle("t", TransitionType.SPIN, TransitionDirection.RIGHT)).getOrFail()
        assertEquals(TransitionType.SPIN, done.timeline.transitions.single().type)
        assertEquals(before, done.undo().timeline)
    }

    // endregion

    // region render plan

    @Test
    fun `the render plan hands each clip the look of its transition`() {
        val clips = scene(TransitionType.PUSH, TransitionDirection.DOWN).renderClips().associateBy { it.clipId }
        assertNull(clips.getValue("A").transitionIn)
        assertEquals(TransitionLook(TransitionType.PUSH, TransitionDirection.DOWN, 10), clips.getValue("A").transitionOut)
        assertEquals(TransitionLook(TransitionType.PUSH, TransitionDirection.DOWN, 10), clips.getValue("B").transitionIn)
        assertNull(clips.getValue("B").transitionOut)
        // The audio crossfade still reads the lengths.
        assertEquals(10L, clips.getValue("B").crossfadeInFrames)
        assertEquals(10L, clips.getValue("A").crossfadeOutFrames)
    }

    @Test
    fun `a slide only shapes the pose of the incoming clip while a push shapes both`() {
        val slide = scene(TransitionType.SLIDE).renderClips().associateBy { it.clipId }
        assertTrue(slide.getValue("A").transitionPoseRanges().isEmpty())
        assertEquals(listOf(95L until 105L), slide.getValue("B").transitionPoseRanges())
        val push = scene(TransitionType.PUSH).renderClips().associateBy { it.clipId }
        assertEquals(listOf(95L until 105L), push.getValue("A").transitionPoseRanges())
        assertEquals(listOf(95L until 105L), push.getValue("B").transitionPoseRanges())
        assertTrue(scene(TransitionType.WIPE).renderClips().none { it.bakesTransitionPose })
        assertTrue(scene(TransitionType.WIPE).renderClips().all { it.shapesTransitionFx })
        assertTrue(scene(TransitionType.CROSSFADE).renderClips().none { it.bakesTransitionPose || it.shapesTransitionFx })
    }

    @Test
    fun `only a fading look fades the incoming opacity`() {
        val fade = scene(TransitionType.CROSSFADE).renderClips().first { it.clipId == "B" }
        assertTrue(fade.appearanceAt(95).opacity < 0.2)
        val leak = scene(TransitionType.LIGHT_LEAK).renderClips().first { it.clipId == "B" }
        assertTrue(leak.appearanceAt(95).opacity < 0.2)
        for (type in listOf(TransitionType.SLIDE, TransitionType.PUSH, TransitionType.ZOOM, TransitionType.WIPE, TransitionType.WHIP_PAN)) {
            val b = scene(type).renderClips().first { it.clipId == "B" }
            assertEquals("$type", 1.0, b.appearanceAt(95).opacity, 0.0)
        }
    }

    @Test
    fun `outside its transition a clip is drawn exactly as before`() {
        val b = scene(TransitionType.SPIN).renderClips().first { it.clipId == "B" }
        assertEquals(b.appearanceAt(150), b.appearanceAt(150, 1920, 1080))
        assertEquals(b.fxAt(150), b.fxAt(150, 1920, 1080))
    }

    @Test
    fun `the clip's own pose and the transition combine`() {
        val tl = timeline(
            track("v1", clip("A", 0, 100).copy(transform = ClipTransform(positionX = 50.0)), clip("B", 100, 100, srcIn = 50).copy(transform = ClipTransform(positionX = 50.0, scaleX = 2.0, scaleY = 2.0))),
        )
        val withSlide = TimelineOps.addTransition(tl, Transition("t", "A", "B", 10, TransitionType.SLIDE)).getOrFail()
        val b = withSlide.renderClips().first { it.clipId == "B" }
        val first = b.appearanceAt(95, 1920, 1080)
        assertTrue(first.positionX > 50.0 + 0.9 * 1920)
        assertEquals(2.0, first.scaleX, 0.0)
        val settled = b.appearanceAt(120, 1920, 1080)
        assertEquals(50.0, settled.positionX, 0.0)
    }

    // endregion

    // region project file

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project(vararg transitions: TransitionDto) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(
            TrackDto(
                "v", "video", 0,
                listOf(ClipDto("A", "a", 0, 0, 100), ClipDto("B", "a", 100, 50, 150)),
            ),
        ),
        transitions = transitions.toList(),
    )

    @Test
    fun `every type and direction survives the project file`() {
        for (type in TransitionType.entries) for (direction in TransitionDirection.entries) {
            val transition = Transition("t", "A", "B", 10, type, direction)
            val dto = project(TransitionDto("t", TimelineMapper.transitionName(type), "A", "B", 10, direction.name.lowercase().takeIf { type.hasDirection }))
            val timeline = TimelineMapper.toTimeline(dto)
            val read = timeline.transitions.single()
            assertEquals(type, read.type)
            // A look without a direction reads back as the default one.
            assertEquals(if (type.hasDirection) direction else TransitionDirection.LEFT, read.direction)
            val written = TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).transitions.single()
            assertEquals(dto.transitions.single(), written)
            assertEquals(transition.type, read.type)
        }
    }

    @Test
    fun `a project written before the pack still loads and a crossfade writes no direction`() {
        val old = Json.decodeFromString<ProjectDto>(
            """{"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},
               "tracks":[{"id":"v","type":"video","order":0,"clips":[{"id":"A","assetId":"a","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":100},
               {"id":"B","assetId":"a","timelineStartFrame":100,"sourceInFrame":50,"sourceOutFrame":150}]}],
               "transitions":[{"id":"t","type":"crossfade","fromClipId":"A","toClipId":"B","durationFrames":10}]}""",
        )
        val timeline = TimelineMapper.toTimeline(old)
        assertEquals(Transition("t", "A", "B", 10), timeline.transitions.single())
        assertNull(TimelineMapper.toDto(old, timeline, old.mediaLibrary).transitions.single().direction)
        val text = Json.encodeToString(TimelineMapper.toDto(old, timeline, old.mediaLibrary))
        assertTrue(!text.contains("direction"))
    }

    @Test
    fun `an unknown type or direction is reported as a corrupt project`() {
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(TransitionDto("t", "star-wipe", "A", "B", 10))) }
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(TransitionDto("t", "slide", "A", "B", 10, "sideways"))) }
    }

    // endregion
}
