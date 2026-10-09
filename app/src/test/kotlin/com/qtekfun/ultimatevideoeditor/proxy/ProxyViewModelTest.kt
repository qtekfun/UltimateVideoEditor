package com.qtekfun.ultimatevideoeditor.proxy

import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val media = FakeMedia()
    private val index by lazy { ProxyIndex(File(tmp.root, "proxies")) }
    private val transcoder by lazy { FakeTranscoder(index) }
    private val prefs = PausablePrefs(InMemoryProxyPrefs())
    private val manager by lazy {
        ProxyManager(index, prefs, media, transcoder, CoroutineScope(Job() + Dispatchers.Unconfined), executor.asCoroutineDispatcher(), Dispatchers.Unconfined)
    }
    private val created = ArrayList<ProxyViewModel>()
    private fun vm(projectId: String = "p1") = ProxyViewModel(manager, projectId).also { created += it }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // A test that failed before it released the job must not leave the worker waiting for its latch.
        transcoder.hold?.countDown()
        transcoder.gate?.countDown()
        // The collectors of the view models live on Dispatchers.Main: stop them before it goes away.
        created.forEach { it.viewModelScope.cancel() }
        executor.shutdownNow()
        // Wait for the worker thread before the temporary folder is deleted under it.
        executor.awaitTermination(5, TimeUnit.SECONDS)
        Dispatchers.resetMain()
    }

    private val heavy = testAsset(id = "h", uri = "content://heavy")
    private val light = testAsset(id = "l", uri = "content://light")
    private val photo = testAsset(id = "p", uri = "content://photo", hasVideo = false, hasAudio = false, isImage = true)

    private fun registerMedia() {
        media.infos[heavy.uri] = UHD
        media.infos[light.uri] = SourceInfo(1280, 720)
    }

    @Test
    fun `heavy media raises a dismissible suggestion and nothing is made without consent`() {
        registerMedia()
        val vm = vm()

        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy, light, photo)))

        val suggestion = vm.state.value.suggestion
        assertNotNull(suggestion)
        assertEquals(listOf("h"), suggestion!!.assetIds)
        assertTrue(index.all().isEmpty()) // never automatic

        vm.onIntent(ProxyIntent.DismissSuggestion)

        assertNull(vm.state.value.suggestion)
        assertTrue(prefs.suggestionDismissed("p1"))
    }

    @Test
    fun `accepting the suggestion switches proxies on and queues the heavy videos only`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy, light)))

        vm.onIntent(ProxyIntent.AcceptSuggestion)

        assertTrue(vm.state.value.enabled)
        assertTrue(prefs.isEnabled("p1"))
        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready }
        assertEquals(ProxyStatus.None, manager.statusOf(light))
        assertNull(vm.state.value.suggestion)
    }

    @Test
    fun `stalls in the preview offer proxies even for light media`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(light)))
        assertNull(vm.state.value.suggestion)

        repeat(ProxySuggester.STALL_THRESHOLD) { vm.onIntent(ProxyIntent.ReportStall) }

        assertEquals(SuggestionReason.DROPPED_FRAMES, vm.state.value.suggestion?.reason)
    }

    @Test
    fun `switching on queues the videos that have no proxy`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy, light, photo)))

        vm.onIntent(ProxyIntent.SetEnabled(true))

        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready && manager.statusOf(light) is ProxyStatus.Ready }
        assertEquals(listOf("h", "l"), vm.state.value.items.map { it.assetId }.sorted()) // the photo is not listed
    }

    @Test
    fun `the preview is told to reopen when the switch flips or a proxy finishes`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy)))
        val before = vm.state.value.resolveVersion

        vm.onIntent(ProxyIntent.SetEnabled(true))
        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready }

        assertTrue(vm.state.value.resolveVersion > before)
        assertTrue(vm.resolve(heavy, MediaPurpose.PREVIEW).isProxy)
        assertFalse(vm.resolve(heavy, MediaPurpose.EXPORT).isProxy)
    }

    @Test
    fun `a progress tick moves the statuses but not the version the preview watches`() {
        registerMedia()
        transcoder.hold = CountDownLatch(1)
        try {
            val vm = vm()
            vm.onIntent(ProxyIntent.SetAssets(listOf(heavy)))
            vm.onIntent(ProxyIntent.Generate("h"))
            waitUntil { vm.state.value.items.single().status is ProxyStatus.Making }
            val during = vm.state.value.resolveVersion

            Thread.sleep(50) // "does not change": being slow can only make this pass, never fail

            assertEquals(during, vm.state.value.resolveVersion)
        } finally {
            transcoder.hold!!.countDown()
        }
    }

    /**
     * Refreshes run on the worker thread (progress) and on the caller (intents). A refresh that read the statuses
     * before the job started and wrote them after the worker's own refresh used to leave the job shown as queued
     * until the proxy finished.
     */
    @Test
    fun `a refresh that read stale statuses cannot overwrite a newer one`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy)))
        transcoder.gate = CountDownLatch(1) // the job cannot start yet, so the caller reads it as queued
        transcoder.hold = CountDownLatch(1)
        val caller = Thread { vm.onIntent(ProxyIntent.Generate("h")) }
        try {
            // Hold the caller inside its last refresh (the two before it are the ones the queue changes trigger).
            prefs.pauseCallsOf(caller, atCall = 3)
            caller.start()
            assertTrue(prefs.paused.await(30, TimeUnit.SECONDS))

            transcoder.gate!!.countDown() // the job starts and the worker's refresh shows Making
            waitUntil { vm.state.value.items.single().status is ProxyStatus.Making }
            prefs.resume.countDown() // the caller finishes its refresh
            caller.join(30_000)

            assertFalse(caller.isAlive)
            assertTrue(vm.state.value.items.single().status is ProxyStatus.Making)
        } finally {
            prefs.resume.countDown()
            transcoder.hold!!.countDown()
            transcoder.gate!!.countDown()
        }
    }

    @Test
    fun `a proxy the preview could not open is dropped from use and the original is shown`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy)))
        vm.onIntent(ProxyIntent.SetEnabled(true))
        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready }
        assertTrue(vm.resolve(heavy, MediaPurpose.PREVIEW).isProxy)

        vm.onIntent(ProxyIntent.PreviewProxyFailed("h"))

        assertFalse(vm.resolve(heavy, MediaPurpose.PREVIEW).isProxy)
        assertEquals(ProxyStatus.OutOfDate, vm.state.value.items.single().status)
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun `clearing the cache asks first, then frees everything`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy)))
        vm.onIntent(ProxyIntent.Generate("h"))
        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready }

        vm.onIntent(ProxyIntent.RequestClear)
        assertTrue(vm.state.value.confirmClear)
        assertEquals(100L, manager.usage().bytes) // not yet

        vm.onIntent(ProxyIntent.ConfirmClear)

        assertFalse(vm.state.value.confirmClear)
        assertEquals(0L, vm.state.value.usage.bytes)
        assertEquals(ProxyStatus.None, vm.state.value.items.single().status)
    }

    @Test
    fun `cancelling the clear dialog changes nothing`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy)))
        vm.onIntent(ProxyIntent.Generate("h"))
        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready }

        vm.onIntent(ProxyIntent.RequestClear)
        vm.onIntent(ProxyIntent.CancelClear)

        assertFalse(vm.state.value.confirmClear)
        assertEquals(100L, manager.usage().bytes)
    }

    @Test
    fun `budget and proxy size are settings of the device`() {
        val vm = vm()

        vm.onIntent(ProxyIntent.SetBudget(2L shl 30))
        vm.onIntent(ProxyIntent.SetTarget(1080))

        assertEquals(2L shl 30, vm.state.value.usage.budgetBytes)
        assertEquals(1080, vm.state.value.targetShortSide)
        assertEquals(2L shl 30, prefs.budgetBytes)
        assertEquals(1080, prefs.targetShortSide)
    }

    @Test
    fun `the switch is per project`() {
        registerMedia()
        val first = vm("p1")
        first.onIntent(ProxyIntent.SetAssets(listOf(heavy)))
        first.onIntent(ProxyIntent.SetEnabled(true))
        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready }

        val second = vm("p2")

        assertTrue(first.state.value.enabled)
        assertFalse(second.state.value.enabled)
        assertFalse(second.resolve(heavy, MediaPurpose.PREVIEW).isProxy)
    }

    @Test
    fun `removing a single proxy removes only that one`() {
        registerMedia()
        val vm = vm()
        vm.onIntent(ProxyIntent.SetAssets(listOf(heavy, light)))
        vm.onIntent(ProxyIntent.GenerateAll)
        waitUntil { manager.statusOf(heavy) is ProxyStatus.Ready && manager.statusOf(light) is ProxyStatus.Ready }

        vm.onIntent(ProxyIntent.Remove("h"))

        assertEquals(ProxyStatus.None, manager.statusOf(heavy))
        assertTrue(manager.statusOf(light) is ProxyStatus.Ready)
    }
}

/** Preferences that can hold one chosen thread inside its Nth read of the "suggestion dismissed" flag. */
private class PausablePrefs(private val inner: ProxyPrefs) : ProxyPrefs by inner {
    val paused = CountDownLatch(1)
    val resume = CountDownLatch(1)
    @Volatile private var target: Thread? = null
    @Volatile private var atCall = 0
    private val calls = java.util.concurrent.atomic.AtomicInteger()

    fun pauseCallsOf(thread: Thread, atCall: Int) {
        this.atCall = atCall
        target = thread
    }

    override fun suggestionDismissed(projectId: String): Boolean {
        if (Thread.currentThread() === target && calls.incrementAndGet() == atCall) {
            paused.countDown()
            resume.await(60, TimeUnit.SECONDS)
        }
        return inner.suggestionDismissed(projectId)
    }
}
