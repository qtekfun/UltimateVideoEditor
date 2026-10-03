package com.ultimatevideo.uveditor.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Random edits including speed, reverse, ramps and freezes: invariants hold and undo is exact. */
class RetimeRandomizedTest {

    private fun randomCommand(random: Random, timeline: Timeline, nextId: () -> String): EditCommand {
        val clips = timeline.tracks.flatMap { it.clips }
        val trackId = timeline.tracks.random(random).id
        val anyClip = clips.randomOrNull(random)
        val clipId = anyClip?.id ?: "missing"
        val frame = f(random.nextLong(-5, 500))
        return when (random.nextInt(12)) {
            0 -> EditCommand.Split(trackId, frame, nextId())
            1 -> EditCommand.Move(clipId, frame, snap = Snap(f(random.nextLong(0, 500)), random.nextLong(0, 8)))
            2 -> EditCommand.Overwrite(trackId, clip(nextId(), random.nextLong(-2, 450), random.nextLong(-1, 80), srcIn = random.nextLong(0, 100)))
            3 -> EditCommand.RippleDelete(clipId)
            4 -> EditCommand.Trim(clipId, TrimEdge.START, frame)
            5 -> EditCommand.Trim(clipId, TrimEdge.END, frame, sourceLength = random.nextLong(50, 900))
            6 -> EditCommand.SetSpeed(clipId, random.nextLong(1, 20), random.nextLong(1, 12), ripple = random.nextBoolean())
            7 -> EditCommand.SetReverse(clipId, random.nextBoolean())
            8 -> {
                val duration = anyClip?.durationFrames ?: 1
                val keys = (0 until random.nextInt(0, 5)).map { SpeedKey(random.nextLong(0, duration), random.nextInt(50, 4000)) }
                    .distinctBy { it.frame }.sortedBy { it.frame }
                EditCommand.SetSpeedRamp(clipId, keys)
            }
            9 -> EditCommand.FreezeFrame(trackId, frame, random.nextLong(1, 40), nextId(), nextId())
            10 -> EditCommand.SetKeyframe(clipId, Keyframe(random.nextLong(0, 40), ClipTransform(positionX = random.nextDouble(-50.0, 50.0))))
            else -> EditCommand.Overwrite(trackId, clip(nextId(), random.nextLong(0, 450), random.nextLong(1, 80), srcIn = random.nextLong(0, 100)).copy(reverse = random.nextBoolean()))
        }
    }

    @Test
    fun `random edits with retiming preserve invariants and undo restores every earlier state`() {
        var applied = 0
        for (seed in 1..80) {
            val random = Random(seed)
            var counter = 0
            val nextId = { "n${counter++}" }
            var history = EditHistory(
                timeline(track("v1", clip("a", 0, 100), clip("b", 150, 60)), track("v2", clip("c", 20, 40)), track("v3")),
                limit = 1000,
            )
            val snapshots = mutableListOf(history.timeline)
            repeat(150) {
                val result = history.execute(randomCommand(random, history.timeline, nextId))
                if (result is EditResult.Success) {
                    history = result.value
                    snapshots += history.timeline
                    applied++
                }
                assertTrue("seed $seed: ${history.timeline.invariantViolations()}", history.timeline.invariantViolations().isEmpty())
                // Every clip maps its frames into its own source range.
                for (clip in history.timeline.tracks.flatMap { it.clips }) {
                    val r = clip.retime
                    for (t in listOf(0L, clip.durationFrames / 2, clip.durationFrames - 1)) {
                        val s = r.sourceFrameAt(t)
                        assertTrue("seed $seed clip ${clip.id} t=$t -> $s outside ${clip.sourceIn}..${clip.sourceOut}", s >= clip.sourceIn.value && s < clip.sourceOut.value)
                    }
                }
            }
            var undone = history
            for (expected in snapshots.asReversed()) {
                assertEquals("seed $seed undo", expected, undone.timeline)
                undone = undone.undo()
            }
            assertTrue(!undone.canUndo)
        }
        assertTrue("only $applied edits applied", applied > 1500)
    }

    @Test
    fun `splitting any retimed clip anywhere tiles it`() {
        val random = Random(7)
        repeat(300) {
            val span = random.nextLong(2, 300)
            val length = random.nextLong(2, 300)
            val reverse = random.nextBoolean()
            val ramp = if (random.nextBoolean()) SpeedRamps.bell(length) else emptyList()
            val clip = Clip("c", "a", f(0), f(5), f(5 + span), retimedFrames = length.takeIf { it != span }, reverse = reverse, speedRamp = ramp)
            val at = random.nextLong(1, length)
            val left = clip.cropped(0, at)
            val right = clip.cropped(at, length)
            assertEquals(length, left.durationFrames + right.durationFrames)
            // The two halves never reach outside the source range of the whole.
            for (half in listOf(left, right)) {
                assertTrue(half.sourceIn >= clip.sourceIn && half.sourceOut <= clip.sourceOut)
            }
            // Where the original showed source frame s, a half shows within a frame of it (constant
            // speed) or within a couple of frames' worth of source (a ramp is re-normalised per half).
            val tolerance = if (ramp.isEmpty()) 1L else 3 + 2 * Math.ceil(clip.speed).toLong()
            for (t in 0 until length) {
                val whole = clip.retime.sourceFrameAt(t)
                val part = if (t < at) left.retime.sourceFrameAt(t) else right.retime.sourceFrameAt(t - at)
                assertTrue("t=$t whole=$whole part=$part", kotlin.math.abs(whole - part) <= tolerance)
            }
        }
    }
}
