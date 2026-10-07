package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.interchange.BundleItemKind
import com.ultimatevideo.uveditor.data.interchange.BundleVerification
import com.ultimatevideo.uveditor.data.interchange.BundleWriteCancelled
import com.ultimatevideo.uveditor.data.interchange.BundleWriteObserver
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import com.ultimatevideo.uveditor.data.interchange.WrittenEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BundleExportExecutorTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private class Io : ExportIO {
        val deleted = mutableListOf<String>()
        var name: String? = "Holiday.uvbundle"
        var deletes = true
        override fun openAsset(uri: String) = 1
        override fun openOutput(uri: String) = 2
        override fun close(fd: Int) = Unit
        override fun displayName(uri: String): String? = name
        override fun deleteOutput(uri: String): Boolean {
            deleted += uri
            return deletes
        }
    }

    private val io = Io()
    private var started = 0
    private var now = 1_000L
    private var otherBusy: String? = null
    private var verifier: BundleVerifier? = BundleVerifier { _, written, _ -> BundleVerification.Verified(written.entries.size, written.bytesWritten) }

    private fun executor() = BundleExportExecutor(
        io, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, { now }, { started++ }, verifier, { otherBusy },
    )

    private val result = BundleWriteResult(
        mediaCopied = 14, mediaSkipped = emptyList(), bytesWritten = 7_945_689_497,
        entries = listOf(WrittenEntry("bundle.json", 10), WrittenEntry("project.json", 10)),
    )

    private fun job(name: String = "Holiday", run: suspend (BundleWriteObserver) -> BundleWriteResult = { result }) =
        BundleJob("p1", name, "content://out/$name.uvbundle", run)

    @Test
    fun `a backup runs to a saved state with the summary the owner asked for`() {
        val executor = executor()
        val during = ArrayList<BundleJobState>()

        val start = executor.start(
            job { observer ->
                observer.onStart(1000, 2)
                observer.onItem(BundleItemKind.MEDIA, "IMG_0014.mov", 1)
                now += 300
                observer.onBytes(400)
                during += executor.state.value
                now += 252_000
                observer.onBytes(600)
                result
            },
        )

        assertEquals(BundleStart.Started, start)
        assertEquals(1, started)
        val running = during.single() as BundleJobState.Running
        assertEquals(40, running.progress.percent)
        assertEquals("IMG_0014.mov", running.progress.itemName)
        val done = executor.state.value as BundleJobState.Done
        assertEquals("Holiday.uvbundle", done.fileName)
        assertEquals(252_300L, done.tookMs)
        assertTrue(done.verification is BundleVerification.Verified)
        val view = bundleViewFor(done)
        assertEquals("Backup saved: Holiday.uvbundle (7.4 GB, 14 media files, took 4:12)", view?.message)
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
                    now += 10
                    observer.onBytes(1000)
                    (executor.state.value as? BundleJobState.Running)?.let { distinct += it.progress }
                }
                result
            },
        )

        // About four a second over ten seconds, plus the item and the last chunk; not a thousand.
        assertTrue("published ${distinct.size}", distinct.size in 30..60)
    }

    @Test
    fun `cancel stops the copy at the next chunk and removes the partial file`() {
        val executor = executor()
        var wrote = 0L

        executor.start(
            job { observer ->
                observer.onStart(1_000_000, 1)
                repeat(100) {
                    if (it == 10) executor.cancel()
                    if (observer.isCancelled()) throw BundleWriteCancelled()
                    wrote += 1000
                    observer.onBytes(1000)
                }
                result
            },
        )

        assertEquals(10_000L, wrote)
        assertEquals(listOf("content://out/Holiday.uvbundle"), io.deleted)
        assertEquals(BundleJobState.Cancelled("p1", "Holiday"), executor.state.value)
        assertNull("cancelled and cleaned up: nothing to show", bundleViewFor(executor.state.value))
        assertFalse(executor.detailsOpen.value)
    }

    @Test
    fun `cancelling when the provider refuses to delete says the partial file is left`() {
        io.deletes = false
        val executor = executor()

        executor.start(job { observer -> executor.cancel(); if (observer.isCancelled()) throw BundleWriteCancelled(); result })

        val cancelled = executor.state.value as BundleJobState.Cancelled
        assertTrue(cancelled.leftoverNote, cancelled.leftoverNote.contains("could not be removed: Holiday.uvbundle"))
        val view = bundleViewFor(cancelled)
        assertEquals(BundleView.Phase.CANCELLED, view?.phase)
        assertTrue(view?.message.orEmpty().contains("incomplete; delete it"))
        assertTrue("the dialog stays open to say so", executor.detailsOpen.value)
    }

    @Test
    fun `a cancel that arrives with the last byte still discards the file`() {
        val executor = executor()

        executor.start(job { executor.cancel(); result })

        assertTrue(executor.state.value is BundleJobState.Cancelled)
        assertEquals(1, io.deleted.size)
    }

    @Test
    fun `a failure keeps the reason, cleans up, and says when the file could not be removed`() {
        io.deletes = false
        val executor = executor()

        executor.start(job { throw IOException("No space left on device") })

        val failed = executor.state.value as BundleJobState.Failed
        assertEquals("The storage is full. Free some space or choose another location.", failed.message)
        assertEquals(1, io.deleted.size)
        assertTrue(bundleViewFor(failed)?.message.orEmpty().contains("could not be removed"))
    }

    @Test
    fun `a lost permission is named`() {
        val executor = executor()

        executor.start(job { throw SecurityException("Permission Denial") })

        assertTrue((executor.state.value as BundleJobState.Failed).message.contains("permission"))
    }

    @Test
    fun `a second backup is refused while one runs, and the first is untouched`() {
        val executor = executor()
        var refusal: BundleStart? = null

        executor.start(job { refusal = executor.start(job("Second")); result })

        val refused = refusal as BundleStart.Refused
        assertTrue(refused.reason, refused.reason.contains("already running: Holiday"))
        assertEquals(1, started)
        assertTrue(executor.state.value is BundleJobState.Done)
    }

    @Test
    fun `a backup is refused while a movie export runs and nothing is started`() {
        otherBusy = "Exporting Wedding"
        val executor = executor()

        val start = executor.start(job())

        val refused = start as BundleStart.Refused
        assertTrue(refused.reason, refused.reason.contains("Exporting Wedding"))
        assertEquals(0, started)
        assertEquals(BundleJobState.Idle, executor.state.value)
    }

    @Test
    fun `after a finished backup is acknowledged a new one can start`() {
        val executor = executor()
        executor.start(job())

        executor.acknowledge()
        assertEquals(BundleJobState.Idle, executor.state.value)
        assertEquals(BundleStart.Started, executor.start(job("Again")))
        assertEquals(2, started)
    }

    @Test
    fun `acknowledge never touches a running backup`() {
        val executor = executor()
        executor.start(job { executor.acknowledge(); assertTrue(executor.state.value.isRunning); result })
    }

    @Test
    fun `the check of the saved file runs in a verifying state and a warning is never a plain success`() {
        val states = ArrayList<BundleJobState>()
        lateinit var executor: BundleExportExecutor
        verifier = BundleVerifier { _, _, _ ->
            states += executor.state.value
            BundleVerification.Warning(listOf("Its table of contents cannot be read"))
        }
        executor = executor()

        executor.start(job())

        assertTrue(states.single().let { it is BundleJobState.Running && it.verifying })
        val done = executor.state.value as BundleJobState.Done
        val view = bundleViewFor(done)
        assertEquals(BundleView.Phase.WARNING, view?.phase)
        assertEquals("Backup saved, but check the file", view?.title)
        assertFalse("a damaged file is not offered for sharing", view?.canShare == true)
        assertNull(bundleNotificationFor(done)?.shareUri)
    }

    @Test
    fun `a check that cannot run says so`() {
        verifier = BundleVerifier { _, _, _ -> throw IllegalStateException("boom") }
        val executor = executor()

        executor.start(job())

        val verification = (executor.state.value as BundleJobState.Done).verification
        assertTrue(verification is BundleVerification.CouldNotVerify)
    }

    @Test
    fun `cancel during the check keeps the finished file and skips the check`() {
        // The user presses Cancel while the saved file is being checked: the file is complete, so only the check stops.
        var inCheck = false
        lateinit var executor: BundleExportExecutor
        verifier = BundleVerifier { _, _, cancelled ->
            inCheck = true
            executor.cancel()
            if (cancelled()) BundleVerification.Skipped else BundleVerification.Verified(2, 1)
        }
        executor = executor()

        executor.start(job())

        assertTrue(inCheck)
        assertEquals(BundleVerification.Skipped, (executor.state.value as BundleJobState.Done).verification)
        assertTrue("the file is kept", io.deleted.isEmpty())
    }

    @Test
    fun `without a verifier the state has no verification and the words say nothing about a check`() {
        verifier = null
        val executor = executor()

        executor.start(job())

        val done = executor.state.value as BundleJobState.Done
        assertNull(done.verification)
        assertEquals(BundleView.Phase.SAVED, bundleViewFor(done)?.phase)
    }

    @Test
    fun `hide and show only move the dialog`() {
        val executor = executor()
        executor.start(job { assertTrue(executor.detailsOpen.value); executor.hideDetails(); assertFalse(executor.detailsOpen.value); executor.showDetails(); result })
        executor.hideDetails()
        executor.showDetails()
        assertTrue("a finished result can be looked at again", executor.detailsOpen.value)
        executor.acknowledge()
        executor.showDetails()
        assertFalse("nothing to show once forgotten", executor.detailsOpen.value)
    }

    @Test
    fun `the movie export is refused while a backup runs, with words`() {
        val backups = executor()
        var movieRefusal: String? = null

        backups.start(
            job {
                val movie = ExportExecutor(
                    io = object : ExportIO {
                        override fun openAsset(uri: String) = 1
                        override fun openOutput(uri: String) = 2
                        override fun close(fd: Int) = Unit
                        override fun deleteOutput(uri: String) = true
                    },
                    runner = object : com.ultimatevideo.uveditor.engine.export.ExportRunner {
                        override fun start(request: com.ultimatevideo.uveditor.engine.export.ExportRequest, listener: com.ultimatevideo.uveditor.engine.export.ExportListener): com.ultimatevideo.uveditor.engine.export.ExportHandle =
                            throw AssertionError("must not start")
                    },
                    scope = CoroutineScope(SupervisorJob() + dispatcher),
                    ioDispatcher = dispatcher,
                    otherJobBusy = { (backups.state.value as? BundleJobState.Running)?.let { "Backing up ${it.projectName}" } },
                )
                assertFalse(movie.start(ExportJob("p2", "Wedding", "content://out/w.mp4") { throw AssertionError("must not prepare") }))
                movieRefusal = movie.refusalReason()
                result
            },
        )

        assertTrue(movieRefusal, movieRefusal.orEmpty().contains("Backing up Holiday"))
    }
}

