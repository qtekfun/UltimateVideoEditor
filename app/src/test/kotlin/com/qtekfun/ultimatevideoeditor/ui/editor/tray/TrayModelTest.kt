package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrayModelTest {
    private fun asset(
        id: String,
        name: String,
        video: Boolean = true,
        audio: Boolean = true,
        image: Boolean = false,
        colorSpace: String = "Rec709-SDR",
    ) = MediaAssetDto(
        id = id, uri = "content://x/$name", durationFrames = 100, nativeFpsNum = 30, nativeFpsDen = 1,
        colorSpace = colorSpace, hasVideo = video, hasAudio = audio, isImage = image, displayName = name,
    )

    private val clipA = asset("a", "Beach.mp4")
    private val clipB = asset("b", "city.MOV", colorSpace = "Rec2020-HLG")
    private val photo = asset("p", "Sunset.jpg", video = false, audio = false, image = true)
    private val song = asset("s", "song.mp3", video = false)
    private val library = listOf(clipA, clipB, photo, song)

    private fun items(
        tab: TrayTab = TrayTab.MEDIA,
        filter: AssetFilter = AssetFilter.ALL,
        query: String = "",
        usage: Map<String, Int> = emptyMap(),
        missing: Set<String> = emptySet(),
    ) = trayItems(library, usage, missing, tab, filter, query).map { it.asset.id }

    @Test
    fun `kinds follow what the file contains`() {
        assertEquals(AssetKind.VIDEO, clipA.kind)
        assertEquals(AssetKind.PHOTO, photo.kind)
        assertEquals(AssetKind.AUDIO, song.kind)
    }

    @Test
    fun `the media tab lists video and photos and the audio tab lists audio only`() {
        assertEquals(listOf("a", "b", "p"), items())
        assertEquals(listOf("s"), items(tab = TrayTab.AUDIO))
        assertEquals(emptyList<String>(), items(tab = TrayTab.STICKERS))
        assertEquals(emptyList<String>(), items(tab = TrayTab.TEMPLATES))
    }

    @Test
    fun `filters narrow the media tab`() {
        assertEquals(listOf("a", "b"), items(filter = AssetFilter.VIDEO))
        assertEquals(listOf("p"), items(filter = AssetFilter.PHOTO))
        assertEquals(listOf("b", "p"), items(filter = AssetFilter.UNUSED, usage = mapOf("a" to 2)))
    }

    @Test
    fun `search is case insensitive, trimmed and combines with the filter`() {
        assertEquals(listOf("b"), items(query = " CITY "))
        assertEquals(listOf("a", "b"), items(query = ".m"))
        assertEquals(emptyList<String>(), items(filter = AssetFilter.PHOTO, query = "beach"))
        assertEquals(listOf("s"), items(tab = TrayTab.AUDIO, filter = AssetFilter.PHOTO, query = "SONG"))
    }

    @Test
    fun `items carry usage, missing flag and display name`() {
        val listed = trayItems(library, mapOf("a" to 3), setOf("b"), TrayTab.MEDIA, AssetFilter.ALL, "")
        assertEquals(3, listed.first { it.asset.id == "a" }.usage)
        assertEquals(true, listed.first { it.asset.id == "b" }.missing)
        assertEquals("Beach.mp4", listed.first().name)
    }

    @Test
    fun `usage counts clips per asset across tracks`() {
        fun clip(id: String, asset: String?) = Clip(id, asset, FrameIndex(0), FrameIndex(0), FrameIndex(10))
        val timeline = Timeline(
            listOf(
                Track("v2", TrackType.VIDEO, listOf(clip("1", "a"))),
                Track("v1", TrackType.VIDEO, listOf(clip("2", "a"), Clip("3", "a", FrameIndex(10), FrameIndex(0), FrameIndex(5)), clip("4", "b"))),
            ),
        )
        assertEquals(mapOf("a" to 3, "b" to 1), usageCounts(timeline))
    }

    @Test
    fun `moving an asset keeps the others in order and clamps the index`() {
        assertEquals(listOf("b", "a", "p", "s"), moveAsset(library, "a", 1).map { it.id })
        assertEquals(listOf("s", "a", "b", "p"), moveAsset(library, "s", 0).map { it.id })
        assertEquals(listOf("b", "p", "s", "a"), moveAsset(library, "a", 99).map { it.id })
        assertEquals(listOf("s", "a", "b", "p"), moveAsset(library, "s", -5).map { it.id })
        assertSame(library, moveAsset(library, "a", 0))
        assertSame(library, moveAsset(library, "missing", 1))
    }

    @Test
    fun `colour badges only mark HDR media`() {
        assertNull(colourBadge(clipA))
        assertEquals("HLG", colourBadge(clipB))
        assertEquals("PQ", colourBadge(asset("q", "q.mp4", colorSpace = "Rec2020-PQ")))
    }

    @Test
    fun `opening a tab expands a collapsed tray and keeps a taller one`() {
        assertEquals(TrayState(tab = TrayTab.STICKERS, height = TrayHeight.HALF), TrayState().open(TrayTab.STICKERS))
        assertEquals(TrayHeight.FULL, TrayState(height = TrayHeight.FULL).open(TrayTab.TEMPLATES).height)
        assertEquals(TrayHeight.HALF, TrayHeight.COLLAPSED.taller())
        assertEquals(TrayHeight.FULL, TrayHeight.FULL.taller())
        assertEquals(TrayHeight.COLLAPSED, TrayHeight.COLLAPSED.shorter())
        assertEquals(TrayHeight.HALF, TrayHeight.FULL.shorter())
    }
}
