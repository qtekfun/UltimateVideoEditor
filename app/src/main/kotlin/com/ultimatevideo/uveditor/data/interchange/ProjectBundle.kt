package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.data.model.CURRENT_SCHEMA_VERSION
import com.ultimatevideo.uveditor.data.model.ProjectDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Why a bundle could not be read. [message] is meant for the user. */
sealed class BundleError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotABundle(detail: String) : BundleError("This is not a ultimateVE project bundle ($detail)")
    class UnsafePath(entry: String) : BundleError("The bundle contains an unsafe file name: ${entry.take(80)}")
    class TooLarge(what: String) : BundleError("The bundle is larger than allowed ($what)")
    class Corrupt(detail: String, cause: Throwable? = null) : BundleError("The bundle is damaged: $detail", cause)
    class UnsupportedVersion(found: Int, supported: Int) : BundleError("The bundle was made by a newer version of the app (format $found, this app reads up to $supported)")
}

/** Reads the files a bundle can carry. In the app this goes through the system content resolver. */
interface BundleMediaSource {
    /** Size in bytes of the file behind [uri], or null when it cannot be read. */
    fun sizeOf(uri: String): Long?

    /** A stream over the file behind [uri], or null when it cannot be opened. */
    fun open(uri: String): InputStream?
}

/** Upper bounds that protect a device from a hostile or broken bundle. Media and everything else are limited separately. */
data class BundleLimits(
    val maxEntries: Int = 20_000,
    val maxJsonBytes: Long = 64L * 1024 * 1024,
    val maxThumbnailBytes: Long = 8L * 1024 * 1024,
    val maxFileBytes: Long = 64L * 1024 * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024 * 1024,
    /** A LUT or a font: the largest LUT is about 9 MB of text and the font parser refuses more than 25 MB. */
    val maxResourceBytes: Long = 32L * 1024 * 1024,
    val maxResources: Int = 256,
    val maxResourceTotalBytes: Long = 512L * 1024 * 1024,
)

/** `bundle.json`: describes what is inside, so an import can tell what was copied and what must be relinked. */
@Serializable
data class BundleManifest(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val app: String = "ultimateVE",
    val projectName: String = "",
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** One entry per media file of the project's library, whether or not its bytes are in the bundle. */
    val media: List<BundleMedia> = emptyList(),
    /** Entry names under `thumbnails/`. */
    val thumbnails: List<String> = emptyList(),
    /**
     * LUTs and fonts the project refers to whose bytes are inside under `resources/`. Added after format 1 was
     * released and kept optional: older builds ignore this key and the `resources/` entries, and bundles without it import as before.
     */
    val resources: List<BundleResource> = emptyList(),
) {
    companion object {
        const val FORMAT = "uveditor-bundle"
        const val FORMAT_VERSION = 1
    }
}

/** A library file: its size and name allow finding it again on another device; [entry] is set when its bytes are inside. */
@Serializable
data class BundleMedia(
    val assetId: String,
    val name: String,
    val sizeBytes: Long = -1,
    val entry: String? = null,
)

/** The project of a bundle after it was unpacked into a directory. Nothing outside that directory was touched. */
class ExtractedBundle(
    val projectJson: String,
    val manifest: BundleManifest,
    /** Unpacked media files by asset id. */
    val mediaFiles: Map<String, File>,
    val thumbnailFiles: List<File>,
    /** Unpacked LUT and font files by their entry name (`resources/...`), as listed in the manifest. */
    val resourceFiles: Map<String, File> = emptyMap(),
)

/**
 * What writing a bundle did: the media that were not copied (unreadable, or by choice) are named, and so are
 * the LUTs and fonts that were asked for but could not be put in.
 */
data class BundleWriteResult(
    val mediaCopied: Int,
    val mediaSkipped: List<String>,
    val resourcesIncluded: Int = 0,
    val resourcesSkipped: List<String> = emptyList(),
    val lutsIncluded: Int = 0,
    val fontsIncluded: Int = 0,
    /** The size of the finished file as the writer counted it, and every entry in it: what the check of the saved file compares against. */
    val bytesWritten: Long = 0,
    val entries: List<WrittenEntry> = emptyList(),
)

