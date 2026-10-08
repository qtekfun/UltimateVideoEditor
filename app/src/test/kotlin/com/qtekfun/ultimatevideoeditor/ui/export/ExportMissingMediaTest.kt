package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.MissingMediaCard
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.export.ExportHandle
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRequest
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The preview shows a "Media missing" card for a clip whose file is gone; an export must never contain it (SPECS 5.40). Export refuses
 * to start while a clip needs unreadable media, and its plan has no way to produce the card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportMissingMediaTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class NoIO : ExportIO {
        val opened = mutableListOf<String>()

        override fun freeBytes(): Long? = null
        override fun displayName(uri: String): String? = null
        override fun openAsset(uri: String): Int = open(uri)
        override fun openOutput(uri: String): Int = open(uri)

        private fun open(uri: String): Int {
            opened += uri
            return 100
        }

        override fun close(fd: Int) = Unit
        override fun deleteOutput(uri: String): Boolean = true
    }

    private class RecordingRunner : ExportRunner {
        var started = 0

        override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
            started++
            return object : ExportHandle {
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }

    private val fps = FrameRate(30, 1)
    private fun asset(id: String) = MediaAssetDto(id, "content://usb/$id.mp4", 600, 30, 1, "Rec709-SDR", displayName = "$id.mp4")
    private val tl = timeline(
        track("v2", clip("over", 0, 90, asset = "gone")),
        track("v1", clip("base", 0, 90, asset = "ok")),
    )
    private val input = ExportInput(
        projectId = "p1",
        projectName = "On the drive",
        projectWidth = 1920,
        projectHeight = 1080,
        fps = fps,
        timeline = tl,
        assets = listOf(asset("ok"), asset("gone")),
        missingAssetIds = setOf("gone"),
    )

    private val io = NoIO()
    private val runner = RecordingRunner()
    private fun viewModel() = ExportViewModel(io, runner, dispatcher, projectId = "p1")

    @Test
    fun `choosing where to save is refused while a clip needs missing media`() = runTest {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input))

        vm.onIntent(ExportIntent.ChooseLocation)

        val phase = vm.state.value.phase
        assertTrue(phase.toString(), phase is ExportPhase.Failed)
        assertTrue((phase as ExportPhase.Failed).message.contains("media for 1 clip(s) is missing"))
        assertTrue("no file picker is opened", vm.state.value.phase is ExportPhase.Failed)
    }

    @Test
    fun `an export that is started anyway is refused before any file is opened`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input))

        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        assertTrue(vm.state.value.phase is ExportPhase.Failed)
        assertTrue(io.opened.isEmpty())
        assertTrue(runner.started == 0)
    }

    @Test
    fun `once the media is back the same export starts`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input.copy(missingAssetIds = emptySet())))

        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        assertTrue(vm.state.value.phase !is ExportPhase.Failed)
        assertTrue(runner.started == 1)
    }

    @Test
    fun `the export plan holds no missing-media card whatever the media state`() {
        for (assets in listOf(listOf(asset("ok"), asset("gone")), listOf(asset("ok")), emptyList())) {
            val plan = buildExportPlan(tl, assets, fps, 1920, 1080)
            assertTrue(
                "export titles: ${plan?.titles?.values}",
                plan == null || plan.titles.values.none { MissingMediaCard.isCard(it) },
            )
        }
    }

    @Test
    fun `a missing clip leaves no title raster in the plan, so nothing could be drawn for it`() {
        val plan = buildExportPlan(tl, listOf(asset("ok")), fps, 1920, 1080)

        assertNull(plan?.titles?.values?.firstOrNull())
    }
}
