package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.model.ClipAudioDto
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TitleDto
import com.ultimatevideo.uveditor.data.model.TitleLayerDto
import com.ultimatevideo.uveditor.data.model.TrackAudioDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransformDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10

/** Why a LumaFusion project could not be read. [message] is meant for the user. */
sealed class LumaFusionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotLumaFusion(detail: String) : LumaFusionError("This is not a LumaFusion project ($detail)")
    class Malformed(detail: String, cause: Throwable? = null) : LumaFusionError("The LumaFusion project is damaged: $detail", cause)
    class Unsupported(detail: String) : LumaFusionError("This LumaFusion project cannot be imported: $detail")
}

/** A CMTime of the archive: [value] / [timescale] seconds. */
data class LfTime(val value: Long, val timescale: Long)

/** One clip of a LumaFusion track, reduced to what the importer reads. Unknown keys are ignored, missing ones defaulted. */
data class LfClip(
    val id: String,
    val title: String,
    /** The asset's identity in the archive (its `assetID`), or the file name when that is missing. */
    val assetKey: String,
    /** File name of the footage (leaf of `originalFilename`), matched against the package's entries. */
    val fileName: String,
    val assetType: Int,
    val assetIsBlank: Boolean,
    val trackStart: LfTime?,
    val trackDuration: LfTime?,
    val sourceStart: LfTime?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val reversed: Boolean,
    val transitionType: Int,
    val anchoredToOthers: Boolean,
    /** Speed values of the streams that differ from 1. */
    val speed: Double,
    val effectCount: Int,
    /** Video attributes that are not at their default (scale, position, ...), by name. Opacity is imported, not listed. */
    val videoChanges: List<String>,
    val audioChanges: List<String>,
    val keyframed: List<String>,
    val opacity: Double,
    /** Linear gain of the audio stream (1 = unchanged) and pan (-1..1), when the clip has audio. */
    val volume: Double,
    val pan: Double,
    /** Constant picture placement as the archive stores it: scale (x, y), translation (x, y) and rotation (radians). */
    val scale: List<Double> = listOf(1.0, 1.0),
    val translation: List<Double> = listOf(0.0, 0.0),
    val rotation: Double = 0.0,
    /** The file's own orientation (radians) as LumaFusion records it for clips shot in portrait. */
    val orientation: Double = 0.0,
    /** Names of the effects on the clip (their `displayName`), for the report. */
    val effectNames: List<String> = emptyList(),
    /** The content of a title clip (`runtimeTitle`), when the clip is one. */
    val runtimeTitle: LfTitle? = null,
)

/** One layer of a LumaFusion title: a text box or a plain shape, placed by its rectangle in the title's frame. */
data class LfTitleLayer(
    val isText: Boolean,
    val text: String,
    val fontName: String,
    val pointSize: Double,
    /** `#AARRGGBB` of the text or the fill. */
    val color: String,
    /** NSTextAlignment of the paragraph: 0 left, 1 centre, 2 right. */
    val alignment: Int,
    /** Origin and size in the title's frame, y down. */
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val opacity: Double,
    /** The layer is rotated, scaled, moved or has a shadow: details this importer does not carry. */
    val extras: List<String>,
)

data class LfTitle(val frameWidth: Double, val frameHeight: Double, val layers: List<LfTitleLayer>, val unsupportedLayers: Int)

data class LfTrack(
    val title: String,
    val isVideo: Boolean,
    val isAudio: Boolean,
    val isAnchor: Boolean,
    val offset: Int,
    val hidden: Boolean,
    val locked: Boolean,
    val volume: Double,
    val clips: List<LfClip>,
)

data class LfProject(
    val title: String,
    val width: Int,
    val height: Int,
    /** The project's frame step (`stepTime`), null when absent or invalid. */
    val step: LfTime?,
    val colorspace: Int,
    val primaryVolume: Double,
    val backgroundBlack: Boolean,
    val appVersion: String,
    val tracks: List<LfTrack>,
    val markerCount: Int,
    val hasNotes: Boolean,
    val needsCloudMedia: Boolean,
)

/** What an import did, as lines for a person: what came across and what did not. */
data class LumaFusionReport(val imported: List<String>, val notImported: List<String>) {
    val isClean: Boolean get() = notImported.isEmpty()

    companion object {
        val EMPTY = LumaFusionReport(emptyList(), emptyList())
    }
}

/** The converted project and, per footage file, which asset stands for it. */
class LumaFusionConversion(
    val project: ProjectDto,
    /** The asset ids by file name; the importer points each at an extracted file or leaves it missing. */
    val assetNames: Map<String, String>,
    val report: LumaFusionReport,
)

/** Reads the `.lfarchive` JSON of LumaFusion for iOS (verified with an archive written by version 5.5.2) and converts it. */
object LumaFusionImport {
    private val json = Json { ignoreUnknownKeys = true }
    const val ARCHIVE_EXTENSION = ".lfarchive"

