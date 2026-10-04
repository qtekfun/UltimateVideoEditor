package com.ultimatevideo.uveditor.proxy

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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
        executor.shutdownNow()
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
}
