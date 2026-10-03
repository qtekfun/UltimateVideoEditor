package com.ultimatevideo.uveditor.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Applies random edits with fixed seeds, checking invariants hold and undo/redo is exact. */
class RandomizedEditTest {

    private fun randomCommand(random: Random, timeline: Timeline, nextId: () -> String): EditCommand {
        val clips = timeline.tracks.flatMap { it.clips }
        val trackId = timeline.tracks.random(random).id
        val anyClip = clips.randomOrNull(random)?.id ?: "missing"
        val frame = f(random.nextLong(-5, 500))
        return when (random.nextInt(7)) {
            0 -> EditCommand.Split(trackId, frame, nextId())
            1 -> EditCommand.Move(anyClip, frame, toTrackId = if (random.nextBoolean()) trackId else null, snap = Snap(f(random.nextLong(0, 500)), random.nextLong(0, 8)))
            2 -> EditCommand.Overwrite(trackId, clip(nextId(), random.nextLong(-2, 450), random.nextLong(-1, 80), srcIn = random.nextLong(0, 100)))
            3 -> EditCommand.RippleDelete(anyClip)
            4 -> EditCommand.RippleAppend(anyClip)
            5 -> EditCommand.Trim(anyClip, TrimEdge.START, frame)
            else -> EditCommand.Trim(anyClip, TrimEdge.END, frame, sourceLength = random.nextLong(50, 700))
        }
    }

    @Test
    fun `random edits preserve invariants and undo restores every earlier state`() {
        for (seed in 1..60) {
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
                }
                assertTrue("seed $seed: ${history.timeline.invariantViolations()}", history.timeline.invariantViolations().isEmpty())
            }
            assertTrue("seed $seed applied only ${snapshots.size - 1} edits", snapshots.size > 20)
            var undone = history
            for (expected in snapshots.asReversed()) {
                assertEquals("seed $seed undo", expected, undone.timeline)
                undone = undone.undo()
            }
            assertTrue(!undone.canUndo)
            var redone = undone
            for (expected in snapshots) {
                assertEquals("seed $seed redo", expected, redone.timeline)
                redone = redone.redo()
            }
        }
    }

    private fun randomBaseAwareCommand(random: Random, timeline: Timeline, nextId: () -> String): EditCommand {
        val base = checkNotNull(ClipDeletion.baseTrack(timeline))
        val overlays = timeline.tracks.filter { it.id != base.id }
        val clips = timeline.tracks.flatMap { it.clips }
        val overlayClips = overlays.flatMap { it.clips }
        val anyClip = clips.randomOrNull(random)?.id ?: "missing"
        val frame = f(random.nextLong(-5, 400))
        val trackId = timeline.tracks.random(random).id
        return when (random.nextInt(8)) {
            0 -> EditCommand.InsertBase(clip(nextId(), 0, random.nextLong(1, 60), srcIn = random.nextLong(0, 40)), frame)
            1 -> EditCommand.MoveClip(anyClip, frame, toTrackId = if (random.nextBoolean()) timeline.tracks.random(random).id else null)
            2 -> EditCommand.TrimClip(anyClip, TrimEdge.START, frame)
            3 -> EditCommand.TrimClip(anyClip, TrimEdge.END, frame)
            4 -> EditCommand.DeleteClip(anyClip)
            5 -> EditCommand.Split(trackId, frame, nextId())
            6 -> overlays.randomOrNull(random)?.let {
                EditCommand.Overwrite(it.id, clip(nextId(), random.nextLong(0, 350), random.nextLong(1, 70), srcIn = random.nextLong(0, 40)))
            } ?: EditCommand.DeleteClip(anyClip)
            else -> EditCommand.RippleAppend(overlayClips.randomOrNull(random)?.id ?: "missing")
        }
    }

    @Test
    fun `random base-aware edits keep the base gap-free, overlays valid and undo exact`() {
        var applied = 0
        for (seed in 1..80) {
            val random = Random(seed)
            var counter = 0
            val nextId = { "m${counter++}" }
            var history = EditHistory(
                timeline(
                    track("v3", clip("p", 10, 30), clip("q", 120, 40)),
                    track("a1", clip("m", 0, 200), type = TrackType.AUDIO),
                    track("v2", clip("c", 20, 40)),
                    track("v1", clip("a", 0, 100), clip("b", 100, 60), clip("d", 160, 80)),
                ),
                limit = 1000,
            )
            assertEquals(emptyList<String>(), MagneticBase.baseViolations(history.timeline))
            val snapshots = mutableListOf(history.timeline)
            repeat(150) {
                val result = history.execute(randomBaseAwareCommand(random, history.timeline, nextId))
                if (result is EditResult.Success) {
                    history = result.value
                    snapshots += history.timeline
                    applied++
                }
                val timeline = history.timeline
                assertTrue("seed $seed: ${timeline.invariantViolations()}", timeline.invariantViolations().isEmpty())
                // Split and overwrite on the base itself are the only ways to touch it without a magnetic op,
                // and neither can open a gap, so the base must stay contiguous from frame 0.
                assertTrue("seed $seed base: ${MagneticBase.baseViolations(timeline)}", MagneticBase.baseViolations(timeline).isEmpty())
            }
            var undone = history
            for (expected in snapshots.asReversed()) {
                assertEquals("seed $seed undo", expected, undone.timeline)
                undone = undone.undo()
            }
        }
        assertTrue("only $applied edits applied", applied > 1000)
    }
}
