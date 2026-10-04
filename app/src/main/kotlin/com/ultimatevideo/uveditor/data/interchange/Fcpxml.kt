package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipDeletion
import com.ultimatevideo.uveditor.domain.Marker
import com.ultimatevideo.uveditor.domain.MarkerKind
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.isFreeze
import com.ultimatevideo.uveditor.domain.sourceSpan
import java.util.Locale

/** The document and what the format subset could not carry. */
data class FcpxmlExport(val xml: String, val notes: List<String>)

/**
 * FCPXML 1.9 export of the parts of a project the format subset carries: the base track as the primary
 * storyline, every other track as clips connected to it (lanes above for video and titles, below for
 * audio), trims, constant speed and reverse, a basic transform and opacity, clip gain, titles as
 * generators and markers. Everything else (effects, LUTs, keyframes, masks, speed ramps, stickers,
 * transitions, colour grades, audio tools) is left out and listed in [FcpxmlExport.notes], which are also
 * written in the sequence's `<note>`. Layout rules and the unit choices are documented in SPECS 9.7;
 * positions are written as a percentage of the frame height and have not been checked against a
 * real Final Cut Pro or DaVinci Resolve import.
 */
object Fcpxml {

    fun export(project: ProjectDto): FcpxmlExport = Writer(project, TimelineMapper.toTimeline(project)).write()

    private class Writer(private val project: ProjectDto, private val timeline: Timeline) {
        private val num = project.settings.fpsNum.toLong()
        private val den = project.settings.fpsDen.toLong()
        private val height = project.settings.height
        private val notes = linkedSetOf<String>()
        private val sb = StringBuilder()
        private val assetIds = LinkedHashMap<String, String>()
        private var textStyles = 0
        private val styleDefs = StringBuilder()

        /** One entry of the primary storyline: a base clip, or a gap where the base has no clip. */
        private class Item(val clip: Clip?, val start: Long, val end: Long, val sourceStart: Long)

        fun write(): FcpxmlExport {
            val total = maxOf(timeline.tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEnd.value } ?: 0L, 1L)
            val base = ClipDeletion.baseTrack(timeline)
            val items = spine(base, total)
            val lanes = laneMap(base)
            val connected = timeline.tracks.filter { it.id != base?.id }.flatMap { track -> track.clips.map { track to it } }

            val libraryAssets = project.mediaLibrary.associateBy { it.id }
            for (track in timeline.tracks) for (clip in track.clips) {
                val id = clip.assetId ?: continue
                val fileBacked = clip.hasMedia || clip.still == StillKind.PHOTO
                if (fileBacked && id in libraryAssets && id !in assetIds) assetIds[id] = "a${assetIds.size + 1}"
            }

            collectNotes()

            val body = StringBuilder()
            for (item in items) {
                val children = connected.filter { (_, clip) -> parentOf(items, clip.timelineStart.value) === item }
                val markers = timeline.markers.filter { parentOf(items, it.frame.value) === item }
                body.append(spineItem(item, children, markers, lanes, libraryAssets))
            }

            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE fcpxml>\n")
            sb.append("<fcpxml version=\"1.9\">\n  <resources>\n")
            sb.append("    <format id=\"r1\" name=\"${formatName()}\" frameDuration=\"${time(1)}\" width=\"${project.settings.width}\" height=\"$height\" colorSpace=\"${colorSpace()}\"/>\n")
            for ((assetId, resource) in assetIds) sb.append(assetResource(resource, libraryAssets.getValue(assetId)))
            if (styleDefs.isNotEmpty() || hasTitles()) {
                sb.append("    <effect id=\"rT\" name=\"Basic Title\" uid=\".../Titles.localized/Bumper:Opener.localized/Basic Title.localized/Basic Title.moti\"/>\n")
            }
            sb.append("  </resources>\n  <library>\n    <event name=\"ultimateVE\">\n      <project name=\"${esc(project.name)}\">\n")
            val dropFrame = SmpteTimecode.isNtsc(project.settings.fpsNum, project.settings.fpsDen)
            sb.append("        <sequence format=\"r1\" duration=\"${time(total)}\" tcStart=\"0s\" tcFormat=\"${if (dropFrame) "DF" else "NDF"}\" audioLayout=\"stereo\" audioRate=\"48k\">\n")
            if (notes.isNotEmpty()) {
                sb.append("          <note>").append(esc("Not exported: " + notes.joinToString(" "))).append("</note>\n")
            }
            sb.append("          <spine>\n").append(body).append("          </spine>\n        </sequence>\n      </project>\n    </event>\n  </library>\n</fcpxml>\n")
            return FcpxmlExport(sb.toString(), notes.toList())
        }

