package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.interchange.LfFixture.ClipSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two import defects that reached the user. (1) A package whose clips carry uncompressed PCM audio exported with no sound: the
 * archive was right (audio stream present, volume 1 = 0 dB, volume 0 = -96 dB) and the probe was wrong, so the importer must keep
 * `hasAudio` and the gain exactly as the archive says, for any audio codec. (2) Clips were turned twice: the decoder already
 * applies the container's rotation, and LumaFusion's `videoRotation` records that same orientation, so no rotation may be imported.
 * The file side of both (the probe finding PCM, the exporter rotating once) is in `MovAudioScanTableTest` and `scripts/qa-smoke.sh`.
 */
class LumaFusionPackageGuardTest {
    private fun convert(json: String) = LumaFusionImport.convert(LumaFusionImport.parse(json), emptySet())

    private fun archive(vararg clips: ClipSpec, trackVolume: Double = 1.0) = LfFixture.archive(
        tracks = listOf(LfFixture.track(0, 0, anchor = true, clips = clips.toList(), volume = trackVolume)),
    )

    @Test
    fun `volumes map to decibels whatever the codec of the clip's sound`() {
        // Volume 1 -> 0 dB, 0.5 -> -6.02 dB, 0 -> -96 dB (silent), above 1 is a boost.
        val table = listOf(1.0 to 0.0, 0.5 to -6.0206, 0.25 to -12.0412, 0.0 to -96.0, 2.0 to 6.0206)
        val clips = table.mapIndexed { i, (volume, _) -> ClipSpec("c$i", "pcm$i.MOV", i * 600L, 600, volume = volume) }
        val converted = convert(archive(*clips.toTypedArray()))
        val base = converted.project.tracks.last { it.type == "video" }
        assertEquals(table.size, base.clips.size)
        for ((i, clip) in base.clips.withIndex()) assertEquals("volume ${table[i].first}", table[i].second, clip.gainDb, 0.001)
    }

    @Test
    fun `a clip with a sound stream keeps its audio in the library`() {
        val converted = convert(archive(ClipSpec("a", "pcm.MOV", 0, 600), ClipSpec("b", "mute.MOV", 600, 600, audio = false)))
        val byName = converted.project.mediaLibrary.associateBy { it.displayName }
        assertTrue("a clip with audio in the archive must stay hasAudio (the probe repairs it from the file)", byName.getValue("pcm.MOV").hasAudio)
        assertTrue(!byName.getValue("mute.MOV").hasAudio)
    }

    @Test
    fun `rotation and orientation values are never imported as a turn`() {
        val pi = Math.PI
        val table = listOf(0.0 to 0.0, pi to 0.0, -pi / 2 to 0.0, pi / 2 to -pi / 2, pi to pi)
        val clips = table.mapIndexed { i, (rotation, orientation) ->
            ClipSpec("r$i", "turn$i.MOV", i * 600L, 600, rotation = rotation, orientation = orientation)
        }
        val base = convert(archive(*clips.toTypedArray())).project.tracks.last { it.type == "video" }
        for ((i, clip) in base.clips.withIndex()) assertEquals("clip $i (${table[i]})", 0.0, clip.transform.rotation, 0.0)
    }
}
