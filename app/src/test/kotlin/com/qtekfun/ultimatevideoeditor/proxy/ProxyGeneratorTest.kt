package com.qtekfun.ultimatevideoeditor.proxy

import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread

class ProxyGeneratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val media = FakeMedia()
    private val runner = FakeRunner(media)
    private val index by lazy { ProxyIndex(File(tmp.root, "proxies")) { 42L } }
    private val generator by lazy { ProxyGenerator(runner, media, index) { 42L } }

    private fun queued(asset: com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto = testAsset(), short: Int = 720): ProxyEntry {
        val job = jobOf(asset)
        val entry = ProxyEntry(ProxyKeys.of(job, short), job, ProxyState.QUEUED, short)
        index.put(entry)
        return entry
    }

    @Test
    fun `a proxy is a one-clip movie at the source frame rate and length, so every frame maps to the same frame`() {
        val asset = testAsset(frames = 450, fpsNum = 60000, fpsDen = 1001)
        media.infos[asset.uri] = UHD
        media.sizes[asset.uri] = 5_000_000
        generator.generate(queued(asset)) { }

        val request = runner.requests.single()
        assertEquals(60000, request.projectFpsNum)
        assertEquals(1001, request.projectFpsDen)
        assertEquals(60000, request.settings.fpsNum)
        assertEquals(1001, request.settings.fpsDen)
        assertEquals(450L, request.totalFrames)
        val clip = request.videoClips.single()
        assertEquals(0L, clip.startFrame)
        assertEquals(450L, clip.durationFrames)
        assertEquals(0L, clip.sourceInFrame)
        assertNull(clip.sourceFrames) // plain 1x: source frame = timeline frame
        assertEquals(1280, request.canvasWidth)
        assertEquals(720, request.canvasHeight)
        assertEquals(1280, request.settings.width)
        assertEquals(ExportCodec.H264, request.settings.codec)
        assertFalse(request.settings.hdr)
        assertNull(request.audioSnapshot) // sound always comes from the original
    }

    @Test
    fun `a successful job ends READY with the file renamed from its part`() {
        val asset = testAsset()
        media.infos[asset.uri] = UHD
        media.sizes[asset.uri] = 7_000_000
        val entry = queued(asset)

        val ready = generator.generate(entry) { }

        assertEquals(ProxyState.READY, ready.state)
        assertEquals(1000L, ready.bytes)
        assertEquals(7_000_000L, ready.sourceBytes)
        assertEquals(42L, ready.createdAtMs)
        assertEquals("${entry.key}.mp4", ready.fileName)
        assertTrue(index.finalFileFor(entry.key).isFile)
        assertFalse(index.partFileFor(entry.key).exists())
        assertEquals(ready, index.get(entry.key))
    }

    @Test
    fun `an HDR source is read with its own colour mode and the proxy is SDR`() {
        val asset = testAsset(colorSpace = "Rec2020-HLG")
        media.infos[asset.uri] = UHD

        generator.generate(queued(asset)) { }

        assertEquals(1, runner.requests.single().videoClips.single().colorMode) // HLG source, tone-mapped by the engine
        assertFalse(runner.requests.single().settings.hdr)
    }

    @Test
    fun `progress is passed on`() {
        val asset = testAsset()
        media.infos[asset.uri] = UHD
        val seen = ArrayList<Int>()

        generator.generate(queued(asset)) { seen += it }

        assertEquals(listOf(500), seen)
    }

    @Test
    fun `a video that is already small needs no proxy and leaves nothing behind`() {
        val asset = testAsset()
        media.infos[asset.uri] = SourceInfo(1280, 720)
        val entry = queued(asset)

        try {
            generator.generate(entry) { }
            fail("expected NOT_NEEDED")
        } catch (e: ProxyException) {
            assertEquals(ProxyErrorCode.NOT_NEEDED, e.code)
        }
        assertNull(index.get(entry.key))
        assertTrue(runner.requests.isEmpty())
    }

    @Test
    fun `an unreadable source fails the entry with a reason`() {
        val asset = testAsset() // no probe registered
        val entry = queued(asset)

        try {
            generator.generate(entry) { }
            fail("expected SOURCE_UNREADABLE")
        } catch (e: ProxyException) {
            assertEquals(ProxyErrorCode.SOURCE_UNREADABLE, e.code)
        }
        val failed = index.get(entry.key)!!
        assertEquals(ProxyState.FAILED, failed.state)
        assertNotNull(failed.error)
    }

    @Test
    fun `a source that cannot be opened closes what it opened and fails with IO`() {
        val asset = testAsset()
        media.infos[asset.uri] = UHD
        media.failOpenSource = true
        val entry = queued(asset)

        try {
            generator.generate(entry) { }
            fail("expected IO")
        } catch (e: ProxyException) {
            assertEquals(ProxyErrorCode.IO, e.code)
        }
        assertEquals(ProxyState.FAILED, index.get(entry.key)!!.state)
        assertFalse(index.partFileFor(entry.key).exists())
    }

    @Test
    fun `an encoder that cannot do it fails the entry and deletes the part`() {
        val asset = testAsset()
        media.infos[asset.uri] = UHD
        runner.behavior = { request, listener ->
            media.outputs.getValue(request.outputFd).writeBytes(ByteArray(10))
            listener.onFinished(ExportException(ExportErrorCode.UNSUPPORTED_FORMAT, "no AVC encoder"))
        }
        val entry = queued(asset)

        try {
            generator.generate(entry) { }
            fail("expected ENCODER_UNSUPPORTED")
        } catch (e: ProxyException) {
            assertEquals(ProxyErrorCode.ENCODER_UNSUPPORTED, e.code)
        }
        assertEquals(ProxyState.FAILED, index.get(entry.key)!!.state)
        assertFalse(index.partFileFor(entry.key).exists())
        assertFalse(index.finalFileFor(entry.key).exists())
    }

    @Test
    fun `an engine that refuses to start is reported without leaving a part file`() {
        val asset = testAsset()
        media.infos[asset.uri] = UHD
        runner.startError = ExportException(ExportErrorCode.NOT_INITIALIZED, "native engine missing")
        val entry = queued(asset)

        try {
            generator.generate(entry) { }
            fail("expected ENGINE")
        } catch (e: ProxyException) {
            assertEquals(ProxyErrorCode.ENGINE, e.code)
        }
        assertFalse(index.partFileFor(entry.key).exists())
    }

    @Test
    fun `an empty output is a failure, not a ready proxy`() {
        val asset = testAsset()
        media.infos[asset.uri] = UHD
        runner.behavior = { _, listener -> listener.onFinished(null) } // wrote nothing
        val entry = queued(asset)

        try {
            generator.generate(entry) { }
            fail("expected IO")
        } catch (e: ProxyException) {
            assertEquals(ProxyErrorCode.IO, e.code)
        }
        assertEquals(ProxyState.FAILED, index.get(entry.key)!!.state)
    }

    @Test
    fun `cancelling a running job leaves no trace`() {
        val asset = testAsset()
        media.infos[asset.uri] = UHD
        runner.behavior = { request, _ -> media.outputs.getValue(request.outputFd).writeBytes(ByteArray(10)) } // never finishes by itself
        val entry = queued(asset)
        var thrown: ProxyException? = null
        val worker = thread {
            try {
                generator.generate(entry) { }
            } catch (e: ProxyException) {
                thrown = e
            }
        }

        waitUntil { runner.requests.isNotEmpty() }
        generator.cancelCurrent()
        worker.join(5_000)

        assertEquals(ProxyErrorCode.CANCELLED, thrown?.code)
        assertNull(index.get(entry.key))
        assertFalse(index.partFileFor(entry.key).exists())
    }
}
