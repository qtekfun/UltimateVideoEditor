package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import com.qtekfun.ultimatevideoeditor.data.LutStore
import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.fakeFont
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleMediaSource
import com.qtekfun.ultimatevideoeditor.data.interchange.StoreResourceLibrary
import com.qtekfun.ultimatevideoeditor.data.interchange.sampleProject
import com.qtekfun.ultimatevideoeditor.data.model.EffectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleLayerDto
import com.qtekfun.ultimatevideoeditor.engine.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import com.qtekfun.ultimatevideoeditor.ui.text.english

@OptIn(ExperimentalCoroutinesApi::class)
class HubBundleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val files = mutableMapOf<String, ByteArray>()
    private var counter = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val media = object : BundleMediaSource {
        override fun sizeOf(uri: String): Long? = if (uri == "content://media/1") 1000L else null

        override fun open(uri: String): InputStream? = if (uri == "content://media/1") ByteArrayInputStream(ByteArray(1000) { 1 }) else null
    }

    private fun luts(dir: String) = LutStore(File(tmp.root, "$dir-luts"))

    private fun fonts(dir: String) = FontRegistry(File(tmp.root, "$dir-fonts"))

    private fun repository(dir: String) = ProjectRepository(
        rootDir = File(tmp.root, dir),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = files[uri] ?: throw IOException("missing $uri")

            override fun write(uri: String, bytes: ByteArray) {
                files[uri] = bytes
            }
        },
        ioDispatcher = dispatcher,
        idGenerator = { "id-${++counter}" },
        mediaAccess = media,
        resourceLibrary = StoreResourceLibrary(luts(dir), fonts(dir)),
    )

    private class Engine : EngineClient {
        override fun version() = "test"
    }

    private fun viewModel(repo: ProjectRepository) = HubViewModel(Engine(), repo, dispatcher)

    /** Runs [block] and returns the first effect the view model emitted (they are buffered, so none is missed). */
    private suspend fun HubViewModel.nextEffect(block: () -> Unit): HubEffect {
        block()
        return effects.first()
    }

    private fun cube(size: Int) = buildString {
        append("LUT_3D_SIZE $size\n")
        val d = (size - 1).toFloat()
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) append("${r / d} ${g / d} ${b / d}\n")
    }

    /** The sample project with a LUT on a base clip and a font on a title. */
    private fun withResources(lutKey: Int, fontId: String): ProjectDto {
        val base = sampleProject()
        return base.copy(
            tracks = base.tracks.map { t ->
                when (t.id) {
                    "v1" -> t.copy(clips = t.clips.mapIndexed { i, c -> if (i == 0) c.copy(effects = listOf(EffectDto("e", "lut", listOf(lutKey.toDouble(), 1.0)))) else c })
                    "t1" -> t.copy(clips = t.clips.map { c -> c.copy(title = TitleDto(text = "Hi", layers = listOf(TitleLayerDto("text", text = "Hi", font = fontId)))) })
                    else -> t
                }
            },
        )
    }

    @Test
    fun `asking for a bundle opens the dialog with the project measured and picks nothing until confirmed`() = runBlocking {
        val lutKey = luts("a").import("grade.cube", cube(2)).key
        val fontId = fonts("a").import(fakeFont("Dialog Font")).id
        val repo = repository("a")
        repo.save(withResources(lutKey, fontId))
        val vm = viewModel(repo)
        val project = vm.state.value.projects.single()

        vm.onIntent(HubIntent.RequestExportBundle(project, includeMedia = true))
        val dialog = vm.state.value.bundleExport!!
        assertEquals("p1", dialog.project.id)
        val preview = dialog.draft.preview!!
        // One readable media file (the others have no size), the LUT and the font.
        assertEquals(1, preview.mediaCount)
        assertEquals(1000L, preview.mediaBytes)
        assertEquals(1, preview.luts.size)
        assertEquals(1, preview.fonts.size)
        // The media switch starts where it was asked; LUTs on, fonts off.
        assertEquals(BundleChoice(includeMedia = true, includeLuts = true, includeFonts = false), dialog.draft.choice)
        assertTrue(dialog.draft.canExport)
        assertNotNull(dialog.draft.estimatedBytes)
    }

    @Test
    fun `confirming asks for a file named after the project with the ticked choice`() = runBlocking {
        val repo = repository("a")
        repo.save(sampleProject())
        val vm = viewModel(repo)
        val project = vm.state.value.projects.single()
        vm.onIntent(HubIntent.RequestExportBundle(project, includeMedia = false))
        vm.onIntent(HubIntent.BundleChoiceChanged(BundleChoice(includeMedia = true, includeLuts = false, includeFonts = true)))
        val effect = vm.nextEffect { vm.onIntent(HubIntent.ConfirmBundleExport) }
        assertEquals(
            HubEffect.LaunchBundleExportPicker("p1", "Sample & Co.uvbundle", BundleChoice(includeMedia = true, includeLuts = false, includeFonts = true)),
            effect,
        )
        assertNull(vm.state.value.bundleExport)
    }

    @Test
    fun `cancelling the dialog closes it and a project that cannot be read cannot be exported`() = runBlocking {
        val repo = repository("a")
        repo.save(sampleProject())
        val vm = viewModel(repo)
        val project = vm.state.value.projects.single()
        vm.onIntent(HubIntent.RequestExportBundle(project))
        vm.onIntent(HubIntent.DismissBundleExport)
        assertNull(vm.state.value.bundleExport)

        // A project whose file is gone: the dialog says why and offers no export.
        File(tmp.root, "a/p1/project.json").delete()
        vm.onIntent(HubIntent.RequestExportBundle(project))
        val draft = vm.state.value.bundleExport!!.draft
        assertNotNull(draft.failed)
        assertFalse(draft.canExport)
        vm.onIntent(HubIntent.ConfirmBundleExport)
        assertNotNull(vm.state.value.bundleExport)
    }

    @Test
    fun `exporting a bundle and importing it elsewhere reports what happened to the media`() = runBlocking {
        val source = repository("a")
        source.save(sampleProject())
        val vmA = viewModel(source)
        val message = vmA.nextEffect { vmA.onIntent(HubIntent.ExportBundleTo("p1", "doc://b", BundleChoice(includeMedia = true))) }
        assertTrue(message.toString(), (message as HubEffect.ShowMessage).text.english().startsWith("Bundle exported with 1 media file"))

        val target = viewModel(repository("b"))
        val imported = target.nextEffect { target.onIntent(HubIntent.ImportFrom("doc://b")) }
        val text = (imported as HubEffect.ShowMessage).text.english()
        assertTrue(text, text.startsWith("Imported \"Sample & Co\". 1 media file came with it"))
        assertTrue(text, text.contains("Missing (relink in the editor):"))
        assertEquals(listOf("Sample & Co"), target.state.value.projects.map { it.name })
        assertNull(target.state.value.importNotes)
    }

    @Test
    fun `an export says how many LUTs went in and a font left out is listed as missing on import`() = runBlocking {
        val lutKey = luts("a").import("grade.cube", cube(2)).key
        val fontId = fonts("a").import(fakeFont("Private Font")).id
        val source = repository("a")
        source.save(withResources(lutKey, fontId))
        val vmA = viewModel(source)
        val exported = vmA.nextEffect { vmA.onIntent(HubIntent.ExportBundleTo("p1", "doc://b", BundleChoice())) } as HubEffect.ShowMessage
        assertTrue(exported.text.english(), exported.text.english().contains("1 LUT"))

        val target = viewModel(repository("b"))
        val imported = target.nextEffect { target.onIntent(HubIntent.ImportFrom("doc://b")) } as HubEffect.ShowMessage
        assertTrue(imported.text.english(), imported.text.english().contains("1 LUT/font installed"))
        assertTrue(imported.text.english(), imported.text.english().contains("1 still missing"))
        val notes = target.state.value.importNotes!!
        assertEquals("Sample & Co", notes.projectName)
        assertEquals(1, notes.problems.size)
        assertTrue(notes.problems.single().english(), notes.problems.single().english().startsWith("font Private Font"))
        assertTrue(notes.notes.single().english(), notes.notes.single().english().startsWith("Installed: LUT grade"))
        assertNotNull(luts("b").info(lutKey))

        target.onIntent(HubIntent.DismissImportNotes)
        assertNull(target.state.value.importNotes)
    }
}
