package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.BundleImportSummary
import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleItemKind
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteObserver
import com.qtekfun.ultimatevideoeditor.data.interchange.ImportCancelled
import com.qtekfun.ultimatevideoeditor.data.interchange.sampleProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BundleImportExecutorTest {
    private val testScope = TestScope()
    private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
    private var started = 0
    private var otherBusy: String? = null

    private fun executor(name: String? = "Holiday.uvbundle") = BundleImportExecutor(
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        ioDispatcher = dispatcher,
        clock = { testScope.currentTime },
        onStarted = { started++ },
        displayName = { name },
        otherJobBusy = { otherBusy },
    )

    private val report = ImportReport(
        sampleProject().copy(id = "id-1", name = "Holiday"),
        BundleImportSummary(mediaCopied = 14, relinked = 0, missing = emptyList()),
    )

    private fun job(uri: String = "content://in/Holiday.uvbundle", run: suspend (BundleWriteObserver) -> ImportReport = { report }) = ImportJob(uri, run)

    @Test
    fun `an import that ends at once is quick, shows nothing and never starts the service`() {
        val executor = executor("Holiday.json")

        assertEquals(BundleStart.Started, executor.start(job()))
        assertTrue("the work waits for the IO dispatcher, start returned at once", executor.state.value.isRunning)
        testScope.advanceUntilIdle()

        val done = executor.state.value as ImportJobState.Done
        assertTrue(done.quick)
        assertEquals(0, started)
        assertFalse(executor.detailsOpen.value)
        assertNull("nothing to flash: the project list says it in a message", importViewFor(done))
        assertNull(importNotificationFor(done))
    }

    @Test
    fun `nothing is shown for the first 400 ms, then the dialog opens and the service starts`() {
        val executor = executor()
        executor.start(job { delay(5_000); report })

        testScope.advanceTimeBy(399)
        testScope.runCurrent()
        assertNull("not yet", importViewFor(executor.state.value))
        assertFalse(executor.detailsOpen.value)
        assertEquals(0, started)

        testScope.advanceTimeBy(2)
        testScope.runCurrent()
        val running = executor.state.value as ImportJobState.Running
        assertTrue(running.revealed)
        assertNotNull(importViewFor(running))
        assertTrue(executor.detailsOpen.value)
        assertEquals(1, started)
        assertEquals("Holiday.uvbundle", running.sourceName)
    }

    @Test
    fun `a long import ends with the summary the owner asked for`() {
        val executor = executor()
        executor.start(
            job { observer ->
                observer.onStart(7_945_689_497, 14)
                observer.onItem(BundleItemKind.MEDIA, "IMG_0014.mov", 3)
                delay(1_000)
                observer.onBytes(1_800_000_000)
                delay(251_000)
                observer.onBytes(7_945_689_497 - 1_800_000_000)
                report
            },
        )

        testScope.advanceTimeBy(2_000)
        testScope.runCurrent()
        val view = importViewFor(executor.state.value)!!
        assertEquals("Importing project: file 3 of 14: IMG_0014.mov", view.step)
        assertEquals("Importing Holiday.uvbundle", view.title)
        assertTrue(view.progressLine, view.progressLine.startsWith("1.7 of 7.4 GB"))

        testScope.advanceUntilIdle()
        val done = executor.state.value as ImportJobState.Done
        assertFalse(done.quick)
        assertEquals(252_000L, done.tookMs)
        assertEquals(7_945_689_497, done.bytes)
        val finished = importViewFor(done)!!
        assertEquals("Imported: Holiday (7.4 GB, 14 media files, took 4:12)", finished.message)
        assertEquals(ImportView.Phase.IMPORTED, finished.phase)
        assertEquals("id-1", finished.projectId)
        assertTrue(executor.detailsOpen.value)
    }

    @Test
    fun `progress reaches the state throttled, not once per chunk`() {
        val executor = executor()
        val distinct = HashSet<BundleProgress>()
        executor.start(
            job { observer ->
                observer.onStart(1_000_000, 1)
                observer.onItem(BundleItemKind.MEDIA, "a", 1)
                repeat(1000) { // 1000 chunks within 10 s
                    delay(10)
                    observer.onBytes(1000)
                    (executor.state.value as? ImportJobState.Running)?.let { distinct += it.progress }
                }
                report
            },
        )

        testScope.advanceUntilIdle()

        // About four a second over ten seconds, plus the item and the last chunk; not a thousand.
        assertTrue("published ${distinct.size}", distinct.size in 30..60)
    }

    @Test
    fun `cancel stops at the next chunk, the importer cleans up and no project is left`() {
        val executor = executor()
        var cleaned = false
        var wrote = 0L
        executor.start(
            job { observer ->
                try {
                    observer.onStart(1_000_000, 1)
                    repeat(100) {
                        if (it == 10) executor.cancel()
                        if (observer.isCancelled()) throw ImportCancelled()
                        wrote += 1000
                        observer.onBytes(1000)
                        delay(100)
                    }
                    report
                } finally {
                    cleaned = true
                }
            },
        )

        testScope.advanceUntilIdle()

        assertEquals(10_000L, wrote)
        assertTrue(cleaned)
        assertEquals(ImportJobState.Cancelled("content://in/Holiday.uvbundle", "Holiday.uvbundle"), executor.state.value)
        assertNull("cancelled and cleaned up: nothing to show", importViewFor(executor.state.value))
        assertFalse(executor.detailsOpen.value)
    }

    @Test
    fun `an error after cancel is still a cancel, not a failure`() {
        val executor = executor()
        executor.start(job { executor.cancel(); throw IOException("stream closed") })

        testScope.advanceUntilIdle()

        assertTrue(executor.state.value is ImportJobState.Cancelled)
    }

    @Test
    fun `a failure names its cause and stays in the bar`() {
        val executor = executor()
        executor.start(job { delay(1_000); throw ProjectError.Io("import", IOException("No space left on device")) })

        testScope.advanceUntilIdle()

        val failed = executor.state.value as ImportJobState.Failed
        assertEquals("The storage is full. Free some space and import again. Nothing was added to the project list.", failed.message)
        val view = importViewFor(failed)!!
        assertEquals(ImportView.Phase.FAILED, view.phase)
        assertEquals("Import failed", view.title)
        assertTrue("the user was looking at the dialog: it stays", executor.detailsOpen.value)
        assertEquals(false, importNotificationFor(failed)?.ongoing)
    }

    @Test
    fun `a package that needs a media folder is not a failure`() {
        val executor = executor()
        executor.start(job("content://in/p.lfpackage") { throw ProjectError.MediaFolderRequired() })

        testScope.advanceUntilIdle()

        assertEquals(ImportJobState.NeedsMediaFolder("content://in/p.lfpackage"), executor.state.value)
        assertNull(importViewFor(executor.state.value))
    }

    @Test
    fun `hide and show only move the dialog`() {
        val executor = executor()
        executor.start(job { delay(5_000); report })
        testScope.advanceTimeBy(500)
        testScope.runCurrent()
        assertTrue(executor.detailsOpen.value)

        executor.hideDetails()
        assertFalse(executor.detailsOpen.value)
        assertTrue("the bar and the job are untouched", executor.state.value.isRunning)
        executor.showDetails()
        assertTrue(executor.detailsOpen.value)

        testScope.advanceUntilIdle()
        executor.hideDetails()
        executor.showDetails()
        assertTrue("a finished result can be looked at again", executor.detailsOpen.value)
        executor.acknowledge()
        executor.showDetails()
        assertFalse("nothing to show once forgotten", executor.detailsOpen.value)
    }

    @Test
    fun `acknowledge never touches a running import`() {
        val executor = executor()
        executor.start(job { executor.acknowledge(); assertTrue(executor.state.value.isRunning); report })
        testScope.advanceUntilIdle()
        assertTrue(executor.state.value is ImportJobState.Done)
    }

    @Test
    fun `a second import is refused while one runs and the first is untouched`() {
        val executor = executor()
        var refusal: BundleStart? = null
        executor.start(job { refusal = executor.start(job("content://in/second")); report })

        testScope.advanceUntilIdle()

        val refused = refusal as BundleStart.Refused
        assertTrue(refused.reason, refused.reason.contains("already running: Holiday.uvbundle"))
        assertTrue(executor.state.value is ImportJobState.Done)
    }

    @Test
    fun `after a finished import is acknowledged a new one can start`() {
        val executor = executor()
        executor.start(job())
        testScope.advanceUntilIdle()

        executor.acknowledge()
        assertEquals(ImportJobState.Idle, executor.state.value)
        assertEquals(BundleStart.Started, executor.start(job()))
    }

    // region one long job at a time, across the movie export, the backup and the import

    private val noMovie: ExportJobState? = null

    @Test
    fun `an import is refused while another long job runs, naming it`() {
        val executor = executor()
        otherBusy = LongJobs.describe(noMovie, BundleJobState.Running("p9", "Wedding", BundleProgress(), 0), ImportJobState.Idle, LongJobs.Kind.IMPORT)

        val refused = executor.start(job()) as BundleStart.Refused

        assertTrue(refused.reason, refused.reason.contains("Backing up Wedding"))
        assertEquals(ImportJobState.Idle, executor.state.value)
    }

    @Test
    fun `the backup and the movie export are refused while an import runs, naming it`() {
        val imports = executor()
        imports.start(job { delay(10_000); report })
        testScope.runCurrent()

        val seenByBackup = LongJobs.describe(noMovie, BundleJobState.Idle, imports.state.value, LongJobs.Kind.BACKUP)
        val seenByMovie = LongJobs.describe(noMovie, BundleJobState.Idle, imports.state.value, LongJobs.Kind.MOVIE)
        val seenByImport = LongJobs.describe(noMovie, BundleJobState.Idle, imports.state.value, LongJobs.Kind.IMPORT)

        assertEquals("Importing Holiday.uvbundle", seenByBackup)
        assertEquals("Importing Holiday.uvbundle", seenByMovie)
        assertNull("an import does not refuse itself with its own name", seenByImport)

        val backup = BundleExportExecutor(
            io = object : ExportIO {
                override fun openAsset(uri: String) = 1
                override fun openOutput(uri: String) = 2
                override fun close(fd: Int) = Unit
                override fun deleteOutput(uri: String) = true
            },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
            otherJobBusy = { seenByBackup },
        )
        val refused = backup.start(BundleJob("p1", "Holiday", "content://out/h.uvbundle") { throw AssertionError("must not run") }) as BundleStart.Refused
        assertTrue(refused.reason, refused.reason.contains("Importing Holiday.uvbundle"))
    }

    @Test
    fun `the words name each running job and nothing else`() {
        val running = BundleJobState.Running("p", "Holiday", BundleProgress(), 0)
        assertEquals("Backing up Holiday", LongJobs.describe(noMovie, running, ImportJobState.Idle, LongJobs.Kind.MOVIE))
        assertNull(LongJobs.describe(noMovie, running, ImportJobState.Idle, LongJobs.Kind.BACKUP))
        assertNull(LongJobs.describe(noMovie, BundleJobState.Idle, ImportJobState.Done("u", "n", report, 0, 0, true), LongJobs.Kind.MOVIE))
    }

    // endregion

    @Test
    fun `start returns at once and the import never runs on the calling thread`() {
        fun daemon(name: String) = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }
        val io = daemon("uv-import-io")
        val scopePool = daemon("uv-import-scope")
        val inJob = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobThread = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val executor = BundleImportExecutor(
            scope = CoroutineScope(SupervisorJob() + scopePool.asCoroutineDispatcher()),
            ioDispatcher = io.asCoroutineDispatcher(),
            displayName = { jobThread.set(Thread.currentThread().name); null },
            revealAfterMs = 20,
        )
        try {
            val begun = System.nanoTime()
            // The job blocks until the test lets it go: if start() waited for it, this line would never return.
            executor.start(ImportJob("content://in/big") { inJob.countDown(); release.await(10, TimeUnit.SECONDS); report })
            assertTrue("start returned at once", System.nanoTime() - begun < TimeUnit.SECONDS.toNanos(2))
            assertTrue(inJob.await(5, TimeUnit.SECONDS))
            assertTrue("the provider is asked for the name off the calling thread too: ${jobThread.get()}", jobThread.get().orEmpty().startsWith("uv-import-io"))
            release.countDown()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (executor.state.value !is ImportJobState.Done && System.nanoTime() < end) Thread.sleep(10)
        } finally {
            io.shutdownNow()
            scopePool.shutdownNow()
        }
        assertTrue(executor.state.value is ImportJobState.Done)
    }
}
