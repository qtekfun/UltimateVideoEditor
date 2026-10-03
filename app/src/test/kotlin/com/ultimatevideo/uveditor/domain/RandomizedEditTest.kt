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
}
