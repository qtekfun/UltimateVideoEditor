package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import com.qtekfun.ultimatevideoeditor.data.voice.VoiceException
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceProblem
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceRecorder
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceRecording
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOverControllerTest {
    private class FakeRecorder : VoiceRecorder {
        var startFails: VoiceProblem? = null
        var stopFails: VoiceProblem? = null
        var takes: VoiceRecording? = VoiceRecording("file:///take.wav", 4_000)
        var started = 0
        var cancelled = 0
        var stopped = 0
        override var level = 0f
        override var elapsedMs = 0L

        override suspend fun start() {
            startFails?.let { throw VoiceException(it) }
            started++
        }

        override suspend fun stop(): VoiceRecording? {
            stopped++
            stopFails?.let { throw VoiceException(it) }
            return takes
        }

        override fun cancel() { cancelled++ }
    }

    private class FakePlayback : VoicePlayback {
        override var isPlaying = false
        var frame = 120L
        var plays = 0
        var pauses = 0
        override fun playheadFrame() = frame
        override fun play() { isPlaying = true; plays++ }
        override fun pause() { isPlaying = false; pauses++ }
    }

    private class Rig(scope: TestScope, granted: Boolean = true) {
        val recorder = FakeRecorder()
        val playback = FakePlayback()
        val takes = mutableListOf<VoiceTake>()
        var asked = 0
        var permission = granted
        val controller = VoiceOverController(
            scope = scope,
            recorder = recorder,
            playback = playback,
            hasPermission = { permission },
            requestPermission = { asked++ },
            onTake = { takes += it },
            now = { scope.currentTime },
            countInSeconds = 3,
            tickMs = 50,
            latencyMs = 0,
        )
    }

    private fun TestScope.rig(granted: Boolean = true) = Rig(this, granted)

    @Test
    fun `it counts in, records while the timeline plays from the playhead, and a stop hands over the take`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(1)
        assertEquals(VoiceState.Counting(3), r.controller.state)
        advanceTimeBy(1_000)
        assertEquals(VoiceState.Counting(2), r.controller.state)
        advanceTimeBy(2_000)
        assertTrue(r.controller.state is VoiceState.Recording)
        assertEquals(1, r.recorder.started)
        assertTrue(r.playback.isPlaying)

        r.recorder.elapsedMs = 1_500
        r.recorder.level = 0.4f
        advanceTimeBy(100)
        assertEquals(VoiceState.Recording(1_500, 0.4f), r.controller.state)

        r.controller.stop()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertFalse(r.playback.isPlaying)
        assertEquals(listOf(VoiceTake("file:///take.wav", 120, 0)), r.takes)
    }

    @Test
    fun `without the permission it asks once, and a yes starts the count-in`() = runTest {
        val r = rig(granted = false)
        r.controller.start()
        assertEquals(VoiceState.AskingPermission, r.controller.state)
        assertEquals(1, r.asked)
        // A second press while the question is open does not ask again.
        r.controller.start()
        assertEquals(1, r.asked)

        r.permission = true
        r.controller.onPermissionResult(true)
        advanceTimeBy(1)
        assertEquals(VoiceState.Counting(3), r.controller.state)
        r.controller.cancel()
    }

    @Test
    fun `a no to the permission is reported and nothing starts`() = runTest {
        val r = rig(granted = false)
        r.controller.start()
        r.controller.onPermissionResult(false)
        assertEquals(VoiceState.Failed(VoiceProblem.NO_PERMISSION), r.controller.state)
        assertEquals(0, r.recorder.started)
        r.controller.dismissFailure()
        assertEquals(VoiceState.Idle, r.controller.state)
        // An answer nobody asked for is ignored.
        r.controller.onPermissionResult(true)
        assertEquals(VoiceState.Idle, r.controller.state)
    }

    @Test
    fun `cancelling during the count-in records nothing and leaves the timeline alone`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(1_500)
        r.controller.stop()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(0, r.recorder.started)
        assertEquals(0, r.playback.plays)
        assertTrue(r.takes.isEmpty())
    }

    @Test
    fun `discarding while recording throws the take away and stops the timeline`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(3_100)
        r.controller.cancel()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.recorder.cancelled)
        assertEquals(0, r.recorder.stopped)
        assertFalse(r.playback.isPlaying)
        assertTrue(r.takes.isEmpty())
    }

    @Test
    fun `a microphone that cannot start says why and leaves the timeline stopped`() = runTest {
        val r = rig()
        r.recorder.startFails = VoiceProblem.MIC_BUSY
        r.controller.start()
        advanceUntilIdle()
        assertEquals(VoiceState.Failed(VoiceProblem.MIC_BUSY), r.controller.state)
        assertEquals(0, r.playback.plays)
        assertTrue(r.takes.isEmpty())
    }

    @Test
    fun `a failure when closing the take is reported and nothing is added`() = runTest {
        val r = rig()
        r.recorder.stopFails = VoiceProblem.DISK
        r.controller.start()
        advanceTimeBy(3_100)
        r.controller.stop()
        advanceUntilIdle()
        assertEquals(VoiceState.Failed(VoiceProblem.DISK), r.controller.state)
        assertTrue(r.takes.isEmpty())
    }

    @Test
    fun `an empty take adds nothing`() = runTest {
        val r = rig()
        r.recorder.takes = null
        r.controller.start()
        advanceTimeBy(3_100)
        r.controller.stop()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertTrue(r.takes.isEmpty())
    }

    @Test
    fun `when the timeline stops by itself the take ends there, but starting it is not a stop`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(3_100)
        // The play the controller asked for has not shown up yet: a "not playing" now is not a stop.
        r.controller.onPlaybackChanged(false)
        assertTrue(r.controller.state is VoiceState.Recording)
        r.controller.onPlaybackChanged(true)
        r.controller.onPlaybackChanged(false)
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.takes.size)
    }

    @Test
    fun `a take that runs to the limit ends by itself`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(3_100)
        r.recorder.elapsedMs = VoiceOverController.MAX_TAKE_MS
        advanceTimeBy(100)
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.takes.size)
    }

    @Test
    fun `leaving the screen keeps a take in progress and drops a count-in`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(3_100)
        r.controller.onLeaving()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.takes.size)

        val q = rig()
        q.controller.start()
        advanceTimeBy(500)
        q.controller.onLeaving()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, q.controller.state)
        assertTrue(q.takes.isEmpty())
        assertEquals(0, q.recorder.started)
    }

    @Test
    fun `the timeline that was playing is paused before the count-in, so the take starts from the playhead`() = runTest {
        val r = rig()
        r.playback.isPlaying = true
        r.controller.start()
        advanceTimeBy(10)
        assertFalse(r.playback.isPlaying)
        r.controller.cancel()
    }

    @Test
    fun `the lead between the microphone and the timeline is cut from the take, with the phone's latency`() = runTest {
        val rr = Rig(this)
        val lagging = VoiceOverController(
            scope = this, recorder = rr.recorder, playback = rr.playback, hasPermission = { true }, requestPermission = {},
            onTake = { rr.takes += it }, now = { currentTime }, countInSeconds = 0, tickMs = 50, latencyMs = 40,
        )
        lagging.start()
        advanceTimeBy(10)
        lagging.stop()
        advanceUntilIdle()
        assertEquals(40L, rr.takes.single().trimMs)
    }

    @Test
    fun `the button is busy only while something is going on`() = runTest {
        val r = rig()
        assertFalse(r.controller.active)
        r.controller.start()
        advanceTimeBy(1)
        assertTrue(r.controller.active)
        r.controller.cancel()
        assertFalse(r.controller.active)
    }
}
