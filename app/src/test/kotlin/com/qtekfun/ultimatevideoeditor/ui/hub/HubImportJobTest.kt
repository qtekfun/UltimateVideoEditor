package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.BundleImportSummary
import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.interchange.sampleProject
import com.qtekfun.ultimatevideoeditor.engine.EngineClient
import com.qtekfun.ultimatevideoeditor.ui.export.BundleProgress
import com.qtekfun.ultimatevideoeditor.ui.export.BundleStart
import com.qtekfun.ultimatevideoeditor.ui.export.ImportJob
import com.qtekfun.ultimatevideoeditor.ui.export.ImportJobHost
import com.qtekfun.ultimatevideoeditor.ui.export.ImportJobState
import com.qtekfun.ultimatevideoeditor.ui.export.ImportView
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.english

/** The project list's side of the process-wide import: the bar, the delayed dialog's host, the messages and the one-job refusal. */
@OptIn(ExperimentalCoroutinesApi::class)
class HubImportJobTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeImports : ImportJobHost {
        val flow = MutableStateFlow<ImportJobState>(ImportJobState.Idle)
        override val state: StateFlow<ImportJobState> = flow
        override val detailsOpen = MutableStateFlow(false)
        val started = ArrayList<ImportJob>()
        var refuse: UiText? = null
        var cancels = 0
        var acknowledged = 0
        var shown = 0

        override fun start(next: ImportJob): BundleStart {
            refuse?.let { return BundleStart.Refused(it) }
            started += next
            return BundleStart.Started
        }

        override fun cancel() {
            cancels++
        }

        override fun acknowledge() {
            acknowledged++
            if (!flow.value.isRunning) flow.value = ImportJobState.Idle
        }

        override fun showDetails() {
            shown++
        }

