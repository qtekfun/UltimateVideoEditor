package com.ultimatevideo.uveditor.ui.about

import com.ultimatevideo.uveditor.crash.CrashReportStore
import com.ultimatevideo.uveditor.ui.onboarding.OnboardingStore
import java.io.File

/** Name and code of the installed build, as the package manager reports them. */
data class AppVersion(val name: String, val code: Long) {
    val display: String get() = "$name ($code)"

    companion object {
        fun of(context: android.content.Context): AppVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return AppVersion(info.versionName ?: "unknown", info.longVersionCode)
        }
    }
}

/** Bytes used on the device by what the app keeps. */
data class StorageUsage(val projectsBytes: Long, val cacheBytes: Long, val proxyBytes: Long)

/**
 * Measures and clears what the app can rebuild. Projects themselves are never touched here, and neither are the
 * proxies (they have their own switch and clear action in the proxy sheet, because a running job owns them).
 */
class StorageMeter(private val filesDir: File, private val cacheDir: File) {
    private val projects get() = File(filesDir, "projects")

    fun usage(): StorageUsage {
        val derived = projectCacheDirs().sumOf { it.sizeBytes() }
        val cacheRoot = cacheDir.listFiles().orEmpty().filter { it.name != PROXIES }.sumOf { it.sizeBytes() }
        val total = projects.sizeBytes()
        return StorageUsage(
            projectsBytes = (total - derived).coerceAtLeast(0),
            cacheBytes = derived + cacheRoot,
            proxyBytes = File(cacheDir, PROXIES).sizeBytes(),
        )
    }

    /** Deletes waveforms, thumbnails and analysis results of every project and the app's cache folder (not proxies). Returns the bytes freed. */
    fun clearCaches(): Long {
        val before = usage().cacheBytes
        projectCacheDirs().forEach { it.deleteRecursively() }
        cacheDir.listFiles().orEmpty().filter { it.name != PROXIES }.forEach { it.deleteRecursively() }
        return (before - usage().cacheBytes).coerceAtLeast(0)
    }

    private fun projectCacheDirs(): List<File> =
        projects.listFiles().orEmpty().filter { it.isDirectory }.flatMap { project ->
            DERIVED.map { File(project, it) }.filter { it.exists() }
        }

    private fun File.sizeBytes(): Long = if (!exists()) 0 else walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val PROXIES = "proxies"

        // Rebuilt on demand: waveform peaks, thumbnail tiles, stabilisation and tracking analysis.
        val DERIVED = listOf("waveforms", "thumbnails", "stab", "track")
    }
}

/** What the About screen shows. */
data class AboutSnapshot(
    val version: AppVersion,
    val repositoryUrl: String,
    val usage: StorageUsage,
    val crashReport: String?,
)

/** Plain logic behind the About screen, so it can be tested without a device. */
class AboutController(
    private val version: AppVersion,
    private val crashStore: CrashReportStore,
    private val storage: StorageMeter,
    private val onboarding: OnboardingStore,
) {
    fun snapshot(): AboutSnapshot = AboutSnapshot(version, REPOSITORY_URL, storage.usage(), crashStore.read())

    fun deleteCrashReport() = crashStore.delete()

    fun clearCaches(): Long = storage.clearCaches()

    /** The three tips are shown again the next time the hub opens. */
    fun showTipsAgain() = onboarding.reset()

    companion object {
        const val REPOSITORY_URL = "https://github.com/qtekfun/UltimateVideoEditor"

        /** The user guide on GitHub Pages. Only ever opened in the browser by a tap (ACTION_VIEW); the app fetches nothing. */
        const val ONLINE_GUIDE_URL = "https://qtekfun.github.io/UltimateVideoEditor/"
        const val LICENCE_NAME = "GNU General Public License v3.0"

        fun formatBytes(bytes: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "%.1f KB".format(locale, bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(locale, bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(locale, bytes / (1024.0 * 1024 * 1024))
        }
    }
}