/**
 * The `.uvbundle` container: a zip with `bundle.json`, `project.json`, optional `thumbnails/<file>`,
 * optional `media/<file>` and optional `resources/<file>` (the LUTs and fonts the project refers to).
 * Entry names are checked so that unpacking can never write outside its target
 * directory (zip-slip), sizes are bounded, and an import into the repository is atomic.
 */
object ProjectBundle {
    const val MANIFEST = "bundle.json"
    const val PROJECT = "project.json"
    private const val THUMBS = "thumbnails/"
    private const val MEDIA = "media/"
    const val RESOURCES = "resources/"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** True when [head] (the first bytes of a file) starts like a zip archive. */
    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    /** The entry name under `media/` for an asset: its id plus a safe version of the file name. */
    fun mediaEntryName(assetId: String, displayName: String): String = MEDIA + safeLeaf("$assetId-$displayName")

    /**
     * Writes [projectJson] (the raw text of `project.json`, so fields this build does not know survive) and,
     * with [includeMedia], the media files of [project] that [media] can read. Entries carry no timestamps,
     * so the same input produces the same bytes. Media is stored, not recompressed.
     */
    fun write(
        projectJson: String,
        project: ProjectDto,
        media: BundleMediaSource?,
        includeMedia: Boolean,
        thumbnails: Map<String, ByteArray>,
        out: OutputStream,
        resources: ResourcePlan = ResourcePlan(emptyList(), emptyList()),
        observer: BundleWriteObserver = BundleWriteObserver.NONE,
    ): BundleWriteResult {
        val skipped = mutableListOf<String>()
        var copied = 0
        val entries = ArrayList<BundleMedia>()
        for (asset in project.mediaLibrary) {
            val name = MissingMedia.nameOf(asset)
            val size = media?.sizeOf(asset.uri)
            val copy = includeMedia && media != null && size != null
            if (includeMedia && !copy) skipped += name
            entries += BundleMedia(asset.id, name, size ?: -1L, if (copy) mediaEntryName(asset.id, name) else null)
        }
        val resourceEntries = resources.payloads.map { payload ->
            BundleResource(
                kind = payload.kind.wire,
                key = payload.key,
                name = payload.file.name,
                sizeBytes = payload.file.bytes.size.toLong(),
                sha256 = payload.sha256,
                entry = resourceEntryName(payload.kind, payload.key, payload.file.name),
            )
        }
        val manifest = BundleManifest(
            projectName = project.name,
            schemaVersion = project.version,
            media = entries,
            thumbnails = thumbnails.keys.sorted(),
            resources = resourceEntries + resources.references,
        )
        // The total is known before anything is written: every other payload is in memory and each media file has a size.
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val projectBytes = projectJson.toByteArray(Charsets.UTF_8)
        val total = manifestBytes.size + projectBytes.size + thumbnails.values.sumOf { it.size.toLong() } +
            resources.payloads.sumOf { it.file.bytes.size.toLong() } +
            entries.filter { it.entry != null }.sumOf { it.sizeBytes.coerceAtLeast(0) }
        observer.onStart(total, entries.count { it.entry != null })
        val written = ArrayList<WrittenEntry>()
        val counting = CountingOutputStream(out)
        val buffer = ByteArray(COPY_BUFFER)
        ZipOutputStream(counting).use { zip ->
            fun put(name: String, level: Int, bytes: ByteArray) {
                if (observer.isCancelled()) throw BundleWriteCancelled()
                zip.setLevel(level)
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
                written += WrittenEntry(name, bytes.size.toLong())
                observer.onBytes(bytes.size.toLong())
            }
            observer.onItem(BundleItemKind.PROJECT, PROJECT, 0)
            put(MANIFEST, DEFLATE, manifestBytes)
            put(PROJECT, DEFLATE, projectBytes)
            for ((name, bytes) in thumbnails.toSortedMap()) {
                observer.onItem(BundleItemKind.THUMBNAIL, name, 0)
                put(THUMBS + safeLeaf(name), DEFLATE, bytes)
            }
            for ((index, payload) in resources.payloads.withIndex()) {
                observer.onItem(BundleItemKind.RESOURCE, payload.file.name, 0)
                put(resourceEntryName(payload.kind, payload.key, payload.file.name), if (payload.kind == ResourceKind.LUT) DEFLATE else 0, payload.file.bytes)
            }
            var mediaIndex = 0
            for (entry in entries) {
                val name = entry.entry ?: continue
                mediaIndex++
                if (observer.isCancelled()) throw BundleWriteCancelled()
                observer.onItem(BundleItemKind.MEDIA, entry.name, mediaIndex)
                val asset = project.mediaLibrary.first { it.id == entry.assetId }
                val stream = media?.open(asset.uri)
                if (stream == null) {
                    skipped += entry.name
                    observer.onBytes(entry.sizeBytes.coerceAtLeast(0)) // these bytes will not come: keep the percent honest
                    continue
                }
                zip.setLevel(0)
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                var size = 0L
                stream.use { source ->
                    while (true) {
                        if (observer.isCancelled()) throw BundleWriteCancelled()
                        // Reading and writing fail for different reasons (a vanished file, a full disk): only a failed read says "could not read".
                        val n = try {
                            source.read(buffer)
                        } catch (e: IOException) {
                            throw IOException("could not read ${entry.name} while writing the bundle", e)
                        }
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        size += n
                        observer.onBytes(n.toLong())
                    }
                }
                zip.closeEntry()
                written += WrittenEntry(name, size)
                copied++
            }
        }
        return BundleWriteResult(
            mediaCopied = copied,
            mediaSkipped = skipped,
            resourcesIncluded = resources.payloads.size,
            resourcesSkipped = resources.skipped,
            lutsIncluded = resources.payloads.count { it.kind == ResourceKind.LUT },
            fontsIncluded = resources.payloads.count { it.kind == ResourceKind.FONT },
            bytesWritten = counting.count,
            entries = written,
        )
    }

