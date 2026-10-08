package com.qtekfun.ultimatevideoeditor.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Random group edits mixed with the base-aware single-clip edits: invariants hold, the base has no gaps, undo is exact. */
class GroupRandomizedTest {

    private class Board(var clipboard: Clipboard? = null)

    private fun pick(random: Random, timeline: Timeline): List<String> {
        val all = timeline.tracks.flatMap { it.clips }.map { it.id }
        if (all.isEmpty()) return listOf("missing")
        return all.shuffled(random).take(random.nextInt(1, 5))
    }

    private fun randomCommand(random: Random, timeline: Timeline, board: Board, nextId: () -> String): EditCommand {
        val ids = pick(random, timeline)
        val frame = f(random.nextLong(-5, 400))
        val one = ids.first()
        return when (random.nextInt(15)) {
            0 -> GroupMove(ids, random.nextLong(-60, 120), random.nextInt(-1, 2))
            1 -> GroupMove(ids, random.nextLong(-60, 120))
            2 -> GroupDelete(ids)
            3 -> GroupDuplicate(ids)
            4 -> {
                board.clipboard = Clipboard.capture(timeline, ids) ?: board.clipboard
                GroupPaste(board.clipboard ?: Clipboard(emptyList(), emptyList()), frame)
            }
            5 -> GroupAlign(ids, if (random.nextBoolean()) AlignEdge.START else AlignEdge.END)
            6 -> GroupSetSpeed(ids, random.nextLong(1, 9), random.nextLong(1, 4))
            7 -> GroupSetOpacity(ids, random.nextDouble())
            8 -> GroupSetGain(ids, random.nextDouble(-30.0, 6.0))
            9 -> GroupTransitions(
                ids, random.nextLong(1, 30), if (random.nextBoolean()) GroupTransition.BETWEEN else GroupTransition.HEAD_AND_TAIL,
                timeline.tracks.flatMap { it.clips }.associate { it.id to 600L },
            )
            10 -> timeline.tracks.flatMap { it.clips }.randomOrNull(random)?.let { GroupPasteAttributes(ClipAttributes.of(it), ids) } ?: GroupDelete(ids)
            11 -> EditCommand.InsertBase(clip(nextId(), 0, random.nextLong(1, 60), srcIn = random.nextLong(0, 40)), frame)
            12 -> EditCommand.MoveClip(one, frame)
            13 -> EditCommand.TrimClip(one, if (random.nextBoolean()) TrimEdge.START else TrimEdge.END, frame)
            else -> EditCommand.DeleteClip(one)
        }
    }

    @Test
    fun `random group edits keep every invariant, a gap-free base and exact undo`() {
        var applied = 0
        var groupApplied = 0
        for (seed in 1..80) {
            val random = Random(seed)
            var counter = 0
            val nextId = { "g${counter++}" }
            val board = Board()
            var history = EditHistory(
                timeline(
                    track("v3", clip("p", 10, 30), clip("q", 120, 40)),
                    track("a1", clip("m", 0, 200), type = TrackType.AUDIO),
                    track("v2", clip("c", 20, 40), clip("e", 70, 30)),
                    track("v1", clip("a", 0, 100), clip("b", 100, 60), clip("d", 160, 80)),
                ),
                limit = 1000,
            )
            assertEquals(emptyList<String>(), MagneticBase.baseViolations(history.timeline))
            val snapshots = mutableListOf(history.timeline)
            repeat(120) {
                val command = randomCommand(random, history.timeline, board, nextId)
                val result = history.execute(command)
                if (result is EditResult.Success) {
                    history = result.value
                    snapshots += history.timeline
                    applied++
                    if (command.javaClass.simpleName.startsWith("Group")) groupApplied++
                }
                val timeline = history.timeline
                assertTrue("seed $seed after $command: ${timeline.invariantViolations()}", timeline.invariantViolations().isEmpty())
                assertTrue("seed $seed base after $command: ${MagneticBase.baseViolations(timeline)}", MagneticBase.baseViolations(timeline).isEmpty())
            }
            var undone = history
            for (expected in snapshots.asReversed()) {
                assertEquals("seed $seed undo", expected, undone.timeline)
                undone = undone.undo()
            }
            var redone = undone
            for (expected in snapshots) {
                assertEquals("seed $seed redo", expected, redone.timeline)
                redone = redone.redo()
            }
        }
        assertTrue("only $applied edits applied", applied > 1500)
        assertTrue("only $groupApplied group edits applied", groupApplied > 600)
    }

    @Test
    fun `every successful random group move yields a valid timeline and a refusal is a typed failure`() {
        var moved = 0
        for (seed in 1..40) {
            val random = Random(seed)
            val t = timeline(
                track("v2", clip("c", 20, 40), clip("e", 70, 30), clip("f", 120, 20)),
                track("v1", clip("a", 0, 100), clip("b", 100, 60)),
            )
            repeat(100) {
                val result = GroupOps.move(t, pick(random, t), random.nextLong(-80, 200), random.nextInt(-1, 2))
                if (result is EditResult.Success) {
                    moved++
                    assertEquals(emptyList<String>(), result.value.invariantViolations())
                    assertEquals(emptyList<String>(), MagneticBase.baseViolations(result.value))
                }
            }
        }
        assertTrue("only $moved moves applied", moved > 300)
    }
}
