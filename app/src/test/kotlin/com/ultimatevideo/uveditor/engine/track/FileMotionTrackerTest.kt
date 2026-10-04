package com.ultimatevideo.uveditor.engine.track

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.MotionTrack
import com.ultimatevideo.uveditor.domain.TrackSeed
import com.ultimatevideo.uveditor.engine.stabilise.StabJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

@OptIn(ExperimentalCoroutinesApi::class)
class FileMotionTrackerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fps = FrameRate(30, 1)
    private val asset = MediaAssetDto("a1", "content://m/a1", 900, 30, 1, "Rec709-SDR")
    private val second = 1_000_000L
    private val seed = TrackSeed(sourceFrame = 60, cx = 0.4, cy = 0.6, w = 0.1, h = 0.2)
    private val track = MotionTrack("mt1", "c1", "Track 1", seed)

    private fun clip(inFrame: Long, outFrame: Long) =
        Clip(id = "c1", assetId = "a1", timelineStart = FrameIndex(0), sourceIn = FrameIndex(inFrame), sourceOut = FrameIndex(outFrame))

    private class FakeNative : TrackNative {
        class Start(val handle: Long, val fd: Int, val startUs: Long, val endUs: Long, val seedUs: Long, val halfFrameUs: Long, val box: List<Float>, val path: String)

        var nextHandle = 1L
        val destroyed = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        val starts = mutableListOf<Start>()
        var startStatus = 0
        var script: List<Long> = listOf(pack(StabJob.State.DONE, 1000, 0))
        private var polls = 0

        override fun create(): Long = nextHandle++

        override fun destroy(handle: Long) {
            destroyed += handle
        }

        override fun start(handle: Long, fd: Int, startUs: Long, endUs: Long, seedUs: Long, halfFrameUs: Long, cx: Float, cy: Float, w: Float, h: Float, cachePath: String): Int {
            starts += Start(handle, fd, startUs, endUs, seedUs, halfFrameUs, listOf(cx, cy, w, h), cachePath)
            return startStatus
        }

        override fun cancel(handle: Long) {
            cancelled += handle
        }

        override fun poll(handle: Long): Long = script[minOf(polls++, script.size - 1)]

        companion object {
            fun pack(state: StabJob.State, permille: Int, error: Int): Long = state.ordinal.toLong() or (permille.toLong() shl 8) or (error.toLong() shl 24)
        }
    }

    private fun tracker(native: FakeNative, fd: Int? = 77, dir: File = File(folder.root, "track")) =
        FileMotionTracker(dir, { fd }, { 16.0 / 9.0 }, native, pollMillis = 1L)

    /** One sample as the native code writes it (36 bytes). */
    private fun sample(buf: ByteBuffer, ptsUs: Long, cx: Float, cy: Float, lost: Boolean) {
        buf.putLong(ptsUs)
        buf.putFloat(cx)
        buf.putFloat(cy)
        buf.putFloat(0.1f)
        buf.putFloat(0.2f)
        buf.putFloat(0f)
        buf.putFloat(if (lost) 0f else 1f)
        buf.put(if (lost) 1 else 0)
        buf.put(0)
        buf.put(0)
        buf.put(0)
    }

    /** Writes a track cache exactly as `track/track_path.cpp` lays it out, with a valid CRC-32. */
    private fun writeCache(
        file: File,
        startUs: Long,
        endUs: Long,
        samples: Int = 100,
        version: Int = TrackCacheFile.ANALYSIS_VERSION,
        lostFrom: Int = Int.MAX_VALUE,
        rate: FrameRate = fps,
    ) {
        file.parentFile?.mkdirs()
        val body = ByteBuffer.allocate(TrackCacheFile.HEADER_BYTES + samples * TrackCacheFile.SAMPLE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        body.put("UVTK".toByteArray())
        body.putInt(1)
        body.putInt(version)
        body.putFloat(1.7777f)
        body.putLong(2 * second)
        body.putLong(startUs)
        body.putLong(endUs)
        body.putLong(samples.toLong())
        for (i in 0 until samples) {
            sample(body, startUs + rate.framesToMicros(i.toLong()), 0.1f + 0.005f * i, 0.5f, i >= lostFrom)
        }
        val crc = CRC32().apply { update(body.array()) }.value
        val all = ByteBuffer.allocate(body.capacity() + 4).order(ByteOrder.LITTLE_ENDIAN)
        all.put(body.array())
        all.putInt(crc.toInt())
        file.writeBytes(all.array())
    }

    private fun cacheFile(dir: File = File(folder.root, "track")) = File(dir, TrackCacheFile.nameFor(asset, seed))

    // region status and loading

    @Test
    fun `without a cache file the target needs analysing`() {
        assertEquals(TrackStatus.NotAnalysed, tracker(FakeNative()).statusOf(asset, clip(0, 90), track, fps))
    }

    @Test
    fun `a cache that covers the clip is ready and reports the frames that were lost`() {
        val t = tracker(FakeNative())
        writeCache(cacheFile(), startUs = 0, endUs = 3 * second, samples = 91, lostFrom = 80)
        val status = t.statusOf(asset, clip(0, 90), track, fps)
        assertEquals(TrackStatus.Ready(lost = 11, frames = 91), status)
    }

    @Test
    fun `a cache that does not reach the clip is stale and an old analysis version needs analysing again`() {
        val t = tracker(FakeNative())
        writeCache(cacheFile(), startUs = 0, endUs = 2 * second)
        assertEquals(TrackStatus.Stale, t.statusOf(asset, clip(0, 150), track, fps))
        writeCache(cacheFile(), startUs = 0, endUs = 10 * second, version = 0)
        assertEquals(TrackStatus.NotAnalysed, t.statusOf(asset, clip(0, 90), track, fps))
        assertNull(t.load(asset, track, fps))
    }

    @Test
    fun `the clip range is widened to hold a seed outside it`() {
        val t = tracker(FakeNative())
        // The clip was trimmed to 0..30 but the seed (frame 60) is later: the analysis must reach frame 60.
        writeCache(cacheFile(), startUs = 0, endUs = 1 * second)
        assertEquals(TrackStatus.Stale, t.statusOf(asset, clip(0, 30), track, fps))
    }

    @Test
    fun `load turns presentation times into project frames and keeps the lost flags`() {
        val t = tracker(FakeNative())
        writeCache(cacheFile(), startUs = 1 * second, endUs = 4 * second, samples = 10, lostFrom = 7)
        val path = checkNotNull(t.load(asset, track, fps))
        assertEquals(30L, path.frames.first().sourceFrame)
        assertEquals(39L, path.frames.last().sourceFrame)
        assertEquals(1.7777, path.aspect, 1e-3)
        assertEquals(3, path.lostCount)
        assertEquals(0.1, path.frames.first().cx, 1e-6)
        assertTrue(path.frames.last().lost)
    }

    @Test
    fun `frame numbers are exact at a fractional frame rate`() {
        val ntsc = FrameRate(60000, 1001)
        val t = tracker(FakeNative())
        writeCache(cacheFile(), startUs = 0, endUs = 4 * second, samples = 200, rate = ntsc)
        val path = checkNotNull(t.load(asset, track, ntsc))
        for ((i, f) in path.frames.withIndex()) assertEquals(i.toLong(), f.sourceFrame)
    }

    @Test
    fun `a corrupt, truncated or foreign file is not a track`() {
        val t = tracker(FakeNative())
        val file = cacheFile()
        writeCache(file, startUs = 0, endUs = 10 * second)
        val bytes = file.readBytes()
        bytes[60] = (bytes[60] + 1).toByte()  // flipped byte: the checksum no longer matches
        file.writeBytes(bytes)
        assertNull(t.load(asset, track, fps))
        file.writeBytes(bytes.copyOf(bytes.size - 10))
        assertNull(t.load(asset, track, fps))
        assertEquals(TrackStatus.NotAnalysed, t.statusOf(asset, clip(0, 90), track, fps))
        file.writeBytes("not a cache".toByteArray())
        assertNull(t.load(asset, track, fps))
    }

    @Test
    fun `the cache name depends on the target and the media`() {
        val a = TrackCacheFile.nameFor(asset, seed)
        assertEquals(a, TrackCacheFile.nameFor(asset, seed))
        assertNotEquals(a, TrackCacheFile.nameFor(asset, seed.copy(sourceFrame = 61)))
        assertNotEquals(a, TrackCacheFile.nameFor(asset, seed.copy(cx = 0.41)))
        assertNotEquals(a, TrackCacheFile.nameFor(asset, seed.copy(w = 0.2)))
        assertNotEquals(a, TrackCacheFile.nameFor(asset.copy(uri = "content://m/other"), seed))
        assertNotEquals(a, TrackCacheFile.nameFor(asset.copy(durationFrames = 901), seed))
        assertTrue(a.startsWith("a1."))
    }

    // endregion

    // region analysis

    @Test
    fun `analysing starts the native job on the widened range and reports progress`() = runTest {
        val native = FakeNative().apply {
            script = listOf(FakeNative.pack(StabJob.State.RUNNING, 300, 0), FakeNative.pack(StabJob.State.RUNNING, 700, 0), FakeNative.pack(StabJob.State.DONE, 1000, 0))
        }
        val t = tracker(native, fd = 77)
        val progress = mutableListOf<Float>()
        val outcome = t.analyse(asset, clip(30, 120), track, fps) { progress += it }
        assertEquals(TrackOutcome.Done, outcome)
        val start = native.starts.single()
        assertEquals(77, start.fd)
        // The clip plays source frames 30..120 (1 s to 4 s) and the seed (frame 60) is inside it: half a second of margin each side.
        assertEquals(500_000L, start.startUs)
        assertEquals(4_500_000L, start.endUs)
        assertEquals(fps.framesToMicros(60), start.seedUs)
        assertEquals(fps.framesToMicros(1) / 2, start.halfFrameUs)
        assertEquals(listOf(0.4f, 0.6f, 0.1f, 0.2f), start.box)
        assertEquals(cacheFile().path, start.path)
        assertTrue(progress.isNotEmpty() && progress.last() == 1f)
        assertEquals(native.starts.map { it.handle }, native.destroyed)
    }

    @Test
    fun `a failure becomes a message and the worker is still released`() = runTest {
        val native = FakeNative().apply { script = listOf(FakeNative.pack(StabJob.State.FAILED, 100, 4)) }
        val outcome = tracker(native).analyse(asset, clip(0, 90), track, fps) {}
        assertTrue(outcome is TrackOutcome.Failed)
        assertEquals(1, native.destroyed.size)
    }

    @Test
    fun `a job that cannot start, a missing file and a cancelled job are reported`() = runTest {
        val refuses = FakeNative().apply { startStatus = 1 }
        assertTrue(tracker(refuses).analyse(asset, clip(0, 90), track, fps) {} is TrackOutcome.Failed)
        assertEquals(1, refuses.destroyed.size)

        val native = FakeNative()
        assertTrue(tracker(native, fd = null).analyse(asset, clip(0, 90), track, fps) {} is TrackOutcome.Failed)
        assertTrue(native.starts.isEmpty())

        val cancelled = FakeNative().apply { script = listOf(FakeNative.pack(StabJob.State.CANCELLED, 200, 8)) }
        assertEquals(TrackOutcome.Cancelled, tracker(cancelled).analyse(asset, clip(0, 90), track, fps) {})
    }

    @Test
    fun `forgetting a track deletes its analysis`() {
        val t = tracker(FakeNative())
        writeCache(cacheFile(), startUs = 0, endUs = 10 * second)
        assertTrue(cacheFile().isFile)
        t.forget(asset, track)
        assertFalse(cacheFile().isFile)
        assertEquals(TrackStatus.NotAnalysed, t.statusOf(asset, clip(0, 90), track, fps))
    }

    // endregion
}
