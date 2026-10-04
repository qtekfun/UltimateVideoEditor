package com.ultimatevideo.uveditor.ui.hub

import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.data.interchange.BundleMediaSource
import com.ultimatevideo.uveditor.data.interchange.sampleProject
import com.ultimatevideo.uveditor.engine.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

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

    @Test
    fun `a bundle export asks for a file named after the project`() = runBlocking {
        val repo = repository("a")
        repo.save(sampleProject())
        val vm = viewModel(repo)
        val project = vm.state.value.projects.single()
        val effect = vm.nextEffect { vm.onIntent(HubIntent.RequestExportBundle(project, includeMedia = true)) }
        assertEquals(HubEffect.LaunchBundleExportPicker("p1", "Sample & Co.uvbundle", true), effect)
    }

    @Test
    fun `exporting a bundle and importing it elsewhere reports what happened to the media`() = runBlocking {
        val source = repository("a")
        source.save(sampleProject())
        val vmA = viewModel(source)
        val message = vmA.nextEffect { vmA.onIntent(HubIntent.ExportBundleTo("p1", "doc://b", includeMedia = true)) }
        assertTrue(message.toString(), (message as HubEffect.ShowMessage).text.startsWith("Bundle exported with 1 media file"))

        val target = viewModel(repository("b"))
        val imported = target.nextEffect { target.onIntent(HubIntent.ImportFrom("doc://b")) }
        val text = (imported as HubEffect.ShowMessage).text
        assertTrue(text, text.startsWith("Imported \"Sample & Co\". 1 media file came with it"))
        assertTrue(text, text.contains("Missing (relink in the editor):"))
        assertEquals(listOf("Sample & Co"), target.state.value.projects.map { it.name })
    }
}
