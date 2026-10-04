package com.ultimatevideo.uveditor.proxy

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPlannerTest {
    private val entries = HashMap<String, ProxyEntry>()
    private val planner = ProxyPlanner(
        entryOf = { asset -> entries[asset.id] },
        fileUriOf = { entry -> entry.fileName?.let { "file:///proxies/$it" } },
    )

    private fun ready(asset: MediaAssetDto, state: ProxyState = ProxyState.READY) {
        entries[asset.id] = ProxyEntry(
            key = "k-${asset.id}",
            job = jobOf(asset),
            state = state,
            targetShortSide = 720,
            fileName = if (state == ProxyState.READY || state == ProxyState.STALE) "k-${asset.id}.mp4" else null,
        )
    }

    @Test
    fun `preview and thumbnails use a ready proxy when the project switch is on`() {
        val asset = testAsset()
        ready(asset)

        for (purpose in listOf(MediaPurpose.PREVIEW, MediaPurpose.THUMBNAIL)) {
            val source = planner.resolve(asset, purpose, useProxies = true)
            assertEquals("file:///proxies/k-a1.mp4", source.uri)
            assertEquals("a1", source.proxyAssetId)
            assertEquals(asset.uri, source.originalUri)
            assertTrue(source.isProxy)
        }
    }

    @Test
    fun `export always gets the original, even with the switch on and a proxy ready`() {
        val asset = testAsset()
        ready(asset)

        val source = planner.resolve(asset, MediaPurpose.EXPORT, useProxies = true)

        assertEquals(asset.uri, source.uri)
        assertNull(source.proxyAssetId)
    }

    @Test
    fun `analysis reads the original too`() {
        val asset = testAsset()
        ready(asset)

        assertEquals(asset.uri, planner.resolve(asset, MediaPurpose.ANALYSIS, useProxies = true).uri)
    }

    @Test
    fun `with the switch off nothing changes`() {
        val asset = testAsset()
        ready(asset)

        assertEquals(asset.uri, planner.resolve(asset, MediaPurpose.PREVIEW, useProxies = false).uri)
    }

    @Test
    fun `a proxy that is not ready is never used`() {
        val asset = testAsset()
        for (state in listOf(ProxyState.QUEUED, ProxyState.RUNNING, ProxyState.STALE, ProxyState.FAILED)) {
            ready(asset, state)
            val source = planner.resolve(asset, MediaPurpose.PREVIEW, useProxies = true)
            assertEquals("state $state", asset.uri, source.uri)
            assertFalse(source.isProxy)
        }
    }

    @Test
    fun `no entry or a missing file falls back to the original`() {
        val asset = testAsset()
        assertEquals(asset.uri, planner.resolve(asset, MediaPurpose.PREVIEW, useProxies = true).uri)

        val noFile = ProxyPlanner(entryOf = { ProxyEntry("k", jobOf(it), ProxyState.READY, 720, fileName = "k.mp4") }, fileUriOf = { null })
        assertEquals(asset.uri, noFile.resolve(asset, MediaPurpose.PREVIEW, useProxies = true).uri)
    }

    @Test
    fun `photos and audio never have proxies`() {
        val photo = testAsset(id = "p", hasVideo = false, hasAudio = false, isImage = true)
        val audio = testAsset(id = "s", hasVideo = false)
        ready(photo)
        ready(audio)

        assertEquals(photo.uri, planner.resolve(photo, MediaPurpose.PREVIEW, useProxies = true).uri)
        assertEquals(audio.uri, planner.resolve(audio, MediaPurpose.PREVIEW, useProxies = true).uri)
    }

    @Test
    fun `the key depends on the source file, its length and rate, and the proxy size`() {
        val job = jobOf(testAsset())
        val base = ProxyKeys.of(job, 720)

        assertEquals(base, ProxyKeys.of(job.copy(), 720))
        assertTrue(base != ProxyKeys.of(job, 1080))
        assertTrue(base != ProxyKeys.of(job.copy(uri = "content://other"), 720)) // a relinked file
        assertTrue(base != ProxyKeys.of(job.copy(durationFrames = 301), 720))
        assertTrue(base != ProxyKeys.of(job.copy(fpsNum = 60), 720))
    }
}
