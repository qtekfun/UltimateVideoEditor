package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.FontException
import com.ultimatevideo.uveditor.data.FontRegistry
import com.ultimatevideo.uveditor.data.LutStore
import com.ultimatevideo.uveditor.domain.LutParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Resources a project refers to that live in app-wide libraries rather than in the project: imported 3D LUTs
 * (a clip's LUT effect holds the library key) and imported fonts (a text layer holds the font id). Looks,
 * title presets and templates are copied into the project by value when they are applied, and stickers and
 * emoji are built in, so none of those needs to travel.
 */
enum class ResourceKind(val wire: String) {
    LUT("lut"),
    FONT("font"),
    ;

    companion object {
        fun of(wire: String): ResourceKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A resource listed in `bundle.json`: [key] is the LUT library key or the font id; [entry] is set when its bytes are inside. */
@Serializable
data class BundleResource(
    val kind: String,
    val key: String,
    val name: String,
    val sizeBytes: Long = -1,
    val sha256: String = "",
    val entry: String? = null,
)

/** A resource file with the name it should carry in the bundle. */
class ResourceBytes(val name: String, val bytes: ByteArray)

/** Why a resource was refused; the message is meant for the user. */
class ResourceRejected(message: String) : Exception(message)

enum class PlacementStatus { ADDED, ALREADY_PRESENT, REKEYED }

/** Where a LUT ended up: [key] can differ from the one asked for (see [LutStore.install]). */
data class LutPlacement(val key: Int, val status: PlacementStatus)

data class FontPlacement(val id: String, val status: PlacementStatus)

/** What the app-wide libraries hold, and how a bundle's resources get in. Lets tests use in-memory libraries. */
interface ResourceLibrary {
    fun lutName(key: Int): String?
    fun readLut(key: Int): ResourceBytes?

    /** @throws ResourceRejected when [bytes] is not a usable LUT, @throws IOException when it cannot be stored. */
    fun installLut(name: String, bytes: ByteArray, desiredKey: Int): LutPlacement

    fun fontFamily(id: String): String?
    fun readFont(id: String): ResourceBytes?

    /** The id [bytes] would be stored under, so a manifest that names another id is refused before anything is installed. */
    fun fontIdOf(bytes: ByteArray): String

    /** @throws ResourceRejected when [bytes] is not a usable font or its id is taken, @throws IOException when it cannot be stored. */
    fun installFont(bytes: ByteArray): FontPlacement
}

/** The real libraries: the LUT store and the font registry. */
class StoreResourceLibrary(private val luts: LutStore, private val fonts: FontRegistry) : ResourceLibrary {
    override fun lutName(key: Int): String? = luts.info(key)?.name

    override fun readLut(key: Int): ResourceBytes? {
        val info = luts.info(key) ?: return null
        val text = luts.readText(key) ?: return null
        return ResourceBytes("${info.name}.cube", text.toByteArray(Charsets.UTF_8))
    }

    override fun installLut(name: String, bytes: ByteArray, desiredKey: Int): LutPlacement {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ResourceRejected("it is not a text .cube file")
        }
        val result = try {
            luts.install(name, text, desiredKey)
        } catch (e: LutParseException) {
            throw ResourceRejected("it is not a valid .cube file (${e.message})")
        }
        val status = when (result.status) {
            LutStore.InstallStatus.ADDED -> PlacementStatus.ADDED
            LutStore.InstallStatus.ALREADY_PRESENT -> PlacementStatus.ALREADY_PRESENT
            LutStore.InstallStatus.REKEYED -> PlacementStatus.REKEYED
        }
        return LutPlacement(result.info.key, status)
    }

    override fun fontFamily(id: String): String? = fonts.family(id)

    override fun readFont(id: String): ResourceBytes? {
        val file = fonts.file(id) ?: return null
        val family = fonts.family(id) ?: id
        return try {
            ResourceBytes("$family.${file.extension.ifEmpty { "ttf" }}", file.readBytes())
        } catch (e: IOException) {
            null
        }
    }

    override fun fontIdOf(bytes: ByteArray): String = FontRegistry.idOf(bytes)

    override fun installFont(bytes: ByteArray): FontPlacement {
        val result = try {
            fonts.install(bytes)
        } catch (e: FontException) {
            throw ResourceRejected(e.message ?: "it is not a usable font")
        }
        return when (result.status) {
            FontRegistry.InstallStatus.ADDED -> FontPlacement(result.entry.id, PlacementStatus.ADDED)
            FontRegistry.InstallStatus.ALREADY_PRESENT -> FontPlacement(result.entry.id, PlacementStatus.ALREADY_PRESENT)
            FontRegistry.InstallStatus.CONFLICT -> throw ResourceRejected("another font with the same id is already stored")
        }
    }
}

/** What goes into a bundle besides the project: media files and the resource categories, chosen by the user. */
data class BundleChoice(
    val includeMedia: Boolean = false,
    val includeLuts: Boolean = true,
    /** Off by default: font licences may not allow redistributing the file. */
    val includeFonts: Boolean = false,
)

/** One resource a project refers to: [available] is false when this device's library does not hold it (so it cannot be included). */
data class ResourceInfo(
    val kind: ResourceKind,
    val key: String,
    val name: String,
    val sizeBytes: Long,
    val available: Boolean,
)

/** What a bundle of a project could contain, for the export dialog. */
data class BundlePreview(
    val mediaCount: Int = 0,
    val mediaBytes: Long = 0,
    val mediaUnreadable: List<String> = emptyList(),
    val luts: List<ResourceInfo> = emptyList(),
    val fonts: List<ResourceInfo> = emptyList(),
) {
    /** Size of what [choice] puts in the bundle (a project file and a picture add a few hundred KB more). */
    fun estimatedBytes(choice: BundleChoice): Long =
        (if (choice.includeMedia) mediaBytes else 0L) +
            (if (choice.includeLuts) luts.filter { it.available }.sumOf { it.sizeBytes } else 0L) +
            (if (choice.includeFonts) fonts.filter { it.available }.sumOf { it.sizeBytes } else 0L)

    val hasResources: Boolean get() = luts.isNotEmpty() || fonts.isNotEmpty()

    companion object {
        val EMPTY = BundlePreview()
    }
}

/** A resource to write into a bundle. */
class ResourcePayload(val kind: ResourceKind, val key: String, val file: ResourceBytes) {
    val sha256: String by lazy { Hashes.sha256Hex(file.bytes) }
}

/** The resources a bundle is made of and the ones the user asked for but this device cannot provide. */
class ResourcePlan(val payloads: List<ResourcePayload>, val skipped: List<String>)

/** The library keys and font ids a project refers to. */
data class ResourceRefs(val lutKeys: Set<Int>, val fontIds: Set<String>) {
    val isEmpty: Boolean get() = lutKeys.isEmpty() && fontIds.isEmpty()

    companion object {
        val NONE = ResourceRefs(emptySet(), emptySet())
        private const val MAX_LUT_KEY = 16_777_215

        /** Reads the references from the raw `project.json`, so fields this build does not know cannot hide one. */
        fun collect(raw: JsonObject): ResourceRefs {
            val luts = LinkedHashSet<Int>()
            val fonts = LinkedHashSet<String>()
            for (clip in clipsOf(raw)) {
                for (effect in (clip["effects"] as? JsonArray).orEmpty()) {
                    val e = effect as? JsonObject ?: continue
                    if (e["type"].text() != "lut") continue
                    lutKeyOf(e)?.let(luts::add)
                }
                val layers = ((clip["title"] as? JsonObject)?.get("layers") as? JsonArray).orEmpty()
                for (layer in layers) {
                    val l = layer as? JsonObject ?: continue
                    if (l["type"].text() != "text") continue
                    l["font"].text()?.takeIf { it.isNotBlank() }?.let(fonts::add)
                }
            }
            return ResourceRefs(luts, fonts)
        }

        /** [raw] with the LUT keys in [remap] (old to new) replaced in every LUT effect; nothing else changes. */
        fun withLutKeys(raw: JsonObject, remap: Map<Int, Int>): JsonObject {
            if (remap.isEmpty()) return raw
            val tracks = (raw["tracks"] as? JsonArray) ?: return raw
            val newTracks = tracks.map { track ->
                val t = track as? JsonObject ?: return@map track
                val clips = (t["clips"] as? JsonArray) ?: return@map track
                JsonObject(t + ("clips" to JsonArray(clips.map { remapClip(it, remap) })))
            }
            return JsonObject(raw + ("tracks" to JsonArray(newTracks)))
        }

        private fun remapClip(clip: JsonElement, remap: Map<Int, Int>): JsonElement {
            val c = clip as? JsonObject ?: return clip
            val effects = (c["effects"] as? JsonArray) ?: return clip
            val mapped = effects.map { effect ->
                val e = effect as? JsonObject ?: return@map effect
                if (e["type"].text() != "lut") return@map effect
                val key = lutKeyOf(e) ?: return@map effect
                val to = remap[key] ?: return@map effect
                val values = e["values"] as JsonArray
                JsonObject(e + ("values" to JsonArray(listOf<JsonElement>(JsonPrimitive(to.toDouble())) + values.drop(1))))
            }
            return JsonObject(c + ("effects" to JsonArray(mapped)))
        }

        private fun lutKeyOf(effect: JsonObject): Int? {
            val first = (effect["values"] as? JsonArray)?.firstOrNull() as? JsonPrimitive ?: return null
            val d = first.doubleOrNull ?: return null
            val k = d.toInt()
            return if (k.toDouble() == d && k in 1..MAX_LUT_KEY) k else null
        }

        private fun clipsOf(raw: JsonObject): List<JsonObject> =
            (raw["tracks"] as? JsonArray).orEmpty().flatMap { track ->
                ((track as? JsonObject)?.get("clips") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            }

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}

internal object Hashes {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What exporting a project's resources looks like: used by the dialog and by the writer. */
object BundleResources {
    /** The resources [refs] points at, with what this device's [library] can provide. */
    fun infos(refs: ResourceRefs, library: ResourceLibrary?): List<ResourceInfo> {
        val out = ArrayList<ResourceInfo>()
        for (key in refs.lutKeys.sorted()) {
            val data = library?.readLut(key)
            out += ResourceInfo(ResourceKind.LUT, key.toString(), library?.lutName(key) ?: "LUT #$key", data?.bytes?.size?.toLong() ?: 0L, data != null)
        }
        for (id in refs.fontIds.sorted()) {
            val data = library?.readFont(id)
            out += ResourceInfo(ResourceKind.FONT, id, library?.fontFamily(id) ?: "Font $id", data?.bytes?.size?.toLong() ?: 0L, data != null)
        }
        return out
    }

    /** The payloads for [choice], in a fixed order (so the same input gives the same bundle) and the references that cannot be provided. */
    fun plan(refs: ResourceRefs, library: ResourceLibrary?, choice: BundleChoice): ResourcePlan {
        val payloads = ArrayList<ResourcePayload>()
        val skipped = ArrayList<String>()
        if (choice.includeLuts) {
            for (key in refs.lutKeys.sorted()) {
                val data = library?.readLut(key)
                if (data == null) skipped += "LUT #$key (not in this device's library)" else payloads += ResourcePayload(ResourceKind.LUT, key.toString(), data)
            }
        }
        if (choice.includeFonts) {
            for (id in refs.fontIds.sorted()) {
                val data = library?.readFont(id)
                if (data == null) skipped += "font $id (not in this device's library)" else payloads += ResourcePayload(ResourceKind.FONT, id, data)
            }
        }
        return ResourcePlan(payloads, skipped)
    }
}

/** A resource that could not be installed, with the reason. */
data class ResourceProblem(val name: String, val reason: String)

/**
 * What an import did with the resources: [installed] are new in this app, [alreadyHere] were there,
 * [rekeyed] names the LUTs that got another key (a 24-bit hash clash with a different LUT), [failed] could
 * not be installed and [missing] are referenced by the project but exist neither in the bundle nor here.
 */
data class ResourceImportReport(
    val installed: List<String> = emptyList(),
    val alreadyHere: List<String> = emptyList(),
    val rekeyed: List<String> = emptyList(),
    val failed: List<ResourceProblem> = emptyList(),
    val missing: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = installed.isEmpty() && alreadyHere.isEmpty() && rekeyed.isEmpty() && failed.isEmpty() && missing.isEmpty()

    companion object {
        val EMPTY = ResourceImportReport()
    }
}

/** The report and the LUT keys the project must be rewritten with (old key to new key). */
class ResourceInstallResult(val report: ResourceImportReport, val lutRemap: Map<Int, Int>)

/** Installs the resources of an unpacked bundle into the app-wide libraries. */
object BundleResourceInstaller {
    fun install(
        manifest: BundleManifest,
        files: Map<String, File>,
        refs: ResourceRefs,
        library: ResourceLibrary?,
    ): ResourceInstallResult {
        val installed = ArrayList<String>()
        val here = ArrayList<String>()
        val rekeyed = ArrayList<String>()
        val failed = ArrayList<ResourceProblem>()
        val remap = LinkedHashMap<Int, Int>()
        val provided = HashSet<String>() // "lut:<key>" / "font:<id>" the project can use afterwards

        for (res in manifest.resources) {
            val kind = ResourceKind.of(res.kind)
            val label = labelOf(res)
            if (kind == null) {
                failed += ResourceProblem(label, "this version of the app does not know this kind of resource")
                continue
            }
            val file = res.entry?.let(files::get)
            if (file == null) {
                // Listed without bytes (the exporter could not read it, or it was left out): nothing to install.
                continue
            }
            if (library == null) {
                failed += ResourceProblem(label, "this build cannot install resources")
                continue
            }
            try {
                val bytes = file.readBytes()
                if (res.sha256.isNotEmpty() && Hashes.sha256Hex(bytes) != res.sha256) throw ResourceRejected("the file is damaged (its checksum does not match)")
                when (kind) {
                    ResourceKind.LUT -> {
                        val desired = res.key.toIntOrNull() ?: throw ResourceRejected("it has no valid key")
                        val placement = library.installLut(res.name, bytes, desired)
                        when (placement.status) {
                            PlacementStatus.ADDED -> installed += label
                            PlacementStatus.ALREADY_PRESENT -> here += label
                            PlacementStatus.REKEYED -> {
                                installed += label
                                rekeyed += label
                            }
                        }
                        if (placement.key != desired) remap[desired] = placement.key
                        provided += "lut:$desired"
                    }
                    ResourceKind.FONT -> {
                        if (library.fontIdOf(bytes) != res.key) throw ResourceRejected("its id does not match its content")
                        val placement = library.installFont(bytes)
                        if (placement.status == PlacementStatus.ADDED) installed += label else here += label
                        provided += "font:${res.key}"
                    }
                }
            } catch (e: ResourceRejected) {
                failed += ResourceProblem(label, e.message ?: "it could not be installed")
            } catch (e: IOException) {
                failed += ResourceProblem(label, "it could not be saved (${e.message ?: "disk error"})")
            }
        }

        val missing = ArrayList<String>()
        for (key in refs.lutKeys.sorted()) {
            if ("lut:$key" in provided) continue
            if (library?.lutName(key) != null) continue
            if (manifest.resources.any { it.kind == ResourceKind.LUT.wire && it.key == key.toString() && it.entry != null }) continue // reported as failed
            missing += manifest.resources.firstOrNull { it.kind == ResourceKind.LUT.wire && it.key == key.toString() }?.let(::labelOf) ?: "LUT #$key"
        }
        for (id in refs.fontIds.sorted()) {
            if ("font:$id" in provided) continue
            if (library?.fontFamily(id) != null) continue
            if (manifest.resources.any { it.kind == ResourceKind.FONT.wire && it.key == id && it.entry != null }) continue
            missing += manifest.resources.firstOrNull { it.kind == ResourceKind.FONT.wire && it.key == id }?.let(::labelOf) ?: "font $id"
        }
        return ResourceInstallResult(ResourceImportReport(installed, here, rekeyed, failed, missing), remap)
    }

    private fun labelOf(res: BundleResource): String {
        val base = res.name.removeSuffix(".cube").removeSuffix(".ttf").removeSuffix(".otf").ifBlank { res.key }
        return when (res.kind) {
            ResourceKind.LUT.wire -> "LUT $base"
            ResourceKind.FONT.wire -> "font $base"
            else -> base
        }
    }
}
