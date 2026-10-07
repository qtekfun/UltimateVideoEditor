package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.interchange.AutoRelink
import com.ultimatevideo.uveditor.data.interchange.BundleError
import com.ultimatevideo.uveditor.data.interchange.BundleChoice
import com.ultimatevideo.uveditor.data.interchange.BundleMediaSource
import com.ultimatevideo.uveditor.data.interchange.BundlePreview
import com.ultimatevideo.uveditor.data.interchange.BundleResourceInstaller
import com.ultimatevideo.uveditor.data.interchange.BundleResources
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import com.ultimatevideo.uveditor.data.interchange.ImportProgress
import com.ultimatevideo.uveditor.data.interchange.LumaFusionError
import com.ultimatevideo.uveditor.data.interchange.LumaFusionImport
import com.ultimatevideo.uveditor.data.interchange.LumaFusionPackage
import com.ultimatevideo.uveditor.data.interchange.LumaFusionReport
import com.ultimatevideo.uveditor.data.interchange.MediaFileNames
import com.ultimatevideo.uveditor.data.interchange.MediaFolder
import com.ultimatevideo.uveditor.data.interchange.MediaLayout
import com.ultimatevideo.uveditor.data.interchange.MediaTarget
import com.ultimatevideo.uveditor.data.interchange.ProjectBundle
import com.ultimatevideo.uveditor.data.interchange.ZipEntryInfo
import com.ultimatevideo.uveditor.data.interchange.ZipReader
import com.ultimatevideo.uveditor.data.interchange.RelinkCandidate
import com.ultimatevideo.uveditor.data.interchange.ResourceImportReport
import com.ultimatevideo.uveditor.data.interchange.ResourceKind
import com.ultimatevideo.uveditor.data.interchange.ResourceLibrary
import com.ultimatevideo.uveditor.data.interchange.ResourceRefs
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ProjectSummary(
    val id: String,
    val name: String,
    val settings: ProjectSettingsDto,
    val lastModifiedMillis: Long,
    /** End of the last clip in project frames; 0 for an empty project. */
    val durationFrames: Long = 0L,
    /** Where the card picture comes from; null for a project with no video or photo yet. */
    val thumbnail: ThumbnailSource? = null,
)

/**
 * What an import did with the media of a bundle: copied out of it, relinked to files already on this device, or
 * still missing (by name); and with its LUTs and fonts ([resources]).
 */
data class BundleImportSummary(
    val mediaCopied: Int,
    val relinked: Int,
    val missing: List<String>,
    val resources: ResourceImportReport = ResourceImportReport.EMPTY,
)

/** What a LumaFusion import did: the [report] lines, the media that came inside the package and the media still to link by name. */
data class LumaFusionImportSummary(val report: LumaFusionReport, val mediaCopied: Int, val missing: List<String>)

/** The imported project and, when it came from a bundle, [bundle]; from LumaFusion, [lumaFusion]. */
data class ImportReport(val project: ProjectDto, val bundle: BundleImportSummary? = null, val lumaFusion: LumaFusionImportSummary? = null)

/** A project folder whose file cannot be read. [recoverable] means a leftover temp or backup file holds a usable copy. */
data class UnreadableProject(val id: String, val error: ProjectError, val recoverable: Boolean = false)

data class ProjectListing(
    val projects: List<ProjectSummary>,
    val unreadable: List<UnreadableProject>,
)

/**
 * Stores each project as `<rootDir>/<id>/project.json`. Writes are atomic (temp file + rename).
 * All operations are safe to call from any coroutine; mutations are serialised.
 */
