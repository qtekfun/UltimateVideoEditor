package com.qtekfun.ultimatevideoeditor.data.relink

import com.qtekfun.ultimatevideoeditor.domain.relink.FolderFile

/** How far a scan may go; a folder tree beyond it is reported as cut short rather than read for ever. */
data class ScanLimits(
    val maxFiles: Int = 50_000,
    val maxFolders: Int = 5_000,
    val maxDepth: Int = 16,
)

/** What a running scan has seen so far. */
data class ScanProgress(val files: Int, val folders: Int)

/** The media files of a folder tree. [truncated] is set when a limit stopped the scan before it was done. */
data class FolderListing(val files: List<FolderFile>, val folders: Int, val truncated: Boolean)

/** The folder could not be read (access revoked, drive gone). The message is meant for the user. */
class FolderScanException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Lists the media files under a folder the user picked. An interface so the flow is tested without the Storage Access
 * Framework; [TreeFolderScanner] is the Android implementation.
 */
interface FolderScanner {
    /** Keeps read access to [treeUri] across restarts. Returns false when the platform refused (the scan still works this session). */
    fun retainAccess(treeUri: String): Boolean

    /**
     * Walks [treeUri] recursively within [limits], reporting through [onProgress]. Does its I/O off the caller's thread and
     * stops promptly when the calling coroutine is cancelled. @throws FolderScanException
     */
    suspend fun scan(treeUri: String, limits: ScanLimits, onProgress: (ScanProgress) -> Unit): FolderListing
}

/** Used where no folder access is wired (tests of other features): always reports that scanning is unavailable. */
object NoFolderScanner : FolderScanner {
    override fun retainAccess(treeUri: String): Boolean = false

    override suspend fun scan(treeUri: String, limits: ScanLimits, onProgress: (ScanProgress) -> Unit): FolderListing =
        throw FolderScanException("Scanning a folder is not available here")
}
