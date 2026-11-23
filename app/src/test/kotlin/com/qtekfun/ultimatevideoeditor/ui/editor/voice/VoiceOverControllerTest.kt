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
        var pauses = 0
        var resumes = 0
        var paused = false

        override fun pause() { pauses++; paused = true }

        override fun resume() { resumes++; paused = false }

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
        var end = 1_000L
        var plays = 0
        var pauses = 0
        val seeks = mutableListOf<Long>()
        override fun playheadFrame() = frame
        override fun endFrame() = end
        override fun play() { isPlaying = true; plays++ }
        override fun pause() { isPlaying = false; pauses++ }
        override fun seek(frame: Long) { this.frame = frame; seeks += frame }

        /** The person presses the transport's play/pause button while the controller is told about it, as the screen does. */
        fun userToggles(controller: VoiceOverController) {
            isPlaying = !isPlaying
            controller.onPlaybackChanged(isPlaying)
        }
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
    fun `when the timeline reaches its end the take ends there, but starting it is not a stop`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(3_100)
        // A "not playing" that lags behind the play the controller asked for is not a stop.
        r.controller.onPlaybackChanged(false)
        assertTrue(r.controller.state is VoiceState.Recording)
        r.controller.onPlaybackChanged(true)
        r.playback.isPlaying = false
        r.playback.frame = r.playback.end
        r.controller.onPlaybackChanged(false)
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.takes.size)
    }

    private fun TestScope.recordingRig(): Rig {
        val r = rig()
        r.controller.start()
        advanceTimeBy(3_100)
        r.recorder.elapsedMs = 4_000
        advanceTimeBy(100)
        assertTrue(r.controller.state is VoiceState.Recording)
        return r
    }

    @Test
    fun `pausing stops the microphone and the timeline together and keeps the clock where it was`() = runTest {
        val r = recordingRig()
        r.playback.frame = 200
        r.controller.pause()
        assertEquals(VoiceState.Paused(4_000), r.controller.state)
        assertEquals(1, r.recorder.pauses)
        assertTrue(r.recorder.paused)
        assertFalse(r.playback.isPlaying)
        // The state does not tick on while paused, and the stale "stopped" from our own pause is not another event.
        r.recorder.elapsedMs = 9_999
        advanceTimeBy(1_000)
        r.controller.onPlaybackChanged(false)
        assertEquals(VoiceState.Paused(4_000), r.controller.state)
        assertTrue(r.takes.isEmpty())
        assertTrue(r.controller.active)
    }

    @Test
    fun `resuming continues from the frame it paused at and appends to the same take`() = runTest {
        val r = recordingRig()
        r.playback.frame = 200
        r.controller.pause()
        r.controller.resume()
        assertTrue(r.controller.state is VoiceState.Recording)
        assertEquals(1, r.recorder.resumes)
        assertTrue(r.playback.isPlaying)
        // The playhead had not moved: nothing to seek back.
        assertTrue(r.playback.seeks.isEmpty())

        r.recorder.elapsedMs = 5_000
        advanceTimeBy(100)
        assertEquals(VoiceState.Recording(5_000, 0f), r.controller.state)
        r.controller.stop()
        advanceUntilIdle()
        // One recorder, one start, one take: it starts where the recording began, not where it resumed.
        assertEquals(1, r.recorder.started)
        assertEquals(listOf(VoiceTake("file:///take.wav", 120, 0)), r.takes)
    }

    @Test
    fun `resuming after the playhead was moved seeks back to where the take paused`() = runTest {
        val r = recordingRig()
        r.playback.frame = 300
        r.controller.pause()
        r.playback.frame = 700 // the person scrubbed
        r.controller.resume()
        assertEquals(listOf(300L), r.playback.seeks)
        assertEquals(300L, r.playback.frame)
        assertTrue(r.playback.isPlaying)
        r.controller.cancel()
    }

    @Test
    fun `the transport's play button pauses the take and resumes it, from where the take paused`() = runTest {
        val r = recordingRig()
        r.playback.frame = 400
        r.playback.userToggles(r.controller) // pause
        assertEquals(VoiceState.Paused(4_000), r.controller.state)
        assertEquals(1, r.recorder.pauses)
        assertTrue(r.takes.isEmpty())

        r.playback.frame = 650 // scrubbed, then play
        r.playback.userToggles(r.controller)
        assertTrue(r.controller.state is VoiceState.Recording)
        assertEquals(1, r.recorder.resumes)
        assertEquals(listOf(400L), r.playback.seeks)
        assertEquals(400L, r.playback.frame)
        assertTrue(r.playback.isPlaying)

        // And it can be paused again: the play that resume asked for is seen as playing.
        advanceTimeBy(100)
        r.playback.userToggles(r.controller)
        assertTrue(r.controller.state is VoiceState.Paused)
        assertEquals(2, r.recorder.pauses)
    }

    @Test
    fun `a pause press while not recording does nothing, and so does a resume while recording`() = runTest {
        val r = rig()
        r.controller.pause()
        r.controller.resume()
        assertEquals(VoiceState.Idle, r.controller.state)
        val q = recordingRig()
        q.controller.resume()
        assertEquals(0, q.recorder.resumes)
        q.controller.cancel()
    }

    @Test
    fun `stop from the paused state keeps the take`() = runTest {
        val r = recordingRig()
        r.controller.pause()
        r.controller.stop()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.recorder.stopped)
        assertEquals(1, r.takes.size)
        assertFalse(r.playback.isPlaying)
    }

    @Test
    fun `discard from the paused state drops the take`() = runTest {
        val r = recordingRig()
        r.controller.pause()
        r.controller.cancel()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.recorder.cancelled)
        assertEquals(0, r.recorder.stopped)
        assertTrue(r.takes.isEmpty())
    }

    @Test
    fun `leaving the screen while paused keeps what was recorded`() = runTest {
        val r = recordingRig()
        r.controller.pause()
        r.controller.onLeaving()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.takes.size)
    }

    @Test
    fun `paused time does not count towards the maximum take length`() = runTest {
        val r = recordingRig()
        r.recorder.elapsedMs = VoiceOverController.MAX_TAKE_MS - 1_000
        advanceTimeBy(100)
        r.controller.pause()
        // Hours pass; the recorder's clock holds still while paused, as the real one does.
        advanceTimeBy(3 * 60 * 60 * 1000L)
        assertEquals(VoiceState.Paused(VoiceOverController.MAX_TAKE_MS - 1_000), r.controller.state)
        assertTrue(r.takes.isEmpty())

        r.controller.resume()
        r.recorder.elapsedMs = VoiceOverController.MAX_TAKE_MS
        advanceTimeBy(100)
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        assertEquals(1, r.takes.size)
    }

    @Test
    fun `discarding asks first only for a take longer than three seconds`() = runTest {
        val r = rig()
        assertFalse(r.controller.discardNeedsConfirmation)
        r.controller.start()
        advanceTimeBy(3_050)
        r.recorder.elapsedMs = 2_900
        advanceTimeBy(100)
        assertFalse(r.controller.discardNeedsConfirmation)
        r.recorder.elapsedMs = 3_100
        advanceTimeBy(100)
        assertTrue(r.controller.discardNeedsConfirmation)
        r.controller.pause()
        assertTrue(r.controller.discardNeedsConfirmation)
        r.controller.cancel()
        assertFalse(r.controller.discardNeedsConfirmation)
    }

    @Test
    fun `after the take lands it offers undo and another take, and goes away by itself`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(3_100)
        r.controller.stop()
        advanceUntilIdle()
        assertEquals(VoiceState.Idle, r.controller.state)
        r.controller.onPlaced()
        assertEquals(VoiceState.Added, r.controller.state)
        assertFalse(r.controller.active)

        advanceTimeBy(VoiceOverController.ADDED_SHOWN_MS + 10)
        assertEquals(VoiceState.Idle, r.controller.state)

        // Record again from the confirmation starts a new take at once.
        r.controller.onPlaced()
        r.controller.start()
        advanceTimeBy(1)
        assertEquals(VoiceState.Counting(3), r.controller.state)
        r.controller.cancel()

        // Dismissing is immediate, and a placement reported while something else is going on is not shown.
        r.controller.onPlaced()
        r.controller.dismissAdded()
        assertEquals(VoiceState.Idle, r.controller.state)
        r.controller.start()
        advanceTimeBy(1)
        r.controller.onPlaced()
        assertEquals(VoiceState.Counting(3), r.controller.state)
        r.controller.cancel()
    }

    @Test
    fun `a first press asks for the microphone, and the answer starts the count-in once`() = runTest {
        val r = rig(granted = false)
        r.controller.start()
        assertEquals(VoiceState.AskingPermission, r.controller.state)
        assertEquals(1, r.asked)
        assertTrue(r.controller.active)
        r.permission = true
        r.controller.onPermissionResult(true)
        advanceTimeBy(3_100)
        assertTrue(r.controller.state is VoiceState.Recording)
        assertEquals(1, r.recorder.started)
        assertEquals(1, r.asked)
        r.controller.cancel()
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
