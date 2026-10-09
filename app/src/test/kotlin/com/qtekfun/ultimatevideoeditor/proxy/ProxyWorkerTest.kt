package com.qtekfun.ultimatevideoeditor.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyWorkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val dir get() = File(tmp.root, "proxies")
    private val index by lazy { ProxyIndex(dir) }
    private val transcoder by lazy { FakeTranscoder(index) }
    private var budget = Long.MAX_VALUE
    private var protectedKeys: Set<String> = emptySet()
    private val worker by lazy {
        ProxyWorker(index, transcoder, scope, executor.asCoroutineDispatcher(), budgetBytes = { budget }, protectedKeys = { protectedKeys })
    }

    @After
    fun tearDown() {
        // A test that failed before it released the job must not leave the worker waiting for its latch.
        transcoder.hold?.countDown()
        transcoder.gate?.countDown()
        // Wait for the worker thread to finish before the temporary folder is deleted under it.
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun job(n: Int) = ProxyJob("content://m$n", 300, 30, 1, "Rec709-SDR")

    @Test
    fun `jobs run one at a time in the order they were queued`() {
        transcoder.hold = CountDownLatch(1)
        val first = worker.enqueue(job(1), 720)
        val second = worker.enqueue(job(2), 720)
        val third = worker.enqueue(job(3), 720)

        waitUntil { transcoder.order.size == 1 }
        assertEquals(listOf(first.key), transcoder.order) // the others wait
        assertEquals(ProxyState.QUEUED, index.get(second.key)!!.state)

        transcoder.hold!!.countDown()
        waitUntil { index.all().all { it.state == ProxyState.READY } && index.all().size == 3 }

        assertEquals(listOf(first.key, second.key, third.key), transcoder.order)
    }

    @Test
    fun `a proxy that is ready or already queued is not made twice`() {
        val first = worker.enqueue(job(1), 720)
        waitUntil { index.get(first.key)?.state == ProxyState.READY }

        val again = worker.enqueue(job(1), 720)

        assertEquals(ProxyState.READY, again.state)
        assertEquals(1, transcoder.order.size)
    }

    @Test
    fun `a failed proxy can be queued again`() {
        transcoder.failWith = ProxyException(ProxyErrorCode.ENCODER_UNSUPPORTED, "no encoder")
        val entry = worker.enqueue(job(1), 720)
        waitUntil { index.get(entry.key)?.state == ProxyState.FAILED }

        transcoder.failWith = null
        worker.enqueue(job(1), 720)

        waitUntil { index.get(entry.key)?.state == ProxyState.READY }
        assertEquals(2, transcoder.order.size)
    }

    @Test
    fun `a queued job can be cancelled and is never started`() {
        transcoder.hold = CountDownLatch(1)
        worker.enqueue(job(1), 720)
        val second = worker.enqueue(job(2), 720)
        waitUntil { transcoder.order.size == 1 }

        worker.cancel(second.key)
        transcoder.hold!!.countDown()
        waitUntil { index.all().any { it.state == ProxyState.READY } }
        Thread.sleep(100)

        assertNull(index.get(second.key))
        assertEquals(1, transcoder.order.size)
    }

    @Test
    fun `the running job can be cancelled`() {
        transcoder.hold = CountDownLatch(1)
        val entry = worker.enqueue(job(1), 720)
        waitUntil { transcoder.order.size == 1 }

        worker.cancel(entry.key)

        waitUntil { index.get(entry.key) == null }
        waitUntil { entry.key !in worker.progress.value }
        assertTrue(index.all().isEmpty())
    }

    @Test
    fun `progress is published while a job runs and cleared after`() {
        transcoder.hold = CountDownLatch(1)
        val entry = worker.enqueue(job(1), 720)

        waitUntil { worker.progress.value[entry.key] == 500 }
        transcoder.hold!!.countDown()
        waitUntil { entry.key !in worker.progress.value }
    }

    @Test
    fun `after a restart the unfinished jobs start again from the beginning`() {
        transcoder.hold = CountDownLatch(1)
        val running = worker.enqueue(job(1), 720)
        val waiting = worker.enqueue(job(2), 720)
        waitUntil { transcoder.order.size == 1 }
        // The process dies here: the index says RUNNING and QUEUED, a part file may be on disk.
        index.partFileFor(running.key).writeBytes(ByteArray(20))
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        val restartedExecutor = Executors.newSingleThreadExecutor()
        try {
            val reopened = ProxyIndex(dir)
            val resumed = FakeTranscoder(reopened)
            val restarted = ProxyWorker(
                reopened, resumed, CoroutineScope(SupervisorJob() + restartedExecutor.asCoroutineDispatcher()),
                restartedExecutor.asCoroutineDispatcher(), budgetBytes = { Long.MAX_VALUE }, protectedKeys = { emptySet() },
            )

            restarted.resume()

            waitUntil { reopened.all().size == 2 && reopened.all().all { it.state == ProxyState.READY } }
            assertEquals(setOf(running.key, waiting.key), resumed.order.toSet())
        } finally {
            restartedExecutor.shutdownNow()
            restartedExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the cache is held to its budget after each job but the open project's proxies stay`() {
        budget = 150
        val keep = ProxyKeys.of(job(1), 720)
        protectedKeys = setOf(keep)

        worker.enqueue(job(1), 720)
        worker.enqueue(job(2), 720)
        worker.enqueue(job(3), 720)
        waitUntil { transcoder.order.size == 3 && worker.progress.value.isEmpty() && index.all().none { it.state == ProxyState.QUEUED || it.state == ProxyState.RUNNING } }

        assertTrue(index.get(keep) != null)
        assertTrue(index.totalBytes() <= 150)
    }

    @Test
    fun `an unexpected failure does not leave the job stuck as running`() {
        val boom = object : ProxyTranscoder {
            override fun generate(entry: ProxyEntry, onProgress: (Int) -> Unit): ProxyEntry = throw IllegalStateException("boom")

            override fun cancelCurrent() = Unit
        }
        val broken = ProxyWorker(index, boom, scope, executor.asCoroutineDispatcher(), { Long.MAX_VALUE }, { emptySet() })

        val entry = broken.enqueue(job(1), 720)

        waitUntil { index.get(entry.key)?.state == ProxyState.FAILED }
        assertEquals("boom", index.get(entry.key)!!.error)
    }

    @Test
    fun `a failing save of the index does not end the queue`() {
        // The folder vanishes after the first job and its pending index write fails; the second job must still run.
        var calls = 0
        // The first job waits until both jobs are queued: queueing writes the index, which the vanishing folder would break.
        val bothQueued = CountDownLatch(1)
        val vanishing = object : ProxyTranscoder {
            override fun generate(entry: ProxyEntry, onProgress: (Int) -> Unit): ProxyEntry {
                calls++
                if (calls == 1) bothQueued.await(60, TimeUnit.SECONDS)
                if (calls == 2) dir.mkdirs()
                val result = transcoder.generate(entry, onProgress)
                if (calls == 1) {
                    index.touch(entry.key) // leaves a write pending for the worker's housekeeping
                    dir.deleteRecursively()
                }
                return result
            }

            override fun cancelCurrent() = Unit
        }
        val resilient = ProxyWorker(index, vanishing, scope, executor.asCoroutineDispatcher(), { Long.MAX_VALUE }, { emptySet() })

        resilient.enqueue(job(1), 720)
        val second = resilient.enqueue(job(2), 720)
        bothQueued.countDown()

        waitUntil { index.get(second.key)?.state == ProxyState.READY }
        assertEquals(2, calls)
    }

    @Test
    fun `a job whose write fails is marked failed and the next job still runs`() {
        val flaky = object : ProxyTranscoder {
            private var calls = 0

            override fun generate(entry: ProxyEntry, onProgress: (Int) -> Unit): ProxyEntry {
                calls++
                if (calls == 1) throw IOException("disk full")
                return transcoder.generate(entry, onProgress)
            }

            override fun cancelCurrent() = Unit
        }
        val resilient = ProxyWorker(index, flaky, scope, executor.asCoroutineDispatcher(), { Long.MAX_VALUE }, { emptySet() })

        val first = resilient.enqueue(job(1), 720)
        val second = resilient.enqueue(job(2), 720)

        waitUntil { index.get(second.key)?.state == ProxyState.READY }
        assertEquals(ProxyState.FAILED, index.get(first.key)!!.state)
        assertEquals("disk full", index.get(first.key)!!.error)
    }
}