        private fun hasTitles() = timeline.tracks.any { t -> t.clips.any { it.title != null } }

        // region layout

        private fun spine(base: Track?, total: Long): List<Item> {
            val clips = base?.clips.orEmpty()
            if (clips.isEmpty()) return listOf(Item(null, 0, total, 0))
            val items = mutableListOf<Item>()
            var cursor = 0L
            for (clip in clips) {
                if (clip.timelineStart.value > cursor) items += Item(null, cursor, clip.timelineStart.value, 0)
                items += Item(clip, clip.timelineStart.value, clip.timelineEnd.value, clip.sourceIn.value)
                cursor = clip.timelineEnd.value
            }
            if (cursor < total) items += Item(null, cursor, total, 0)
            return items
        }

        /** The spine item a connected clip or marker at [frame] hangs on: the one it starts in, else the nearest. */
        private fun parentOf(items: List<Item>, frame: Long): Item =
            items.firstOrNull { frame >= it.start && frame < it.end } ?: if (frame < items.first().start) items.first() else items.last()

        /** Lane numbers: overlays count up from the base, titles above them, audio below counting down. */
        private fun laneMap(base: Track?): Map<String, Int> {
            val map = HashMap<String, Int>()
            val videos = timeline.tracks.filter { it.type == TrackType.VIDEO && it.id != base?.id }.reversed()
            videos.forEachIndexed { i, t -> map[t.id] = i + 1 }
            val titles = timeline.tracks.filter { it.type == TrackType.TITLE }
            titles.forEachIndexed { i, t -> map[t.id] = videos.size + 1 + i }
            timeline.tracks.filter { it.type == TrackType.AUDIO }.forEachIndexed { i, t -> map[t.id] = -(i + 1) }
            return map
        }

        // endregion

        private fun spineItem(
            item: Item,
            children: List<Pair<Track, Clip>>,
            markers: List<Marker>,
            lanes: Map<String, Int>,
            assets: Map<String, MediaAssetDto>,
        ): String {
            val out = StringBuilder()
            val pad = "            "
            val inner = StringBuilder()
            val clip = item.clip
            if (clip != null) {
                inner.append(clipBody(clip, assets[clip.assetId], pad + "  "))
            }
            for ((track, c) in children.sortedBy { it.second.timelineStart }) {
                val lane = lanes.getValue(track.id)
                val offset = item.sourceStart + (c.timelineStart.value - item.start)
                inner.append(connected(c, lane, offset, assets, pad + "  "))
            }
            for (marker in markers.sortedBy { it.frame }) {
                val at = item.sourceStart + (marker.frame.value - item.start)
                inner.append(pad).append("  <marker start=\"${time(at)}\" duration=\"${time(1)}\" value=\"${esc(markerText(marker))}\"")
                    .append(marker.note?.let { " note=\"${esc(it)}\"" }.orEmpty()).append("/>\n")
            }
            if (clip != null) {
                out.append(pad).append(assetClipOpen(clip, assets[clip.assetId], offset = item.start, start = item.sourceStart, lane = null))
            } else {
                out.append(pad).append("<gap name=\"Gap\" offset=\"${time(item.start)}\" start=\"0s\" duration=\"${time(item.end - item.start)}\"")
            }
            if (inner.isEmpty()) {
                out.append("/>\n")
            } else {
                out.append(">\n").append(inner).append(pad).append(if (clip != null && clipKind(clip) == "video") "</video>\n" else if (clip != null) "</asset-clip>\n" else "</gap>\n")
            }
            return out.toString()
        }

        private fun clipKind(clip: Clip) = if (clip.still == StillKind.PHOTO) "video" else "asset-clip"

