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

data class UnreadableProject(val id: String, val error: ProjectError)

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
            if (!file.isFile) continue
            try {
                projects += summaryOf(ProjectJson.decode(readText(file)), file)
            } catch (e: ProjectError) {
                unreadable += UnreadableProject(dir.name, e)
            }
        }
        ProjectListing(projects.sortedByDescending { it.lastModifiedMillis }, unreadable)
    }

    suspend fun create(name: String, settings: ProjectSettingsDto): ProjectDto = mutate {
        val project = ProjectDto(id = idGenerator(), name = validName(name), settings = settings)
        writeProject(project)
        project
    }

    override suspend fun load(id: String): ProjectDto = withContext(ioDispatcher) {
        ProjectJson.decode(readText(projectFile(id)))
    }

    override suspend fun save(project: ProjectDto) = mutate { writeProject(project) }

    suspend fun rename(id: String, name: String): ProjectDto = mutate {
        val renamed = ProjectJson.decode(readText(projectFile(id))).copy(name = validName(name))
        writeProject(renamed)
        renamed
    }

    /** Copies a project under a new id, keeping unknown fields; derived caches are not copied. */
    suspend fun clone(id: String, newName: String? = null): ProjectDto = mutate {
        val raw = ProjectJson.parseObject(readText(projectFile(id)))
        val source = ProjectJson.decode(readText(projectFile(id)))
        val copyName = validName(newName ?: "${source.name} copy".take(MAX_NAME_LENGTH))
        storeRaw(raw, idGenerator(), copyName)
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
        storeRaw(raw, if (keepId) imported.id else idGenerator(), imported.name)
    }

    private fun storeRaw(raw: kotlinx.serialization.json.JsonObject, id: String, name: String): ProjectDto {
        val text = ProjectJson.withIdentity(raw, id, name)
        val project = ProjectJson.decode(text)
        atomicWrite(projectFile(id), text.toByteArray(Charsets.UTF_8))
        return project
    }

    private fun writeProject(project: ProjectDto) {
        val file = projectFile(project.id)
        val base = if (file.isFile) existingRawOrNull(file) else null
        atomicWrite(file, ProjectJson.encode(project, base).toByteArray(Charsets.UTF_8))
    }

    // A file we cannot parse has nothing worth preserving; the new content replaces it.
    private fun existingRawOrNull(file: File) = try {
        ProjectJson.parseObject(readText(file))
    } catch (e: ProjectError.Corrupt) {
        null
    }

    private fun atomicWrite(target: File, bytes: ByteArray) = io("write ${target.name}") {
        val dir = target.parentFile ?: throw IOException("no parent for $target")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val temp = File(dir, "$PROJECT_FILE.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

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
        const val MAX_NAME_LENGTH = 80
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}