    /** True when [raw] is the root of an archive: a `tracks` array and `attributes.appVersion`. */
    fun looksLikeArchive(raw: JsonElement): Boolean {
        val obj = raw as? JsonObject ?: return false
        if (obj["tracks"] !is JsonArray) return false
        val version = (obj["attributes"] as? JsonObject)?.get("appVersion")
        return version is JsonPrimitive && version.isString && obj["mediaLibrary"] == null
    }

    /** True when [text] is an archive; never throws. */
    fun looksLikeArchive(text: String): Boolean = try {
        looksLikeArchive(json.parseToJsonElement(text))
    } catch (e: IllegalArgumentException) {
        // Not JSON at all: it is something else (or a damaged file the normal importer will name).
        false
    }

    fun parse(text: String): LfProject {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            throw LumaFusionError.Malformed("it is not valid JSON", e)
        }
        if (!looksLikeArchive(root)) throw LumaFusionError.NotLumaFusion("it has no tracks or no app version")
        val obj = root as JsonObject
        val attrs = obj["attributes"] as? JsonObject
        val size = (obj["resolution"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }.orEmpty()
        val markers = (attrs?.get("compositionMarkers") as? JsonArray)?.size.orEmpty0() +
            nonEmptyValues(attrs?.get("trackMarkers")) + nonEmptyValues(attrs?.get("assetMarkers"))
        val tracks = (obj["tracks"] as JsonArray).mapNotNull { (it as? JsonObject)?.let(::track) }
        return LfProject(
            title = obj.str("title").trim(),
            width = size.getOrElse(0) { 0 },
            height = size.getOrElse(1) { 0 },
            step = time(obj["stepTime"]),
            colorspace = obj.int("videoColorspace", 0),
            primaryVolume = obj.dbl("primaryVolume", 1.0),
            backgroundBlack = (obj.str("backgroundColor").split(' ').filter { it.isNotBlank() }.mapNotNull { it.toDoubleOrNull() }.take(3)).all { abs(it) < EPS },
            appVersion = attrs?.str("appVersion").orEmpty(),
            tracks = tracks,
            markerCount = markers,
            hasNotes = attrs?.str("notes").orEmpty().isNotBlank(),
            needsCloudMedia = (obj["needsCloudMedia"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun Int?.orEmpty0() = this ?: 0

    private fun nonEmptyValues(element: JsonElement?): Int = when (element) {
        is JsonObject -> element.values.sumOf { (it as? JsonArray)?.size ?: 0 }
        is JsonArray -> element.size
        else -> 0
    }

    private fun track(obj: JsonObject): LfTrack {
        val type = obj.int("trackType", -1)
        return LfTrack(
            title = obj.str("title"),
            isVideo = type == 0,
            isAudio = type == 1,
            isAnchor = (obj["isAnchorTrack"] as? JsonPrimitive)?.booleanOrNull == true,
            offset = obj.int("trackOffset", 0),
            hidden = (obj["hidden"] as? JsonPrimitive)?.booleanOrNull == true,
            locked = (obj["locked"] as? JsonPrimitive)?.booleanOrNull == true,
            volume = obj.dbl("trackVolume", 1.0),
            clips = (obj["clips"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::clip) }.orEmpty(),
        )
    }

    // Video attributes with their default; the sample archive wrote exactly these defaults for an untouched clip.
    private val videoDefaults: List<Triple<String, String, List<Double>>> = listOf(
        Triple("videoScale", "scale", listOf(1.0, 1.0)),
        Triple("videoTranslation", "position", listOf(0.0, 0.0)),
        Triple("videoRotation", "rotation", listOf(0.0)),
        Triple("videoAnchor", "anchor point", listOf(0.0, 0.0)),
        Triple("videoCrop", "crop", listOf(0.0, 0.0, 1.0, 1.0)),
        Triple("videoFlipH", "horizontal flip", listOf(0.0)),
        Triple("videoFlipV", "vertical flip", listOf(0.0)),
        Triple("videoBlendMode", "blend mode", listOf(0.0)),
        Triple("videoFitMode", "fit mode", listOf(1.0)),
        Triple("videoCropCorner", "crop corner", listOf(0.0)),
        Triple("videoCropSoftness", "crop softness", listOf(0.0)),
        Triple("videoCropInverted", "inverted crop", listOf(0.0)),
        Triple("videoTransformRotation", "transform rotation", listOf(0.0)),
        Triple("videoOrientation", "orientation", listOf(0.0)),
    )

    private fun clip(obj: JsonObject): LfClip {
        val attrs = obj["attributes"] as? JsonObject
        val streams = (obj["streams"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val video = streams.firstOrNull { it.int("mediaType", -1) == 0 }
        val audio = streams.firstOrNull { it.int("mediaType", -1) == 1 }
        val visual = streams.firstOrNull { it.int("mediaType", -1) in listOf(0, 2, 3) }
        val primary = visual ?: audio
        val range = primary?.get("sourceRange") as? JsonObject
        val original = attrs?.str("originalFilename").orEmpty()
        // A name without an extension is a placeholder (a sample had "sioProviderRelink" for a picture): the title is the file name then.
        val fileName = listOf(leaf(original), attrs?.str("title").orEmpty(), leaf(decodeUrl(obj.str("assetURL"))))
            .let { names -> names.firstOrNull { it.contains('.') } ?: names.firstOrNull { it.isNotEmpty() }.orEmpty() }
        val videoAttrs = visual?.get("streamAttributes") as? JsonObject
        val audioAttrs = audio?.get("streamAttributes") as? JsonObject
        val changes = ArrayList<String>()
        val keyed = ArrayList<String>()
        for ((key, label, default) in videoDefaults) {
            val attr = videoAttrs?.get(key) as? JsonObject ?: continue
            if (hasKeys(attr)) keyed += label
            if (!numbersEqual(flatten(attr["value"]?.let { (it as? JsonObject)?.get("value") ?: it }), default)) changes += label
        }
        val audioChanges = ArrayList<String>()
        for ((key, label) in listOf("audioDuck" to "ducking", "audioFill" to "fill")) {
            val attr = audioAttrs?.get(key) as? JsonObject ?: continue
            if (hasKeys(attr)) keyed += label
            if (!numbersEqual(flatten(valueOf(attr)), listOf(0.0))) audioChanges += label
        }
        for (key in listOf("videoAlpha", "audioVolume", "audioPan")) {
            val attr = (if (key == "videoAlpha") videoAttrs else audioAttrs)?.get(key) as? JsonObject ?: continue
            if (hasKeys(attr)) keyed += when (key) { "videoAlpha" -> "opacity"; "audioVolume" -> "volume"; else -> "pan" }
        }
        var speed = 1.0
        for (s in listOf(visual, audio)) {
            val attr = (s?.get("streamAttributes") as? JsonObject)?.get("speed") as? JsonObject ?: continue
            if (hasKeys(attr)) keyed += "speed"
            flatten(valueOf(attr)).firstOrNull()?.let { if (abs(it - 1.0) > EPS) speed = it }
        }
        return LfClip(
            id = obj.str("clipID"),
            title = obj.str("title"),
            assetKey = attrs?.str("assetID").orEmpty().ifEmpty { fileName },
            fileName = fileName,
            assetType = obj.int("assetType", 0),
            assetIsBlank = (obj["assetIsBlank"] as? JsonPrimitive)?.booleanOrNull == true,
            trackStart = time(obj["trackStart"]),
            trackDuration = time(obj["trackDuration"]),
            sourceStart = time(range?.get("start")) ?: time(primary?.get("sourceStart")),
            hasVideo = video != null,
            hasAudio = audio != null,
            reversed = (obj["reversed"] as? JsonPrimitive)?.booleanOrNull == true,
            transitionType = obj.int("transitionType", 0),
            anchoredToOthers = (obj["anchorFromClipIDs"] as? JsonArray)?.isNotEmpty() == true,
            speed = speed,
            effectCount = streams.sumOf { (it["effects"] as? JsonArray)?.size ?: 0 },
            videoChanges = changes,
            audioChanges = audioChanges,
            keyframed = keyed.distinct(),
            opacity = flatten(valueOf(videoAttrs?.get("videoAlpha"))).firstOrNull() ?: 1.0,
            volume = flatten(valueOf(audioAttrs?.get("audioVolume"))).firstOrNull() ?: 1.0,
            pan = flatten(valueOf(audioAttrs?.get("audioPan"))).firstOrNull() ?: 0.0,
            scale = flatten(valueOf(videoAttrs?.get("videoScale"))).takeIf { it.size == 2 } ?: listOf(1.0, 1.0),
            translation = flatten(valueOf(videoAttrs?.get("videoTranslation"))).takeIf { it.size == 2 } ?: listOf(0.0, 0.0),
            rotation = flatten(valueOf(videoAttrs?.get("videoRotation"))).firstOrNull() ?: 0.0,
            orientation = flatten(valueOf(videoAttrs?.get("videoOrientation"))).firstOrNull() ?: 0.0,
            effectNames = streams.flatMap { s -> (s["effects"] as? JsonArray).orEmpty().map { e -> (e as? JsonObject)?.str("displayName").orEmpty().ifEmpty { "unnamed" } } },
            runtimeTitle = (obj["runtimeTitle"] as? JsonObject)?.let(::title),
        )
    }

    private fun title(obj: JsonObject): LfTitle {
        val frame = (obj["frameSize"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()
        var unsupported = 0
        val layers = ArrayList<LfTitleLayer>()
        for (element in (obj["layers"] as? JsonArray).orEmpty()) {
            val l = element as? JsonObject ?: continue
            if ((l["hidden"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val text = l["text"] as? JsonObject
            val string = (text?.get("string") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val runs = text?.get("runs") as? JsonArray
            val shape = l["shapeAttributes"] as? JsonObject
            val path = (l["shapePath"] as? JsonObject)?.get("elements") as? JsonArray
            val isText = string != null || runs?.isNotEmpty() == true
            val isShape = !isText && path?.isNotEmpty() == true
            if (!isText && !isShape) { unsupported++; continue }
            val runAttrs = ((if (isText) runs else shape?.get("runs") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("attributes") as? JsonObject
            val rect = (l["layerRect"] as? JsonArray)?.map { flatten(it) }.orEmpty()
            val origin = rect.getOrNull(0).orEmpty()
            val size = rect.getOrNull(1).orEmpty()
            if (origin.size != 2 || size.size != 2) { unsupported++; continue }
            val extras = ArrayList<String>()
            if (abs(l.dbl("layerRotation", 0.0)) > EPS || !numbersEqual(flatten(valueOf(l["rotation"])), listOf(0.0))) extras += "rotation"
            if (!numbersEqual(flatten(valueOf(l["scale"])), listOf(1.0, 1.0)) || !numbersEqual(flatten(valueOf(l["translation"])), listOf(0.0, 0.0))) extras += "scale or position offset"
            (l["shadowAttributes"] as? JsonObject)?.let { sh -> if (sh.dbl("shadowBlurRadius", 0.0) > EPS || flatten(sh["shadowOffset"]).any { abs(it) > EPS }) extras += "shadow" }
            if (isText) {
                val content = string.orEmpty()
                layers += LfTitleLayer(
                    true, content, (runAttrs?.get("font") as? JsonObject)?.str("fontName").orEmpty(),
                    (runAttrs?.get("font") as? JsonObject)?.dbl("pointSize", 0.0) ?: 0.0,
                    argb(runAttrs?.str("foregroundColor").orEmpty()), ((runAttrs?.get("paragraphStyle") as? JsonObject)?.int("alignment", 1)) ?: 1,
                    origin[0], origin[1], size[0], size[1], l.dbl("layerOpacity", 1.0) * (flatten(valueOf(l["opacity"])).firstOrNull() ?: 1.0), extras,
                )
            } else {
                layers += LfTitleLayer(
                    false, "", "", 0.0, argb(runAttrs?.str("foregroundColor").orEmpty()), 1,
                    origin[0], origin[1], size[0], size[1], l.dbl("layerOpacity", 1.0) * (flatten(valueOf(l["opacity"])).firstOrNull() ?: 1.0), extras,
                )
            }
        }
        return LfTitle(frame.getOrElse(0) { 0.0 }, frame.getOrElse(1) { 0.0 }, layers, unsupported)
    }

    /** `"r g b a"` (floats 0..1) as `#AARRGGBB`; opaque white when it cannot be read. */
    private fun argb(text: String): String {
        val c = text.split(' ').filter { it.isNotBlank() }.mapNotNull { it.toDoubleOrNull() }
        if (c.size < 3) return "#FFFFFFFF"
        fun b(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt() and 0xFF
        val alpha = if (c.size > 3) b(c[3]) else 255
        return "#%02X%02X%02X%02X".format(alpha, b(c[0]), b(c[1]), b(c[2]))
    }

    private fun hasKeys(attr: JsonObject) = (attr["keyframes"] as? JsonArray)?.isNotEmpty() == true

    /** The `value.value` of an attribute `{value: {type, value}, keyframes, ...}`. */
    private fun valueOf(attr: JsonElement?): JsonElement? = ((attr as? JsonObject)?.get("value") as? JsonObject)?.get("value")

    /** All numbers of a value in reading order; booleans count as 0 or 1; a path of points reads its `point`. */
    private fun flatten(element: JsonElement?): List<Double> = when (element) {
        is JsonPrimitive -> listOfNotNull(element.doubleOrNull ?: element.booleanOrNull?.let { if (it) 1.0 else 0.0 })
        is JsonArray -> element.flatMap { flatten(it) }
        is JsonObject -> element["point"]?.let { flatten(it) } ?: emptyList()
        else -> emptyList()
    }

    private fun numbersEqual(a: List<Double>, b: List<Double>) = a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) < EPS }

    private fun time(element: JsonElement?): LfTime? {
        val obj = element as? JsonObject ?: return null
        val value = (obj["value"] as? JsonPrimitive)?.longOrNull ?: return null
        val scale = (obj["timescale"] as? JsonPrimitive)?.longOrNull ?: return null
        val flags = (obj["flags"] as? JsonPrimitive)?.longOrNull ?: 1L
        // CMTime flag bit 0 is "valid"; a zero timescale or an invalid/indefinite time has no position.
        if (scale <= 0 || flags and 1L == 0L || flags and INVALID_FLAGS != 0L) return null
        return LfTime(value, scale)
    }

    private fun leaf(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

    private fun decodeUrl(url: String): String = try {
        java.net.URLDecoder.decode(url.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        // A malformed escape: the raw text is still a usable name.
        url
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.int(key: String, default: Int) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: default

    private fun JsonObject.dbl(key: String, default: Double) = (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

    private const val EPS = 1e-6

    /** CMTime flags: positive infinity (4), negative infinity (8) and indefinite (16) have no position. */
    private const val INVALID_FLAGS = 4L or 8L or 16L

    // region conversion

    /** `timescale / value` of the step as a reduced rational, or null when it is not a frame rate this app supports (1 to 240 fps). */
    fun frameRate(step: LfTime?): Pair<Int, Int>? {
        if (step == null || step.value <= 0) return null
        val g = BigInteger.valueOf(step.timescale).gcd(BigInteger.valueOf(step.value)).toLong()
        val num = step.timescale / g
        val den = step.value / g
        if (num > 1_000_000 || den > 100_000) return null
        val fps = num.toDouble() / den
        return if (fps in 1.0..240.0) num.toInt() to den.toInt() else null
    }

    /**
     * Frames of `seconds * fpsNum / fpsDen` where seconds is the exact sum of the given times, rounded half up.
     * Positions are rounded, not durations, so two clips that touch in time touch in frames.
     */
    fun toFrames(fpsNum: Int, fpsDen: Int, vararg times: LfTime): Long {
        var num = BigInteger.ZERO
        var den = BigInteger.ONE
        for (t in times) {
            num = num * BigInteger.valueOf(t.timescale) + BigInteger.valueOf(t.value) * den
            den *= BigInteger.valueOf(t.timescale)
        }
        // frames = num/den * fpsNum/fpsDen, rounded half up (floor of x + 1/2).
        val top = num * BigInteger.valueOf(fpsNum.toLong())
        val bottom = den * BigInteger.valueOf(fpsDen.toLong())
        val twice = top.shiftLeft(1) + bottom
        val rounded = twice.divide(bottom.shiftLeft(1)).let { q -> if (twice.signum() < 0 && twice.mod(bottom.shiftLeft(1)).signum() != 0) q - BigInteger.ONE else q }
        return rounded.toLong()
    }

    private class Note(val message: String) {
        val where = ArrayList<String>()
    }

    private class Notes {
        private val map = LinkedHashMap<String, Note>()

        fun add(category: String, where: String) {
            map.getOrPut(category) { Note(category) }.where += where
        }

        fun lines(): List<String> = map.values.map { n ->
            val sample = n.where.take(3).joinToString(", ")
            val more = if (n.where.size > 3) " and ${n.where.size - 3} more" else ""
            "${n.message}: ${n.where.size} ($sample$more)"
        }
    }

    /**
     * Converts [lf] into a project. [available] holds the lower-case file names of footage that came along (empty for an
     * archive without footage): clips of other files still get their place on the timeline, with the media marked missing.
     */
    fun convert(lf: LfProject, available: Set<String> = emptySet()): LumaFusionConversion {
        val notes = Notes()
        val cautions = Notes()
        val imported = ArrayList<String>()
        var (fpsNum, fpsDen) = frameRate(lf.step) ?: (0 to 0)
        if (fpsNum == 0) {
            fpsNum = 30
            fpsDen = 1
            notes.add("Frame rate not found, 30 fps used", "project")
        }
        if (lf.width < 16 || lf.height < 16 || lf.width > 16384 || lf.height > 16384) {
            throw LumaFusionError.Malformed("the picture size ${lf.width} x ${lf.height} is not usable")
        }
        val width = lf.width and 1.inv()
        val height = lf.height and 1.inv()

        val assetIds = LinkedHashMap<String, String>() // assetKey -> asset id
        val assetNames = LinkedHashMap<String, String>() // file name -> asset id
        val assetClips = LinkedHashMap<String, MutableList<LfClip>>()
        var clipCounter = 0
        var skipped = 0
        var kept = 0
        var titles = 0
        val photoAssets = HashSet<String>()

        fun clock(frame: Long): String {
            val s = frame * fpsDen / fpsNum
            return "%d:%02d".format(s / 60, s % 60)
        }

        // Video lanes top first: the highest offset is the top layer; the anchor (base) track is the lowest video track.
        val videoTracks = lf.tracks.filter { it.isVideo }
        val anchor = videoTracks.firstOrNull { it.isAnchor } ?: videoTracks.firstOrNull { it.offset == 0 } ?: videoTracks.minByOrNull { it.offset }
        val overlays = videoTracks.filter { it !== anchor }.sortedByDescending { it.offset }
        val audioTracks = lf.tracks.filter { it.isAudio }.sortedByDescending { it.offset }
        for (t in lf.tracks) if (!t.isVideo && !t.isAudio && t.clips.isNotEmpty()) notes.add("Track of an unknown kind (clips left out)", "${t.clips.size} clips")

        class Placed(val clip: LfClip, val start: Long, var end: Long, val sourceIn: Long, val kind: Kind)

        fun lane(track: LfTrack, label: String, audioLane: Boolean): List<Placed> {
            val placed = ArrayList<Placed>()
            for (c in track.clips.sortedBy { it.trackStart?.let { t -> t.value.toDouble() / t.timescale } ?: 0.0 }) {
                val at = c.trackStart?.let { clock(toFrames(fpsNum, fpsDen, it)) } ?: "?"
                val where = "$label at $at"
                val kind = when {
                    c.assetIsBlank -> { notes.add("Blank or generated clips, left out", where); skipped++; continue }
                    audioLane -> if (c.assetType == 0 && c.hasAudio) Kind.AUDIO else { notes.add("Clips without sound on an audio track, left out", where); skipped++; continue }
                    c.assetType == 0 && c.hasVideo -> Kind.VIDEO
                    c.assetType == 0 -> { notes.add("Clips without a picture on a video track, left out", where); skipped++; continue }
                    c.assetType == 2 -> Kind.PHOTO
                    c.assetType == 4 && c.runtimeTitle != null && c.runtimeTitle.layers.isNotEmpty() && c.runtimeTitle.frameWidth > 0 && c.runtimeTitle.frameHeight > 0 -> Kind.TITLE
                    c.assetType == 4 -> { notes.add("Titles without readable text or shape layers, left out", where); skipped++; continue }
                    else -> { notes.add("Clips of a kind this app does not have (LumaFusion asset type ${c.assetType}), left out", where); skipped++; continue }
                }
                if (c.trackStart == null || c.trackDuration == null || c.sourceStart == null) { notes.add("Clips with missing times, left out", where); skipped++; continue }
                val start = toFrames(fpsNum, fpsDen, c.trackStart)
                val end = toFrames(fpsNum, fpsDen, c.trackStart, c.trackDuration)
                // A photo's or title's own source time is not a position in a file: it always starts at 0.
                val sourceIn = if (kind == Kind.VIDEO || kind == Kind.AUDIO) toFrames(fpsNum, fpsDen, c.sourceStart) else 0L
                if (start < 0 || sourceIn < 0 || end <= start) { notes.add("Clips too short or starting before zero, left out", where); skipped++; continue }
                val prev = placed.lastOrNull()
                if (prev != null && start < prev.end) {
                    if (start > prev.start) prev.end = start else { notes.add("Overlapping clips, left out", where); skipped++; continue }
                }
                placed += Placed(c, start, end, sourceIn, kind)
            }
            return placed
        }

        /** Opacity, 180 degree rotation and a horizontal split-screen style placement; what is left over is reported. */
        fun transformOf(c: LfClip, where: String): TransformDto {
            val left = c.videoChanges.toMutableList()
            var rotation = 0.0
            var scale = listOf(1.0, 1.0)
            var position = listOf(0.0, 0.0)
            if ("rotation" in left || "orientation" in left) {
                // Portrait clips carry orientation +90 and rotation -90 (seen on every portrait clip of a sample): the sum is
                // what is turned beyond the file's own orientation, which the decoder already applies.
                val net = ((c.rotation + c.orientation) % (2 * PI) + 2 * PI) % (2 * PI)
                val resolved = when {
                    net < ROTATION_TOLERANCE || 2 * PI - net < ROTATION_TOLERANCE -> 0.0
                    abs(net - PI) < ROTATION_TOLERANCE -> 180.0
                    else -> null
                }
                if (resolved != null) {
                    rotation = resolved
                    left -= "rotation"
                    left -= "orientation"
                }
            }
            if (("scale" in left || "position" in left) && abs(c.translation[1]) < EPS && c.scale.all { it > 0.0 && it <= MAX_SCALE } && c.translation[0].let { abs(it) <= MAX_OFFSET_UNITS }) {
                scale = c.scale
                position = listOf(c.translation[0] * width / 2.0, 0.0)
                left -= "scale"
                left -= "position"
                cautions.add("Clips whose size and position were converted with unit conventions inferred from sample files, not confirmed (check split screens)", where)
            }
            if (left.isNotEmpty()) notes.add("Picture settings: ${left.joinToString()} (left at default)", where)
            return TransformDto(
                scale = scale,
                rotation = rotation,
                position = position,
                opacity = c.opacity.coerceIn(0.0, 1.0),
            )
        }

        fun reportCommon(c: LfClip, where: String) {
            if (c.reversed) notes.add("Reversed clips (play forwards)", where)
            if (abs(c.speed - 1.0) > EPS) notes.add("Speed changes (play at normal speed, same place on the timeline)", where)
            if (c.transitionType != 0) notes.add("Transitions (hard cut instead)", where)
            if (c.effectNames.isNotEmpty()) notes.add("Effects (not applied)", "$where: ${c.effectNames.joinToString()}")
            else if (c.effectCount > 0) notes.add("Effects (not applied)", where)
            if (c.audioChanges.isNotEmpty()) notes.add("Audio ${c.audioChanges.joinToString()} (not applied)", where)
            if (c.keyframed.isNotEmpty()) notes.add("Animated (keyframed) values: ${c.keyframed.joinToString()} (constant value used)", where)
        }

        fun titleOf(c: LfClip, where: String): TitleDto {
            val t = checkNotNull(c.runtimeTitle)
            val layers = t.layers.map { l ->
                val offsetX = ((l.x + l.w / 2.0) - t.frameWidth / 2.0) / t.frameWidth
                val offsetY = ((l.y + l.h / 2.0) - t.frameHeight / 2.0) / t.frameHeight
                if (l.extras.isNotEmpty()) notes.add("Title layer details (${l.extras.joinToString()}) not imported", where)
                if (l.isText) {
                    if (l.fontName.isNotEmpty()) notes.add("Title fonts (default font used)", "$where: ${l.fontName}")
                    TitleLayerDto(
                        type = "text",
                        text = l.text,
                        size = if (l.pointSize > 0) (l.pointSize / t.frameHeight).coerceIn(0.01, 0.5) else 0.08,
                        color = l.color,
                        alignment = when (l.alignment) { 0 -> "left"; 2 -> "right"; else -> "center" },
                        bold = l.fontName.contains("bold", ignoreCase = true),
                        italic = l.fontName.contains("italic", ignoreCase = true) || l.fontName.contains("oblique", ignoreCase = true),
                        offsetX = offsetX.coerceIn(-MAX_OFFSET_TITLE, MAX_OFFSET_TITLE),
                        offsetY = offsetY.coerceIn(-MAX_OFFSET_TITLE, MAX_OFFSET_TITLE),
                        opacity = l.opacity.coerceIn(0.0, 1.0),
                    )
                } else {
                    TitleLayerDto(
                        type = "shape",
                        shape = "rect",
                        width = (l.w / t.frameWidth).coerceIn(0.002, 2.0),
                        height = (l.h / t.frameHeight).coerceIn(0.002, 2.0),
                        fill = l.color,
                        offsetX = offsetX.coerceIn(-MAX_OFFSET_TITLE, MAX_OFFSET_TITLE),
                        offsetY = offsetY.coerceIn(-MAX_OFFSET_TITLE, MAX_OFFSET_TITLE),
                        opacity = l.opacity.coerceIn(0.0, 1.0),
                    )
                }
            }
            if (t.unsupportedLayers > 0) notes.add("Title layers that are not text or plain shapes (pictures, other shapes)", where)
            if (c.videoChanges.isNotEmpty()) notes.add("Title placement settings: ${c.videoChanges.joinToString()} (left at default)", where)
            titles++
            return TitleDto(text = layers.firstOrNull { it.type == "text" }?.text.orEmpty(), layers = layers)
        }

        val trackDtos = ArrayList<TrackDto>()
        val titleTracks = ArrayList<TrackDto>()
        fun build(track: LfTrack, label: String, id: String, type: String): TrackDto {
            val audioLane = type == "audio"
            val placed = lane(track, label, audioLane)
            fun clipOf(p: Placed): ClipDto {
                val c = p.clip
                clipCounter++
                kept++
                val where = "$label at ${clock(p.start)}"
                reportCommon(c, where)
                if (p.kind == Kind.TITLE) {
                    return ClipDto(id = "clip-$clipCounter", timelineStartFrame = p.start, sourceInFrame = 0, sourceOutFrame = p.end - p.start, title = titleOf(c, where))
                }
                val assetId = assetIds.getOrPut(c.assetKey) { "asset-${assetIds.size + 1}" }
                assetNames.putIfAbsent(c.fileName, assetId)
                assetClips.getOrPut(assetId) { ArrayList() } += c
                if (p.kind == Kind.PHOTO) photoAssets += assetId
                val gain = if (p.kind == Kind.PHOTO || !c.hasAudio) 0.0 else dbOf(c.volume)
                val pan = if (p.kind == Kind.PHOTO || !c.hasAudio) 0.0 else c.pan.coerceIn(-1.0, 1.0)
                return ClipDto(
                    id = "clip-$clipCounter",
                    assetId = assetId,
                    timelineStartFrame = p.start,
                    sourceInFrame = p.sourceIn,
                    sourceOutFrame = p.sourceIn + (p.end - p.start),
                    transform = if (audioLane) TransformDto() else transformOf(c, where),
                    gainDb = gain,
                    audio = if (abs(pan) > EPS) ClipAudioDto(pan = pan) else null,
                    still = if (p.kind == Kind.PHOTO) "photo" else null,
                )
            }
            val mediaClips = placed.filter { it.kind != Kind.TITLE }.map(::clipOf)
            val titleClips = placed.filter { it.kind == Kind.TITLE }.map(::clipOf)
            if (titleClips.isNotEmpty()) titleTracks += TrackDto(id = "track-t${titleTracks.size + 1}", type = "title", order = 0, clips = titleClips)
            if (track.hidden) notes.add("Hidden tracks (shown)", label)
            if (track.locked) notes.add("Locked tracks (unlocked)", label)
            val volume = if (abs(track.volume - 1.0) > EPS) TrackAudioDto(volumeDb = dbOf(track.volume).coerceIn(-96.0, 24.0)) else null
            return TrackDto(id = id, type = type, order = 0, clips = mediaClips, audio = volume)
        }

        val built = ArrayList<TrackDto>()
        overlays.forEachIndexed { i, t -> built += build(t, "V${overlays.size - i + 1}", "track-v${overlays.size - i + 1}", "video") }
        // The base track always exists, so edits that follow the base have something to follow.
        built += if (anchor != null) build(anchor, "V1", "track-v1", "video") else TrackDto("track-v1", "video", 0)
        val audioBuilt = audioTracks.mapIndexed { i, t -> build(t, "A${i + 1}", "track-a${i + 1}", "audio") }
        // Titles draw above every video layer: their tracks go first, topmost lane's titles first.
        (titleTracks + built + audioBuilt).forEachIndexed { index, t -> trackDtos += t.copy(order = index) }

        val assets = assetIds.map { (_, id) ->
            val clips = assetClips[id].orEmpty()
            val name = clips.first().fileName.ifEmpty { id }
            val isPhoto = id in photoAssets
            val last = clips.maxOf { c -> (if (isPhoto) 0L else toFrames(fpsNum, fpsDen, c.sourceStart ?: LfTime(0, 1))) + toFrames(fpsNum, fpsDen, c.trackDuration ?: LfTime(0, 1)) }
            MediaAssetDto(
                id = id,
                uri = "",
                durationFrames = last.coerceAtLeast(1),
                nativeFpsNum = fpsNum,
                nativeFpsDen = fpsDen,
                colorSpace = SDR,
                hasVideo = !isPhoto && clips.any { it.hasVideo },
                hasAudio = !isPhoto && clips.any { it.hasAudio },
                isImage = isPhoto,
                displayName = name,
            )
        }

        if (lf.colorspace != 0) notes.add("Colour space ${lf.colorspace} (Rec. 709 used)", "project")
        if (abs(lf.primaryVolume - 1.0) > EPS) notes.add("Project master volume (not applied)", "project")
        if (!lf.backgroundBlack) notes.add("Background colour (black used)", "project")
        if (lf.markerCount > 0) notes.add("Markers", "${lf.markerCount} markers")
        if (lf.hasNotes) notes.add("Project notes", "project")
        if (lf.needsCloudMedia) notes.add("Media stored in the cloud (not part of the file)", "project")

        val inPackage = assetNames.keys.count { it.lowercase() in available }
        val clipsWord = if (kept == 1) "1 clip" else "$kept clips"
        imported += "$clipsWord on ${trackDtos.count { it.type == "video" }} video and ${trackDtos.count { it.type == "audio" }} audio tracks, ${width} x $height at ${rate(fpsNum, fpsDen)} fps"
        if (assets.isNotEmpty()) {
            imported += if (available.isEmpty()) "${assets.size} media files are not included: link them in the editor (Relink)"
            else "$inPackage of ${assets.size} media files came with the package" + if (inPackage < assets.size) "; link the rest in the editor (Relink)" else ""
        }
        val project = ProjectDto(
            id = "lumafusion-import",
            name = lf.title.ifEmpty { "LumaFusion project" }.take(80),
            settings = ProjectSettingsDto(width, height, fpsNum, fpsDen, SDR),
            mediaLibrary = assets,
            tracks = trackDtos,
        )
        imported += cautions.lines().map { "Caution: $it" }
        return LumaFusionConversion(project, assetNames, LumaFusionReport(imported, notes.lines()))
    }

    private fun dbOf(linear: Double): Double = if (linear <= 0.0) -96.0 else (20.0 * log10(linear)).coerceIn(-96.0, 24.0)

    private fun rate(num: Int, den: Int): String = if (den == 1) "$num" else "%.3f".format(num.toDouble() / den).trimEnd('0').trimEnd('.')

    private const val SDR = "Rec709-SDR"
    private const val ROTATION_TOLERANCE = 1e-4
    private const val MAX_SCALE = 20.0
    private const val MAX_OFFSET_UNITS = 4.0
    private const val MAX_OFFSET_TITLE = 2.0

    private enum class Kind { VIDEO, PHOTO, TITLE, AUDIO }

    // endregion
}
