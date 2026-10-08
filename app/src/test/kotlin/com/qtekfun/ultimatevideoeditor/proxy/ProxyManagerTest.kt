package com.qtekfun.ultimatevideoeditor.proxy

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val media = FakeMedia()
    private val index by lazy { ProxyIndex(File(tmp.root, "proxies")) }
    private val transcoder by lazy { FakeTranscoder(index) }
    private val prefs = InMemoryProxyPrefs()
    private val manager by lazy {
        ProxyManager(index, prefs, media, transcoder, CoroutineScope(Job() + Dispatchers.Unconfined), executor.asCoroutineDispatcher(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        // Wait for the worker thread before the temporary folder is deleted under it.
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun asset(n: Int = 1) = testAsset(id = "a$n", uri = "content://m$n")

    private fun makeReady(vararg assets: MediaAssetDto) {
        manager.generate(assets.toList())
        waitUntil { assets.all { manager.statusOf(it) is ProxyStatus.Ready } }
    }

    @Test
    fun `a ready proxy is used for preview only while the project switch is on`() {
        val a = asset()
        makeReady(a)

        assertEquals(a.uri, manager.resolve(a, MediaPurpose.PREVIEW, "p1").uri) // switch off
        manager.setEnabled("p1", true)
        val source = manager.resolve(a, MediaPurpose.PREVIEW, "p1")

        assertTrue(source.isProxy)
        assertTrue(source.uri.startsWith("file://"))
        assertTrue(source.uri.endsWith(".mp4"))
        assertEquals("a1", source.proxyAssetId)
        // Another project that has not turned proxies on still gets the original.
        assertEquals(a.uri, manager.resolve(a, MediaPurpose.PREVIEW, "p2").uri)
    }

    @Test
    fun `export and analysis get the original with the switch on and the proxy ready`() {
        val a = asset()
        makeReady(a)
        manager.setEnabled("p1", true)

        assertEquals(a.uri, manager.resolve(a, MediaPurpose.EXPORT, "p1").uri)
        assertEquals(a.uri, manager.resolve(a, MediaPurpose.ANALYSIS, "p1").uri)
    }

    @Test
    fun `turning the switch on changes the answer right away`() {
        val a = asset()
        makeReady(a)

        assertFalse(manager.resolve(a, MediaPurpose.THUMBNAIL, "p").isProxy)
        manager.setEnabled("p", true)
        assertTrue(manager.resolve(a, MediaPurpose.THUMBNAIL, "p").isProxy)
        manager.setEnabled("p", false)
        assertFalse(manager.resolve(a, MediaPurpose.THUMBNAIL, "p").isProxy)
    }

    @Test
    fun `statuses follow the life of a proxy`() {
        val a = asset()
        assertEquals(ProxyStatus.None, manager.statusOf(a))

        transcoder.hold = java.util.concurrent.CountDownLatch(1)
        manager.generate(listOf(a))
        waitUntil { manager.statusOf(a) is ProxyStatus.Making }
        transcoder.hold!!.countDown()
        waitUntil { manager.statusOf(a) is ProxyStatus.Ready }

        assertEquals(ProxyStatus.Ready(100, 1280, 720), manager.statusOf(a))
        assertTrue(manager.hasUsableProxy(a))
    }

    @Test
    fun `photos and audio are never queued`() {
        val photo = testAsset(id = "p", hasVideo = false, hasAudio = false, isImage = true)
        val audio = testAsset(id = "s", hasVideo = false)

        manager.generate(listOf(photo, audio))

        assertTrue(index.all().isEmpty())
        assertEquals(ProxyStatus.None, manager.statusOf(photo))
    }

    @Test
    fun `an unusable proxy is not used again until it is made anew`() {
        val a = asset()
        makeReady(a)
        manager.setEnabled("p", true)
        assertTrue(manager.resolve(a, MediaPurpose.PREVIEW, "p").isProxy)

        manager.markUnusable(a)

        assertEquals(ProxyStatus.OutOfDate, manager.statusOf(a))
        assertFalse(manager.resolve(a, MediaPurpose.PREVIEW, "p").isProxy)
    }

    @Test
    fun `validation marks a proxy out of date when its source changed or its file is gone`() {
        val changed = asset(1)
        val gone = asset(2)
        val same = asset(3)
        for (a in listOf(changed, gone, same)) media.sizes[a.uri] = 1_000
        makeReady(changed, gone, same)
        // The generator fake does not record the source size; set what the real one records.
        for (a in listOf(changed, gone, same)) {
            val key = ProxyKeys.of(jobOf(a), 720)
            index.put(index.get(key)!!.copy(sourceBytes = 1_000))
        }
        media.sizes[changed.uri] = 2_000
        index.finalFileFor(ProxyKeys.of(jobOf(gone), 720)).delete()

        runBlocking { manager.validate(listOf(changed, gone, same)) }

        assertEquals(ProxyStatus.OutOfDate, manager.statusOf(changed))
        assertEquals(ProxyStatus.OutOfDate, manager.statusOf(gone))
        assertTrue(manager.statusOf(same) is ProxyStatus.Ready)
    }

    @Test
    fun `removing a proxy deletes it`() {
        val a = asset()
        makeReady(a)
        val file = index.finalFileFor(ProxyKeys.of(jobOf(a), 720))
        assertTrue(file.isFile)

        manager.remove(a)

        assertEquals(ProxyStatus.None, manager.statusOf(a))
        assertFalse(file.exists())
    }

    @Test
    fun `clearing the cache frees every proxy`() {
        val a = asset(1)
        val b = asset(2)
        makeReady(a, b)

        val freed = manager.clearCache()

        assertEquals(200L, freed)
        assertEquals(0L, manager.usage().bytes)
        assertEquals(ProxyStatus.None, manager.statusOf(a))
    }

    @Test
    fun `lowering the budget evicts what the open project does not use`() {
        val used = asset(1)
        val other = asset(2)
        makeReady(used, other)
        manager.protect(listOf(used))

        manager.setBudget(100)

        assertTrue(manager.statusOf(used) is ProxyStatus.Ready)
        assertEquals(ProxyStatus.None, manager.statusOf(other))
        assertEquals(100L, prefs.budgetBytes)
    }

    @Test
    fun `a different proxy size is a different proxy`() {
        val a = asset()
        makeReady(a)

        manager.setTargetShortSide(1080)

        assertEquals(ProxyStatus.None, manager.statusOf(a)) // the 720p one is not the 1080p one
        manager.setTargetShortSide(720)
        assertTrue(manager.statusOf(a) is ProxyStatus.Ready)
    }

    @Test
    fun `probing remembers each file once`() {
        val a = asset()
        media.infos[a.uri] = UHD

        runBlocking { manager.probe(listOf(a)) }
        media.infos.clear() // a second probe would now fail

        runBlocking { manager.probe(listOf(a)) }

        assertEquals(UHD, manager.infoOf(a))
    }

    @Test
    fun `an unreadable file is simply not probed`() {
        val a = asset()

        runBlocking { manager.probe(listOf(a)) }

        assertEquals(null, manager.infoOf(a))
    }
}
