package com.qtekfun.ultimatevideoeditor.proxy

import android.content.Context
import android.os.Process
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.engine.export.NativeExportRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What the proxy cache holds and may hold. */
data class ProxyUsage(val bytes: Long, val budgetBytes: Long)

/** The proxy state of one asset, as the tray, the library and the proxy sheet show it. */
sealed interface ProxyStatus {
    data object None : ProxyStatus

    data object Queued : ProxyStatus

    data class Making(val permille: Int) : ProxyStatus

    data class Ready(val bytes: Long, val width: Int, val height: Int) : ProxyStatus

    /** The source changed or the proxy file is gone: it is not used until made again. */
    data object OutOfDate : ProxyStatus

    data class Failed(val reason: String) : ProxyStatus
}

/**
 * Everything the editor knows about proxy media: the index, the queue, the settings and the choice between
 * original and proxy. One per app. All of it is local: nothing here touches the network.
 */
class ProxyManager(
    private val index: ProxyIndex,
    val prefs: ProxyPrefs,
    private val media: ProxyMediaAccess,
    transcoder: ProxyTranscoder,
    scope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile private var protectedKeys: Set<String> = emptySet()
    private val infos = ConcurrentHashMap<String, SourceInfo>()
    private val resolved = ConcurrentHashMap<String, ResolvedSource>()

    val worker = ProxyWorker(index, transcoder, scope, workerDispatcher, budgetBytes = { prefs.budgetBytes }, protectedKeys = { protectedKeys })
    private val planner = ProxyPlanner(
        entryOf = { asset -> index.get(ProxyKeys.of(jobOf(asset), prefs.targetShortSide)) },
        fileUriOf = { entry -> index.fileOf(entry)?.let { "file://${it.absolutePath}" } },
    )

    /** Counts changes to the proxies or the settings; collectors re-read statuses and re-resolve sources. */
    val changes: StateFlow<Int> get() = worker.changes
    val progress: StateFlow<Map<String, Int>> get() = worker.progress

    /** Fixes the index after a restart and resumes the queue. Call once at startup. */
    fun start() {
        worker.resume()
    }

    fun isEnabled(projectId: String): Boolean = prefs.isEnabled(projectId)

    fun setEnabled(projectId: String, enabled: Boolean) {
        prefs.setEnabled(projectId, enabled)
        invalidate()
    }

    /**
     * The file to open for [asset] for [purpose]. Cheap enough to call on every preview tick: the answer is
     * remembered until a proxy or a setting changes. Using a proxy marks it as recently used.
     */
    fun resolve(asset: MediaAssetDto, purpose: MediaPurpose, projectId: String): ResolvedSource {
        val enabled = prefs.isEnabled(projectId)
        val cacheKey = "${asset.id}|${asset.uri}|${asset.durationFrames}|${purpose.ordinal}|$enabled|${prefs.targetShortSide}"
        return resolved.getOrPut(cacheKey) {
            planner.resolve(asset, purpose, enabled).also { source ->
                if (source.isProxy) index.touch(ProxyKeys.of(jobOf(asset), prefs.targetShortSide))
            }
        }
    }

    fun statusOf(asset: MediaAssetDto): ProxyStatus {
        if (!asset.canHaveProxy()) return ProxyStatus.None
        val entry = index.get(ProxyKeys.of(jobOf(asset), prefs.targetShortSide)) ?: return ProxyStatus.None
        return when (entry.state) {
            ProxyState.QUEUED -> ProxyStatus.Queued
            ProxyState.RUNNING -> ProxyStatus.Making(worker.progress.value[entry.key] ?: 0)
            ProxyState.READY -> ProxyStatus.Ready(entry.bytes, entry.width, entry.height)
            ProxyState.STALE -> ProxyStatus.OutOfDate
            ProxyState.FAILED -> ProxyStatus.Failed(entry.error ?: "The proxy could not be made")
        }
    }

    fun hasUsableProxy(asset: MediaAssetDto): Boolean = statusOf(asset) is ProxyStatus.Ready

    /** Queues proxies for the videos among [assets] that are not ready or already queued. */
    fun generate(assets: List<MediaAssetDto>) {
        for (asset in assets) {
            if (asset.canHaveProxy()) worker.enqueue(jobOf(asset), prefs.targetShortSide)
        }
        invalidate()
    }

    /** Deletes the proxy of [asset] (or stops it being made). */
    fun remove(asset: MediaAssetDto) {
        val key = ProxyKeys.of(jobOf(asset), prefs.targetShortSide)
        worker.cancel(key)
        index.remove(key)
        invalidate()
    }

    /** The preview could not open the proxy of [asset]: it is not used again until it is made anew. */
    fun markUnusable(asset: MediaAssetDto) {
        val key = ProxyKeys.of(jobOf(asset), prefs.targetShortSide)
        val entry = index.get(key) ?: return
        index.put(entry.copy(state = ProxyState.STALE, error = "The proxy could not be opened"))
        invalidate()
    }

    /** Tells the cache which proxies the open project uses, so eviction keeps them. */
    fun protect(assets: List<MediaAssetDto>) {
        protectedKeys = assets.filter { it.canHaveProxy() }.map { ProxyKeys.of(jobOf(it), prefs.targetShortSide) }.toSet()
    }

    /** Marks proxies whose source changed or whose file is gone as out of date. Runs off the main thread. */
    suspend fun validate(assets: List<MediaAssetDto>) = withContext(ioDispatcher) {
        var changed = false
        for (asset in assets.filter { it.canHaveProxy() }) {
            val key = ProxyKeys.of(jobOf(asset), prefs.targetShortSide)
            val entry = index.get(key) ?: continue
            if (entry.state != ProxyState.READY) continue
            val size = media.sizeOf(asset.uri)
            val sourceChanged = entry.sourceBytes > 0 && size > 0 && size != entry.sourceBytes
            if (sourceChanged || index.fileOf(entry) == null) {
                index.put(entry.copy(state = ProxyState.STALE, error = "The source file changed"))
                changed = true
            }
        }
        if (changed) invalidate()
    }

    /** Probes the videos among [assets] once each (size, bitrate) for the suggestion; off the main thread. */
    suspend fun probe(assets: List<MediaAssetDto>) = withContext(ioDispatcher) {
        for (asset in assets.filter { it.canHaveProxy() && !infos.containsKey(it.uri) }) {
            try {
                infos[asset.uri] = media.probe(asset.uri)
            } catch (e: ProxyException) {
                // An unreadable file is already reported as missing media; it just gets no suggestion.
            }
        }
    }

    fun infoOf(asset: MediaAssetDto): SourceInfo? = infos[asset.uri]

    fun usage(): ProxyUsage = ProxyUsage(index.totalBytes(), prefs.budgetBytes)

    fun setBudget(bytes: Long) {
        prefs.budgetBytes = bytes
        index.evictToBudget(bytes, protectedKeys)
        invalidate()
    }

    fun setTargetShortSide(shortSide: Int) {
        prefs.targetShortSide = shortSide
        invalidate()
    }

    /** Deletes every proxy not being made. Returns the bytes freed. */
    fun clearCache(): Long {
        val freed = index.clear()
        invalidate()
        return freed
    }

    fun flush() = index.flush()

    private fun invalidate() {
        resolved.clear()
        worker.bump()
    }

    companion object {
        @Volatile private var instance: ProxyManager? = null

        /** The app's proxy manager, created on first use (and its queue resumed). */
        fun of(context: Context): ProxyManager = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also {
                instance = it
                it.start()
            }
        }

        private fun create(context: Context): ProxyManager {
            val media = AndroidProxyMediaAccess(context)
            val index = ProxyIndex(File(context.cacheDir, "proxies"))
            val dispatcher = Executors.newSingleThreadExecutor { task ->
                Thread({
                    // The proxy thread yields to everything the user is doing.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                }, "proxy-worker")
            }.asCoroutineDispatcher()
            return ProxyManager(
                index = index,
                prefs = SharedPreferencesProxyPrefs(context),
                media = media,
                transcoder = ProxyGenerator(NativeExportRunner(), media, index),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                workerDispatcher = dispatcher,
            )
        }
    }
}
