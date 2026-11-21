package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.view.Surface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePreviewMathTest {
    @Test
    fun `the slider and the position map both ways and stay inside the file`() {
        assertEquals(5_000L, SourcePreviewMath.positionAt(0.5f, 10_000))
        assertEquals(0L, SourcePreviewMath.positionAt(-1f, 10_000))
        assertEquals(10_000L, SourcePreviewMath.positionAt(7f, 10_000))
        assertEquals(0L, SourcePreviewMath.positionAt(0.5f, 0))
        assertEquals(0.25f, SourcePreviewMath.fractionOf(2_500, 10_000), 0.0001f)
        assertEquals(1f, SourcePreviewMath.fractionOf(99_000, 10_000), 0.0001f)
        assertEquals(0f, SourcePreviewMath.fractionOf(5, 0), 0.0001f)
        assertEquals(0f, SourcePreviewMath.fractionOf(-5, 10_000), 0.0001f)
    }

    @Test
    fun `the facts read the way a person would say them`() {
        assertEquals("1920 x 1080", SourcePreviewMath.sizeText(1920, 1080))
        assertNull(SourcePreviewMath.sizeText(0, 1080))
        assertNull(SourcePreviewMath.sizeText(null, null))
        assertEquals("30 fps", SourcePreviewMath.rateText(30f))
        assertEquals("29.97 fps", SourcePreviewMath.rateText(29.97f))
        assertEquals("23.98 fps", SourcePreviewMath.rateText(23.976f))
        assertNull(SourcePreviewMath.rateText(0f))
        assertNull(SourcePreviewMath.rateText(Float.NaN))
        assertEquals("12.4 Mbit/s", SourcePreviewMath.bitrateText(12_400_000))
        assertEquals("850 kbit/s", SourcePreviewMath.bitrateText(850_000))
        assertNull(SourcePreviewMath.bitrateText(0))
        assertEquals("MP4", SourcePreviewMath.formatText("video/mp4"))
        assertNull(SourcePreviewMath.formatText(null))
        assertNull(SourcePreviewMath.formatText("video/"))
    }

    @Test
    fun `only the known facts are listed, in a fixed order`() {
        assertEquals(
            listOf("3840 x 2160", "60 fps", "MP4", "40.0 Mbit/s"),
            SourcePreviewMath.facts(SourceInfo(10_000, 3840, 2160, 60f, "video/mp4", 40_000_000)),
        )
        assertEquals(listOf("MP3"), SourcePreviewMath.facts(SourceInfo(1000, null, null, null, "audio/mp3", null)))
        assertEquals(emptyList<String>(), SourcePreviewMath.facts(null))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SourcePreviewControllerTest {
    private val video = SourceItem("content://x/a.mp4", "a.mp4", AssetKind.VIDEO, 100)
    private val song = SourceItem("content://x/s.wav", "s.wav", AssetKind.AUDIO)

    private class FakePlayer(val listener: AuditionPlayer.Listener) : AuditionPlayer {
        val calls = mutableListOf<String>()
        override var positionMs = 0L
        var released = false
        var surfaces = 0

        override fun open(item: SourceItem) { calls += "open:${item.name}" }
        override fun setSurface(surface: Surface?) { surfaces++ }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(ms: Long) { calls += "seek:$ms"; positionMs = ms }
        override fun setMuted(muted: Boolean) { calls += "mute:$muted" }
        override fun setLooping(looping: Boolean) { calls += "loop:$looping" }
        override fun release() { released = true }
    }

    private class Rig(scope: TestScope, info: SourceInfo? = null) {
        val players = mutableListOf<FakePlayer>()
        val controller = SourcePreviewController(scope, { listener -> FakePlayer(listener).also { players += it } }, { info }, tickMs = 100)
        val player get() = players.last()
    }

    private fun TestScope.rig(info: SourceInfo? = null) = Rig(this, info)

    @Test
    fun `opening a file starts loading, and when the player is ready it is paused at the start`() = runTest {
        val r = rig(SourceInfo(10_000, 1920, 1080, 30f, "video/mp4", null))
        r.controller.open(video)
        assertEquals(SourcePhase.Loading, r.controller.phase)
        assertEquals(video, r.controller.item)
        assertEquals(listOf("mute:false", "loop:false", "open:a.mp4"), r.player.calls)

        r.player.listener.onPrepared(10_000, 1920, 1080)
        advanceUntilIdle()
        assertEquals(SourcePhase.Ready, r.controller.phase)
        assertEquals(10_000L, r.controller.durationMs)
        assertEquals(1920 to 1080, r.controller.videoSize)
        assertFalse(r.controller.playing)
        assertEquals(1920, r.controller.info?.width)
    }

    @Test
    fun `sound and pictures have no picture size`() = runTest {
        val r = rig()
        r.controller.open(song)
        r.player.listener.onPrepared(4_000, 0, 0)
        assertNull(r.controller.videoSize)
        assertEquals(SourcePhase.Ready, r.controller.phase)
    }

    @Test
    fun `play and pause follow the position while it plays`() = runTest {
        val r = rig()
        r.controller.open(video)
        r.player.listener.onPrepared(10_000, 100, 100)
        r.controller.togglePlay()
        assertTrue(r.controller.playing)
        r.player.positionMs = 1_200
        advanceTimeBy(250)
        assertEquals(1_200L, r.controller.positionMs)

        r.player.positionMs = 2_000
        r.controller.togglePlay()
        assertFalse(r.controller.playing)
        assertEquals(2_000L, r.controller.positionMs)
        // Paused: the position no longer follows the player.
        r.player.positionMs = 9_000
        advanceTimeBy(500)
        assertEquals(2_000L, r.controller.positionMs)
        assertEquals(listOf("play", "pause"), r.player.calls.filter { it == "play" || it == "pause" })
    }

    @Test
    fun `nothing plays or seeks before the file is ready`() = runTest {
        val r = rig()
        r.controller.open(video)
        r.controller.togglePlay()
        r.controller.seekToFraction(0.5f)
        assertFalse(r.controller.playing)
        assertEquals(listOf("mute:false", "loop:false", "open:a.mp4"), r.player.calls)
    }

    @Test
    fun `seeking jumps to the fraction of the file`() = runTest {
        val r = rig()
        r.controller.open(video)
        r.player.listener.onPrepared(10_000, 100, 100)
        r.controller.seekToFraction(0.3f)
        assertEquals(3_000L, r.controller.positionMs)
        assertTrue("seek:3000" in r.player.calls)
        r.controller.seekToFraction(5f)
        assertEquals(10_000L, r.controller.positionMs)
    }

    @Test
    fun `at the end the file stops, and the next play starts again from the top`() = runTest {
        val r = rig()
        r.controller.open(video)
        r.player.listener.onPrepared(10_000, 100, 100)
        r.controller.togglePlay()
        r.player.listener.onCompleted()
        assertFalse(r.controller.playing)
        assertEquals(10_000L, r.controller.positionMs)

        r.controller.togglePlay()
        assertTrue(r.controller.playing)
        assertTrue("seek:0" in r.player.calls)
        assertEquals(0L, r.controller.positionMs)
        // The ticker of a running preview is stopped with it, so no job outlives the file.
        r.controller.close()
    }

    @Test
    fun `mute and repeat reach the player and are kept for the next file`() = runTest {
        val r = rig()
        r.controller.open(video)
        r.controller.toggleMute()
        r.controller.toggleLooping()
        assertTrue(r.player.calls.containsAll(listOf("mute:true", "loop:true")))
        r.controller.open(song)
        assertEquals(listOf("mute:true", "loop:true", "open:s.wav"), r.players[1].calls)
    }

    @Test
    fun `a failure says why and stops everything`() = runTest {
        val r = rig()
        r.controller.open(video)
        r.player.listener.onPrepared(10_000, 100, 100)
        r.controller.togglePlay()
        r.player.listener.onFailed(SourceProblem.UNSUPPORTED)
        assertEquals(SourcePhase.Failed(SourceProblem.UNSUPPORTED), r.controller.phase)
        assertFalse(r.controller.playing)
        r.controller.togglePlay()
        assertFalse(r.controller.playing)
    }

    @Test
    fun `while a movie is exported nothing is opened`() = runTest {
        val r = rig()
        r.controller.busy = true
        r.controller.open(video)
        assertEquals(SourcePhase.Failed(SourceProblem.BUSY), r.controller.phase)
        assertTrue(r.players.isEmpty())
        r.controller.busy = false
        r.controller.open(video)
        assertEquals(SourcePhase.Loading, r.controller.phase)
        assertEquals(1, r.players.size)
    }

    @Test
    fun `closing lets go of the player and forgets the file`() = runTest {
        val r = rig()
        r.controller.open(video)
        r.player.listener.onPrepared(10_000, 100, 100)
        r.controller.togglePlay()
        r.controller.close()
        assertTrue(r.player.released)
        assertNull(r.controller.item)
        assertFalse(r.controller.playing)
        assertEquals(0L, r.controller.positionMs)
        // The ticker is gone: a later position does not come back.
        r.player.positionMs = 5_000
        advanceTimeBy(500)
        assertEquals(0L, r.controller.positionMs)
    }

    @Test
    fun `opening another file replaces the player, and the old one is ignored when it answers late`() = runTest {
        val r = rig()
        r.controller.open(video)
        val first = r.player
        r.controller.open(song)
        assertTrue(first.released)
        assertEquals(2, r.players.size)

        first.listener.onPrepared(99_000, 100, 100)
        first.listener.onFailed(SourceProblem.UNREADABLE)
        assertEquals(SourcePhase.Loading, r.controller.phase)
        assertEquals(0L, r.controller.durationMs)
        r.players[1].listener.onPrepared(4_000, 0, 0)
        assertEquals(4_000L, r.controller.durationMs)
    }

    @Test
    fun `facts that arrive after the file was closed are dropped`() = runTest {
        val r = rig(SourceInfo(1, 1, 1, 1f, "video/mp4", 1))
        r.controller.open(video)
        r.controller.close()
        advanceUntilIdle()
        assertNull(r.controller.info)
    }
}
