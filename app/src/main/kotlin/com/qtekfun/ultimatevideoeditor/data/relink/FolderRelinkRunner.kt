package com.qtekfun.ultimatevideoeditor.data.relink

import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.RelinkApply
import com.qtekfun.ultimatevideoeditor.data.RelinkCheck
import com.qtekfun.ultimatevideoeditor.data.RelinkVerdict
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.relink.AmbiguousMatch
import com.qtekfun.ultimatevideoeditor.domain.relink.FolderFile
import com.qtekfun.ultimatevideoeditor.domain.relink.FolderMatch
import com.qtekfun.ultimatevideoeditor.domain.relink.FolderRelinkMatcher
import com.qtekfun.ultimatevideoeditor.domain.relink.MatchBasis
import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind
import com.qtekfun.ultimatevideoeditor.domain.relink.WantedMedia
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A missing item that was found and checked; [asset] is the library entry to store in place of [old]. */
data class RelinkedAsset(
    val old: MediaAssetDto,
    val asset: MediaAssetDto,
    val file: FolderFile,
    val basis: MatchBasis,
    val warnings: List<String>,
)

/** A missing item with nothing usable in the folder; [reason] says why in words for the user. */
data class UnresolvedAsset(val assetId: String, val name: String, val reason: String)

/** A missing item that several files of the folder could be. */
data class AmbiguousAsset(val assetId: String, val name: String, val candidates: List<FolderFile>)

data class FolderRelinkOutcome(
    /** How many missing items were looked for. */
    val total: Int,
    val relinked: List<RelinkedAsset>,
    val notFound: List<UnresolvedAsset>,
    val ambiguous: List<AmbiguousAsset>,
    val filesSeen: Int,
    /** A scan limit stopped the walk before the whole folder was read. */
    val truncated: Boolean,
    /** Read access to the folder will survive a restart (relinked files stay readable). */
    val accessKept: Boolean,
)

/** What a run is doing, for the progress line. */
sealed interface FolderRelinkProgress {
    data class Scanning(val files: Int, val folders: Int) : FolderRelinkProgress
    data class Checking(val done: Int, val total: Int) : FolderRelinkProgress
}

/**
 * Finds the missing media of a project in a folder and checks every match before accepting it. It changes nothing: the
 * result lists the new library entries and the caller stores them in one step. Suspending and cancellable throughout, and
 * every file is opened off the caller's thread by the scanner and importer.
 */
