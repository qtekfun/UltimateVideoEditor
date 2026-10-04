package com.ultimatevideo.uveditor.domain.templates

import com.ultimatevideo.uveditor.domain.AddTextTemplate
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TextTemplates
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.TransitionDirection
import com.ultimatevideo.uveditor.domain.TransitionType

/**
 * The starter templates, written in code (no files, no downloads, nothing licensed from anyone). Each is a normal
 * [ProjectTemplate]; the user's own templates use the same shape.
 */
object BuiltInTemplates {

    val all: List<ProjectTemplate> by lazy { listOf(verticalMontage(), introOutro(), lowerThirds()) }

    fun find(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }

    private fun title(id: String, text: String, start: Long, frames: Long) = Clip(
        id = id,
        assetId = null,
        timelineStart = FrameIndex(start),
        sourceIn = FrameIndex.ZERO,
        sourceOut = FrameIndex(frames),
        title = TitleContent(text),
    )

    /** Five 3-second slots for videos or photos with slide transitions, a title and an optional music slot, 9:16. */
    private fun verticalMontage(): ProjectTemplate {
        val slots = List(5) { Placeholder("clip${it + 1}", "Clip ${it + 1}", PlaceholderKind.VIDEO_OR_PHOTO, frames = 90, minFrames = 30) }
        val music = Placeholder("music", "Music", PlaceholderKind.AUDIO, frames = 450, minFrames = 90, optional = true)
        val base = Track("track-v1", TrackType.VIDEO, slots.mapIndexed { i, p -> TemplateBuilder.slotClip(p, i * 90L) })
        val transitions = (0 until 4).map { Transition("montage-t$it", "slot-clip${it + 1}", "slot-clip${it + 2}", 8, TransitionType.SLIDE, TransitionDirection.UP) }
        val timeline = Timeline(
            tracks = listOf(
                Track("track-t1", TrackType.TITLE, listOf(title("montage-title", "My story", 6, 72))),
                base,
                Track("track-a1", TrackType.AUDIO, listOf(TemplateBuilder.slotClip(music, 0))),
            ),
            transitions = transitions,
        )
        return ProjectTemplate(
            id = "builtin-vertical-montage",
            name = "Vertical montage",
            description = "Five clips or photos in 9:16 with slide transitions, a title and optional music.",
            width = 1080,
            height = 1920,
            fpsNum = 30,
            fpsDen = 1,
            colorSpace = "Rec709-SDR",
            timeline = timeline,
            placeholders = slots + music,
        )
    }

    /** An intro, a main clip and an outro with crossfades and a title over the intro and the outro, 16:9. */
    private fun introOutro(): ProjectTemplate {
        val intro = Placeholder("intro", "Intro", PlaceholderKind.VIDEO_OR_PHOTO, frames = 120, minFrames = 45)
        val main = Placeholder("main", "Main clip", PlaceholderKind.VIDEO, frames = 300, minFrames = 90)
        val outro = Placeholder("outro", "Outro", PlaceholderKind.VIDEO_OR_PHOTO, frames = 120, minFrames = 45)
        val base = Track(
            "track-v1",
            TrackType.VIDEO,
            listOf(TemplateBuilder.slotClip(intro, 0), TemplateBuilder.slotClip(main, 120), TemplateBuilder.slotClip(outro, 420)),
        )
        val timeline = Timeline(
            tracks = listOf(
                Track("track-t1", TrackType.TITLE, listOf(title("intro-title", "Your title", 12, 90), title("outro-title", "Thanks for watching", 432, 90))),
                base,
            ),
            transitions = listOf(
                Transition("intro-t1", "slot-intro", "slot-main", 12),
                Transition("intro-t2", "slot-main", "slot-outro", 12),
            ),
        )
        return ProjectTemplate(
            id = "builtin-intro-outro",
            name = "Intro and outro",
            description = "An intro, a main clip and an outro with crossfades and titles, in 16:9.",
            width = 1920,
            height = 1080,
            fpsNum = 30,
            fpsDen = 1,
            colorSpace = "Rec709-SDR",
            timeline = timeline,
            placeholders = listOf(intro, main, outro),
        )
    }

    /** One clip with three animated lower thirds over it, 16:9. */
    private fun lowerThirds(): ProjectTemplate {
        val clip = Placeholder("clip", "Main clip", PlaceholderKind.VIDEO, frames = 300, minFrames = 120)
        var timeline = Timeline(tracks = listOf(Track("track-v1", TrackType.VIDEO, listOf(TemplateBuilder.slotClip(clip, 0)))))
        val template = checkNotNull(TextTemplates.find("lower-third") ?: TextTemplates.all.firstOrNull()) { "no text templates" }
        val names = listOf("First speaker", "Second speaker", "Third speaker")
        names.forEachIndexed { i, text ->
            val command = AddTextTemplate(
                templateId = template.id,
                template = template,
                text = text,
                start = FrameIndex(30L + i * 90L),
                durationFrames = 75,
                canvasWidth = 1920,
                canvasHeight = 1080,
                fps = FrameRate(30, 1),
                ids = List(TextTemplates.idCount(template)) { "lt$i-$it" },
            )
            timeline = when (val result = command.apply(timeline)) {
                is EditResult.Success -> result.value
                is EditResult.Failure -> error("built-in template failed: ${result.error}")
            }
        }
        return ProjectTemplate(
            id = "builtin-lower-thirds",
            name = "Lower thirds",
            description = "One clip with three animated name titles over it, in 16:9.",
            width = 1920,
            height = 1080,
            fpsNum = 30,
            fpsDen = 1,
            colorSpace = "Rec709-SDR",
            timeline = timeline,
            placeholders = listOf(clip),
        )
    }
}
