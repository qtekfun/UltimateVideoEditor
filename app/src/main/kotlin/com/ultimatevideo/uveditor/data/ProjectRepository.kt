package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

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
) : ProjectStore {
    private val mutex = Mutex()

    suspend fun list(): ProjectListing = withContext(ioDispatcher) {
        val dirs = rootDir.listFiles { file -> file.isDirectory }.orEmpty()
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
        for (dir in rootDir.listFiles { file -> file.isDirectory }.orEmpty()) {
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

    /** Imports a project document; it gets a fresh id when its own id is invalid or already used. */
    suspend fun importFrom(uri: String): ProjectDto = mutate {
        val text = io("import from $uri") { transferIO.read(uri).toString(Charsets.UTF_8) }
        val raw = ProjectJson.parseObject(text)
        val imported = ProjectJson.decode(text)
        val keepId = isValidId(imported.id) && !projectDir(imported.id).exists()
        // An imported file must not be refused over a clash, so it is renamed to "<name> (2)" etc.
        val name = ProjectNames.unique(validName(imported.name), namesInUse(null), MAX_NAME_LENGTH) { b, n -> "$b ($n)" }
        storeRaw(raw, if (keepId) imported.id else idGenerator(), name)
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
        ProjectSummary(project.id, project.name, project.settings, file.lastModified())

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
        val dirs = rootDir.listFiles { file -> file.isDirectory && file.name != excludingId }.orEmpty()
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
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}
