package com.ultimatevideo.uveditor.proxy

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
private data class ProxyIndexFile(val version: Int = 1, val entries: List<ProxyEntry> = emptyList())

/**
 * The side index of proxy media: `index.json` plus the proxy files in one directory under app storage.
 * Writes are atomic (temp file then rename) and a corrupt index is treated as empty, never as a crash:
 * proxies are a cache and can always be made again. Thread-safe.
 */
class ProxyIndex(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = LinkedHashMap<String, ProxyEntry>()
    private var dirty = false
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        dir.mkdirs()
        load()
    }

    @Synchronized
    fun get(key: String): ProxyEntry? = entries[key]

    @Synchronized
    fun all(): List<ProxyEntry> = entries.values.toList()

    @Synchronized
    fun put(entry: ProxyEntry) {
        entries[entry.key] = entry
        save()
    }

    /** Bumps the last-use time of [key] (for LRU eviction). Persisted with the next change or [flush]. */
    @Synchronized
    fun touch(key: String) {
        val entry = entries[key] ?: return
        entries[key] = entry.copy(lastUsedMs = clock())
        dirty = true
    }

    @Synchronized
    fun flush() {
        if (dirty) save()
    }

    /** Forgets [key] and deletes its files. */
    @Synchronized
    fun remove(key: String) {
        val entry = entries.remove(key) ?: return
        entry.fileName?.let { File(dir, it).delete() }
        partFileFor(key).delete()
        save()
    }

    fun finalFileFor(key: String): File = File(dir, "$key.mp4")

    fun partFileFor(key: String): File = File(dir, "$key.part")

    /** The finished file of [entry], or null when it is not READY or the file is gone. */
    fun fileOf(entry: ProxyEntry): File? =
        entry.fileName?.let { File(dir, it) }?.takeIf { entry.state == ProxyState.READY && it.isFile }

    /** Bytes the proxies hold on disk: finished files plus the part being written. */
    @Synchronized
    fun totalBytes(): Long = entries.values.sumOf { entry ->
        when (entry.state) {
            ProxyState.READY, ProxyState.STALE -> entry.bytes
            ProxyState.RUNNING -> partFileFor(entry.key).length()
            else -> 0L
        }
    }

    /**
     * Deletes least recently used proxies until the finished ones fit in [budgetBytes]. Entries in
     * [protect] (those the open project uses) and anything queued or running stay. Returns what was removed.
     */
    @Synchronized
    fun evictToBudget(budgetBytes: Long, protect: Set<String> = emptySet()): List<ProxyEntry> {
        val removed = ArrayList<ProxyEntry>()
        while (finishedBytes() > budgetBytes) {
            val victim = entries.values
                .filter { it.bytes > 0 && (it.state == ProxyState.READY || it.state == ProxyState.STALE) && it.key !in protect }
                .minByOrNull { it.lastUsedMs }
                ?: break
            entries.remove(victim.key)
            victim.fileName?.let { File(dir, it).delete() }
            removed += victim
        }
        if (removed.isNotEmpty()) save()
        return removed
    }

    /** Deletes every proxy that is not being made right now, and any file the index does not know. Returns the bytes freed. */
    @Synchronized
    fun clear(): Long {
        var freed = 0L
        for (entry in entries.values.toList()) {
            if (entry.state == ProxyState.RUNNING) continue
            freed += entry.bytes
            entries.remove(entry.key)
            entry.fileName?.let { File(dir, it).delete() }
        }
        val known = entries.values.mapNotNull { it.fileName }.toSet() + INDEX_NAME + runningPartNames()
        dir.listFiles()?.filter { it.isFile && it.name !in known && it.name != "$INDEX_NAME.tmp" }?.forEach {
            freed += it.length()
            it.delete()
        }
        save()
        return freed
    }

    /**
     * Makes the index true after the process died: a job that was running goes back to the queue (its part
     * file is deleted, so it starts again from the beginning), a READY entry whose file is missing or the
     * wrong size is dropped, and files nothing refers to are removed. Returns the entries to run again.
     */
    @Synchronized
    fun recoverAfterKill(): List<ProxyEntry> {
        for (entry in entries.values.toList()) {
            when (entry.state) {
                ProxyState.RUNNING -> {
                    partFileFor(entry.key).delete()
                    entries[entry.key] = entry.copy(state = ProxyState.QUEUED, error = null)
                }
                ProxyState.READY -> {
                    val file = entry.fileName?.let { File(dir, it) }
                    if (file == null || !file.isFile || file.length() != entry.bytes) {
                        file?.delete()
                        entries.remove(entry.key)
                    }
                }
                else -> Unit
            }
        }
        val known = entries.values.mapNotNull { it.fileName }.toSet() + INDEX_NAME
        dir.listFiles()?.filter { it.isFile && it.name !in known && it.name != "$INDEX_NAME.tmp" }?.forEach { it.delete() }
        save()
        return entries.values.filter { it.state == ProxyState.QUEUED }
    }

    private fun finishedBytes(): Long =
        entries.values.filter { it.state == ProxyState.READY || it.state == ProxyState.STALE }.sumOf { it.bytes }

    private fun runningPartNames(): Set<String> =
        entries.values.filter { it.state == ProxyState.RUNNING }.map { "${it.key}.part" }.toSet()

    private fun load() {
        val file = File(dir, INDEX_NAME)
        if (!file.isFile) return
        val parsed = try {
            json.decodeFromString<ProxyIndexFile>(file.readText())
        } catch (e: IOException) {
            return
        } catch (e: SerializationException) {
            return // a damaged cache index: start empty; recoverAfterKill removes the files nothing refers to
        } catch (e: IllegalArgumentException) {
            return
        }
        for (entry in parsed.entries) entries[entry.key] = entry
    }

    private fun save() {
        val tmp = File(dir, "$INDEX_NAME.tmp")
        tmp.writeText(json.encodeToString(ProxyIndexFile.serializer(), ProxyIndexFile(entries = entries.values.toList())))
        if (!tmp.renameTo(File(dir, INDEX_NAME))) throw IOException("Cannot write the proxy index")
        dirty = false
    }

    private companion object {
        const val INDEX_NAME = "index.json"
    }
}