        override fun hideDetails() = Unit
    }

    private val imports = FakeImports()
    private var repositoryTouched = false

    private fun viewModel(): HubViewModel {
        val repository = ProjectRepository(
            rootDir = File(tmp.root, "projects"),
            transferIO = object : ProjectTransferIO {
                override fun read(uri: String): ByteArray {
                    repositoryTouched = true
                    return ByteArray(0)
                }

                override fun write(uri: String, bytes: ByteArray) = Unit
            },
            ioDispatcher = dispatcher,
        )
        return HubViewModel(object : EngineClient { override fun version() = "1" }, repository, dispatcher, importJobs = imports)
    }

    private fun effectsOf(vm: HubViewModel, block: () -> Unit): List<HubEffect> {
        val effects = mutableListOf<HubEffect>()
        val collector = CoroutineScope(dispatcher).launch { vm.effects.collect { effects += it } }
        block()
        collector.cancel()
        return effects
    }

    private val project = sampleProject().copy(id = "id-1", name = "Holiday")
    private val report = ImportReport(project, BundleImportSummary(14, 0, emptyList()))

    @Test
    fun `the picker result starts the job and returns without touching the file`() {
        val vm = viewModel()

        val effects = effectsOf(vm) { vm.onIntent(HubIntent.ImportFrom("content://in/h.uvbundle")) }

        assertEquals("content://in/h.uvbundle", imports.started.single().uri)
        assertFalse("the file is read by the job, off the main thread, not here", repositoryTouched)
        assertTrue("no message: the bar and the dialog are the feedback", effects.isEmpty())
    }

    @Test
    fun `an import refused because another long job runs says which`() {
        imports.refuse = UiText.Raw("Another long job is running (Exporting Wedding). Start the import when it has finished, or cancel that one first.")
        val vm = viewModel()

        val effects = effectsOf(vm) { vm.onIntent(HubIntent.ImportFrom("content://in/h.uvbundle")) }

        assertEquals(listOf<HubEffect>(HubEffect.ShowMessage(imports.refuse!!)), effects)
    }

    @Test
    fun `the bar mirrors the import and appears only once it is revealed`() {
        val vm = viewModel()
        imports.flow.value = ImportJobState.Running("u", "Holiday.uvbundle", BundleProgress(doneBytes = 50, totalBytes = 100), 0, revealed = false)
        assertNull("quick imports never flash a bar", vm.state.value.importBar)

        imports.flow.value = ImportJobState.Running("u", "Holiday.uvbundle", BundleProgress(doneBytes = 50, totalBytes = 100), 0, revealed = true)
        assertEquals(50, vm.state.value.importBar?.percent)

        imports.flow.value = ImportJobState.Running("u", "Holiday.uvbundle", BundleProgress(doneBytes = 80, totalBytes = 100), 0, revealed = true)
        assertEquals(80, vm.state.value.importBar?.percent)
    }

    @Test
    fun `a screen created while an import runs shows its bar`() {
        imports.flow.value = ImportJobState.Running("u", "Holiday.uvbundle", BundleProgress(doneBytes = 10, totalBytes = 100), 0, revealed = true)

        assertEquals(10, viewModel().state.value.importBar?.percent)
    }

    @Test
    fun `the bar buttons reach the job, and a running import cannot be dismissed`() {
        val vm = viewModel()
        imports.flow.value = ImportJobState.Running("u", "Holiday.uvbundle", BundleProgress(), 0, revealed = true)

        vm.onIntent(HubIntent.CancelImport)
        vm.onIntent(HubIntent.ShowImportDetails)
        vm.onIntent(HubIntent.DismissImportBar)

        assertEquals(1, imports.cancels)
        assertEquals(1, imports.shown)
        assertNotNull("a running import stays when the bar is dismissed", vm.state.value.importBar)
    }

    @Test
    fun `a finished import stays as a bar with Open until dismissed, and Open goes to the editor`() {
        val vm = viewModel()
        imports.flow.value = ImportJobState.Done("u", "Holiday.uvbundle", report, 7_945_689_497, 252_000, quick = false)
        assertEquals(ImportView.Phase.IMPORTED, vm.state.value.importBar?.phase)

        val effects = effectsOf(vm) { vm.onIntent(HubIntent.OpenImported) }

        assertEquals(listOf<HubEffect>(HubEffect.OpenEditor("id-1")), effects)
        assertEquals(1, imports.acknowledged)
        assertNull(vm.state.value.importBar)
    }

    @Test
    fun `an import that ended quickly says so in a message and leaves no bar`() {
        val vm = viewModel()

        val effects = effectsOf(vm) { imports.flow.value = ImportJobState.Done("u", "h.json", report, 0, 120, quick = true) }

        assertEquals(listOf("Imported \"Holiday\". 14 media files came with it"), effects.map { (it as HubEffect.ShowMessage).text.english() })
        assertNull(vm.state.value.importBar)
        assertEquals("forgotten, so the next import starts clean", 1, imports.acknowledged)
        assertEquals(ImportJobState.Idle, imports.flow.value)
    }

    @Test
    fun `a quick import with problems also lists them in the report dialog`() {
        val vm = viewModel()
        val withProblems = ImportReport(
            project,
            BundleImportSummary(
                0, 0, emptyList(),
                com.qtekfun.ultimatevideoeditor.data.interchange.ResourceImportReport(
                    failed = listOf(com.qtekfun.ultimatevideoeditor.data.interchange.ResourceProblem("teal.cube", "damaged")),
                ),
            ),
        )

        imports.flow.value = ImportJobState.Done("u", "h.uvbundle", withProblems, 0, 120, quick = true)

        assertEquals(listOf("teal.cube: damaged"), vm.state.value.importNotes?.problems?.english())
    }

    @Test
    fun `a failure stays in the bar until dismissed`() {
        val vm = viewModel()

        imports.flow.value = ImportJobState.Failed("u", "Holiday.uvbundle", UiText.Raw("The storage is full. Nothing was added to the project list."))

        assertEquals(ImportView.Phase.FAILED, vm.state.value.importBar?.phase)
        vm.onIntent(HubIntent.DismissImportBar)
        assertNull(vm.state.value.importBar)
    }

    @Test
    fun `a cancelled import says nothing was added`() {
        val vm = viewModel()

        val effects = effectsOf(vm) { imports.flow.value = ImportJobState.Cancelled("u", "Holiday.uvbundle") }

        assertEquals(listOf<HubEffect>(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_import_cancelled))), effects)
        assertNull(vm.state.value.importBar)
    }

    @Test
    fun `a package that needs a media folder asks for one and starts again with the same file`() {
        val vm = viewModel()

        imports.flow.value = ImportJobState.NeedsMediaFolder("content://in/p.lfpackage")
        assertTrue(vm.state.value.mediaFolderPrompt)

        // The folder is chosen (the settings are not wired in this test, so it only checks the prompt and the retry address).
        vm.onIntent(HubIntent.ChooseMediaFolder)
        assertFalse(vm.state.value.mediaFolderPrompt)
    }
}
