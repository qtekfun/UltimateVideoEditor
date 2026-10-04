package com.ultimatevideo.uveditor.engine.stabilise

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.StabCrop
import com.ultimatevideo.uveditor.domain.StabKey
import com.ultimatevideo.uveditor.domain.Stabilise
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

@OptIn(ExperimentalCoroutinesApi::class)
class FileStabiliserTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fps = FrameRate(30, 1)
    private val asset = MediaAssetDto("a1", "content://m/a1", 900, 30, 1, "Rec709-SDR")
    private val second = 1_000_000L

    private fun clip(inFrame: Long, outFrame: Long, stabilise: Stabilise? = Stabilise()) =
        Clip(id = "c1", assetId = "a1", timelineStart = FrameIndex(0), sourceIn = FrameIndex(inFrame), sourceOut = FrameIndex(outFrame), stabilise = stabilise)

    private class FakeNative : StabNative {
        class Start(val handle: Long, val fd: Int, val startUs: Long, val endUs: Long, val path: String)
        class Register(val key: Int, val path: String, val fpsNum: Int, val fpsDen: Int, val strength: Float, val crop: Int)

        var nextHandle = 1L
        val created = mutableListOf<Long>()
        val destroyed = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        val starts = mutableListOf<Start>()
        val registers = mutableListOf<Register>()
        val released = mutableListOf<Int>()
        var releasedAll = 0
        var startStatus = 0
        var registerStatus = 0
        val registeredKeys = mutableSetOf<Int>()
        /** Successive answers to poll(); the last one repeats. */
        var script: List<Long> = listOf(pack(StabJob.State.DONE, 1000, 0))
        private var polls = 0

        override fun create(): Long = nextHandle++.also { created += it }

        override fun destroy(handle: Long) {
            destroyed += handle
        }

        override fun start(handle: Long, fd: Int, startUs: Long, endUs: Long, cachePath: String): Int {
            starts += Start(handle, fd, startUs, endUs, cachePath)
            return startStatus
        }

        override fun cancel(handle: Long) {
            cancelled += handle
        }

        override fun poll(handle: Long): Long = script[minOf(polls++, script.size - 1)]

        override fun register(key: Int, cachePath: String, fpsNum: Int, fpsDen: Int, strength: Float, crop: Int): Int {
            registers += Register(key, cachePath, fpsNum, fpsDen, strength, crop)
            if (registerStatus == 0) registeredKeys += key
            return registerStatus
        }

        override fun release(key: Int) {
            released += key
            registeredKeys -= key
        }

        override fun releaseAll() {
            releasedAll++
            registeredKeys.clear()
        }

        override fun isRegistered(key: Int): Boolean = key in registeredKeys

        companion object {
            fun pack(state: StabJob.State, permille: Int, error: Int): Long =
                state.ordinal.toLong() or (permille.toLong() shl 8) or (error.toLong() shl 24)
        }
    }

    private fun stabiliser(native: FakeNative, fd: Int? = 77, dir: File = File(folder.root, "stab")) =
        FileStabiliser(dir, { fd }, native, pollMillis = 1L)

    /** Fails the way the real library crashes: a call on a service that was already deleted. */
    private class StrictNative(private val inner: FakeNative) : StabNative by inner {
        val gone = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        val usedAfterDestroy = java.util.concurrent.atomic.AtomicInteger()

        override fun destroy(handle: Long) {
            gone += handle
            inner.destroy(handle)
        }

        override fun cancel(handle: Long) {
            if (handle in gone) usedAfterDestroy.incrementAndGet()
            inner.cancel(handle)
        }

        override fun poll(handle: Long): Long {
            if (handle in gone) usedAfterDestroy.incrementAndGet()
            return inner.poll(handle)
        }
    }

    // A cancel from the UI racing with the end of the analysis must never reach a native service that was just
    // destroyed (a crash of the real library: the handle was read under the lock but used after releasing it).
    @Test
    fun `cancel never touches a service the analysis has already destroyed`() {
        val native = StrictNative(FakeNative())
        val stabiliser = FileStabiliser(File(folder.root, "stab"), { 77 }, native, pollMillis = 1L)
        val c = clip(0, 90)
        repeat(300) {
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val canceller = Thread {
                while (running.get()) stabiliser.cancel()
            }
            canceller.start()
            runBlocking { stabiliser.analyse(asset, c, fps) { } }
            running.set(false)
            canceller.join()
        }
        assertEquals(0, native.usedAfterDestroy.get())
    }

    /** Writes a structurally valid cache file as the native code lays it out (the checksum is native's business). */
    private fun writeCache(file: File, startUs: Long, endUs: Long, samples: Int = 100, version: Int = StabCacheFile.ANALYSIS_VERSION) {
        file.parentFile?.mkdirs()
        val buf = ByteBuffer.allocate(StabCacheFile.HEADER_BYTES + samples * 28 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("UVST".toByteArray())
        buf.putInt(1)
        buf.putInt(version)
        buf.putFloat(1.7777f)
        buf.putInt(480)
        buf.putInt(270)
        buf.putLong(startUs)
        buf.putLong(endUs)
        buf.putLong(samples.toLong())
        file.writeBytes(buf.array())
    }

    private fun cacheOf(s: FileStabiliser, dir: File = File(folder.root, "stab")) = File(dir, StabCacheFile.nameFor(asset))

    // region status

    @Test
    fun `a clip with the stabiliser off, or an asset without picture, has no status to show`() {
        val s = stabiliser(FakeNative())
        assertEquals(StabStatus.Off, s.statusOf(asset, clip(0, 90, stabilise = null), fps))
        assertEquals(StabStatus.Off, s.statusOf(asset.copy(hasVideo = false), clip(0, 90), fps))
        assertEquals(StabStatus.Off, s.statusOf(asset.copy(isImage = true, hasVideo = false), clip(0, 90), fps))
    }

    @Test
    fun `without a cache file the clip needs analysing`() {
        assertEquals(StabStatus.NotAnalysed, stabiliser(FakeNative()).statusOf(asset, clip(0, 90), fps))
    }

    @Test
    fun `a cache that covers the clip is ready, one that does not is stale`() {
        val s = stabiliser(FakeNative())
        writeCache(cacheOf(s), startUs = 0, endUs = 10 * second)
        assertEquals(StabStatus.Ready, s.statusOf(asset, clip(30, 270), fps))   // 1 s to 9 s
        assertEquals(StabStatus.Ready, s.statusOf(asset, clip(0, 300), fps))    // exactly the analysed range
        assertEquals(StabStatus.Stale, s.statusOf(asset, clip(30, 450), fps))   // reaches 15 s
        assertEquals(StabStatus.Ready, s.statusOf(asset, clip(2, 298), fps))
    }

    @Test
    fun `a cache that starts later than the clip is stale`() {
        val s = stabiliser(FakeNative())
        writeCache(cacheOf(s), startUs = 5 * second, endUs = 20 * second)
        assertEquals(StabStatus.Stale, s.statusOf(asset, clip(60, 300), fps))  // starts at 2 s
        assertEquals(StabStatus.Ready, s.statusOf(asset, clip(160, 300), fps))
    }

    @Test
    fun `a cache from another analysis version is not trusted`() {
        val s = stabiliser(FakeNative())
        writeCache(cacheOf(s), 0, 10 * second, version = StabCacheFile.ANALYSIS_VERSION + 1)
        assertEquals(StabStatus.Stale, s.statusOf(asset, clip(30, 270), fps))
    }

    @Test
    fun `a damaged cache file counts as missing`() {
        val s = stabiliser(FakeNative())
        val file = cacheOf(s)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(10))
        assertEquals(StabStatus.NotAnalysed, s.statusOf(asset, clip(0, 90), fps))
        file.writeBytes("not a cache at all, just some text to be long enough for a header....".toByteArray())
        assertEquals(StabStatus.NotAnalysed, s.statusOf(asset, clip(0, 90), fps))
    }

    @Test
    fun `the cache name follows the asset's identity`() {
        val a = StabCacheFile.nameFor(asset)
        assertTrue(a.startsWith("a1."))
        assertEquals(a, StabCacheFile.nameFor(asset))
        assertNotEquals(a, StabCacheFile.nameFor(asset.copy(uri = "content://m/other")))   // relinked
        assertNotEquals(a, StabCacheFile.nameFor(asset.copy(durationFrames = 901)))
        assertNotEquals(a, StabCacheFile.nameFor(asset.copy(nativeFpsNum = 60)))
    }

    @Test
    fun `the header parser checks magic, sizes and plausibility`() {
        val file = folder.newFile("h.bin")
        writeCache(file, 0, 5 * second, samples = 10)
        val header = StabCacheFile.readHeader(file)!!
        assertEquals(10L, header.samples)
        assertEquals(5 * second, header.rangeEndUs)
        assertEquals(1.7777f, header.aspect, 1e-6f)
        // One byte too many: the length no longer matches the sample count.
        file.appendBytes(byteArrayOf(0))
        assertNull(StabCacheFile.readHeader(file))
        assertNull(StabCacheFile.readHeader(File(folder.root, "missing.bin")))
        // Fewer than two samples cannot hold a camera path.
        writeCache(file, 0, 0, samples = 1)
        assertNull(StabCacheFile.readHeader(file))
    }

    // endregion

    // region analysing

    @Test
    fun `an analysis runs the native job over the clip plus a margin and reports progress`() = runTest {
        val native = FakeNative()
        native.script = listOf(
            FakeNative.pack(StabJob.State.RUNNING, 100, 0),
            FakeNative.pack(StabJob.State.RUNNING, 500, 0),
            FakeNative.pack(StabJob.State.RUNNING, 900, 0),
            FakeNative.pack(StabJob.State.DONE, 1000, 0),
        )
        val s = stabiliser(native)
        val progress = mutableListOf<Float>()
        val outcome = s.analyse(asset, clip(300, 600), fps) { progress += it }

        assertEquals(StabOutcome.Done, outcome)
        val start = native.starts.single()
        assertEquals(77, start.fd)
        assertEquals(9 * second, start.startUs)    // 10 s minus the 1 s margin
        assertEquals(21 * second, start.endUs)     // 20 s plus the margin
        assertEquals(cacheOf(s).path, start.path)
        assertEquals(listOf(start.handle), native.destroyed)  // the worker is joined and freed
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)  // never goes backwards
        assertTrue(progress.size >= 3)
    }

    @Test
    fun `the margin never reaches before the start of the media`() = runTest {
        val native = FakeNative()
        stabiliser(native).analyse(asset, clip(10, 100), fps) { }
        assertEquals(0L, native.starts.single().startUs)
    }

    @Test
    fun `extending a clip keeps what was analysed before`() = runTest {
        val native = FakeNative()
        val s = stabiliser(native)
        writeCache(cacheOf(s), startUs = 2 * second, endUs = 30 * second)
        s.analyse(asset, clip(300, 360), fps) { }  // 10 s to 12 s, inside the old range
        assertEquals(2 * second, native.starts.single().startUs)
        assertEquals(30 * second, native.starts.single().endUs)
    }

    @Test
    fun `a file that cannot be opened fails without touching the native side`() = runTest {
        val native = FakeNative()
        val outcome = stabiliser(native, fd = null).analyse(asset, clip(0, 90), fps) { }
        assertTrue(outcome is StabOutcome.Failed)
        assertTrue(native.created.isEmpty())
    }

    @Test
    fun `a job that does not start reports why and is cleaned up`() = runTest {
        val native = FakeNative().apply { startStatus = 3 }
        val outcome = stabiliser(native).analyse(asset, clip(0, 90), fps) { }
        assertEquals("The media file could not be read", (outcome as StabOutcome.Failed).message)
        assertEquals(native.created, native.destroyed)
    }

    @Test
    fun `a failure while analysing carries the native reason`() = runTest {
        val native = FakeNative().apply {
            script = listOf(FakeNative.pack(StabJob.State.RUNNING, 300, 0), FakeNative.pack(StabJob.State.FAILED, 300, 4))
        }
        val outcome = stabiliser(native).analyse(asset, clip(0, 90), fps) { }
        assertEquals("This clip has too little picture or movement to analyse", (outcome as StabOutcome.Failed).message)
        assertEquals(native.created, native.destroyed)
        val codec = FakeNative().apply { script = listOf(FakeNative.pack(StabJob.State.FAILED, 0, 5)) }
        assertEquals("The video could not be decoded", (stabiliser(codec).analyse(asset, clip(0, 90), fps) { } as StabOutcome.Failed).message)
        val odd = FakeNative().apply { script = listOf(FakeNative.pack(StabJob.State.FAILED, 0, 9)) }
        assertTrue((stabiliser(odd).analyse(asset, clip(0, 90), fps) { } as StabOutcome.Failed).message.contains("code 9"))
    }

    @Test
    fun `a cancelled job reports cancelled`() = runTest {
        val native = FakeNative().apply {
            script = listOf(FakeNative.pack(StabJob.State.RUNNING, 200, 0), FakeNative.pack(StabJob.State.CANCELLED, 200, 8))
        }
        assertEquals(StabOutcome.Cancelled, stabiliser(native).analyse(asset, clip(0, 90), fps) { })
    }

    @Test
    fun `cancel stops the running job and does nothing when idle`() = runBlocking {
        val native = FakeNative().apply { script = listOf(FakeNative.pack(StabJob.State.RUNNING, 100, 0)) }
        val s = stabiliser(native)
        s.cancel()
        assertTrue(native.cancelled.isEmpty())  // idle: nothing to stop

        val job = launch { s.analyse(asset, clip(0, 90), fps) { } }
        while (native.starts.isEmpty()) kotlinx.coroutines.delay(1)
        s.cancel()
        assertEquals(native.created, native.cancelled.distinct())
        job.cancelAndJoin()
    }

    @Test
    fun `cancelling the caller stops the worker and frees the handle`() = runBlocking {
        val native = FakeNative().apply { script = listOf(FakeNative.pack(StabJob.State.RUNNING, 100, 0)) }
        val s = stabiliser(native)
        val job: Job = launch { s.analyse(asset, clip(0, 90), fps) { } }
        while (native.starts.isEmpty()) kotlinx.coroutines.delay(1)
        job.cancelAndJoin()
        assertTrue(native.cancelled.contains(native.created.single()))
        assertEquals(native.created, native.destroyed)
        // And a new analysis can start afterwards.
        native.script = listOf(FakeNative.pack(StabJob.State.DONE, 1000, 0))
        assertEquals(StabOutcome.Done, s.analyse(asset, clip(0, 90), fps) { })
    }

    @Test
    fun `the status packing round trips`() {
        val job = StabJob.unpack(FakeNative.pack(StabJob.State.FAILED, 731, 5))
        assertEquals(StabJob.State.FAILED, job.state)
        assertEquals(731, job.permille)
        assertEquals(5, job.errorCode)
        assertEquals(StabJob.State.FAILED, StabJob.unpack(99).state)  // an unknown state is a failure, never a crash
    }

    // endregion

    // region registering tables

    private fun ready(s: FileStabiliser) = writeCache(cacheOf(s), 0, 40 * second)

    @Test
    fun `a clip is registered with the native table under its key`() {
        val native = FakeNative()
        val s = stabiliser(native)
        ready(s)
        assertTrue(s.register(asset, clip(30, 270, Stabilise(0.5, StabCrop.TIGHT)), fps))
        val call = native.registers.single()
        assertEquals(StabKey.of("a1", Stabilise(0.5, StabCrop.TIGHT)), call.key)
        assertEquals(cacheOf(s).path, call.path)
        assertEquals(30, call.fpsNum)
        assertEquals(1, call.fpsDen)
        assertEquals(0.5f, call.strength, 0f)
        assertEquals(StabCrop.TIGHT.code, call.crop)
    }

    @Test
    fun `registering again with the same settings costs nothing`() {
        val native = FakeNative()
        val s = stabiliser(native)
        ready(s)
        assertTrue(s.register(asset, clip(30, 270), fps))
        assertTrue(s.register(asset, clip(60, 240), fps))  // another clip of the file with the same settings
        assertEquals(1, native.registers.size)
    }

    @Test
    fun `other settings are another table`() {
        val native = FakeNative()
        val s = stabiliser(native)
        ready(s)
        s.register(asset, clip(30, 270, Stabilise(0.3, StabCrop.MEDIUM)), fps)
        s.register(asset, clip(30, 270, Stabilise(0.8, StabCrop.MEDIUM)), fps)
        s.register(asset, clip(30, 270, Stabilise(0.8, StabCrop.FULL)), fps)
        assertEquals(3, native.registers.map { it.key }.distinct().size)
    }

    @Test
    fun `nothing is registered for a clip that is off, not analysed or not covered`() {
        val native = FakeNative()
        val s = stabiliser(native)
        assertFalse(s.register(asset, clip(0, 90), fps))               // no analysis
        ready(s)
        assertFalse(s.register(asset, clip(0, 90, stabilise = null), fps))
        assertFalse(s.register(asset, clip(0, 2000), fps))             // reaches outside the analysed range
        assertTrue(native.registers.isEmpty())
    }

    @Test
    fun `a table that the native side refuses is not remembered`() {
        val native = FakeNative().apply { registerStatus = 4 }
        val s = stabiliser(native)
        ready(s)
        assertFalse(s.register(asset, clip(30, 270), fps))
        native.registerStatus = 0
        assertTrue(s.register(asset, clip(30, 270), fps))
        assertEquals(2, native.registers.size)
    }

    @Test
    fun `a table that went missing natively is registered again`() {
        val native = FakeNative()
        val s = stabiliser(native)
        ready(s)
        s.register(asset, clip(30, 270), fps)
        native.registeredKeys.clear()
        assertTrue(s.register(asset, clip(30, 270), fps))
        assertEquals(2, native.registers.size)
    }

    @Test
    fun `a new analysis of the file replaces the table`() {
        val native = FakeNative()
        val s = stabiliser(native)
        ready(s)
        s.register(asset, clip(30, 270), fps)
        cacheOf(s).apply {
            writeBytes(readBytes() + ByteArray(0))
            setLastModified(lastModified() + 5_000)
        }
        assertTrue(s.register(asset, clip(30, 270), fps))
        assertEquals(2, native.registers.size)
    }

    @Test
    fun `releasing everything forgets the tables`() {
        val native = FakeNative()
        val s = stabiliser(native)
        ready(s)
        s.register(asset, clip(30, 270), fps)
        s.releaseAll()
        assertEquals(1, native.releasedAll)
        assertTrue(s.register(asset, clip(30, 270), fps))
        assertEquals(2, native.registers.size)
    }

    @Test
    fun `the no-op stabiliser does nothing and says so`() = runTest {
        assertEquals(StabStatus.Off, NoStabiliser.statusOf(asset, clip(0, 90), fps))
        assertFalse(NoStabiliser.register(asset, clip(0, 90), fps))
        assertTrue(NoStabiliser.analyse(asset, clip(0, 90), fps) { } is StabOutcome.Failed)
        NoStabiliser.cancel()
        NoStabiliser.releaseAll()
    }

    // endregion
}