        private fun assetClipOpen(clip: Clip, asset: MediaAssetDto?, offset: Long, start: Long, lane: Int?): String {
            val tag = clipKind(clip)
            val ref = assetIds[clip.assetId].orEmpty()
            val name = esc(asset?.let(MissingMedia::nameOf) ?: clip.assetId.orEmpty())
            val laneAttr = lane?.let { " lane=\"$it\"" }.orEmpty()
            val startAttr = if (tag == "video") "0s" else time(start)
            val role = if (asset != null && !asset.hasVideo && !asset.isImage) " audioRole=\"music\"" else ""
            return "<$tag ref=\"$ref\"$laneAttr offset=\"${time(offset)}\" name=\"$name\" start=\"$startAttr\" duration=\"${time(clip.durationFrames)}\"" +
                (if (tag == "asset-clip") " tcFormat=\"${if (SmpteTimecode.isNtsc(project.settings.fpsNum, project.settings.fpsDen)) "DF" else "NDF"}\"" else "") + role
        }

        /** The retime and adjust children of a media clip. */
        private fun clipBody(clip: Clip, asset: MediaAssetDto?, pad: String): String {
            val sb = StringBuilder()
            val retimed = clip.retimedFrames != null || clip.reverse || clip.speedRamp.isNotEmpty() || clip.isFreeze
            if (retimed && clip.still == null) {
                val span = clip.sourceSpan
                val from = if (clip.reverse) span else 0L
                val to = if (clip.reverse) 0L else span
                sb.append(pad).append("<timeMap><timept time=\"0s\" value=\"${time(from)}\" interp=\"linear\"/>")
                    .append("<timept time=\"${time(clip.durationFrames)}\" value=\"${time(to)}\" interp=\"linear\"/></timeMap>\n")
            }
            if (!clip.transform.isIdentity) {
                val t = clip.transform
                sb.append(pad).append(
                    "<adjust-transform position=\"${num2(t.positionX * 100.0 / height)} ${num2(-t.positionY * 100.0 / height)}\" " +
                        "rotation=\"${num2(-t.rotationDegrees)}\" scale=\"${num2(t.scaleX)} ${num2(t.scaleY)}\"/>\n",
                )
                if (t.opacity < 1.0) sb.append(pad).append("<adjust-blend amount=\"${num2(t.opacity)}\"/>\n")
            }
            if (clip.gainDb != 0.0 && asset?.hasAudio != false) {
                sb.append(pad).append("<adjust-volume amount=\"${String.format(Locale.ROOT, "%.1f", clip.gainDb)}dB\"/>\n")
            }
            return sb.toString()
        }

        private fun connected(clip: Clip, lane: Int, offset: Long, assets: Map<String, MediaAssetDto>, pad: String): String {
            val title = clip.title
            if (title != null) return titleElement(clip, lane, offset, pad)
            if (clip.still == StillKind.STICKER || clip.assetId == null || clip.assetId !in assetIds) return ""
            val asset = assets[clip.assetId]
            val body = clipBody(clip, asset, "$pad  ")
            val open = assetClipOpen(clip, asset, offset, clip.sourceIn.value, lane)
            val tag = clipKind(clip)
            return if (body.isEmpty()) "$pad$open/>\n" else "$pad$open>\n$body$pad</$tag>\n"
        }

        private fun titleElement(clip: Clip, lane: Int, offset: Long, pad: String): String {
            val title = checkNotNull(clip.title)
            val id = "ts${++textStyles}"
            val font = (title.sizeFraction * height).toInt().coerceAtLeast(1)
            val color = title.colorArgb
            val a = ((color ushr 24) and 0xFF) / 255.0
            val r = ((color ushr 16) and 0xFF) / 255.0
            val g = ((color ushr 8) and 0xFF) / 255.0
            val b = (color and 0xFF) / 255.0
            val align = when (title.alignment) {
                TitleAlignment.LEFT -> "left"
                TitleAlignment.CENTER -> "center"
                TitleAlignment.RIGHT -> "right"
            }
            val sb = StringBuilder()
            sb.append(pad).append("<title ref=\"rT\" lane=\"$lane\" offset=\"${time(offset)}\" name=\"${esc(title.text.take(40))}\" start=\"3600s\" duration=\"${time(clip.durationFrames)}\">\n")
            sb.append(pad).append("  <text><text-style ref=\"$id\">${esc(title.text)}</text-style></text>\n")
            sb.append(pad).append("  <text-style-def id=\"$id\"><text-style font=\"Helvetica Neue\" fontSize=\"$font\" fontColor=\"${num2(r)} ${num2(g)} ${num2(b)} ${num2(a)}\" alignment=\"$align\"${if (title.bold) " bold=\"1\"" else ""}/></text-style-def>\n")
            sb.append(pad).append("</title>\n")
            return sb.toString()
        }