    /** The entry name under `resources/` of a LUT or font: its kind, its key and a safe version of its file name. */
    fun resourceEntryName(kind: ResourceKind, key: String, fileName: String): String = RESOURCES + safeLeaf("${kind.wire}-$key-$fileName")

    /**
     * Unpacks [input] into [dir]. Entry names are validated, sizes are bounded by [limits], and only
     * `bundle.json`, `project.json`, `thumbnails/<file>`, `media/<file>` and `resources/<file>` are accepted;
     * anything else (including directories) is skipped after its name passes the safety check, so a bundle
     * with entries this build does not know still opens.
     */
    @Throws(BundleError::class, IOException::class)
    fun extract(input: InputStream, dir: File, limits: BundleLimits = BundleLimits()): ExtractedBundle {
        val zip = ZipInputStream(BufferedInputStream(input))
        var manifestText: String? = null
        var projectText: String? = null
        val media = LinkedHashMap<String, File>() // by entry name
        val thumbs = ArrayList<File>()
        val resources = LinkedHashMap<String, File>() // by entry name
        var resourceCount = 0
        var resourceTotal = 0L
        val seen = HashSet<String>()
        var total = 0L
        var count = 0
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > limits.maxEntries) throw BundleError.TooLarge("more than ${limits.maxEntries} files")
                val name = checkedName(entry.name)
                if (entry.isDirectory) continue
                if (!seen.add(name)) throw BundleError.Corrupt("the file $name appears twice")
                when {
                    name == MANIFEST -> manifestText = readLimited(zip, limits.maxJsonBytes, name).toString(Charsets.UTF_8)
                    name == PROJECT -> projectText = readLimited(zip, limits.maxJsonBytes, name).toString(Charsets.UTF_8)
                    name.startsWith(THUMBS) && leafOk(name.removePrefix(THUMBS)) -> {
                        val file = File(dir, "thumbnails/${name.removePrefix(THUMBS)}")
                        total += copyLimited(zip, file, limits.maxThumbnailBytes, name)
                        thumbs += file
                    }
                    name.startsWith(MEDIA) && leafOk(name.removePrefix(MEDIA)) -> {
                        val file = File(dir, "media/${name.removePrefix(MEDIA)}")
                        val size = copyLimited(zip, file, limits.maxFileBytes, name)
                        total += size
                        if (total > limits.maxTotalBytes) throw BundleError.TooLarge("more than ${limits.maxTotalBytes / (1024 * 1024 * 1024)} GB in total")
                        media[name] = file
                    }
                    name.startsWith(RESOURCES) && leafOk(name.removePrefix(RESOURCES)) -> {
                        if (++resourceCount > limits.maxResources) throw BundleError.TooLarge("more than ${limits.maxResources} LUTs and fonts")
                        val file = File(dir, "resources/${name.removePrefix(RESOURCES)}")
                        val size = copyLimited(zip, file, limits.maxResourceBytes, name)
                        resourceTotal += size
                        if (resourceTotal > limits.maxResourceTotalBytes) throw BundleError.TooLarge("more than ${limits.maxResourceTotalBytes / (1024 * 1024)} MB of LUTs and fonts")
                        resources[name] = file
                    }
                    // Anything else is ignored, so a newer app can add kinds of entries without older ones refusing the bundle.
                    else -> if (name.startsWith(THUMBS) || name.startsWith(MEDIA)) throw BundleError.UnsafePath(name)
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw BundleError.Corrupt(e.message ?: "unreadable archive", e)
        }
        val manifest = try {
            json.decodeFromString<BundleManifest>(manifestText ?: throw BundleError.NotABundle("it has no $MANIFEST"))
        } catch (e: IllegalArgumentException) {
            throw BundleError.Corrupt("$MANIFEST is not valid", e)
        }
        if (manifest.format != BundleManifest.FORMAT) throw BundleError.NotABundle("format '${manifest.format}'")
        if (manifest.formatVersion > BundleManifest.FORMAT_VERSION) throw BundleError.UnsupportedVersion(manifest.formatVersion, BundleManifest.FORMAT_VERSION)
        val project = projectText ?: throw BundleError.Corrupt("it has no $PROJECT")
        val byAsset = HashMap<String, File>()
        for (m in manifest.media) {
            val entry = m.entry ?: continue
            checkedName(entry)
            media[entry]?.let { byAsset[m.assetId] = it }
        }
        val byResource = LinkedHashMap<String, File>()
        for (r in manifest.resources) {
            val entry = r.entry ?: continue
            checkedName(entry)
            if (!entry.startsWith(RESOURCES)) throw BundleError.Corrupt("the resource ${r.name.take(60)} points outside $RESOURCES")
            resources[entry]?.let { byResource[entry] = it }
        }
        return ExtractedBundle(project, manifest, byAsset, thumbs, byResource)
    }

    /** Opens [input] and tells whether it is a bundle, without consuming it: [input] must support mark/reset. */
    fun sniff(input: BufferedInputStream): Boolean {
        input.mark(8)
        val head = ByteArray(4)
        val n = input.read(head)
        input.reset()
        return n == 4 && looksLikeZip(head)
    }

    // region helpers

    /** A bundle entry name with the checks that make zip-slip impossible: relative, forward slashes, no `..`, no drive letters. */
    internal fun checkedName(raw: String): String {
        if (raw.isEmpty() || raw.length > 400) throw BundleError.UnsafePath(raw)
        if (raw.contains('\u0000') || raw.contains('\\') || raw.startsWith("/") || (raw.length > 1 && raw[1] == ':')) throw BundleError.UnsafePath(raw)
        for (part in raw.removeSuffix("/").split('/')) {
            if (part == ".." || part == "." || part.isEmpty()) throw BundleError.UnsafePath(raw)
        }
        return raw
    }

    private fun leafOk(leaf: String): Boolean {
        if (leaf.isEmpty() || leaf.contains('/')) throw BundleError.UnsafePath(leaf)
        return true
    }

    /** A file name that cannot escape a directory: letters, digits and a few punctuation marks only. */
    internal fun safeLeaf(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").replace(Regex("\\.{2,}"), "_").trim('.')
        return cleaned.take(120).ifEmpty { "file" }
    }

    private fun readLimited(zip: ZipInputStream, max: Long, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw BundleError.TooLarge("$name is bigger than ${max / (1024 * 1024)} MB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun copyLimited(zip: ZipInputStream, target: File, max: Long, name: String): Long {
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot create $it") }
        var total = 0L
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) throw BundleError.TooLarge("$name is bigger than allowed")
                out.write(buffer, 0, n)
            }
        }
        return total
    }

    private const val BUFFER = 64 * 1024
    private const val COPY_BUFFER = 256 * 1024
    private const val DEFLATE = 6

    // endregion
}
