package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleVerification
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteResult
import com.qtekfun.ultimatevideoeditor.engine.EngineClient
import com.qtekfun.ultimatevideoeditor.ui.export.BundleJob
import com.qtekfun.ultimatevideoeditor.ui.export.BundleJobHost
import com.qtekfun.ultimatevideoeditor.ui.export.BundleJobState
import com.qtekfun.ultimatevideoeditor.ui.export.BundleProgress
import com.qtekfun.ultimatevideoeditor.ui.export.BundleStart
import com.qtekfun.ultimatevideoeditor.ui.export.BundleView
import kotlinx.coroutines.CoroutineScope
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
class HubBundleJobTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeBundles : BundleJobHost {
        val flow = MutableStateFlow<BundleJobState>(BundleJobState.Idle)
        override val state: StateFlow<BundleJobState> = flow
        override val detailsOpen = MutableStateFlow(false)
        val started = ArrayList<BundleJob>()
        var refuse: String? = null
        var cancels = 0
        var acknowledged = 0
        var shown = 0

        override fun start(next: BundleJob): BundleStart {
            refuse?.let { return BundleStart.Refused(it) }
            started += next
            return BundleStart.Started
        }

        override fun cancel() {
            cancels++
        }

        override fun acknowledge() {
            acknowledged++
            if (!flow.value.isRunning) flow.value = BundleJobState.Idle
        }

        override fun showDetails() {
            shown++
        }

        override fun hideDetails() = Unit
    }

    private val bundles = FakeBundles()

    private fun viewModel(): HubViewModel {
        val repository = ProjectRepository(
            rootDir = File(tmp.root, "projects"),
            transferIO = object : ProjectTransferIO {
                override fun read(uri: String): ByteArray = ByteArray(0)
                override fun write(uri: String, bytes: ByteArray) = Unit
            },
            ioDispatcher = dispatcher,
        )
        return HubViewModel(object : EngineClient { override fun version() = "1" }, repository, dispatcher, bundleJobs = bundles)
    }

    private fun effectsOf(vm: HubViewModel, block: () -> Unit): List<HubEffect> {
        val effects = mutableListOf<HubEffect>()
        val collector = CoroutineScope(dispatcher).launch { vm.effects.collect { effects += it } }
        block()
        collector.cancel()
        return effects
    }

    @Test
    fun `exporting a bundle hands the work to the process wide job instead of doing it in the screen`() {
        val vm = viewModel()

        val effects = effectsOf(vm) { vm.onIntent(HubIntent.ExportBundleTo("p1", "content://out/x.uvbundle", BundleChoice(includeMedia = true))) }

        assertEquals(1, bundles.started.size)
        assertEquals("content://out/x.uvbundle", bundles.started.single().outputUri)
        assertTrue("no message: the dialog is the feedback", effects.isEmpty())
    }

    @Test
    fun `a refused backup says why`() {
        bundles.refuse = "Another long job is running (Exporting Wedding)."
        val vm = viewModel()

        val effects = effectsOf(vm) { vm.onIntent(HubIntent.ExportBundleTo("p1", "content://out/x.uvbundle", BundleChoice())) }

        assertEquals(listOf<HubEffect>(HubEffect.ShowMessage("Another long job is running (Exporting Wedding).")), effects)
    }

    @Test
    fun `the bar mirrors the backup, also for a screen created after it started`() {
        bundles.flow.value = BundleJobState.Running("p1", "Holiday", BundleProgress(doneBytes = 50, totalBytes = 100), 0)

        val vm = viewModel()
        val bar = vm.state.value.bundleBar!!
        assertEquals(50, bar.percent)

        bundles.flow.value = BundleJobState.Running("p1", "Holiday", BundleProgress(doneBytes = 80, totalBytes = 100), 0)
        assertEquals(80, vm.state.value.bundleBar?.percent)
    }

    @Test
    fun `a finished backup stays as a bar with Share until dismissed`() {
        val vm = viewModel()
        bundles.flow.value = BundleJobState.Done(
            "p1", "Holiday", "content://out/h.uvbundle", "Holiday.uvbundle", BundleWriteResult(3, emptyList(), bytesWritten = 1024), BundleVerification.Verified(5, 1024), 5_000,
        )
        assertEquals(BundleView.Phase.SAVED, vm.state.value.bundleBar?.phase)

        val effects = effectsOf(vm) { vm.onIntent(HubIntent.ShareBundle) }
        assertEquals(listOf<HubEffect>(HubEffect.ShareBundle("content://out/h.uvbundle")), effects)

        vm.onIntent(HubIntent.DismissBundleBar)
        assertNull(vm.state.value.bundleBar)
        assertEquals(1, bundles.acknowledged)
    }

    @Test
    fun `a damaged result cannot be shared from the bar`() {
        val vm = viewModel()
        bundles.flow.value = BundleJobState.Done(
            "p1", "Holiday", "content://out/h.uvbundle", "Holiday.uvbundle", BundleWriteResult(3, emptyList()), BundleVerification.Warning(listOf("cut short")), 5_000,
        )

        val effects = effectsOf(vm) { vm.onIntent(HubIntent.ShareBundle) }

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `the bar buttons reach the job`() {
        val vm = viewModel()
        bundles.flow.value = BundleJobState.Running("p1", "Holiday", BundleProgress(), 0)

        vm.onIntent(HubIntent.CancelBundle)
        vm.onIntent(HubIntent.ShowBundleDetails)
        vm.onIntent(HubIntent.DismissBundleBar)

        assertEquals(1, bundles.cancels)
        assertEquals(1, bundles.shown)
        assertTrue("a running backup stays when the bar is dismissed", vm.state.value.bundleBar != null)
    }
}
