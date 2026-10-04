package com.ultimatevideo.uveditor.proxy

import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.ui.editor.previewRequestsAt
import com.ultimatevideo.uveditor.ui.editor.previewRequestsWithSources
import com.ultimatevideo.uveditor.ui.export.buildExportPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A proxy is the same movie at the same frame rate and length, so mapping a timeline frame to a source
 * frame must not change with it; only the file, and the colour reading, do.
 */
class ProxyPreviewMappingTest {
    private val fps = FrameRate(30, 1)
    private val asset = testAsset(id = "a1", uri = "content://original", frames = 600, colorSpace = "Rec2020-HLG")
    private val proxyUri = "file:///cache/proxies/k.mp4"
    private val toProxy: (com.ultimatevideo.uveditor.data.model.MediaAssetDto) -> ResolvedSource =
        { ResolvedSource(proxyUri, proxyAssetId = it.id, originalUri = it.uri) }

    private fun at(frame: Long, sourceOf: (com.ultimatevideo.uveditor.data.model.MediaAssetDto) -> ResolvedSource) =
        previewRequestsWithSources(
            timeline(track("v1", clip("c", 10, 300, srcIn = 40, asset = "a1"))),
            listOf(asset), fps, FrameIndex(frame), sourceOf,
        ) { 1 }.single()

    @Test
    fun `the proxy changes the file and the colour reading, not a single frame number`() {
        for (frame in listOf(10L, 11L, 100L, 309L)) {
            val original = at(frame) { ResolvedSource(it.uri) }
            val proxied = at(frame, toProxy)

            assertEquals(original.sourceFrame, proxied.sourceFrame)
            assertEquals(original.endFrame, proxied.endFrame)
            assertEquals(original.assetKey, proxied.assetKey)
            assertEquals(original.reverse, proxied.reverse)
            assertEquals(original.transform, proxied.transform)
        }
        val original = at(100) { ResolvedSource(it.uri) }
        val proxied = at(100, toProxy)
        assertEquals("content://original", original.uri)
        assertNull(original.proxyAssetId)
        assertEquals(proxyUri, proxied.uri)
        assertEquals("a1", proxied.proxyAssetId)
    }

    @Test
    fun `a proxy is read as SDR whatever the original was`() {
        assertEquals(-1, at(100) { ResolvedSource(it.uri) }.sourceOverride) // the file's own (HLG)
        assertEquals(0, at(100, toProxy).sourceOverride) // the proxy is Rec.709
    }

    @Test
    fun `retimed clips map the same frames through a proxy`() {
        val fast = TimelineOps.setSpeed(timeline(track("v1", clip("c", 0, 200, asset = "a1"))), "c", 2, 1).getOrFail()
        fun frameAt(frame: Long, sourceOf: (com.ultimatevideo.uveditor.data.model.MediaAssetDto) -> ResolvedSource) =
            previewRequestsWithSources(fast, listOf(asset), fps, FrameIndex(frame), sourceOf) { 1 }.single().sourceFrame

        for (frame in listOf(0L, 7L, 50L, 99L)) {
            assertEquals(frameAt(frame) { ResolvedSource(it.uri) }, frameAt(frame, toProxy))
        }
    }

    @Test
    fun `the plain preview mapping still uses the original`() {
        val request = previewRequestsAt(
            timeline(track("v1", clip("c", 0, 100, asset = "a1"))),
            listOf(asset), fps, FrameIndex(5),
        ) { 1 }.single()

        assertEquals("content://original", request.uri)
        assertNull(request.proxyAssetId)
    }

    @Test
    fun `export code never reaches the proxy package`() {
        val appDir = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }
        val exportSources = listOf("src/main/kotlin/com/ultimatevideo/uveditor/ui/export", "src/main/kotlin/com/ultimatevideo/uveditor/engine/export")
            .flatMap { File(appDir, it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        assertTrue("export sources not found", exportSources.isNotEmpty())

        val offenders = exportSources.filter { file ->
            val text = file.readText()
            text.contains("uveditor.proxy") || text.contains("ProxyManager") || text.contains("MediaPurpose") || text.contains("ResolvedSource")
        }.map { it.name }

        assertTrue("Export must always read the originals; these files mention proxies: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the export plan refers to assets by id and carries the same frames with or without proxies`() {
        val tl = timeline(track("v1", clip("c", 10, 300, srcIn = 40, asset = "a1")))

        val plan = buildExportPlan(tl, listOf(asset), fps)!!

        // The exporter opens each asset by its own uri (MediaAssetDto.uri); the plan has no uri to swap.
        assertEquals(setOf("a1"), plan.assetKeys.keys)
        val clip = plan.videoClips.single()
        assertEquals(10L, clip.startFrame)
        assertEquals(40L, clip.sourceInFrame)
        assertEquals(1, clip.colorMode) // the original's own HLG reading, not the proxy's SDR
    }
}