class ProjectRepository(
    private val rootDir: File,
    private val transferIO: ProjectTransferIO,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /** Reads media files for bundles and finds files this device already has; null disables both. */
    private val mediaAccess: BundleMediaSource? = null,
    /** The picture of a project's card as JPEG bytes, put in the bundles it exports; null leaves bundles without one. */
    private val cardThumbnail: (suspend (ProjectDto) -> ByteArray?)? = null,
    /** The app-wide LUT and font libraries: bundles carry the ones a project refers to, and installs them; null disables both. */
    private val resourceLibrary: ResourceLibrary? = null,
    /** The folder the user chose for footage unpacked from LumaFusion packages (read when an import needs it); null when none is set. */
    private val mediaFolder: (() -> MediaFolder?)? = null,
    /** Reads a media file's real duration, rate and colour space (footage extracted from a LumaFusion package); null keeps the archive's estimate. */
    private val probeMedia: ((String) -> ProbedMedia?)? = null,
    /** Where unexpected failures are logged (the app passes `Log.w`), so a message shown to the user always has a trace. */
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) : ProjectStore {
    private val mutex = Mutex()

    suspend fun list(): ProjectListing = withContext(ioDispatcher) {
        val dirs = rootDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty()
        val projects = mutableListOf<ProjectSummary>()
        val unreadable = mutableListOf<UnreadableProject>()
        for (dir in dirs) {
            val file = File(dir, PROJECT_FILE)
            if (!file.isFile) {
                // A folder with only a temp or backup file is a project whose main file was lost mid-write.
                if (File(dir, TEMP_FILE).isFile || File(dir, BACKUP_FILE).isFile) {
                    unreadable += UnreadableProject(dir.name, ProjectError.Corrupt("the project file is missing"), bestBackup(dir) != null)
                }
                continue
            }
            try {
                projects += summaryOf(ProjectJson.decode(readText(file)), file)
            } catch (e: ProjectError) {
                unreadable += UnreadableProject(dir.name, e, e is ProjectError.Corrupt && bestBackup(dir) != null)
            }
        }
        ProjectListing(projects.sortedByDescending { it.lastModifiedMillis }, unreadable)
    }

    suspend fun create(name: String, settings: ProjectSettingsDto): ProjectDto = mutate {
        val project = ProjectDto(id = idGenerator(), name = freeName(name, excludingId = null), settings = settings)
        writeProject(project)
        project
    }

    override suspend fun load(id: String): ProjectDto = withContext(ioDispatcher) {
        ProjectJson.decode(readText(projectFile(id)))
    }

    override suspend fun save(project: ProjectDto) = mutate { writeProject(project) }

    suspend fun rename(id: String, name: String): ProjectDto = mutate {
        val renamed = ProjectJson.decode(readText(projectFile(id))).copy(name = freeName(name, excludingId = id))
        writeProject(renamed)
        renamed
    }

    /** Copies a project under a new id, keeping unknown fields; derived caches are not copied. */
    suspend fun clone(id: String, newName: String? = null): ProjectDto = mutate {
        val raw = ProjectJson.parseObject(readText(projectFile(id)))
        val source = ProjectJson.decode(readText(projectFile(id)))
        val copyName = if (newName != null) {
            freeName(newName, excludingId = null)
        } else {
            ProjectNames.unique(validName("${source.name} copy".take(MAX_NAME_LENGTH)), namesInUse(null), MAX_NAME_LENGTH) { b, n -> "$b $n" }
        }
        storeRaw(raw, idGenerator(), copyName)
    }

    /**
     * Restores a project whose main file is corrupt or missing from the newest usable leftover: the
     * temp file of an interrupted write, else the backup of the last good save. The damaged file is kept
     * as `project.json.corrupt`. Returns the project as it was restored.
     * @throws ProjectError.Corrupt if nothing usable is left.
     */
    suspend fun recover(id: String): ProjectDto = mutate {
        val dir = projectDir(id)
        val main = File(dir, PROJECT_FILE)
        decodeOrNull(main)?.let { return@mutate it }
        val (source, project) = backupCandidate(dir)
            ?: throw ProjectError.Corrupt("no readable backup of the project was found")
        val bytes = readBytes(source)
        if (main.isFile) {
            io("keep the damaged file") {
                Files.move(main.toPath(), File(dir, CORRUPT_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        atomicWrite(main, bytes, backupExisting = false)
        project
    }

    /** Every media URI that a readable project refers to; used to release permissions nothing needs. */
    suspend fun referencedMediaUris(): Set<String> = withContext(ioDispatcher) {
        val uris = HashSet<String>()
        for (dir in rootDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty()) {
            val project = decodeOrNull(File(dir, PROJECT_FILE)) ?: continue
            project.mediaLibrary.mapTo(uris) { it.uri }
        }
        uris
    }

    suspend fun delete(id: String) = mutate {
        val dir = projectDir(id)
        if (!dir.isDirectory) throw ProjectError.NotFound(id)
        if (!dir.deleteRecursively()) {
            throw ProjectError.Io("could not delete ${dir.name}", IOException("deleteRecursively failed"))
        }
    }

    suspend fun exportTo(id: String, uri: String) = withContext(ioDispatcher) {
        val bytes = readBytes(projectFile(id))
        io("export to $uri") { transferIO.write(uri, bytes) }
    }

    /** Imports a project document or a bundle; it gets a fresh id when its own id is invalid or already used. */
    suspend fun importFrom(uri: String): ProjectDto = importWithReport(uri).project

    /**
     * Like [importFrom], and for a `.uvbundle` also says what became of its media. A bundle is unpacked in a
     * scratch folder and moved into place in one step, so an import that fails halfway leaves nothing behind.
     */
    suspend fun importWithReport(uri: String, onProgress: ((ImportProgress) -> Unit)? = null): ImportReport {
        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isActive == false }
        return mutate {
            try {
                transferIO.openInput(uri).use { source ->
                    val input = BufferedInputStream(source)
                    if (ProjectBundle.sniff(input)) {
                        lumaFusionPackage(uri, onProgress, cancelled) ?: importBundle(input)
                    } else {
                        val text = input.readBytes().toString(Charsets.UTF_8)
                        if (LumaFusionImport.looksLikeArchive(text)) importLumaFusion(text, null, null, onProgress, cancelled)
                        else ImportReport(importDocument(text))
                    }
                }
            } catch (e: IOException) {
                log("import from $uri failed", e)
                throw ProjectError.Io("import from $uri: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * The import of an `.lfpackage` at [uri], or null when the zip is not one or the document cannot be read at random
     * (then it is read as a stream and tried as a bundle).
     */
    private fun lumaFusionPackage(uri: String, onProgress: ((ImportProgress) -> Unit)?, cancelled: () -> Boolean): ImportReport? {
        val document = try {
            transferIO.openSeekable(uri)
        } catch (e: IOException) {
            log("no random access to $uri, reading it as a stream", e)
            null
        } ?: return null
        document.use {
            val zip = try {
                ZipReader(it.access)
            } catch (e: BundleError) {
                throw ProjectError.Bundle(e.message ?: "The file is not a readable zip", e)
            }
            val archive = LumaFusionPackage.archiveEntry(zip) ?: return null
            if (zip.find(ProjectBundle.MANIFEST) != null) return null
            val text = try {
                LumaFusionPackage.readArchive(zip, archive)
            } catch (e: BundleError) {
                throw ProjectError.Bundle(e.message ?: "The package could not be read", e)
            }
            return importLumaFusion(text, zip, archive, onProgress, cancelled)
        }
    }

    /**
     * Converts a LumaFusion archive. With a package ([zip]) the footage it holds is copied into the project's own
     * folder first (after checking the free space); without one every media file is left missing, so Relink offers it.
     */
    private fun importLumaFusion(
        text: String,
        zip: ZipReader?,
        archive: ZipEntryInfo?,
        onProgress: ((ImportProgress) -> Unit)?,
        cancelled: () -> Boolean,
    ): ImportReport {
        val parsed = try {
            LumaFusionImport.parse(text)
        } catch (e: LumaFusionError) {
            throw ProjectError.Bundle(e.message ?: "The LumaFusion project could not be read", e)
        }
        val entries = if (zip != null && archive != null) LumaFusionPackage.mediaEntries(zip, archive) else emptyMap()
        val conversion = try {
            LumaFusionImport.convert(parsed, entries.keys)
        } catch (e: LumaFusionError) {
            throw ProjectError.Bundle(e.message ?: "The LumaFusion project could not be converted", e)
        }
        val scratch = File(rootDir, ".import-${idGenerator()}")
        val created = ArrayList<MediaTarget>()
        val createdFolders = ArrayList<MediaFolder>()
        var committed = false
        try {
            val id = idGenerator()
            val name = ProjectNames.unique(validName(conversion.project.name), namesInUse(null), MAX_NAME_LENGTH) { b, n -> "$b ($n)" }
            val finalDir = projectDir(id)
            // Footage to copy: the files the project uses that the package holds, each once.
            val wanted = conversion.assetNames.mapNotNull { (file, assetId) -> entries[file.lowercase()]?.let { Triple(assetId, file, it) } }
            val total = wanted.sumOf { it.third.size.coerceAtLeast(0) }
            val chosen = if (wanted.isEmpty()) null else (mediaFolder?.invoke() ?: throw ProjectError.MediaFolderRequired())
            // The footage goes into <chosen>/ultimateVE/Media/<project>/, created now (and only now) so nothing empty is left lying around.
            val folder = chosen?.let { c ->
                try {
                    val media = MediaLayout.path(c, MediaLayout.MEDIA).ensure(createdFolders)
                    media.createFolder(MediaLayout.projectFolderName(name, media.fileNames())).also { createdFolders += it }
                } catch (e: IOException) {
                    throw folderUnavailable(e)
                }
            }
            var done = 0L
            var reported = 0L
            val uris = HashMap<String, String>()
            val probed = HashMap<String, ProbedMedia>()
            if (folder != null) {
                val taken = HashSet<String>(try {
                    folder.fileNames()
                } catch (e: IOException) {
                    throw folderUnavailable(e)
                })
                var spaceChecked = false
                for ((assetId, file, entry) in wanted) {
                    // Never replace a file the user already has: a taken name gets " (2)" before its extension.
                    val fileName = MediaFileNames.unique(MediaFileNames.clean(file), taken)
                    taken += fileName
                    val target = try {
                        folder.create(fileName, MediaFileNames.mimeOf(fileName))
                    } catch (e: IOException) {
                        throw folderUnavailable(e)
                    }
                    created += target
                    if (!spaceChecked) {
                        spaceChecked = true
                        val free = target.freeBytes()
                        if (free != null && free < total + SPACE_MARGIN) {
                            throw ProjectError.Bundle("Not enough free space in the media folder: the footage of this package needs ${gb(total + SPACE_MARGIN)} and ${gb(free)} are free. Free some space or choose another folder in Settings.")
                        }
                    }
                    try {
                        target.openOutput().use { out ->
                            LumaFusionPackage.copyEntry(
                                checkNotNull(zip), entry, out,
                                onBytes = { n ->
                                    done += n
                                    if (done - reported >= PROGRESS_STEP || done == total) {
                                        reported = done
                                        onProgress?.invoke(ImportProgress(file, done, total))
                                    }
                                },
                                cancelled = cancelled,
                            )
                        }
                    } catch (e: BundleError) {
                        throw ProjectError.Bundle(e.message ?: "The footage could not be copied", e)
                    } catch (e: java.util.zip.ZipException) {
                        throw ProjectError.Bundle("The package is damaged near $file (${e.message}). Nothing was kept.", e)
                    } catch (e: IOException) {
                        log("copy of $fileName failed", e)
                        throw ProjectError.Bundle("Could not write $fileName to the media folder (${e.message}). The folder may be full or unplugged; nothing was kept.", e)
                    }
                    uris[assetId] = target.uri
                    probeMedia?.invoke(target.uri)?.let { probed[assetId] = it }
                }
            }
            // Footage that is not here is "missing": its address points at where it would live, so Relink can replace it.
            val library = conversion.project.mediaLibrary.map { asset ->
                val uri = uris[asset.id] ?: fileUri(File(finalDir, "media/${ProjectBundle.safeLeaf("${asset.id}-${asset.displayName}")}"))
                val real = probed[asset.id]
                if (real == null || real.isImage) {
                    asset.copy(uri = uri)
                } else {
                    val settings = conversion.project.settings
                    val num = if (real.hasVideo) real.fpsNum else settings.fpsNum
                    val den = if (real.hasVideo) real.fpsDen else settings.fpsDen
                    asset.copy(
                        uri = uri,
                        durationFrames = com.ultimatevideo.uveditor.domain.FrameRate(num, den).microsToFrames(real.durationMicros).coerceAtLeast(1),
                        nativeFpsNum = num,
                        nativeFpsDen = den,
                        colorSpace = real.colorSpace,
                        hasVideo = real.hasVideo,
                        hasAudio = real.hasAudio,
                    )
                }
            }
            val finalProject = conversion.project.copy(id = id, name = name, mediaLibrary = library)
            atomicWrite(File(scratch, PROJECT_FILE), ProjectJson.encode(finalProject).toByteArray(Charsets.UTF_8), backupExisting = false)
            io("move the imported project into place") {
                Files.move(scratch.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            committed = true
            val missing = library.filter { it.id !in uris }.map { MissingMedia.nameOf(it) }
            return ImportReport(finalProject, lumaFusion = LumaFusionImportSummary(conversion.report, uris.size, missing))
        } finally {
            if (scratch.exists()) scratch.deleteRecursively()
            // The files this import created in the user's folder are removed when it did not finish (cancel, error, full disk).
            if (!committed) {
                created.forEach { it.delete() }
                MediaLayout.discardEmpty(createdFolders)
            }
        }
    }

    private fun folderUnavailable(e: IOException) = ProjectError.Bundle(
        "The media folder is not available (${e.message}). Choose it again in Settings, under About, and import again. Nothing was added to the project list.",
        e,
    )

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))

    private fun importDocument(text: String): ProjectDto {
        val raw = ProjectJson.parseObject(text)
        val imported = ProjectJson.decode(text)
        val keepId = isValidId(imported.id) && !projectDir(imported.id).exists()
        // An imported file must not be refused over a clash, so it is renamed to "<name> (2)" etc.
        val name = ProjectNames.unique(validName(imported.name), namesInUse(null), MAX_NAME_LENGTH) { b, n -> "$b ($n)" }
        return storeRaw(raw, if (keepId) imported.id else idGenerator(), name)
    }

    private fun importBundle(input: java.io.InputStream): ImportReport {
        val scratch = File(rootDir, ".import-${idGenerator()}")
        try {
            val extracted = try {
                ProjectBundle.extract(input, scratch)
            } catch (e: BundleError) {
                throw ProjectError.Bundle(e.message ?: "The bundle could not be read", e)
            }
            val text = extracted.projectJson
            val raw = ProjectJson.parseObject(text)
            val imported = ProjectJson.decode(text)
            val id = if (isValidId(imported.id) && !projectDir(imported.id).exists()) imported.id else idGenerator()
            val name = ProjectNames.unique(validName(imported.name), namesInUse(null), MAX_NAME_LENGTH) { b, n -> "$b ($n)" }
            val finalDir = projectDir(id)
            val uris = HashMap<String, String>()
            for ((assetId, file) in extracted.mediaFiles) uris[assetId] = fileUri(File(finalDir, "media/${file.name}"))
            val missing = imported.mediaLibrary.filter { it.id !in uris }
            val missingIds = missing.mapTo(HashSet()) { it.id }
            val wanted = extracted.manifest.media.filter { it.assetId in missingIds }
            val relinked = AutoRelink.match(wanted, relinkCandidates())
            uris.putAll(relinked)
            // The LUTs and fonts that came inside go into the app-wide libraries; a LUT that had to take another key
            // (a hash clash with a different LUT already here) is rewritten in the project before it is stored.
            val resources = BundleResourceInstaller.install(extracted.manifest, extracted.resourceFiles, ResourceRefs.collect(raw), resourceLibrary)
            val remapped = ResourceRefs.withLutKeys(raw, resources.lutRemap)
            val withUris = ProjectJson.parseObject(ProjectJson.withMediaUris(remapped, uris))
            val final = ProjectJson.withIdentity(withUris, id, name)
            val project = ProjectJson.decode(final)
            atomicWrite(File(scratch, PROJECT_FILE), final.toByteArray(Charsets.UTF_8), backupExisting = false)
            io("move the imported project into place") {
                Files.move(scratch.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            val stillMissing = missing.filter { it.id !in relinked }.map { MissingMedia.nameOf(it) }
            return ImportReport(project, BundleImportSummary(extracted.mediaFiles.size, relinked.size, stillMissing, resources.report))
        } finally {
            if (scratch.exists()) scratch.deleteRecursively()
        }
    }

    /** Files that other local projects already point at, with their sizes, for matching a bundle's media. */
    private fun relinkCandidates(): List<RelinkCandidate> {
        val access = mediaAccess ?: return emptyList()
        val out = ArrayList<RelinkCandidate>()
        for (dir in rootDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty()) {
            val project = decodeOrNull(File(dir, PROJECT_FILE)) ?: continue
            for (asset in project.mediaLibrary) {
                out += RelinkCandidate(asset.uri, MissingMedia.nameOf(asset), access.sizeOf(asset.uri))
            }
        }
        return out
    }

    private fun fileUri(file: File) = "file://" + file.absolutePath

    /**
     * Writes project [id] as a `.uvbundle` to [uri]. With [includeMedia] the media files that can be read are
     * copied in (the rest are named in the result); without it the bundle carries only names and sizes, so
     * the importer can relink by them. Reading the project does not block other edits.
     */
    suspend fun exportBundle(
        id: String,
        uri: String,
        includeMedia: Boolean,
        thumbnails: Map<String, ByteArray> = emptyMap(),
    ): BundleWriteResult = exportBundle(id, uri, BundleChoice(includeMedia = includeMedia), thumbnails)

    /**
     * Like the overload above, and also puts the LUTs and fonts the project refers to into the bundle as [choice]
     * says. A resource this device does not hold cannot be included and is named in the result.
     */
    suspend fun exportBundle(
        id: String,
        uri: String,
        choice: BundleChoice,
        thumbnails: Map<String, ByteArray> = emptyMap(),
    ): BundleWriteResult = withContext(ioDispatcher) {
        val file = projectFile(id)
        val text = readText(file)
        val project = ProjectJson.decode(text)
        val resources = BundleResources.plan(ResourceRefs.collect(ProjectJson.parseObject(text)), resourceLibrary, choice)
        val pictures = thumbnails.ifEmpty { cardThumbnail?.invoke(project)?.let { mapOf("project.jpg" to it) } ?: emptyMap() }
        try {
            transferIO.openOutput(uri).use { out ->
                ProjectBundle.write(text, project, mediaAccess, choice.includeMedia, pictures, out, resources)
            }
        } catch (e: IOException) {
            throw ProjectError.Io("export bundle to $uri", e)
        }
    }

    /** What a bundle of project [id] could contain, and how big: the media, the LUTs and the fonts it refers to. */
    suspend fun bundlePreview(id: String): BundlePreview = withContext(ioDispatcher) {
        val text = readText(projectFile(id))
        val project = ProjectJson.decode(text)
        var bytes = 0L
        var readable = 0
        val unreadable = ArrayList<String>()
        for (asset in project.mediaLibrary) {
            val size = mediaAccess?.sizeOf(asset.uri)
            if (size == null) {
                unreadable += MissingMedia.nameOf(asset)
            } else {
                readable++
                bytes += size
            }
        }
        val infos = BundleResources.infos(ResourceRefs.collect(ProjectJson.parseObject(text)), resourceLibrary)
        BundlePreview(readable, bytes, unreadable, infos.filter { it.kind == ResourceKind.LUT }, infos.filter { it.kind == ResourceKind.FONT })
    }

    private fun storeRaw(raw: kotlinx.serialization.json.JsonObject, id: String, name: String): ProjectDto {
        val text = ProjectJson.withIdentity(raw, id, name)
        val project = ProjectJson.decode(text)
        atomicWrite(projectFile(id), text.toByteArray(Charsets.UTF_8), backupExisting = false)
        return project
    }

    private fun writeProject(project: ProjectDto) {
        val file = projectFile(project.id)
        val base = if (file.isFile) existingRawOrNull(file) else null
        // The file on disk is only worth keeping as a backup while it still parses: it is the last good save.
        atomicWrite(file, ProjectJson.encode(project, base).toByteArray(Charsets.UTF_8), backupExisting = base != null)
    }

    // A file we cannot parse has nothing worth preserving; the new content replaces it.
    private fun existingRawOrNull(file: File) = try {
        ProjectJson.parseObject(readText(file))
    } catch (e: ProjectError.Corrupt) {
        null
    }

    private fun atomicWrite(target: File, bytes: ByteArray, backupExisting: Boolean) = io("write ${target.name}") {
        val dir = target.parentFile ?: throw IOException("no parent for $target")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val temp = File(dir, TEMP_FILE)
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (backupExisting && target.isFile) {
            Files.copy(target.toPath(), File(dir, BACKUP_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun decodeOrNull(file: File): ProjectDto? {
        if (!file.isFile) return null
        return try {
            ProjectJson.decode(readText(file))
        } catch (e: ProjectError.Corrupt) {
            // A file that does not parse is exactly what the caller is looking for a replacement for.
            null
        }
    }

    /** The newest leftover that parses (the temp file of an interrupted write, then the backup), with its project. */
    private fun backupCandidate(dir: File): Pair<File, ProjectDto>? {
        for (name in listOf(TEMP_FILE, BACKUP_FILE)) {
            val file = File(dir, name)
            decodeOrNull(file)?.let { return file to it }
        }
        return null
    }

    private fun bestBackup(dir: File): ProjectDto? = backupCandidate(dir)?.second

    private fun readBytes(file: File): ByteArray {
        if (!file.isFile) throw ProjectError.NotFound(file.parentFile?.name.orEmpty())
        return io("read ${file.name}") { file.readBytes() }
    }

    private fun readText(file: File): String = readBytes(file).toString(Charsets.UTF_8)

    private fun summaryOf(project: ProjectDto, file: File) =
        ProjectSummary(
            id = project.id,
            name = project.name,
            settings = project.settings,
            lastModifiedMillis = file.lastModified(),
            durationFrames = ProjectOverview.durationFrames(project),
            thumbnail = ProjectOverview.thumbnailSource(project),
        )

    private fun projectDir(id: String): File {
        if (!isValidId(id)) throw ProjectError.InvalidId(id)
        return File(rootDir, id)
    }

    private fun projectFile(id: String) = File(projectDir(id), PROJECT_FILE)

    private fun isValidId(id: String) = ID_PATTERN.matches(id)

    /** Validates [name] and rejects it if another project (not [excludingId]) already uses it. */
    private fun freeName(name: String, excludingId: String?): String {
        val valid = validName(name)
        if (ProjectNames.isTaken(valid, namesInUse(excludingId))) throw ProjectError.DuplicateName(valid)
        return valid
    }

    /** Names of every readable project. Unreadable files cannot clash and are skipped. */
    private fun namesInUse(excludingId: String?): List<String> {
        val dirs = rootDir.listFiles { file -> file.isDirectory && file.name != excludingId && !file.name.startsWith(".") }.orEmpty()
        return dirs.mapNotNull { dir ->
            val file = File(dir, PROJECT_FILE)
            if (!file.isFile) return@mapNotNull null
            try {
                ProjectJson.decode(readText(file)).name
            } catch (e: ProjectError) {
                null
            }
        }
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ProjectError.InvalidName("Project name cannot be empty")
        if (trimmed.length > MAX_NAME_LENGTH) {
            throw ProjectError.InvalidName("Project name is longer than $MAX_NAME_LENGTH characters")
        }
        return trimmed
    }

    private suspend fun <T> mutate(block: () -> T): T =
        withContext(ioDispatcher) { mutex.withLock { block() } }

    private inline fun <T> io(what: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw ProjectError.Io(what, e)
    }

    companion object {
        const val PROJECT_FILE = "project.json"
        const val TEMP_FILE = "project.json.tmp"
        const val BACKUP_FILE = "project.json.bak"
        const val CORRUPT_FILE = "project.json.corrupt"
        const val MAX_NAME_LENGTH = 80
        private const val SPACE_MARGIN = 64L * 1024 * 1024
        private const val PROGRESS_STEP = 4L * 1024 * 1024
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}
