package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.engine.timeline.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.english

class ThumbnailFailuresTest {
    private val logged = mutableListOf<String>()
    private val shown = mutableListOf<UiText>()

    private fun failures(projectId: String = "p", seen: MutableSet<String> = mutableSetOf()) =
        ThumbnailFailures(projectId, log = { logged += it }, show = { shown += it }, alreadyShown = seen)

    @Test
    fun namesTheClipAndLogsKeyUriAndDetail() {
        val f = failures()
        f.register(7L, "IMG_0014.mov", "content://x/IMG_0014.mov")
        f.onFailure(7L, EngineStatus.IO_ERROR, "empty file (0 bytes)")
        assertEquals(listOf("No filmstrip for IMG_0014.mov (IO_ERROR)"), shown.english())
        assertTrue(logged.single().contains("asset=7 name=IMG_0014.mov uri=content://x/IMG_0014.mov"))
        assertTrue(logged.single().contains("empty file (0 bytes)"))
    }

    @Test
    fun showsOnePerAssetButLogsEveryFailure() {
        val f = failures()
        f.register(1L, "a.mov", "content://a")
        f.register(2L, "b.mov", "content://b")
        repeat(3) { f.onFailure(1L, EngineStatus.CODEC_ERROR, "d") }
        f.onFailure(2L, EngineStatus.CODEC_ERROR, "d")
        assertEquals(2, shown.size)
        assertEquals(4, logged.size)
    }

    @Test
    fun reopeningTheEditorDoesNotRepeatTheMessage() {
        val seen = mutableSetOf<String>()
        repeat(2) {
            val f = failures(seen = seen)
            f.register(1L, "a.mov", "content://a")
            f.onFailure(1L, EngineStatus.IO_ERROR, "d")
        }
        assertEquals(1, shown.size)
    }

    @Test
    fun sameFileInAnotherProjectIsReportedAgain() {
        val seen = mutableSetOf<String>()
        for (project in listOf("p1", "p2")) {
            val f = failures(project, seen)
            f.register(1L, "a.mov", "content://a")
            f.onFailure(1L, EngineStatus.IO_ERROR, "d")
        }
        assertEquals(2, shown.size)
    }

    @Test
    fun unknownKeyStillGetsAMessage() {
        failures().onFailure(99L, EngineStatus.IO_ERROR, "d")
        assertEquals(listOf("Could not generate a filmstrip (IO_ERROR)"), shown.english())
    }
}
