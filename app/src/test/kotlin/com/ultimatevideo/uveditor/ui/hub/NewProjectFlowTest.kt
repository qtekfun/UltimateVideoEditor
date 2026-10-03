package com.ultimatevideo.uveditor.ui.hub

import com.ultimatevideo.uveditor.data.ClipPeeker
import com.ultimatevideo.uveditor.data.MatchedClip
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.NewProjectDefaults
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectSummary
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import com.ultimatevideo.uveditor.data.SavedProjectChoices
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.engine.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NewProjectFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private var counter = 0
    private var stores = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class MemoryDefaults(var saved: SavedProjectChoices? = null, var saves: Int = 0) : NewProjectDefaults {
        override fun load(): SavedProjectChoices? = saved

        override fun save(choices: SavedProjectChoices) {
            saved = choices
            saves++
        }
    }

    private class FakePeeker(private val answers: Map<String, MatchedClip>) : ClipPeeker {
        override suspend fun peek(uri: String): MatchedClip =
            answers[uri] ?: throw MediaImportException("Cannot open the selected file")
    }

    private fun repository() = ProjectRepository(
        // One store per view model, so a test that makes several of them never sees the others' projects.
        rootDir = File(tmp.root, "projects-${++stores}"),
        transferIO = object : ProjectTransferIO {
            override fun read(uri: String): ByteArray = throw IOException("unused")

            override fun write(uri: String, bytes: ByteArray) = throw IOException("unused")
        },
        ioDispatcher = dispatcher,
        idGenerator = { "id-${++counter}" },
    )

    private fun viewModel(
        defaults: NewProjectDefaults? = null,
        peeker: ClipPeeker? = null,
    ) = HubViewModel(
        engine = object : EngineClient {
            override fun version() = "1"
        },
        projects = repository(),
        workDispatcher = dispatcher,
        defaults = defaults,
        peeker = peeker,
    )

    private fun HubViewModel.draft() = checkNotNull(state.value.newProjectDraft)

    private fun aspect(id: String) = checkNotNull(ProjectPresets.aspectById(id))

    private fun tier(id: String) = checkNotNull(ProjectPresets.tierById(id))

    // region sizing

    @Test
    fun `aspect ratio and short side give the usual sizes`() {
        assertEquals(1920 to 1080, ProjectSizing.sizeFor(aspect("16:9"), 1080))
        assertEquals(1280 to 720, ProjectSizing.sizeFor(aspect("16:9"), 720))
        assertEquals(2560 to 1440, ProjectSizing.sizeFor(aspect("16:9"), 1440))
        assertEquals(3840 to 2160, ProjectSizing.sizeFor(aspect("16:9"), 2160))
        assertEquals(1080 to 1920, ProjectSizing.sizeFor(aspect("9:16"), 1080))
        assertEquals(2160 to 3840, ProjectSizing.sizeFor(aspect("9:16"), 2160))
        assertEquals(1080 to 1080, ProjectSizing.sizeFor(aspect("1:1"), 1080))
        assertEquals(1080 to 1350, ProjectSizing.sizeFor(aspect("4:5"), 1080))
        assertEquals(1440 to 1080, ProjectSizing.sizeFor(aspect("4:3"), 1080))
        assertEquals(2520 to 1080, ProjectSizing.sizeFor(aspect("21:9"), 1080))
    }

    @Test
    fun `every aspect at every tier is an even size inside the allowed range`() {
        for (a in ProjectPresets.aspects.filterNot { it.custom }) {
            for (t in ProjectPresets.tiers.filterNot { it.custom }) {
                val (w, h) = ProjectSizing.sizeFor(a, t.shortSide)
                assertTrue("${a.id} at ${t.id} gives ${w}x$h", w % 2 == 0 && h % 2 == 0)
                assertNull("${a.id} at ${t.id}", ProjectSizing.problemWith(w, h))
                assertEquals("${a.id} at ${t.id}", t.shortSide, minOf(w, h))
            }
        }
    }

    @Test
    fun `typed sizes are validated`() {
        assertNull(ProjectSizing.problemWith(1920, 1080))
        assertNotNull(ProjectSizing.problemWith(0, 1080))
        assertNotNull(ProjectSizing.problemWith(1921, 1080))
        assertNotNull(ProjectSizing.problemWith(64, 64))
        assertNotNull(ProjectSizing.problemWith(8194, 1080))
        assertEquals(1080, ProjectSizing.toEven(1079))
        assertEquals(1080, ProjectSizing.toEven(1080))
    }

    // endregion

    // region the sheet

    @Test
    fun `the sheet opens on 1080p30 SDR with a summary line`() {
        val vm = viewModel()

        vm.onIntent(HubIntent.ShowNewProject)

        val draft = vm.draft()
        assertEquals(1920 to 1080, draft.width to draft.height)
        assertEquals("1920 × 1080, 30 fps, SDR", draft.summary)
        assertEquals(StartMode.BLANK, draft.startMode)
        assertTrue(draft.canCreate)
    }

    @Test
    fun `selectors change the exact size`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)

        vm.onIntent(HubIntent.DraftAspectSelected(aspect("9:16")))
        assertEquals(1080 to 1920, vm.draft().width to vm.draft().height)
        vm.onIntent(HubIntent.DraftTierSelected(tier("2160p")))
        assertEquals(2160 to 3840, vm.draft().width to vm.draft().height)
        vm.onIntent(HubIntent.DraftFpsSelected(ProjectPresets.fps.first { it.label == "59.94" }))
        vm.onIntent(HubIntent.DraftColorSpaceSelected(ProjectPresets.colorSpaces[1]))
        assertEquals("2160 × 3840, 59.94 fps, HLG", vm.draft().summary)
    }

    @Test
    fun `every quick preset fills the selectors and creates the project it names`() {
        for (preset in ProjectPresets.quick.filterNot { it.matchFirstClip }) {
            val vm = viewModel()
            vm.onIntent(HubIntent.ShowNewProject)
            vm.onIntent(HubIntent.DraftQuickPreset(preset))
            val draft = vm.draft()
            assertEquals(preset.aspect, draft.aspect)
            assertEquals(preset.tier, draft.tier)
            assertEquals(preset.fps, draft.fps)
            assertEquals(StartMode.BLANK, draft.startMode)

            vm.onIntent(HubIntent.ConfirmCreate)
            val project = vm.state.value.projects.single()
            assertEquals(draft.width to draft.height, project.settings.width to project.settings.height)
            assertEquals(preset.fps.num to preset.fps.den, project.settings.fpsNum to project.settings.fpsDen)
        }
    }

    @Test
    fun `the vertical preset is 1080 by 1920 at 30 fps`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)

        vm.onIntent(HubIntent.DraftQuickPreset(ProjectPresets.quick.first { it.id == "vertical" }))

        assertEquals("1080 × 1920, 30 fps, SDR", vm.draft().summary)
    }

    @Test
    fun `selectors stay editable after a quick preset`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftQuickPreset(ProjectPresets.quick.first { it.id == "cinema" }))

        vm.onIntent(HubIntent.DraftFpsSelected(ProjectPresets.fps.first { it.label == "25" }))

        assertEquals("1920 × 1080, 25 fps, SDR", vm.draft().summary)
    }

    @Test
    fun `a custom aspect starts from the current pixels and validates what is typed`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftQuickPreset(ProjectPresets.quick.first { it.id == "yt-4k" }))

        vm.onIntent(HubIntent.DraftAspectSelected(ProjectPresets.customAspect))
        assertEquals(3840 to 2160, vm.draft().customWidth to vm.draft().customHeight)

        vm.onIntent(HubIntent.DraftCustomWidthChanged("1001"))
        assertNotNull(vm.draft().sizeProblem)
        assertFalse(vm.draft().canCreate)

        vm.onIntent(HubIntent.DraftCustomWidthChanged("abc"))
        assertEquals(0, vm.draft().customWidth)
        assertNotNull(vm.draft().sizeProblem)

        vm.onIntent(HubIntent.DraftCustomWidthChanged("1000"))
        vm.onIntent(HubIntent.DraftCustomHeightChanged("1000"))
        assertNull(vm.draft().sizeProblem)
        vm.onIntent(HubIntent.ConfirmCreate)
        assertEquals(1000 to 1000, vm.state.value.projects.single().settings.let { it.width to it.height })
    }

    @Test
    fun `a custom short side keeps the chosen shape`() {
        val vm = viewModel()
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftAspectSelected(aspect("9:16")))

        vm.onIntent(HubIntent.DraftTierSelected(ProjectPresets.customTier))
        assertEquals(1080, vm.draft().customShortSide)
        vm.onIntent(HubIntent.DraftCustomShortSideChanged("540"))

        assertEquals(540 to 960, vm.draft().width to vm.draft().height)
        vm.onIntent(HubIntent.DraftCustomShortSideChanged(""))
        assertNotNull(vm.draft().sizeProblem)
        assertFalse(vm.draft().canCreate)
    }

    // endregion

    // region match first clip

    private val hlgClip = MatchedClip("trip.mp4", 3840, 2160, 30000, 1001, "Rec2020-HLG")

    @Test
    fun `match first clip copies size frame rate and colour space`() {
        val vm = viewModel(peeker = FakePeeker(mapOf("content://trip" to hlgClip)))
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftQuickPreset(ProjectPresets.quick.first { it.matchFirstClip }))
        assertEquals(StartMode.MATCH_FIRST_CLIP, vm.draft().startMode)
        assertFalse("nothing to match yet", vm.draft().canCreate)

        vm.onIntent(HubIntent.MatchFromClip("content://trip"))

        val draft = vm.draft()
        assertEquals("3840 × 2160, 29.97 fps, HLG", draft.summary)
        assertTrue(draft.aspect.custom)
        assertEquals(hlgClip, draft.matchedClip)
        assertFalse(draft.isMatching)
        assertTrue(draft.canCreate)

        vm.onIntent(HubIntent.ConfirmCreate)
        val settings = vm.state.value.projects.single().settings
        assertEquals(3840 to 2160, settings.width to settings.height)
        assertEquals(30000 to 1001, settings.fpsNum to settings.fpsDen)
        assertEquals("Rec2020-HLG", settings.colorSpace)
    }

    @Test
    fun `a photo gives only its size and PQ maps to the HDR project space`() {
        val photo = MatchedClip("shot.jpg", 4001, 3001, null, null, null)
        val pq = MatchedClip("hdr.mp4", 1920, 1080, 25, 1, "Rec2020-PQ")
        val vm = viewModel(peeker = FakePeeker(mapOf("content://photo" to photo, "content://pq" to pq)))
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftFpsSelected(ProjectPresets.fps.first { it.label == "50" }))

        vm.onIntent(HubIntent.MatchFromClip("content://photo"))
        assertEquals("4002 × 3002, 50 fps, SDR", vm.draft().summary)

        vm.onIntent(HubIntent.MatchFromClip("content://pq"))
        assertEquals("1920 × 1080, 25 fps, HLG", vm.draft().summary)
    }

    @Test
    fun `a frame rate that is not listed is kept as it is`() {
        val odd = MatchedClip("odd.mp4", 1280, 720, 48, 1, "Rec709-SDR")
        val vm = viewModel(peeker = FakePeeker(mapOf("content://odd" to odd)))
        vm.onIntent(HubIntent.ShowNewProject)

        vm.onIntent(HubIntent.MatchFromClip("content://odd"))

        assertEquals(48 to 1, vm.draft().fps.num to vm.draft().fps.den)
        assertEquals("48", vm.draft().fps.label)
        vm.onIntent(HubIntent.ConfirmCreate)
        assertEquals(48, vm.state.value.projects.single().settings.fpsNum)
    }

    @Test
    fun `a clip that cannot be read is reported in the sheet and blocks creating from it`() {
        val vm = viewModel(peeker = FakePeeker(emptyMap()))
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftStartModeSelected(StartMode.MATCH_FIRST_CLIP))

        vm.onIntent(HubIntent.MatchFromClip("content://gone"))

        assertEquals("Cannot open the selected file", vm.draft().matchError)
        assertFalse(vm.draft().isMatching)
        assertFalse(vm.draft().canCreate)

        vm.onIntent(HubIntent.DraftStartModeSelected(StartMode.BLANK))
        assertNull(vm.draft().matchError)
        assertTrue(vm.draft().canCreate)
    }

    // endregion

    // region remembered choices

    @Test
    fun `the last choices are saved on create and offered next time`() {
        val defaults = MemoryDefaults()
        val vm = viewModel(defaults = defaults)
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftQuickPreset(ProjectPresets.quick.first { it.id == "vertical" }))
        vm.onIntent(HubIntent.DraftFpsSelected(ProjectPresets.fps.first { it.label == "60" }))
        vm.onIntent(HubIntent.ConfirmCreate)
        assertEquals(1, defaults.saves)

        vm.onIntent(HubIntent.ShowNewProject)

        assertEquals("1080 × 1920, 60 fps, SDR", vm.draft().summary)
        assertEquals("New project 2", vm.draft().name)
    }

    @Test
    fun `a format copied from a clip is not remembered`() {
        val defaults = MemoryDefaults()
        val vm = viewModel(defaults = defaults, peeker = FakePeeker(mapOf("content://trip" to hlgClip)))
        vm.onIntent(HubIntent.ShowNewProject)
        vm.onIntent(HubIntent.DraftStartModeSelected(StartMode.MATCH_FIRST_CLIP))
        vm.onIntent(HubIntent.MatchFromClip("content://trip"))

        vm.onIntent(HubIntent.ConfirmCreate)

        assertEquals(0, defaults.saves)
        vm.onIntent(HubIntent.ShowNewProject)
        assertEquals("1920 × 1080, 30 fps, SDR", vm.draft().summary)
    }

    @Test
    fun `unknown saved ids fall back to the defaults`() {
        val defaults = MemoryDefaults(SavedProjectChoices("zzz", "yyy", 0, 0, 0, 24000, 1001, "nope"))
        val vm = viewModel(defaults = defaults)

        vm.onIntent(HubIntent.ShowNewProject)

        assertEquals("1920 × 1080, 23.976 fps, SDR", vm.draft().summary)
    }

    // endregion

    // region search and sort

    private fun summary(name: String, modified: Long) =
        ProjectSummary("id-$name", name, ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"), modified)

    @Test
    fun `search and sorting appear only for a long list`() {
        val few = HubState(projects = (1..HubState.SEARCH_THRESHOLD).map { summary("P$it", it.toLong()) }, query = "zzz")
        assertFalse(few.showSearch)
        assertEquals("a short list ignores the query", few.projects.size, few.visibleProjects.size)

        val many = few.copy(projects = few.projects + summary("Extra", 99))
        assertTrue(many.showSearch)
    }

    @Test
    fun `search ignores case and sorting follows the chosen order`() {
        val projects = listOf(
            summary("Beach trip", 10), summary("alpha", 50), summary("City", 30), summary("Dunes", 20),
            summary("Beach party", 40), summary("Echo", 60), summary("Forest", 5),
        )
        val state = HubState(projects = projects)

        assertEquals(listOf("Echo", "alpha", "Beach party", "City", "Dunes", "Beach trip", "Forest"), state.visibleProjects.map { it.name })
        assertEquals(
            listOf("alpha", "Beach party", "Beach trip", "City", "Dunes", "Echo", "Forest"),
            state.copy(sort = ProjectSort.NAME).visibleProjects.map { it.name },
        )
        assertEquals(listOf("Beach party", "Beach trip"), state.copy(query = " BEACH ", sort = ProjectSort.NAME).visibleProjects.map { it.name })
    }

    @Test
    fun `search and sort intents update the state`() {
        val vm = viewModel()

        vm.onIntent(HubIntent.SearchChanged("sea"))
        vm.onIntent(HubIntent.SortSelected(ProjectSort.NAME))

        assertEquals("sea", vm.state.value.query)
        assertEquals(ProjectSort.NAME, vm.state.value.sort)
    }

    // endregion
}