class FolderRelinkRunner(
    private val scanner: FolderScanner,
    private val importer: MediaImporter,
    private val limits: ScanLimits = ScanLimits(),
) {

    /**
     * @param missing the library entries that cannot be read now.
     * @param library the whole library, to refuse a file that is already another item.
     * @param neededMicros how much of an item's source the timeline uses (a shorter replacement earns a warning).
     * @throws FolderScanException when the folder cannot be read.
     */
    suspend fun run(
        treeUri: String,
        missing: List<MediaAssetDto>,
        library: List<MediaAssetDto>,
        fps: FrameRate,
        neededMicros: (String) -> Long,
        onProgress: (FolderRelinkProgress) -> Unit,
    ): FolderRelinkOutcome {
        val accessKept = scanner.retainAccess(treeUri)
        val listing = scanner.scan(treeUri, limits) { onProgress(FolderRelinkProgress.Scanning(it.files, it.folders)) }
        val names = missing.associate { it.id to MissingMedia.nameOf(it) }
        val byId = missing.associateBy { it.id }
        val wanted = missing.map { asset ->
            val name = names.getValue(asset.id)
            WantedMedia(asset.id, name, kindOf(asset), oldPathOf(asset, name))
        }
        val result = FolderRelinkMatcher.match(wanted, listing.files)

        val matches = ArrayList<FolderMatch>(result.matched)
        val ambiguous = ArrayList<AmbiguousMatch>()
        val probes = HashMap<String, ProbedMedia>()
        for (open in result.ambiguous) {
            currentCoroutineContext().ensureActive()
            val asset = byId.getValue(open.assetId)
            val facts = open.candidates.takeIf { it.size <= MAX_PROBED_CANDIDATES }.orEmpty().mapNotNull { file ->
                val probed = probe(file.uri) ?: return@mapNotNull null
                probes[file.uri] = probed
                file to FolderRelinkMatcher.Facts(probed.durationMicros.takeIf { !probed.isImage }, probed.videoWidth, probed.videoHeight)
            }
            val chosen = FolderRelinkMatcher.narrowByFacts(expectedFacts(asset), facts)
            if (chosen != null) matches += FolderMatch(asset.id, chosen, MatchBasis.FACTS) else ambiguous += open
        }

        val relinked = ArrayList<RelinkedAsset>()
        val notFound = ArrayList<UnresolvedAsset>()
        result.notFound.forEach { id -> notFound += UnresolvedAsset(id, names.getValue(id), "No file with this name in the folder") }
        val taken = HashSet<String>()
        var done = 0
        for (match in matches) {
            currentCoroutineContext().ensureActive()
            onProgress(FolderRelinkProgress.Checking(done++, matches.size))
            val old = byId.getValue(match.assetId)
            val name = names.getValue(old.id)
            val probed = probes[match.file.uri] ?: probe(match.file.uri)
            if (probed == null) {
                notFound += UnresolvedAsset(old.id, name, "Found ${match.file.path.joinToString("/")} but it cannot be read")
                continue
            }
            val others = library.filter { it.id != old.id }.map { it.uri } + taken
            val verdict = RelinkCheck.evaluate(old, probed, match.file.uri, others, neededMicros(old.id))
            val warnings = when (verdict) {
                is RelinkVerdict.Rejected -> {
                    notFound += UnresolvedAsset(old.id, name, verdict.reason)
                    continue
                }
                is RelinkVerdict.Accepted -> verdict.warnings
            }
            val asset = RelinkApply.relinked(old, probed, match.file.uri, fps)
            if (asset == null) {
                notFound += UnresolvedAsset(old.id, name, "The file in the folder is too short to use")
                continue
            }
            taken += match.file.uri
            relinked += RelinkedAsset(old, asset, match.file, match.basis, warnings)
        }
        val order = missing.withIndex().associate { it.value.id to it.index }
        return FolderRelinkOutcome(
            total = missing.size,
            relinked = relinked.sortedBy { order.getValue(it.old.id) },
            notFound = notFound.sortedBy { order.getValue(it.assetId) },
            ambiguous = ambiguous.map { AmbiguousAsset(it.assetId, names.getValue(it.assetId), it.candidates) }.sortedBy { order.getValue(it.assetId) },
            filesSeen = listing.files.size,
            truncated = listing.truncated,
            accessKept = accessKept,
        )
    }

    /** Opens a candidate without taking a per-file permission (the folder's covers it); null when it cannot be read. */
    private suspend fun probe(uri: String): ProbedMedia? = try {
        importer.verify(uri)
    } catch (e: MediaImportException) {
        null
    }

    private fun kindOf(asset: MediaAssetDto): RelinkKind = when {
        asset.isImage -> RelinkKind.IMAGE
        asset.hasVideo -> RelinkKind.VIDEO
        else -> RelinkKind.AUDIO
    }

    /** The old address's folder trail, only when its last piece really is the file's name (opaque ids say nothing). */
    private fun oldPathOf(asset: MediaAssetDto, name: String): List<String> {
        val path = MissingMedia.pathOfUri(asset.uri)
        return if (path.isNotEmpty() && path.last().equals(name, ignoreCase = true)) path else listOf(name)
    }

    private fun expectedFacts(asset: MediaAssetDto): FolderRelinkMatcher.Facts {
        val micros = if (asset.isImage || asset.nativeFpsNum <= 0 || asset.nativeFpsDen <= 0) null
        else FrameRate(asset.nativeFpsNum, asset.nativeFpsDen).framesToMicros(asset.durationFrames)
        return FolderRelinkMatcher.Facts(micros, asset.videoWidth, asset.videoHeight)
    }

    private companion object {
        /** Opening a file costs I/O; a name shared by more files than this is left to the user. */
        const val MAX_PROBED_CANDIDATES = 8
    }
}
