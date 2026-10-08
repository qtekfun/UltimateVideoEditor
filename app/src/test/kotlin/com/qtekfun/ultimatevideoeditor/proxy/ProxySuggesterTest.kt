package com.qtekfun.ultimatevideoeditor.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySuggesterTest {
    private val heavy = testAsset(id = "h", uri = "content://heavy")
    private val light = testAsset(id = "l", uri = "content://light")
    private val infos = mapOf(
        heavy.uri to SourceInfo(3840, 2160, bitrateBps = 90_000_000),
        light.uri to SourceInfo(1280, 720, bitrateBps = 8_000_000),
    )

    private fun evaluate(
        assets: List<com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto> = listOf(heavy, light),
        stalls: Int = 0,
        enabled: Boolean = false,
        dismissed: Boolean = false,
        hasProxy: (com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto) -> Boolean = { false },
    ) = ProxySuggester.evaluate(assets, { infos[it.uri] }, hasProxy, stalls, enabled, dismissed)

    @Test
    fun `heavy video is offered a proxy and light video is not`() {
        val suggestion = evaluate()!!

        assertEquals(SuggestionReason.HEAVY_MEDIA, suggestion.reason)
        assertEquals(listOf("h"), suggestion.assetIds)
    }

    @Test
    fun `size and bitrate are each enough to be heavy`() {
        assertTrue(ProxySuggester.isHeavy(SourceInfo(2560, 1440)))
        assertTrue(ProxySuggester.isHeavy(SourceInfo(1920, 1080, bitrateBps = 60_000_000)))
        assertTrue(!ProxySuggester.isHeavy(SourceInfo(1920, 1080, bitrateBps = 20_000_000)))
    }

    @Test
    fun `nothing is offered when proxies are on, dismissed, or already there`() {
        assertNull(evaluate(enabled = true))
        assertNull(evaluate(dismissed = true))
        assertNull(evaluate(hasProxy = { true }))
    }

    @Test
    fun `dropped frames raise the offer for lighter media after a few stalls`() {
        assertNull(evaluate(assets = listOf(light), stalls = ProxySuggester.STALL_THRESHOLD - 1))

        val suggestion = evaluate(assets = listOf(light), stalls = ProxySuggester.STALL_THRESHOLD)!!

        assertEquals(SuggestionReason.DROPPED_FRAMES, suggestion.reason)
        assertEquals(listOf("l"), suggestion.assetIds)
    }

    @Test
    fun `photos and audio are never part of an offer`() {
        val photo = testAsset(id = "p", uri = "content://photo", hasVideo = false, hasAudio = false, isImage = true)
        val audio = testAsset(id = "a", uri = "content://audio", hasVideo = false)

        assertNull(ProxySuggester.evaluate(listOf(photo, audio), { SourceInfo(8000, 6000) }, { false }, 99, enabled = false, dismissed = false))
    }

    @Test
    fun `an asset that could not be probed gets no heavy-media offer`() {
        assertNull(ProxySuggester.evaluate(listOf(heavy), { null }, { false }, 0, enabled = false, dismissed = false))
    }
}
