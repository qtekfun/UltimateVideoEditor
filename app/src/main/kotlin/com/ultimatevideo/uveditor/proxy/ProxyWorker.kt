package com.ultimatevideo.uveditor.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The proxy queue: makes proxies one at a time on [dispatcher] (a single low-priority thread), keeps the
 * outcome of every job in the [index] and holds the cache to its budget afterwards. The queue lives in the
 * index, so [resume] after the process died starts the unfinished jobs again from the beginning.
 */
class ProxyWorker(
    private val index: ProxyIndex,
    private val transcoder: ProxyTranscoder,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val budgetBytes: () -> Long,
    private val protectedKeys: () -> Set<String>,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val changeCount = MutableStateFlow(0)
    private val progressByKey = MutableStateFlow<Map<String, Int>>(emptyMap())
    @Volatile private var currentKey: String? = null

    /** Counts every change to the index: collectors re-read what they show. */
    val changes: StateFlow<Int> = changeCount.asStateFlow()

    /** Progress (0..1000) of the job being made, by key. */
    val progress: StateFlow<Map<String, Int>> = progressByKey.asStateFlow()

    init {
        scope.launch(dispatcher) {
            for (key in queue) runOne(key)
        }
    }

    /** Queues the proxy of [job]. A proxy that is ready or already queued or running is left alone. */
    @Synchronized
    fun enqueue(job: ProxyJob, targetShortSide: Int): ProxyEntry {
        val key = ProxyKeys.of(job, targetShortSide)
        val existing = index.get(key)
        if (existing != null && (existing.state == ProxyState.READY || existing.state == ProxyState.QUEUED || existing.state == ProxyState.RUNNING)) {
            return existing
        }
        val entry = ProxyEntry(key = key, job = job, state = ProxyState.QUEUED, targetShortSide = targetShortSide)
        index.put(entry)
        queue.trySend(key)
        bump()
        return entry
    }

    /** After a restart: fixes the index and queues what was unfinished. Call once at startup. */
    fun resume() {
        for (entry in index.recoverAfterKill()) queue.trySend(entry.key)
        bump()
    }

    /** Stops [key]: a queued job is dropped, the running one is cancelled. */
    @Synchronized
    fun cancel(key: String) {
        if (currentKey == key) {
            transcoder.cancelCurrent()
        } else if (index.get(key)?.state == ProxyState.QUEUED) {
            index.remove(key)
            bump()
        }
    }

    /** Drops everything queued and cancels the running job. */
    @Synchronized
    fun cancelAll() {
        for (entry in index.all()) if (entry.state == ProxyState.QUEUED) index.remove(entry.key)
        currentKey?.let { transcoder.cancelCurrent() }
        bump()
    }

    fun bump() {
        changeCount.update { it + 1 }
    }

    private fun runOne(key: String) {
        val entry = index.get(key) ?: return
        if (entry.state != ProxyState.QUEUED) return
        currentKey = key
        var lastShown = -1
        try {
            transcoder.generate(entry) { permille ->
                if (permille / PROGRESS_STEP != lastShown) {
                    lastShown = permille / PROGRESS_STEP
                    progressByKey.update { it + (key to permille) }
                }
            }
        } catch (e: ProxyException) {
            // The transcoder recorded the outcome in the index (FAILED, or removed when cancelled or not needed).
        } catch (e: InterruptedException) {
            // The thread is being shut down (the app is going away). The entry stays RUNNING in the index, so
            // the next start puts it back in the queue; the interrupt is passed on.
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            // An unexpected failure must not leave the job stuck as running.
            index.put(entry.copy(state = ProxyState.FAILED, fileName = null, bytes = 0, error = e.message ?: e.javaClass.simpleName))
        } finally {
            currentKey = null
            progressByKey.update { it - key }
            index.evictToBudget(budgetBytes(), protectedKeys())
            index.flush()
            bump()
        }
    }

    private companion object {
        const val PROGRESS_STEP = 10 // publish every 1 %
    }
}
