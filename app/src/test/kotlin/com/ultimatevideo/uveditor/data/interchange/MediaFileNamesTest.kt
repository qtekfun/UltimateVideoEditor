package com.ultimatevideo.uveditor.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFileNamesTest {
    @Test
    fun `a free name is kept and a taken one gets a counter before the extension`() {
        assertEquals("IMG_1.MOV", MediaFileNames.unique("IMG_1.MOV", listOf("other.mp4")))
        assertEquals("IMG_1 (2).MOV", MediaFileNames.unique("IMG_1.MOV", listOf("IMG_1.MOV")))
        assertEquals("IMG_1 (3).MOV", MediaFileNames.unique("IMG_1.MOV", listOf("IMG_1.MOV", "IMG_1 (2).MOV")))
    }

    @Test
    fun `names compare ignoring case and extensions are optional`() {
        assertEquals("img_1 (2).mov", MediaFileNames.unique("img_1.mov", listOf("IMG_1.MOV")))
        assertEquals("notes (2)", MediaFileNames.unique("notes", listOf("Notes")))
        assertEquals(".hidden (2)", MediaFileNames.unique(".hidden", listOf(".hidden")))
    }

    @Test
    fun `cleaning drops folders and characters file systems refuse`() {
        assertEquals("a.mov", MediaFileNames.clean("../../etc/a.mov"))
        assertEquals("a.mov", MediaFileNames.clean("C:\\clips\\a.mov"))
        assertEquals("a_b_c.mov", MediaFileNames.clean("a:b|c.mov"))
        assertEquals("media", MediaFileNames.clean("   "))
        assertEquals("media", MediaFileNames.clean("../"))
        val long = "x".repeat(300) + ".MOV"
        val cleaned = MediaFileNames.clean(long)
        assertEquals(120, cleaned.length)
        assertEquals(true, cleaned.endsWith(".MOV"))
    }

    @Test
    fun `media types follow the extension`() {
        assertEquals("video/quicktime", MediaFileNames.mimeOf("a.MOV"))
        assertEquals("video/mp4", MediaFileNames.mimeOf("a.mp4"))
        assertEquals("image/jpeg", MediaFileNames.mimeOf("a.JPG"))
        assertEquals("application/octet-stream", MediaFileNames.mimeOf("a"))
    }
}
