package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.ui.about.StorageMeter
import java.io.File

/** One entry of a directory as the storage scan sees it. */
data class DiskEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long)

/** Lists a directory; the scan only talks to this, so tests substitute a map and the app uses [FileDiskLister]. */
interface DiskLister {
    /** The entries of [path]; empty when it does not exist. */
    fun list(path: String): List<DiskEntry>
}

class FileDiskLister : DiskLister {
    override fun list(path: String): List<DiskEntry> =
        File(path).listFiles().orEmpty().map { DiskEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L) }
}

/** What the storage card shows. All sizes are what the app itself keeps; the footage the projects refer to is not counted. */
data class StorageSnapshot(
    val projectsBytes: Long,
    val cacheBytes: Long,
    val proxyBytes: Long,
    /** Free bytes where the app stores its data. */
    val freeBytes: Long,
    /** Bytes on disk of each project folder, by project id (the folder name); caches inside it are included. */
    val perProject: Map<String, Long>,
) {
    val usedBytes: Long get() = projectsBytes + cacheBytes + proxyBytes

    /** The meter's segments in order, each with its share of [usedBytes]; empty when nothing is used. */
    val segments: List<StorageSegment>
        get() {
            val total = usedBytes
            if (total <= 0L) return emptyList()
            return listOf(
                StorageSegment(StorageKind.PROJECTS, projectsBytes, projectsBytes.toFloat() / total),
                StorageSegment(StorageKind.CACHE, cacheBytes, cacheBytes.toFloat() / total),
                StorageSegment(StorageKind.PROXIES, proxyBytes, proxyBytes.toFloat() / total),
            )
        }
}

enum class StorageKind(val label: String) {
    PROJECTS("Projects"),
    CACHE("Cache"),
    PROXIES("Proxies"),
}

data class StorageSegment(val kind: StorageKind, val bytes: Long, val fraction: Float)

/**
 * Measures the app's own storage. Meant for a background thread: it walks the project folders. The split matches About:
 * the derived folders inside a project (waveforms, thumbnails, stabilisation, tracking) and the cache folder count as
 * cache, the proxy folder of the cache directory as proxies, everything else in the project folders as projects.
 */
class StorageScanner(
    private val lister: DiskLister,
    private val projectsDir: String,
    private val cacheDir: String,
    private val freeBytes: () -> Long,
) {
    fun scan(): StorageSnapshot {
        var projects = 0L
        var cache = 0L
        val perProject = HashMap<String, Long>()
        for (dir in lister.list(projectsDir).filter { it.isDirectory }) {
            var total = 0L
            for (entry in lister.list("$projectsDir/${dir.name}")) {
                val bytes = if (entry.isDirectory) treeBytes("$projectsDir/${dir.name}/${entry.name}") else entry.sizeBytes
                total += bytes
                if (entry.isDirectory && entry.name in StorageMeter.DERIVED) cache += bytes else projects += bytes
            }
            perProject[dir.name] = total
        }
        var proxies = 0L
        for (entry in lister.list(cacheDir)) {
            val bytes = if (entry.isDirectory) treeBytes("$cacheDir/${entry.name}") else entry.sizeBytes
            if (entry.name == StorageMeter.PROXIES) proxies += bytes else cache += bytes
        }
        return StorageSnapshot(projects, cache, proxies, freeBytes(), perProject)
    }

    private fun treeBytes(path: String): Long {
        var sum = 0L
        for (entry in lister.list(path)) sum += if (entry.isDirectory) treeBytes("$path/${entry.name}") else entry.sizeBytes
        return sum
    }
}
