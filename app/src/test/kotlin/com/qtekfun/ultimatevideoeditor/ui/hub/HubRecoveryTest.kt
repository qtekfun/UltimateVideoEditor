package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.SessionStore
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HubRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private var counter = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSession(var unfinished: String?) : SessionStore {
        var closed = 0

        override fun markOpen(projectId: String) {
            unfinished = projectId
        }

        override fun markClosed() {
            unfinished = null
            closed++
        }

        override fun unfinishedProjectId(): String? = unfinished
    }

    private val engine = object : EngineClient {
        override fun version() = "1.0"
    }

    private fun repository() = ProjectRepository(
        rootDir = File(tmp.root, "projects"),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = throw IOException("unused")

            override fun write(uri: String, bytes: ByteArray) = throw IOException("unused")
        },
        ioDispatcher = dispatcher,
        idGenerator = { "id-${++counter}" },
    )

    private fun projectFile(id: String) = File(tmp.root, "projects/$id/project.json")

    private fun viewModel(repo: ProjectRepository, session: SessionStore? = null) = HubViewModel(engine, repo, dispatcher, session)

    // region unreadable projects

    @Test
    fun `an unreadable project is listed with whether it can be recovered`() {
        val repo = repository()
        val good = runBlocking { repo.create("Good", settings) }
        val damaged = runBlocking { repo.create("Damaged", settings).also { repo.save(it.copy(name = "Damaged 2")) } }
        projectFile(damaged.id).writeText("{ nope")

        val state = viewModel(repo).state.value

        assertEquals(listOf(good.id), state.projects.map { it.id })
        val unreadable = state.unreadable.single()
        assertEquals(damaged.id, unreadable.id)
        assertTrue(unreadable.recoverable)
        assertEquals(1, state.unreadableCount)
    }

    @Test
    fun `recovering restores the project to the list`() = runBlocking {
        val repo = repository()
        val created = repo.create("A", settings)
        repo.save(created.copy(name = "A2"))
        projectFile(created.id).writeText("{ nope")
        val vm = viewModel(repo)
        assertTrue(vm.state.value.projects.isEmpty())

        vm.onIntent(HubIntent.RecoverProject(created.id))

        assertEquals(listOf(created.id), vm.state.value.projects.map { it.id })
        assertTrue(vm.state.value.unreadable.isEmpty())
    }

    @Test
    fun `recovering a project with no usable copy reports it and keeps it listed`() = runBlocking {
        val repo = repository()
        val created = repo.create("A", settings)
        projectFile(created.id).writeText("{ nope")
        val vm = viewModel(repo)

        vm.onIntent(HubIntent.RecoverProject(created.id))

        // The error is shown to the user, not dropped.
        val message = vm.effects.first() as HubEffect.ShowMessage
        assertTrue(message.text.contains("no readable backup"))
        assertFalse(vm.state.value.unreadable.single().recoverable)
    }

    @Test
    fun `deleting an unreadable project removes it`() = runBlocking {
        val repo = repository()
        val created = repo.create("A", settings)
        projectFile(created.id).writeText("{ nope")
        val vm = viewModel(repo)

        vm.onIntent(HubIntent.DeleteUnreadable(created.id))

        assertTrue(vm.state.value.unreadable.isEmpty())
        assertFalse(File(tmp.root, "projects/${created.id}").exists())
    }

    // endregion

    // region reopening an interrupted session

    @Test
    fun `an interrupted session offers to reopen its project`() {
        val repo = repository()
        val created = runBlocking { repo.create("Film", settings) }

        val vm = viewModel(repo, FakeSession(unfinished = created.id))

        assertEquals(created.id, vm.state.value.resumeProject?.id)
    }

    @Test
    fun `nothing is offered after a clean exit or when the project is gone`() {
        val repo = repository()
        runBlocking { repo.create("Film", settings) }

        assertNull(viewModel(repo, FakeSession(unfinished = null)).state.value.resumeProject)
        assertNull(viewModel(repo, FakeSession(unfinished = "deleted-long-ago")).state.value.resumeProject)
        assertNull(viewModel(repo).state.value.resumeProject)
    }

    @Test
    fun `reopening opens the editor once and the offer is gone`() = runBlocking {
        val repo = repository()
        val created = repo.create("Film", settings)
        val vm = viewModel(repo, FakeSession(unfinished = created.id))

        vm.onIntent(HubIntent.ResumeSession)

        assertEquals(HubEffect.OpenEditor(created.id), vm.effects.first())
        assertNull(vm.state.value.resumeProject)
        // A refresh (the hub reloads when the editor closes) must not bring the offer back.
        vm.onIntent(HubIntent.Refresh)
        assertNull(vm.state.value.resumeProject)
    }

    @Test
    fun `dismissing clears the marker and the offer`() {
        val repo = repository()
        val created = runBlocking { repo.create("Film", settings) }
        val session = FakeSession(unfinished = created.id)
        val vm = viewModel(repo, session)

        vm.onIntent(HubIntent.DismissResume)
        vm.onIntent(HubIntent.Refresh)

        assertNull(vm.state.value.resumeProject)
        assertNull(session.unfinished)
        assertEquals(1, session.closed)
    }

    @Test
    fun `opening a project by hand also drops the offer`() {
        val repo = repository()
        val created = runBlocking { repo.create("Film", settings) }
        val vm = viewModel(repo, FakeSession(unfinished = created.id))

        vm.onIntent(HubIntent.OpenProject(created.id))
        vm.onIntent(HubIntent.Refresh)

        assertNull(vm.state.value.resumeProject)
    }

    // endregion
}