        private fun assetResource(id: String, asset: MediaAssetDto): String {
            val duration = if (asset.isImage) "0s" else ratTime(asset.durationFrames * asset.nativeFpsDen, asset.nativeFpsNum.toLong())
            val audio = if (asset.hasAudio) " hasAudio=\"1\" audioSources=\"1\" audioChannels=\"2\" audioRate=\"48000\"" else ""
            val video = if (asset.hasVideo || asset.isImage) " hasVideo=\"1\"" else ""
            return "    <asset id=\"$id\" name=\"${esc(MissingMedia.nameOf(asset))}\" start=\"0s\" duration=\"$duration\"$video$audio format=\"r1\">\n" +
                "      <media-rep kind=\"original-media\" src=\"${esc(asset.uri)}\"/>\n    </asset>\n"
        }

        private fun collectNotes() {
            val clips = timeline.tracks.flatMap { it.clips }
            if (assetIds.keys.any { id -> project.mediaLibrary.firstOrNull { it.id == id }?.uri?.startsWith("content://") == true }) {
                notes += "Media is referenced by Android content:// addresses; relink the files in your editor."
            }
            if (clips.any { it.fx.effects.isNotEmpty() || it.fx.mask != null || it.fx.blendMode.name != "NORMAL" }) {
                notes += "Effects, LUTs, colour grades, masks and blend modes."
            }
            if (clips.any { it.keyframes.isNotEmpty() }) notes += "Keyframes."
            if (clips.any { it.speedRamp.isNotEmpty() }) notes += "Speed ramps (their average speed is used)."
            if (clips.any { it.still == StillKind.STICKER }) notes += "Stickers."
            if (timeline.transitions.isNotEmpty()) notes += "Transitions (cuts are written)."
            if (clips.any { it.audio != com.ultimatevideo.uveditor.domain.ClipAudio.NONE } || timeline.tracks.any { !it.audio.isNeutral }) {
                notes += "Audio tools (pan, fades, EQ, noise suppression, mixer settings, ducking)."
            }
            if (clips.any { !it.transform.isIdentity }) notes += "Transform positions are written as a percentage of the frame height (unchecked in an editor)."
            if (clips.any { it.retimedFrames != null || it.reverse || it.isFreeze }) notes += "Retimed clips use a timeMap (unchecked in an editor)."
            if (timeline.markers.any { it.color != null }) notes += "Marker colours are written as a text prefix."
            val base = ClipDeletion.baseTrack(timeline)
            if (timeline.tracks.any { it.id != base?.id && it.clips.isNotEmpty() }) {
                notes += "Connected clips are placed as if the clip they hang on played at normal speed."
            }
        }

        private fun markerText(marker: Marker): String {
            val label = marker.note?.takeIf { it.isNotBlank() } ?: if (marker.kind == MarkerKind.BEAT) "Beat" else "Marker"
            return marker.color?.let { "[${it.name.lowercase()}] $label" } ?: label
        }

        private fun formatName(): String {
            val code = when {
                num == 24000L && den == 1001L -> "2398"
                num == 30000L && den == 1001L -> "2997"
                num == 60000L && den == 1001L -> "5994"
                den == 1L -> num.toString()
                else -> String.format(Locale.ROOT, "%.3f", num.toDouble() / den)
            }
            return "FFVideoFormat${height}p$code"
        }

        private fun colorSpace() = if (project.settings.colorSpace.contains("HLG", ignoreCase = true)) "9-18-9 (Rec. 2020 HLG)" else "1-1-1 (Rec. 709)"

        /** Time of [frames] project frames as FCPXML rational seconds. */
        private fun time(frames: Long): String = ratTime(frames * den, num)

        private fun ratTime(numerator: Long, denominator: Long): String {
            if (numerator == 0L) return "0s"
            val g = gcd(numerator, denominator)
            val n = numerator / g
            val d = denominator / g
            return if (d == 1L) "${n}s" else "$n/${d}s"
        }

        private fun gcd(a: Long, b: Long): Long = if (b == 0L) kotlin.math.abs(a) else gcd(b, a % b)

        private fun num2(value: Double): String = String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

        private fun esc(text: String): String = buildString {
            for (c in text) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c.code >= 0x20 || c == '\n' || c == '\t') append(c)
            }
        }
    }
}
