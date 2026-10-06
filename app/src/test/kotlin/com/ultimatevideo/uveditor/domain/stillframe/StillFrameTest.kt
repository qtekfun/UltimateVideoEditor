package com.ultimatevideo.uveditor.domain.stillframe

import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillFrameTest {

    // ---- sizes ----

    @Test
    fun `fixed presets are exactly what they say`() {
        assertEquals(FrameSize(1280, 720), frameTarget(FrameSizePreset.YOUTUBE_THUMBNAIL, 0, 3840, 2160).size)
        assertEquals(FrameSize(1920, 1080), frameTarget(FrameSizePreset.FULL_HD, 0, 1080, 1920).size)
        assertEquals(FrameSize(1080, 1920), frameTarget(FrameSizePreset.VERTICAL_COVER, 0, 1920, 1080).size)
        assertEquals(FrameSize(1080, 1080), frameTarget(FrameSizePreset.SQUARE, 0, 1920, 1080).size)
    }

    @Test
    fun `project size keeps odd sizes and needs no even rounding`() {
        assertEquals(FrameSize(1081, 607), frameTarget(FrameSizePreset.PROJECT, 0, 1081, 607).size)
        assertFalse(frameTarget(FrameSizePreset.PROJECT, 0, 3840, 2160).reduced)
    }

    @Test
    fun `a project larger than the limit is scaled down keeping its shape`() {
        val target = frameTarget(FrameSizePreset.PROJECT, 0, 7680, 4320)

        assertEquals(FrameSize(4096, 2304), target.size)
        assertTrue(target.reduced)
        assertEquals(FrameSize(2304, 4096), frameTarget(FrameSizePreset.PROJECT, 0, 4320, 7680).size)
    }

    @Test
    fun `custom width keeps the project's shape and rounds the height`() {
        assertEquals(FrameSize(1000, 563), frameTarget(FrameSizePreset.CUSTOM, 1000, 1920, 1080).size) // 562.5 rounds up
        assertEquals(FrameSize(1000, 1778), frameTarget(FrameSizePreset.CUSTOM, 1000, 1080, 1920).size)
        assertEquals(FrameSize(333, 333), frameTarget(FrameSizePreset.CUSTOM, 333, 500, 500).size)
    }

    @Test
    fun `custom width is clamped and a tall project is limited by its height`() {
        assertEquals(MIN_CUSTOM_WIDTH, frameTarget(FrameSizePreset.CUSTOM, -5, 1920, 1080).size.width)
        assertEquals(MAX_FRAME_SIDE, frameTarget(FrameSizePreset.CUSTOM, 100_000, 1920, 1080).size.width)
        val tall = frameTarget(FrameSizePreset.CUSTOM, 4000, 1080, 4320)
        assertEquals(MAX_FRAME_SIDE, tall.size.height)
        assertEquals(1024, tall.size.width)
        assertTrue(tall.reduced)
    }

    @Test
    fun `a very wide project never gets a zero height`() {
        assertEquals(1, frameTarget(FrameSizePreset.CUSTOM, 16, 10_000, 1).size.height)
    }

    // ---- fit and fill ----

    @Test
    fun `letterbox draws the output itself`() {
        val plan = planFrameRender(FrameSize(1080, 1080), 1920, 1080, FrameFit.LETTERBOX)

        assertEquals(FrameRenderPlan(1080, 1080, 0, 0, 1080, 1080), plan)
        assertFalse(plan.isCropped)
    }

    @Test
    fun `fill with the same shape is a plain render`() {
        val plan = planFrameRender(FrameSize(1280, 720), 1920, 1080, FrameFit.FILL)

        assertEquals(FrameRenderPlan(1280, 720, 0, 0, 1280, 720), plan)
    }

    @Test
    fun `fill crops a wide project to a square from the centre`() {
        val plan = planFrameRender(FrameSize(1080, 1080), 1920, 1080, FrameFit.FILL)

        assertEquals(1080, plan.renderHeight)
        assertTrue(plan.renderWidth in 1920..1922)
        assertEquals((plan.renderWidth - 1080) / 2, plan.cropX)
        assertEquals(0, plan.cropY)
        assertEquals(1080 to 1080, plan.outWidth to plan.outHeight)
        assertTrue(plan.isCropped)
    }

    @Test
    fun `fill crops a wide project to a vertical cover`() {
        val plan = planFrameRender(FrameSize(1080, 1920), 1920, 1080, FrameFit.FILL)

        assertEquals(1920, plan.renderHeight)
        assertTrue(plan.renderWidth in 3413..3416)
        assertEquals((plan.renderWidth - 1080) / 2, plan.cropX)
    }

    @Test
    fun `fill crops a tall project to a wide thumbnail from the centre`() {
        val plan = planFrameRender(FrameSize(1280, 720), 1080, 1920, FrameFit.FILL)

        assertEquals(1280, plan.renderWidth)
        assertTrue(plan.renderHeight in 2276..2279)
        assertEquals(0, plan.cropX)
        assertEquals((plan.renderHeight - 720) / 2, plan.cropY)
    }

    @Test
    fun `the cropped rectangle is always fully covered by the picture`() {
        val projects = listOf(1920 to 1080, 1080 to 1920, 1081 to 607, 1000 to 1000, 3840 to 2160, 2560 to 1080, 719 to 1279, 4096 to 2160)
        val targets = listOf(1280 to 720, 1080 to 1920, 1080 to 1080, 1920 to 1080, 333 to 187, 1001 to 999, 4096 to 3000, 17 to 4000)
        for ((pw, ph) in projects) {
            for ((tw, th) in targets) {
                val plan = planFrameRender(FrameSize(tw, th), pw, ph, FrameFit.FILL)
                val placed = letterboxPlacement(pw, ph, plan.renderWidth, plan.renderHeight)
                val where = "project ${pw}x$ph target ${tw}x$th plan $plan"
                assertTrue(where, plan.cropX >= placed.x && plan.cropY >= placed.y)
                assertTrue(where, plan.cropX + plan.outWidth <= placed.x + placed.width)
                assertTrue(where, plan.cropY + plan.outHeight <= placed.y + placed.height)
                assertTrue(where, plan.cropX + plan.outWidth <= plan.renderWidth && plan.cropY + plan.outHeight <= plan.renderHeight)
                assertTrue(where, plan.renderWidth <= MAX_RENDER_SIDE && plan.renderHeight <= MAX_RENDER_SIDE)
            }
        }
    }

    @Test
    fun `a surface that would be too large shrinks the output instead`() {
        // A 32:9 project cropped to a 4096 px square needs a 14563 px wide surface.
        val plan = planFrameRender(FrameSize(4096, 4096), 3840, 1080, FrameFit.FILL)

        assertTrue(plan.shrunk)
        assertTrue(plan.renderWidth <= MAX_RENDER_SIDE)
        assertTrue(plan.outWidth < 4096)
        assertEquals(plan.outWidth, plan.outHeight)
    }

    // ---- JPEG quality ----

    /** A made-up but monotonic size curve: about 900 bytes per quality point per megapixel-ish unit. */
    private fun curve(scale: Long): (Int) -> Long = { q -> scale * q * q / 100 + 20_000 }

    @Test
    fun `the requested quality is kept when the file fits`() {
        var calls = 0
        val choice = chooseJpegQuality(92, YOUTUBE_THUMBNAIL_MAX_BYTES) { calls++; 500_000L }

        assertEquals(JpegChoice(92, 500_000, true), choice)
        assertEquals(1, calls)
    }

    @Test
    fun `no limit means one encode at the requested quality`() {
        var calls = 0
        val choice = chooseJpegQuality(75, null) { calls++; 9_000_000L }

        assertEquals(75, choice.quality)
        assertTrue(choice.fits)
        assertEquals(1, calls)
    }

    @Test
    fun `an oversize file gets the highest quality that fits`() {
        val size = curve(1_000_000L) // q=92 -> 8.4 MB, q=40 -> 1.6 MB, q=45 -> 2.04 MB
        var calls = 0
        val choice = chooseJpegQuality(92, YOUTUBE_THUMBNAIL_MAX_BYTES) { calls++; size(it) }

        assertTrue(choice.fits)
        assertTrue(choice.bytes <= YOUTUBE_THUMBNAIL_MAX_BYTES)
        assertTrue("one step up is too big", size(choice.quality + 1) > YOUTUBE_THUMBNAIL_MAX_BYTES)
        assertEquals(size(choice.quality), choice.bytes)
        assertTrue("converges in a few encodes, took $calls", calls <= 8)
    }

    @Test
    fun `the search never raises the quality above the requested one`() {
        val choice = chooseJpegQuality(60, 5_000_000L) { 4_000_000L }

        assertEquals(60, choice.quality)
    }

    @Test
    fun `a limit that nothing fits reports it and falls back to the lowest quality`() {
        val choice = chooseJpegQuality(92, 1_000L) { 50_000L + it }

        assertFalse(choice.fits)
        assertEquals(MIN_JPEG_QUALITY, choice.quality)
        assertEquals(50_001L, choice.bytes)
    }

    @Test
    fun `quality is clamped to the valid range and each quality is encoded once`() {
        val seen = HashMap<Int, Int>()
        val choice = chooseJpegQuality(500, 10L) { seen.merge(it, 1, Int::plus); 100L }

        assertFalse(choice.fits)
        assertTrue(seen.keys.all { it in MIN_JPEG_QUALITY..MAX_JPEG_QUALITY })
        assertTrue(seen.values.all { it == 1 })
    }

    @Test
    fun `the lowest fitting quality is found at the edge of the range`() {
        // Only quality 1 fits.
        val choice = chooseJpegQuality(92, 100L) { if (it == 1) 100L else 101L }

        assertTrue(choice.fits)
        assertEquals(1, choice.quality)
        // Everything fits down from 99.
        assertEquals(99, chooseJpegQuality(100, 100L) { if (it <= 99) 100L else 101L }.quality)
    }

    // ---- names ----

    @Test
    fun `the file name carries the project and the timecode`() {
        assertEquals("My movie frame 00-00-05-12.png", frameFileName("My movie", "00:00:05:12", FrameFormat.PNG))
        assertEquals("a_b frame 00-01-00-00.jpg", frameFileName(" a/b ", "00:01:00:00", FrameFormat.JPEG))
        assertEquals("ultimateVE frame 00-00-00-00.jpg", frameFileName("  ", "00:00:00:00", FrameFormat.JPEG))
    }

    // ---- what the frame shows ----

    private val twoClips = timeline(
        track("v1", clip("c1", 0, 30), clip("c2", 60, 30)), // a gap at frames 30..59
        track("a1", clip("au", 0, 200), type = TrackType.AUDIO),
    )

    @Test
    fun `frame zero and the last frame of a clip are pictures`() {
        assertEquals(FrameContent.PICTURE, frameContent(twoClips, 0))
        assertEquals(FrameContent.PICTURE, frameContent(twoClips, 29))
        assertEquals(FrameContent.PICTURE, frameContent(twoClips, 89))
    }

    @Test
    fun `a gap is a black frame and so is anything past the end`() {
        assertEquals(FrameContent.GAP, frameContent(twoClips, 30))
        assertEquals(FrameContent.GAP, frameContent(twoClips, 59))
        // The audio clip is longer than the picture; the project end counts every clip, so this is a gap, not "past end".
        assertEquals(FrameContent.GAP, frameContent(twoClips, 90))
        assertEquals(FrameContent.PAST_END, frameContent(twoClips, 200))
        assertEquals(FrameContent.PAST_END, frameContent(twoClips, 10_000))
    }

    @Test
    fun `a timeline without pictures is empty`() {
        assertEquals(FrameContent.EMPTY, frameContent(timeline(), 0))
        assertEquals(FrameContent.EMPTY, frameContent(timeline(track("a1", clip("au", 0, 100), type = TrackType.AUDIO)), 5))
    }

    // ---- selected clip ----

    @Test
    fun `the selected clip is usable on its first and last frame only inside it`() {
        assertEquals(SelectedClipStatus.USABLE, selectedClipStatus(twoClips, "c2", 60))
        assertEquals(SelectedClipStatus.USABLE, selectedClipStatus(twoClips, "c2", 89))
        assertEquals(SelectedClipStatus.OUTSIDE_CLIP, selectedClipStatus(twoClips, "c2", 90))
        assertEquals(SelectedClipStatus.OUTSIDE_CLIP, selectedClipStatus(twoClips, "c2", 59))
        assertEquals(SelectedClipStatus.OUTSIDE_CLIP, selectedClipStatus(twoClips, "c2", 0))
    }

    @Test
    fun `no selection, an unknown id and an audio clip are refused with a reason`() {
        assertEquals(SelectedClipStatus.NO_SELECTION, selectedClipStatus(twoClips, null, 0))
        assertEquals(SelectedClipStatus.NO_SELECTION, selectedClipStatus(twoClips, "gone", 0))
        assertEquals(SelectedClipStatus.NOT_VISUAL, selectedClipStatus(twoClips, "au", 10))
        assertNotNull(SelectedClipStatus.NO_SELECTION.reason)
        assertNull(SelectedClipStatus.USABLE.reason)
    }

    @Test
    fun `the clip alone is a one clip timeline without transitions`() {
        val alone = timelineOfClip(twoClips, "c2")!!

        assertEquals(1, alone.tracks.size)
        assertEquals(listOf("c2"), alone.tracks.single().clips.map { it.id })
        assertTrue(alone.transitions.isEmpty())
        assertNull(timelineOfClip(twoClips, "au"))
        assertNull(timelineOfClip(twoClips, "gone"))
    }
}
