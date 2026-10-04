package com.ultimatevideo.uveditor.ui.templates

import com.ultimatevideo.uveditor.data.ClipPeeker
import com.ultimatevideo.uveditor.data.MatchedClip
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.data.TemplateFile
import com.ultimatevideo.uveditor.data.TemplateStore
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.domain.templates.BuiltInTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TemplateWizardViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class MemoryIO : ProjectTransferIO {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(uri: String): ByteArray = files[uri] ?: throw IOException("no such document $uri")
        override fun write(uri: String, bytes: ByteArray) {
            files[uri] = bytes
        }
    }

    /** Files by uri: video with 20 s of footage unless named otherwise. */
    private class FakeImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = when {
            uri.endsWith("broken") -> throw MediaImportException("This file cannot be read")
            uri.endsWith("photo") -> ProbedMedia(0, 0, 0, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true, displayName = "photo.jpg")
            uri.endsWith("song") -> ProbedMedia(30_000_000, 0, 0, "Rec709-SDR", hasVideo = false, hasAudio = true, displayName = "song.mp3")
            uri.endsWith("short") -> ProbedMedia(500_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true, displayName = "short.mp4")
            else -> ProbedMedia(20_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true, displayName = "clip.mp4")
        }
    }

    private val peeker = ClipPeeker { MatchedClip("clip.mp4", 1920, 1080, 30, 1, "Rec709-SDR") }
    private val io = MemoryIO()
    private var counter = 0

    private fun repo() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = io,
        ioDispatcher = dispatcher,
        idGenerator = { "id-${++counter}" },
    )

    private fun viewModel(repo: ProjectRepository = repo()) = TemplateWizardViewModel(
        projects = repo,
        store = TemplateStore(File(tmp.root, "templates")),
        importer = FakeImporter(),
        peeker = peeker,
        transfer = io,
        io = dispatcher,
        idGenerator = { "n${++counter}" },
    )

    private val montageId = "builtin-vertical-montage"

    private fun TemplateWizardViewModel.fillMontage() {
        select(montageId)
        for (i in 1..5) pick("clip$i", "content://clip$i")
    }

    @Test
    fun `the list starts with the built-in templates`() = runTest {
        val vm = viewModel()
        assertEquals(BuiltInTemplates.all.map { it.id }, vm.state.value.templates.map { it.id })
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `choosing a template shows what is missing and Create waits for every required slot`() = runTest {
        val vm = viewModel()
        vm.select(montageId)
        assertEquals("Vertical montage", vm.state.value.name)
        assertEquals(5, vm.state.value.missingRequired.size)
        assertFalse(vm.state.value.canCreate)

        for (i in 1..4) vm.pick("clip$i", "content://clip$i")
        assertFalse(vm.state.value.canCreate)
        vm.pick("clip5", "content://clip5")
        // The optional music slot may stay empty.
        assertTrue(vm.state.value.missingRequired.isEmpty())
        assertTrue(vm.state.value.canCreate)
        assertEquals("clip.mp4", vm.state.value.picked.getValue("clip1").label)
    }

    @Test
    fun `a created project holds the template's format, titles, transitions and the chosen files`() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        vm.fillMontage()
        vm.setName("Holiday")
        vm.create()

        val id = vm.state.value.createdProjectId
        assertNotNull(id)
        val project = repo.load(checkNotNull(id))
        assertEquals("Holiday", project.name)
        assertEquals(ProjectSettingsDto(1080, 1920, 30, 1, "Rec709-SDR"), project.settings)
        assertEquals(5, project.mediaLibrary.size)
        val timeline = TimelineMapper.toTimeline(project)
        assertEquals(emptyList<String>(), timeline.invariantViolations())
        assertEquals(1, timeline.tracks.flatMap { it.clips }.count { it.title != null })
        assertEquals(4, timeline.transitions.size)
        assertTrue(timeline.tracks.flatMap { it.clips }.all { c -> c.title != null || c.assetId in project.mediaLibrary.map { it.id } })
        vm.consumeCreated()
        assertNull(vm.state.value.createdProjectId)
    }

    @Test
    fun `a short file is explained before anything is created`() = runTest {
        val vm = viewModel()
        vm.select(montageId)
        for (i in 1..4) vm.pick("clip$i", "content://clip$i")
        vm.pick("clip5", "content://short")
        assertTrue(vm.state.value.warnings.any { it.contains("Clip 5") && it.contains("shorter") })
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun `a file of the wrong kind or one that cannot be read is refused with a message`() = runTest {
        val vm = viewModel()
        vm.select("builtin-intro-outro")
        vm.pick("main", "content://photo")
        // The photo went in, but the slot needs a video: the problem shows and Create stays off.
        for (id in listOf("intro", "outro")) vm.pick(id, "content://clip")
        assertTrue(vm.state.value.problem!!.contains("Main clip"))
        assertFalse(vm.state.value.canCreate)

        vm.pick("main", "content://broken")
        assertEquals("This file cannot be read", vm.state.value.message)
        vm.clear("main")
        vm.pick("main", "content://clip")
        assertNull(vm.state.value.problem)
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun `going back clears the choices and a blank name cannot create`() = runTest {
        val vm = viewModel()
        vm.fillMontage()
        vm.setName("   ")
        assertFalse(vm.state.value.canCreate)
        vm.back()
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.picked.isEmpty())
    }

    @Test
    fun `a project can be saved as a template, shared as a file and imported back`() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        vm.fillMontage()
        vm.create()
        val projectId = checkNotNull(vm.state.value.createdProjectId)
        vm.consumeCreated()

        vm.saveProjectAsTemplate(projectId)
        val mine = vm.state.value.templates.single { BuiltInTemplates.find(it.id) == null }
        assertEquals(5, mine.placeholders.size)
        assertTrue(vm.state.value.message!!.contains("5 slots"))

        vm.exportTemplate(mine.id, "mem://mine.uvtemplate")
        val text = String(io.files.getValue("mem://mine.uvtemplate"))
        assertEquals(mine.id, TemplateFile.decode(text).id)

        vm.deleteTemplate(mine.id)
        assertTrue(vm.state.value.templates.none { it.id == mine.id })
        vm.importTemplate("mem://mine.uvtemplate")
        assertEquals(1, vm.state.value.templates.count { BuiltInTemplates.find(it.id) == null })
        assertTrue(vm.state.value.message!!.startsWith("Added"))
    }

    @Test
    fun `an empty project or a file that is not a template is reported`() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        val empty = repo.create("Empty", ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"))
        vm.saveProjectAsTemplate(empty.id)
        assertTrue(vm.state.value.message!!.contains("no clips"))

        io.files["mem://bad"] = "not json".toByteArray()
        vm.importTemplate("mem://bad")
        assertTrue(vm.state.value.message!!.isNotBlank())
        vm.importTemplate("mem://missing")
        assertTrue(vm.state.value.message!!.contains("could not be read"))
        assertEquals(BuiltInTemplates.all.size, vm.state.value.templates.size)
    }
}
