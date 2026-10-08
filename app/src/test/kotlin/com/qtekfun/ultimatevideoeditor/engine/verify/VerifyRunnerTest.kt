package com.qtekfun.ultimatevideoeditor.engine.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyRunnerTest {
    private val total = 300L // 10 s at 30 fps
    private val exp = VerifyExpectation(total, 30, 1, hasAudio = true, hdr = false)
    private val probes = SyntheticSignatures.probes(total)
    private val runner = VerifyRunner()

    /** A decoder that returns `produce(index)` for every requested frame (null: the frame is missing), or fails at [errorAt]. */
    private class FakeFrames(
        private val exp: VerifyExpectation,
        private val errorAt: Long = Long.MAX_VALUE,
        private val produce: (Long) -> FrameSignature?,
    ) : FrameSource {
        val requests = mutableListOf<LongRange>()

        override fun decode(from: Long, to: Long, cancel: () -> Boolean): DecodedRange {
            requests += from..to
            val frames = mutableListOf<DecodedFrame>()
            for (i in from..to) {
                if (i >= errorAt) return DecodedRange(from, to, frames, "decoder error 0x80000001")
                val sig = produce(i) ?: continue
                frames += DecodedFrame(i, sig)
            }
            return DecodedRange(from, to, frames)
        }
    }

    private fun file(frames: Int = total.toInt(), audio: Int = 469) = Mp4TestBuilder(frames, 30, 1, audioSamples = audio).build()

    private fun run(
        bytes: ByteArray,
        frames: FrameSource?,
        signatures: List<FrameSignature> = probes,
        cancel: () -> Boolean = { false },
        expectation: VerifyExpectation = exp,
    ) = runner.run(ArrayByteSource(bytes), frames, expectation, signatures, cancel)

    private fun good() = FakeFrames(exp) { SyntheticSignatures.frame(it) }

    private fun warning(o: VerificationOutcome) = o as VerificationOutcome.Warning

    @Test
    fun `a clean file is verified and the probes were compared`() {
        val source = good()
        val outcome = run(file().bytes, source)

        assertTrue(outcome.toString(), outcome is VerificationOutcome.Verified)
        val facts = (outcome as VerificationOutcome.Verified).facts
        assertEquals(total, facts.frames)
        assertEquals(2, facts.probesCompared)
        assertEquals("verification=verified", VerificationText.resultLine(outcome).substringBefore(" frames"))
    }

    @Test
    fun `only the first second and the last three seconds are decoded`() {
        val source = good()
        run(file().bytes, source)

        assertEquals(listOf(0L..29L, 210L..299L), source.requests)
    }

    @Test
    fun `lossy coding noise stays inside the tolerance`() {
        val outcome = run(file().bytes, FakeFrames(exp) { SyntheticSignatures.lossy(it, noise = 0.015) })

        assertTrue(outcome.toString(), outcome is VerificationOutcome.Verified)
    }

    @Test
    fun `a file cut off before its index is a container warning`() {
        val built = file()
        val outcome = run(built.bytes.copyOf(built.moovOffset.toInt() - 1000), good())

        val w = warning(outcome)
        assertEquals(listOf(VerifyCheck.CONTAINER), w.findings.map { it.check })
        assertTrue(VerificationText.headline(w).startsWith("WARNING"))
    }

    @Test
    fun `a zeroed tail that took the index with it is a container warning`() {
        val built = file()
        val bytes = built.bytes.copyOf()
        java.util.Arrays.fill(bytes, bytes.size - 4000, bytes.size, 0)

        val w = warning(run(bytes, good())) // whichever check sees it first, it is never "verified"

        assertTrue(w.findings.isNotEmpty())
    }

    @Test
    fun `the last frame dropped from the file is a frame count warning with one frame at the tail`() {
        val outcome = run(file(frames = 299).bytes, FakeFrames(exp) { if (it < 299) SyntheticSignatures.frame(it) else null })

        val w = warning(outcome)
        assertTrue(w.findings.any { it.check == VerifyCheck.FRAME_COUNT })
        assertEquals(1L, w.damagedTailFrames)
        assertEquals("WARNING: the last frame looks missing", VerificationText.headline(w))
    }

    @Test
    fun `the last GOP missing is reported with its length`() {
        // The file ends at frame 270: the container holds 270 frames and the decoder has nothing past them.
        val outcome = run(file(frames = 270).bytes, FakeFrames(exp) { if (it < 270) SyntheticSignatures.frame(it) else null })

        val w = warning(outcome)
        assertEquals(30L, w.damagedTailFrames)
        assertTrue(VerificationText.headline(w), VerificationText.headline(w).contains("last 30 frames look missing"))
    }

    @Test
    fun `a decoder error near the end says how many frames are lost`() {
        val outcome = run(file().bytes, FakeFrames(exp, errorAt = 280) { SyntheticSignatures.frame(it) })

        val w = warning(outcome)
        assertTrue(w.findings.any { it.check == VerifyCheck.DECODE })
        assertEquals(20L, w.findings.first { it.check == VerifyCheck.DECODE }.tailFrames)
        assertTrue(VerificationText.headline(w).contains("last 20 frames look damaged"))
    }

    @Test
    fun `black frames at the end where the picture is not black are a damaged tail`() {
        val outcome = run(file().bytes, FakeFrames(exp) { if (it >= 285) SyntheticSignatures.flat(it) else SyntheticSignatures.frame(it) })

        val w = warning(outcome)
        assertTrue(w.findings.any { it.check == VerifyCheck.PICTURE })
        assertTrue(w.findings.any { it.check == VerifyCheck.FLAT_FRAME })
        assertTrue("tail was ${w.damagedTailFrames}", w.damagedTailFrames in 10..15)
    }

    @Test
    fun `garbage frames that are not flat still differ from what was encoded`() {
        val outcome = run(file().bytes, FakeFrames(exp) { if (it >= 285) SyntheticSignatures.frame(it + 1000) else SyntheticSignatures.frame(it) })

        assertTrue(warning(outcome).findings.any { it.check == VerifyCheck.PICTURE })
    }

    @Test
    fun `a black ending that the encoder was fed is not a problem`() {
        // A fade to black: the last frames of the encoder's input were black too.
        val fade = SyntheticSignatures.probes(total).map { if (it.frame >= 290) SyntheticSignatures.flat(it.frame) else it }
        val outcome = run(file().bytes, FakeFrames(exp) { if (it >= 290) SyntheticSignatures.flat(it) else SyntheticSignatures.frame(it) }, fade)

        assertTrue(outcome.toString(), outcome is VerificationOutcome.Verified)
    }

    @Test
    fun `the file showing frames one second later is caught`() {
        val outcome = run(file().bytes, FakeFrames(exp) { SyntheticSignatures.frame(it + 30) })

        assertTrue(warning(outcome).findings.any { it.check == VerifyCheck.PICTURE })
    }

    @Test
    fun `frames that come out of order are reported`() {
        val source = object : FrameSource {
            override fun decode(from: Long, to: Long, cancel: () -> Boolean): DecodedRange {
                val list = (from..to).map { DecodedFrame(it, SyntheticSignatures.frame(it)) }.toMutableList()
                if (list.size > 5) java.util.Collections.swap(list, 2, 3)
                return DecodedRange(from, to, list)
            }
        }

        assertTrue(warning(run(file().bytes, source)).findings.any { it.check == VerifyCheck.FRAME_ORDER })
    }

    @Test
    fun `audio shorter than the picture is reported`() {
        val outcome = run(file(audio = 400).bytes, good())

        assertTrue(warning(outcome).findings.any { it.check == VerifyCheck.AUDIO && it.message.contains("shorter") })
    }

    @Test
    fun `audio that is a few frames longer than the picture is fine`() {
        // 469 samples are 10.005 s; real exports measured +51 ms.
        assertTrue(run(file(audio = 471).bytes, good()) is VerificationOutcome.Verified)
    }

    @Test
    fun `a missing audio track when the project has sound is reported`() {
        assertTrue(warning(run(file(audio = 0).bytes, good())).findings.any { it.check == VerifyCheck.AUDIO })
    }

    @Test
    fun `zeroed frame data in the last frames is found without decoding`() {
        val built = file()
        val bytes = built.bytes.copyOf()
        for (i in 255 until 300) java.util.Arrays.fill(bytes, built.videoOffsets[i].toInt(), built.videoOffsets[i].toInt() + built.videoSizes[i], 0)

        val w = warning(run(bytes, good()))

        val finding = w.findings.first { it.check == VerifyCheck.SAMPLE_DATA }
        assertEquals(45L, finding.tailFrames.coerceAtMost(45))
        assertTrue(w.damagedTailFrames >= 45)
    }

    @Test
    fun `cancelling skips the check and says so`() {
        val outcome = run(file().bytes, good(), cancel = { true })

        assertEquals(VerificationOutcome.Skipped, outcome)
        assertEquals("Verification skipped (cancelled)", VerificationText.headline(outcome))
        assertFalse(outcome.isVerified)
    }

    @Test
    fun `cancelling in the middle of decoding skips the check`() {
        var polls = 0
        val outcome = run(file().bytes, good(), cancel = { ++polls > 2 })

        assertEquals(VerificationOutcome.Skipped, outcome)
    }

    @Test
    fun `no decoder means could not verify, never verified`() {
        val outcome = run(file().bytes, null)

        assertTrue(outcome is VerificationOutcome.CouldNotVerify)
        assertEquals("Could not check the file", VerificationText.headline(outcome))
    }

    @Test
    fun `no recorded pictures means could not verify`() {
        val outcome = run(file().bytes, good(), signatures = emptyList())

        assertTrue(outcome is VerificationOutcome.CouldNotVerify)
    }

    @Test
    fun `without a decoder a structural problem is still reported`() {
        val outcome = run(file(frames = 299).bytes, null)

        assertTrue(outcome is VerificationOutcome.Warning)
    }

    @Test
    fun `the plan of a long export is small`() {
        val long = VerifyExpectation(12 * 60 * 60L, 60, 1, true, true) // 12 minutes at 60 fps
        val probesLong = SyntheticSignatures.probes(long.totalFrames, 60)
        val plan = runner.plan(long, probesLong)

        assertEquals(60L, plan.head.last - plan.head.first + 1) // the first second at 60 fps
        assertEquals(180L, checkNotNull(plan.tail).let { it.last - it.first + 1 })
        assertTrue("decoded ${plan.frameCount}", plan.frameCount < 300)
    }

    @Test
    fun `progress goes up to the end`() {
        val seen = mutableListOf<Int>()
        runner.run(ArrayByteSource(file().bytes), good(), exp, probes, { false }) { seen += it }

        assertEquals(1000, seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun `a short movie is checked whole`() {
        val short = VerifyExpectation(20, 30, 1, hasAudio = false, hdr = false)
        val sigs = SyntheticSignatures.probes(20)
        val outcome = run(Mp4TestBuilder(20, 30, 1).build().bytes, FakeFrames(short) { SyntheticSignatures.frame(it) }, sigs, expectation = short)

        assertTrue(outcome.toString(), outcome is VerificationOutcome.Verified)
    }
}
