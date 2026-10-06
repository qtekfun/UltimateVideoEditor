package com.ultimatevideo.uveditor.ui.hub

import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.engine.EngineClient
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.ui.export.ExportBar
import com.ultimatevideo.uveditor.ui.export.ExportJobHost
import com.ultimatevideo.uveditor.ui.export.ExportJobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class HubExportBarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeJobs : ExportJobHost {
        val flow = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
        override val state: StateFlow<ExportJobState> = flow
        var cancels = 0
        var acknowledged = 0
        override fun cancel() {
            cancels++
        }

        override fun acknowledge(onlyProject: String?) {
            acknowledged++
            if (!flow.value.isRunning) flow.value = ExportJobState.Idle
        }
    }

    private val jobs = FakeJobs()

    private fun viewModel(): HubViewModel {
        val repository = ProjectRepository(
            rootDir = File(tmp.root, "projects"),
            transferIO = object : ProjectTransferIO {
                override fun read(uri: String): ByteArray = ByteArray(0)
                override fun write(uri: String, bytes: ByteArray) = Unit
            },
            ioDispatcher = dispatcher,
        )
        return HubViewModel(object : EngineClient { override fun version() = "1" }, repository, dispatcher, exportJobs = jobs)
    }

    private val running = ExportJobState.Running("p1", "Holiday", 250, 0)

    @Test
    fun `no bar without an export`() {
        assertNull(viewModel().state.value.exportBar)
    }

    @Test
    fun `a running export shows its progress, also for a screen created after it started`() {
        jobs.flow.value = running

        val bar = viewModel().state.value.exportBar as ExportBar.Running
        assertEquals("Holiday", bar.projectName)
        assertEquals(25, bar.percent)

        jobs.flow.value = running.copy(progressPermille = 800)
        assertEquals(80, (jobs.flow.value as ExportJobState.Running).progressPermille / 10)
    }

    @Test
    fun `progress updates move the bar`() {
        val vm = viewModel()
        jobs.flow.value = running
        jobs.flow.value = running.copy(progressPermille = 800)

        assertEquals(80, (vm.state.value.exportBar as ExportBar.Running).percent)
    }

    @Test
    fun `finished stays as a bar with Share until dismissed`() {
        val vm = viewModel()
        jobs.flow.value = ExportJobState.Done("p1", "Holiday", "content://out/h.mp4", "h.mp4")

        assertEquals(ExportBar.Finished("p1", "Holiday", "content://out/h.mp4", "h.mp4"), vm.state.value.exportBar)

        val effects = mutableListOf<HubEffect>()
        val collector = kotlinx.coroutines.CoroutineScope(dispatcher).launch { vm.effects.collect { effects += it } }
        vm.onIntent(HubIntent.ShareExport)
        collector.cancel()
        assertEquals(listOf<HubEffect>(HubEffect.ShareExport("content://out/h.mp4")), effects)

        vm.onIntent(HubIntent.DismissExportBar)
        assertNull(vm.state.value.exportBar)
    }

    @Test
    fun `failed stays as a bar with the message until dismissed`() {
        val vm = viewModel()
        jobs.flow.value = ExportJobState.Failed("p1", "Holiday", ExportException(ExportErrorCode.IO_ERROR, "disk full"))

        val bar = vm.state.value.exportBar as ExportBar.Failed
        assertTrue(bar.message, bar.message.contains("disk full"))

        vm.onIntent(HubIntent.DismissExportBar)
        assertNull(vm.state.value.exportBar)
    }

    @Test
    fun `cancel asks the executor and the bar goes when the export is cancelled`() {
        val vm = viewModel()
        jobs.flow.value = running

        vm.onIntent(HubIntent.CancelExport)
        assertEquals(1, jobs.cancels)
        jobs.flow.value = ExportJobState.Cancelled("p1", "Holiday")

        assertNull(vm.state.value.exportBar)
    }

    @Test
    fun `tapping the bar opens the exporting project's export`() {
        val vm = viewModel()
        jobs.flow.value = running
        val effects = mutableListOf<HubEffect>()
        val collector = kotlinx.coroutines.CoroutineScope(dispatcher).launch { vm.effects.collect { effects += it } }

        vm.onIntent(HubIntent.OpenExportProject)
        collector.cancel()

        assertEquals(listOf<HubEffect>(HubEffect.OpenExport("p1")), effects)
    }
}
