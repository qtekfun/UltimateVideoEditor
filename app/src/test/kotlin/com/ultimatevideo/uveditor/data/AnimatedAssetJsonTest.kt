package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimatedAssetJsonTest {
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project(vararg assets: MediaAssetDto) =
        ProjectDto(id = "p", name = "P", settings = settings, mediaLibrary = assets.toList(), tracks = emptyList())

    private val gif = MediaAssetDto(
        "gif", "content://pic/anim", 18, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true,
        animationDelaysMs = listOf(100, 200, 300),
    )

    @Test
    fun `the frame delays survive a save and a load`() {
        val loaded = ProjectJson.decode(ProjectJson.encode(project(gif)))
        assertEquals(listOf(100, 200, 300), loaded.mediaLibrary.single().animationDelaysMs)
        assertEquals(gif, loaded.mediaLibrary.single())
    }

    @Test
    fun `a project written before animated pictures loads with no animation`() {
        val old = """
            {"version":1,"id":"p","name":"P",
             "settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},
             "mediaLibrary":[{"id":"img","uri":"content://pic/1","durationFrames":150,"nativeFpsNum":30,"nativeFpsDen":1,
                              "colorSpace":"Rec709-SDR","hasVideo":false,"hasAudio":false,"isImage":true}],
             "tracks":[]}
        """.trimIndent()
        val asset = ProjectJson.decode(old).mediaLibrary.single()
        assertNull(asset.animationDelaysMs)
        assertNull(asset.animationTiming())
    }

    @Test
    fun `a photo keeps no animation through a save and a load`() {
        val photo = gif.copy(id = "img", animationDelaysMs = null)
        val loaded = ProjectJson.decode(ProjectJson.encode(project(photo))).mediaLibrary.single()
        assertNull(loaded.animationDelaysMs)
        assertEquals(photo, loaded)
    }

    @Test
    fun `an unreadable animation in a file reads as a still photo, not as a corrupt project`() {
        val asset = ProjectJson.decode(ProjectJson.encode(project(gif.copy(animationDelaysMs = listOf(100, 0, 300))))).mediaLibrary.single()
        assertNull(asset.animationTiming())
    }
}
